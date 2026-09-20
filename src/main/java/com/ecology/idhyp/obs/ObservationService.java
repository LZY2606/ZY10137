package com.ecology.idhyp.obs;

import com.ecology.idhyp.obs.Models.Individual;
import com.ecology.idhyp.obs.Models.Observation;
import com.ecology.idhyp.obs.Models.RawCandidate;
import com.ecology.idhyp.obs.Models.Site;
import com.ecology.idhyp.obs.Models.SurveyPeriod;
import com.ecology.idhyp.obs.Models.SurveySession;
import com.ecology.idhyp.scoring.MarkSimilarity;
import com.ecology.idhyp.scoring.PatternSimilarity;
import com.ecology.idhyp.support.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class ObservationService {

    public static final String NEW_INDIVIDUAL = "#NEW#";
    private static final double CANDIDATE_KEEP = 0.30;
    private static final double MARK_WEIGHT = 0.55;
    private static final double PATTERN_WEIGHT = 0.45;

    private final JdbcTemplate jdbc;

    public ObservationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ refs

    public List<Site> sites() {
        return jdbc.query("SELECT code,name,lat,lon FROM site ORDER BY code", SITE);
    }

    public List<SurveyPeriod> periods() {
        return jdbc.query("SELECT * FROM survey_period ORDER BY start_epoch", PERIOD);
    }

    public List<SurveySession> sessions() {
        return jdbc.query("SELECT code,site_code,period_code,start_at,end_at,count_zero,note "
                + "FROM survey_session ORDER BY start_epoch", SESSION);
    }

    public List<Individual> individuals() {
        return jdbc.query("SELECT code,label,marking,pattern_summary,created_at "
                + "FROM individual ORDER BY code", IND);
    }

    public Individual individual(String code) {
        List<Individual> list = jdbc.query(
                "SELECT code,label,marking,pattern_summary,created_at FROM individual WHERE code=?",
                IND, code);
        return list.isEmpty() ? null : list.get(0);
    }

    public Site site(String code) {
        List<Site> list = jdbc.query("SELECT code,name,lat,lon FROM site WHERE code=?", SITE, code);
        return list.isEmpty() ? null : list.get(0);
    }

    // ------------------------------------------------------------- periods

    /**
     * Archive an instant into a period using HALF-OPEN intervals [start,end).
     * An observation exactly at a boundary therefore belongs to exactly one
     * period (the one starting there), never both.
     */
    public String periodForEpoch(long epoch) {
        List<SurveyPeriod> ps = jdbc.queryForObject(
                "SELECT count(*) FROM survey_period", Integer.class) == 0
                ? List.of() : periods();
        for (SurveyPeriod p : ps) {
            if (epoch >= p.startEpoch() && epoch < p.endEpoch()) {
                return p.code();
            }
        }
        return null;
    }

    // ------------------------------------------------------------ imports

    @Transactional
    public Map<String, Object> importBatch(String batchRef, String note,
                                          List<Map<String, Object>> rows) {
        String hash = sha256(batchRef + "|" + normalizeRows(rows));
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT batch_ref,imported_at,content_hash,note FROM import_batch WHERE batch_ref=?",
                batchRef);
        boolean reused = !existing.isEmpty();
        if (!reused) {
            jdbc.update("INSERT INTO import_batch(batch_ref,imported_at,note,content_hash) VALUES(?,?,?,?)",
                    batchRef, Instant.now().toString(), note, hash);
        }
        int inserted = 0;
        int skipped = 0;
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                if (importObservation(batchRef, row)) {
                    inserted++;
                } else {
                    skipped++;
                }
            }
        }
        return Map.of("batchRef", batchRef, "idempotentReplay", reused,
                "inserted", inserted, "skipped", skipped,
                "observations", observationsOfBatch(batchRef));
    }

    private List<Observation> observationsOfBatch(String batchRef) {
        return jdbc.query("SELECT * FROM observation WHERE batch_ref=? ORDER BY observed_epoch",
                OBS, batchRef);
    }

    private boolean importObservation(String batchRef, Map<String, Object> row) {
        String siteCode = str(row, "siteCode");
        String observedAt = str(row, "observedAt");
        if (siteCode == null || observedAt == null) {
            throw ApiException.badRequest("each row requires siteCode and observedAt");
        }
        if (site(siteCode) == null) {
            throw ApiException.badRequest("unknown site: " + siteCode);
        }
        long epoch = com.ecology.idhyp.support.Times.epoch(observedAt);
        double confidence = dbl(row, "confidence", 1.0);
        boolean zero = Boolean.TRUE.equals(row.get("zero")) || dbl(row, "isZero", 0) == 1;
        String id = str(row, "id");
        if (id == null) {
            id = batchRef + "-" + sha256(siteCode + observedAt
                    + str(row, "markFragment") + str(row, "patternSummary")).substring(0, 10);
        }
        String contentHash = sha256(String.join("|", siteCode, String.valueOf(epoch),
                nz(str(row, "markFragment")), nz(str(row, "patternSummary")),
                String.valueOf(confidence), String.valueOf(zero),
                nz(str(row, "observer")), nz(str(row, "note"))));
        if (exists("SELECT 1 FROM observation WHERE batch_ref=? AND content_hash=?",
                batchRef, contentHash)) {
            return false;
        }
        jdbc.update("INSERT INTO observation(id,batch_ref,site_code,observed_at,observed_epoch,"
                        + "period_code,mark_fragment,pattern_summary,confidence,is_zero,observer,note,content_hash)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, batchRef, siteCode, Instant.ofEpochSecond(epoch).toString(), epoch,
                periodForEpoch(epoch), str(row, "markFragment"), str(row, "patternSummary"),
                confidence, zero ? 1 : 0, str(row, "observer"), str(row, "note"), contentHash);
        scoreObservation(id);
        return true;
    }

    // ------------------------------------------------------------- scoring

    /** Persist RAW automatic scores only; conflict adjustments never overwrite these. */
    public void scoreObservation(String observationId) {
        Observation o = observation(observationId);
        if (o == null || o.zero()) {
            return;
        }
        double best = 0;
        for (Individual ind : individuals()) {
            double mark = MarkSimilarity.score(o.markFragment(), ind.marking());
            double pat = PatternSimilarity.score(o.patternSummary(), ind.patternSummary());
            double score = clamp((MARK_WEIGHT * mark + PATTERN_WEIGHT * pat) * o.confidence());
            if (score >= CANDIDATE_KEEP) {
                upsertCandidate(observationId, ind.code(), score, mark, pat);
            } else {
                jdbc.update("DELETE FROM candidate WHERE observation_id=? AND individual_code=?",
                        observationId, ind.code());
            }
            best = Math.max(best, score);
        }
        double newScore = clamp((0.42 - 0.35 * best) * o.confidence());
        if (best < 0.85) {
            upsertCandidate(observationId, NEW_INDIVIDUAL, newScore, 0, 0);
        } else {
            jdbc.update("DELETE FROM candidate WHERE observation_id=? AND individual_code=?",
                    observationId, NEW_INDIVIDUAL);
        }
    }

    private void upsertCandidate(String obsId, String code, double score, double mark, double pat) {
        if (exists("SELECT 1 FROM candidate WHERE observation_id=? AND individual_code=?", obsId, code)) {
            jdbc.update("UPDATE candidate SET raw_score=?,mark_component=?,pattern_component=?,scored_at=? "
                    + "WHERE observation_id=? AND individual_code=?", score, mark, pat,
                    Instant.now().toString(), obsId, code);
        } else {
            jdbc.update("INSERT INTO candidate(observation_id,individual_code,raw_score,mark_component,"
                            + "pattern_component,scored_at) VALUES(?,?,?,?,?,?)",
                    obsId, code, score, mark, pat, Instant.now().toString());
        }
    }

    public List<RawCandidate> rawCandidates(String observationId) {
        return jdbc.query("SELECT observation_id,individual_code,raw_score,mark_component,pattern_component "
                + "FROM candidate WHERE observation_id=? ORDER BY raw_score DESC", RAW, observationId);
    }

    public List<Observation> observations() {
        return jdbc.query("SELECT * FROM observation ORDER BY observed_epoch", OBS);
    }

    public Observation observation(String id) {
        List<Observation> list = jdbc.query("SELECT * FROM observation WHERE id=?", OBS, id);
        return list.isEmpty() ? null : list.get(0);
    }

    // ------------------------------------------------------------- helpers

    private boolean exists(String sql, Object... args) {
        return !jdbc.queryForList(sql, args).isEmpty();
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static double dbl(Map<String, Object> m, String k, double dflt) {
        Object v = m.get(k);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(String.valueOf(v));
    }

    private String normalizeRows(List<Map<String, Object>> rows) {
        if (rows == null) {
            return "";
        }
        return rows.stream().map(r -> r.toString()).sorted().reduce("", (a, b) -> a + b);
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------- mappers

    static final RowMapper<Site> SITE = (rs, n) ->
            new Site(rs.getString("code"), rs.getString("name"),
                    rs.getDouble("lat"), rs.getDouble("lon"));

    static final RowMapper<SurveyPeriod> PERIOD = (rs, n) ->
            new SurveyPeriod(rs.getString("code"), rs.getString("name"),
                    rs.getString("start_at"), rs.getString("end_at"),
                    rs.getLong("start_epoch"), rs.getLong("end_epoch"));

    static final RowMapper<SurveySession> SESSION = (rs, n) ->
            new SurveySession(rs.getString("code"), rs.getString("site_code"),
                    rs.getString("period_code"), rs.getString("start_at"),
                    rs.getString("end_at"), rs.getInt("count_zero") == 1,
                    rs.getString("note"));

    static final RowMapper<Individual> IND = (rs, n) ->
            new Individual(rs.getString("code"), rs.getString("label"),
                    rs.getString("marking"), rs.getString("pattern_summary"),
                    rs.getString("created_at"));

    static final RowMapper<Observation> OBS = (rs, n) ->
            new Observation(rs.getString("id"), rs.getString("batch_ref"),
                    rs.getString("site_code"), rs.getString("observed_at"),
                    rs.getLong("observed_epoch"), rs.getString("period_code"),
                    rs.getString("mark_fragment"), rs.getString("pattern_summary"),
                    rs.getDouble("confidence"), rs.getInt("is_zero") == 1,
                    rs.getString("observer"), rs.getString("note"));

    static final RowMapper<RawCandidate> RAW = (rs, n) ->
            new RawCandidate(rs.getString("observation_id"), rs.getString("individual_code"),
                    rs.getDouble("raw_score"), rs.getDouble("mark_component"),
                    rs.getDouble("pattern_component"));
}
