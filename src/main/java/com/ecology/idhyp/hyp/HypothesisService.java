package com.ecology.idhyp.hyp;

import com.ecology.idhyp.obs.Models.Assignment;
import com.ecology.idhyp.obs.Models.ChangeLog;
import com.ecology.idhyp.obs.Models.ConflictCheck;
import com.ecology.idhyp.obs.Models.Hypothesis;
import com.ecology.idhyp.obs.Models.Individual;
import com.ecology.idhyp.obs.Models.Observation;
import com.ecology.idhyp.obs.Models.RawCandidate;
import com.ecology.idhyp.obs.Models.Site;
import com.ecology.idhyp.obs.ObservationService;
import com.ecology.idhyp.support.ApiException;
import com.ecology.idhyp.support.Geo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class HypothesisService {

    public static final int UNREACHABLE_SPEED_KMH = 4;
    public static final int SUSPICIOUS_SPEED_KMH = 2;
    public static final long CONFLICT_WINDOW_HOURS = 72;

    private final JdbcTemplate jdbc;
    private final ObservationService observations;

    public HypothesisService(JdbcTemplate jdbc, ObservationService observations) {
        this.jdbc = jdbc;
        this.observations = observations;
    }

    // ------------------------------------------------------------ queries

    private static final String HYP_SELECT =
            "SELECT h.*, "
            + "(SELECT count(*) FROM hypothesis_assignment a WHERE a.hyp_id=h.id AND a.decision='CONFIRMED') AS confirmed_count, "
            + "(SELECT count(*) FROM hypothesis_assignment a WHERE a.hyp_id=h.id AND a.decision='DENIED') AS denied_count, "
            + "(SELECT count(*) FROM observation o LEFT JOIN hypothesis_assignment a "
            + "  ON a.hyp_id=h.id AND a.observation_id=o.id AND a.decision='CONFIRMED' "
            + "WHERE o.is_zero=0 AND a.id IS NULL) AS unresolved_count "
            + "FROM hypothesis h ";

    public List<Hypothesis> hypotheses() {
        return jdbc.query(HYP_SELECT + "ORDER BY h.lineage_id, h.status, h.version", HYP);
    }

    public Hypothesis hypothesis(String id) {
        List<Hypothesis> list = jdbc.query(HYP_SELECT + "WHERE h.id=?", HYP, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public Hypothesis requireHypothesis(String id) {
        Hypothesis h = hypothesis(id);
        if (h == null) {
            throw ApiException.notFound("hypothesis " + id);
        }
        return h;
    }

    /** Default review context: the draft of a lineage, else its newest publication. */
    public Hypothesis defaultContext() {
        List<Hypothesis> all = hypotheses();
        return all.stream().filter(h -> h.status().equals("DRAFT")).findFirst()
                .orElseGet(() -> all.stream().filter(h -> h.status().equals("PUBLISHED")).findFirst()
                        .orElseThrow(() -> ApiException.notFound("hypothesis")));
    }

    public List<Assignment> assignments(String hypId) {
        return jdbc.query("SELECT * FROM hypothesis_assignment WHERE hyp_id=? ORDER BY id",
                ASM, hypId);
    }

    public List<ChangeLog> changesAfter(String hypId, Integer afterRevision) {
        int rev = afterRevision == null ? -1 : afterRevision;
        return jdbc.query("SELECT * FROM hypothesis_change WHERE hyp_id=? AND revision>? ORDER BY id",
                CHANGE, hypId, rev);
    }

    public List<Assignment> confirmed(String hypId) {
        return jdbc.query(
                "SELECT * FROM hypothesis_assignment WHERE hyp_id=? AND decision='CONFIRMED'", ASM, hypId);
    }

    // ------------------------------------------------------------ creation

    @Transactional
    public Hypothesis createDraft(String lineageId, String title, String note,
                                  String cloneFromId, String actor) {
        String line = lineageId != null ? lineageId
                : "L-" + ObservationService.sha256(title + Instant.now()).substring(0, 8);
        if (lineageHasDraft(line)) {
            throw new ApiException(409, "lineage " + line + " already has a draft");
        }
        String id = "H-" + ObservationService.sha256(line + Instant.now()).substring(0, 10);
        jdbc.update("INSERT INTO hypothesis(id,lineage_id,status,version,revision,parent_hyp_id,"
                        + "title,created_at,note) VALUES(?,?,?,?,?,?,?,?,?)",
                id, line, "DRAFT", null, 0, cloneFromId, title, Instant.now().toString(), note);
        if (cloneFromId != null) {
            cloneAssignments(cloneFromId, id);
        }
        logChange(id, 0, "CREATE_DRAFT", null, null,
                cloneFromId == null ? "blank draft" : "cloned from " + cloneFromId, actor);
        return requireHypothesis(id);
    }

    private boolean lineageHasDraft(String lineage) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM hypothesis WHERE lineage_id=? AND status='DRAFT'",
                Integer.class, lineage);
        return n != null && n > 0;
    }

    private void cloneAssignments(String fromHyp, String toHyp) {
        requireHypothesis(fromHyp);
        jdbc.update("INSERT INTO hypothesis_assignment(hyp_id,observation_id,individual_code,"
                        + "original_individual,decision,reason_code,reason_note,created_at,created_by) "
                        + "SELECT ?,observation_id,individual_code,original_individual,decision,"
                        + "reason_code,reason_note,?,created_by FROM hypothesis_assignment WHERE hyp_id=?",
                toHyp, Instant.now().toString(), fromHyp);
    }

    // --------------------------------------------------------- concurrency

    private Hypothesis requireDraft(String hypId) {
        Hypothesis h = requireHypothesis(hypId);
        if (!"DRAFT".equals(h.status())) {
            throw ApiException.badRequest("hypothesis " + hypId + " is published and immutable");
        }
        return h;
    }

    /** Optimistic-concurrency gate; the client must work from a known revision. */
    private int checkRevision(Hypothesis draft, Integer baseRevision) {
        if (baseRevision == null) {
            throw ApiException.badRequest("baseRevision is required for draft edits");
        }
        if (baseRevision < draft.revision()) {
            throw revisionConflict(draft, baseRevision);
        }
        if (baseRevision > draft.revision()) {
            throw ApiException.badRequest("baseRevision " + baseRevision
                    + " is ahead of server revision " + draft.revision());
        }
        return draft.revision();
    }

    private ApiException revisionConflict(Hypothesis draft, int baseRevision) {
        List<ChangeLog> others = changesAfter(draft.id(), baseRevision);
        Map<String, Object> body = new HashMap<>();
        body.put("error", "revision conflict: another reviewer changed this hypothesis");
        body.put("currentRevision", draft.revision());
        body.put("yourBaseRevision", baseRevision);
        body.put("retainYourChange", true);
        body.put("hint", "inspect concurrentChanges, then rebase your decision and resubmit");
        body.put("concurrentChanges", others);
        return new ApiException(409, "revision conflict", body);
    }

    private int bump(Hypothesis draft) {
        int next = draft.revision() + 1;
        jdbc.update("UPDATE hypothesis SET revision=? WHERE id=?", next, draft.id());
        return next;
    }

    private void logChange(String hypId, int revision, String action, String obsId,
                           String individual, String detail, String actor) {
        jdbc.update("INSERT INTO hypothesis_change(hyp_id,revision,action,observation_id,"
                        + "individual_code,detail,actor,created_at) VALUES(?,?,?,?,?,?,?,?)",
                hypId, revision, action, obsId, individual, detail,
                actor == null ? "researcher" : actor, Instant.now().toString());
    }

    // ------------------------------------------------------------ conflicts

    /**
     * Compare a prospective assignment against every confirmed observation of
     * the same individual in this hypothesis. Same-instant observations at
     * different places are always HARD. Within the window, average travel
     * speed classifies the pair: above UNREACHABLE_SPEED_KMH is HARD (candidate
     * rejected), above SUSPICIOUS_SPEED_KMH is SOFT (score lowered).
     */
    public List<ConflictCheck> evaluate(String hypId, Observation candidate, String individualCode) {
        if (candidate == null || individualCode == null
                || ObservationService.NEW_INDIVIDUAL.equals(individualCode)) {
            return List.of();
        }
        Site s1 = observations.site(candidate.siteCode());
        List<ConflictCheck> out = new ArrayList<>();
        for (Assignment a : confirmed(hypId)) {
            if (!individualCode.equals(a.individualCode()) || a.observationId() == null
                    || a.observationId().equals(candidate.id())) {
                continue;
            }
            Observation anchor = observations.observation(a.observationId());
            if (anchor == null || anchor.zero()) {
                continue;
            }
            long gapSec = Math.abs(candidate.observedEpoch() - anchor.observedEpoch());
            if (gapSec > CONFLICT_WINDOW_HOURS * 3600 && gapSec != 0) {
                continue;
            }
            Site s2 = observations.site(anchor.siteCode());
            double dist = Geo.distanceKm(s1.lat(), s1.lon(), s2.lat(), s2.lon());
            long gapHours = Math.round(gapSec / 3600.0);
            double speed = gapSec == 0
                    ? (s1.code().equals(s2.code()) ? 0 : Double.POSITIVE_INFINITY)
                    : dist / (gapSec / 3600.0);
            String level = "OK";
            if (s1.code().equals(s2.code()) && gapSec == 0) {
                level = "OK";
            } else if (gapSec == 0 || speed > UNREACHABLE_SPEED_KMH) {
                level = "HARD";
            } else if (speed > SUSPICIOUS_SPEED_KMH) {
                level = "SOFT";
            }
            if (!"OK".equals(level)) {
                out.add(new ConflictCheck(round(dist), gapHours,
                        Double.isInfinite(speed) ? -1 : round(speed),
                        anchor.id(), anchor.siteCode(), anchor.observedAt(), level));
            }
        }
        return out;
    }

    /** All pairwise HARD conflicts among currently confirmed assignments. */
    public List<Map<String, Object>> currentConflicts(String hypId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Assignment> done = confirmed(hypId);
        for (int i = 0; i < done.size(); i++) {
            for (int j = i + 1; j < done.size(); j++) {
                Assignment a = done.get(i);
                Assignment b = done.get(j);
                if (!java.util.Objects.equals(a.individualCode(), b.individualCode())) {
                    continue;
                }
                Observation oa = observations.observation(a.observationId());
                Observation ob = observations.observation(b.observationId());
                if (oa == null || ob == null || oa.zero() || ob.zero()) {
                    continue;
                }
                long gapSec = Math.abs(oa.observedEpoch() - ob.observedEpoch());
                if (gapSec > CONFLICT_WINDOW_HOURS * 3600 && gapSec != 0) {
                    continue;
                }
                for (ConflictCheck c : evaluate(hypId, oa, a.individualCode())) {
                    if ("HARD".equals(c.level()) && c.anchorObservationId().equals(ob.id())) {
                        Map<String, Object> row = new HashMap<>();
                        row.put("individualCode", a.individualCode());
                        row.put("observationA", oa.id());
                        row.put("observationB", ob.id());
                        row.put("check", c);
                        out.add(row);
                    }
                }
            }
        }
        return out;
    }

    private ConflictCheck worst(List<ConflictCheck> checks) {
        return checks.stream().filter(c -> "HARD".equals(c.level())).findFirst()
                .orElseGet(() -> checks.stream().filter(c -> "SOFT".equals(c.level())).findFirst()
                        .orElse(null));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ------------------------------------------------------ candidate view

    public List<Map<String, Object>> candidateViews(String hypId, String observationId) {
        requireHypothesis(hypId);
        Observation o = observations.observation(observationId);
        if (o == null) {
            throw ApiException.notFound("observation " + observationId);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (RawCandidate rc : observations.rawCandidates(observationId)) {
            String code = rc.individualCode();
            String label = ObservationService.NEW_INDIVIDUAL.equals(code)
                    ? "可能的新个体" : labelOf(code);
            double adjusted = rc.rawScore();
            String status = "ELIGIBLE";
            String reason = null;
            ConflictCheck conflict = null;
            if (!ObservationService.NEW_INDIVIDUAL.equals(code)) {
                ConflictCheck worst = worst(evaluate(hypId, o, code));
                if (worst != null) {
                    conflict = worst;
                    if ("HARD".equals(worst.level())) {
                        status = "REJECTED";
                        adjusted = 0;
                        reason = "时空不可达：" + describe(worst);
                    } else {
                        status = "SUSPICIOUS";
                        adjusted = round(rc.rawScore() * 0.5);
                        reason = "移动速度可疑，分数下调：" + describe(worst);
                    }
                }
            }
            Map<String, Object> view = new HashMap<>();
            view.put("individualCode", code);
            view.put("label", label);
            view.put("rawScore", round(rc.rawScore()));
            view.put("adjustedScore", round(adjusted));
            view.put("markComponent", round(rc.markComponent()));
            view.put("patternComponent", round(rc.patternComponent()));
            view.put("status", status);
            view.put("reason", reason);
            view.put("conflict", conflict);
            out.add(view);
        }
        out.sort((a, b) -> Double.compare((double) b.get("adjustedScore"),
                (double) a.get("adjustedScore")));
        return out;
    }

    private String describe(ConflictCheck c) {
        return "锚点 " + c.anchorObservationId() + " @ " + c.anchorSiteCode()
                + "，相距 " + c.distanceKm() + " km / " + c.gapHours() + " h，约 "
                + (c.speedKmh() < 0 ? "瞬时" : c.speedKmh() + " km/h");
    }

    private String labelOf(String code) {
        Individual ind = observations.individual(code);
        return ind == null ? code : ind.label();
    }

    // ------------------------------------------------------------- edits

    @Transactional
    public Map<String, Object> confirm(String hypId, String observationId, String individualCode,
                                       String reasonCode, String reasonNote, boolean force,
                                       Integer baseRevision, String actor) {
        Hypothesis draft = requireDraft(hypId);
        int fromRev = checkRevision(draft, baseRevision);
        Observation o = observations.observation(observationId);
        if (o == null) {
            throw ApiException.notFound("observation " + observationId);
        }
        if (o.zero()) {
            throw ApiException.badRequest("zero-count observations cannot be assigned to an individual");
        }
        String code = individualCode;
        String reason = reasonCode == null ? "MANUAL_REVIEW" : reasonCode;
        String actionDetail;
        if (ObservationService.NEW_INDIVIDUAL.equals(code)) {
            throw ApiException.badRequest(
                    "use POST /hypotheses/{id}/new-individual to register a new individual first");
        }
        if (observations.individual(code) == null) {
            throw ApiException.badRequest("unknown individual: " + code);
        }
        List<ConflictCheck> hardConflicts = evaluate(hypId, o, code).stream()
                .filter(c -> "HARD".equals(c.level())).toList();
        if (!hardConflicts.isEmpty() && !force) {
            throw ApiException.unprocessable(
                    "assignment blocked by unreachable spatio-temporal conflict; "
                            + "resend with force=true to keep the observation and override",
                    List.of(Map.of("observationId", observationId, "individualCode", code,
                            "conflicts", hardConflicts)));
        }
        replaceConfirmed(hypId, observationId, code, null,
                hardConflicts.isEmpty() ? reason : "OVERRIDE_CONFLICT", reasonNote, actor);
        actionDetail = "confirmed " + code + (hardConflicts.isEmpty() ? ""
                : " (override, " + hardConflicts.size() + " hard conflict)");
        int rev = bump(draft);
        logChange(hypId, rev, "CONFIRM", observationId, code,
                reasonNote == null ? actionDetail : actionDetail + " :: " + reasonNote, actor);
        return editResult(hypId, rev, fromRev);
    }

    @Transactional
    public Map<String, Object> deny(String hypId, String observationId, String individualCode,
                                    String reasonCode, String reasonNote,
                                    Integer baseRevision, String actor) {
        Hypothesis draft = requireDraft(hypId);
        int fromRev = checkRevision(draft, baseRevision);
        if (observations.observation(observationId) == null) {
            throw ApiException.notFound("observation " + observationId);
        }
        if (reasonCode == null) {
            throw ApiException.badRequest(
                    "denial requires a reasonCode: MARK_LOST | DUPLICATE_CODE | DATA_ENTRY_ERROR");
        }
        jdbc.update("DELETE FROM hypothesis_assignment WHERE hyp_id=? AND observation_id=? "
                + "AND decision='CONFIRMED'", hypId, observationId);
        jdbc.update("INSERT INTO hypothesis_assignment(hyp_id,observation_id,individual_code,"
                        + "original_individual,decision,reason_code,reason_note,created_at,created_by)"
                        + " VALUES(?,?,?,?,'DENIED',?,?,?,?)",
                hypId, observationId, individualCode, individualCode,
                reasonCode, reasonNote, Instant.now().toString(),
                actor == null ? "researcher" : actor);
        int rev = bump(draft);
        logChange(hypId, rev, "DENY", observationId, individualCode, reasonCode
                + (reasonNote == null ? "" : " :: " + reasonNote), actor);
        return editResult(hypId, rev, fromRev);
    }

    /** Register a brand-new individual and confirm the observation against it. */
    @Transactional
    public Map<String, Object> confirmNew(String hypId, String observationId, String label,
                                          String marking, String patternSummary,
                                          String reasonNote, Integer baseRevision, String actor) {
        Hypothesis draft = requireDraft(hypId);
        int fromRev = checkRevision(draft, baseRevision);
        Observation o = observations.observation(observationId);
        if (o == null || o.zero()) {
            throw ApiException.badRequest("observation missing or zero-count");
        }
        String code = nextIndividualCode();
        jdbc.update("INSERT INTO individual(code,label,marking,pattern_summary,created_batch,created_at)"
                        + " VALUES(?,?,?,?,?,?)",
                code, label == null ? code : label,
                marking == null ? o.markFragment() : marking,
                patternSummary == null ? o.patternSummary() : patternSummary,
                o.batchRef(), Instant.now().toString());
        observations.scoreObservation(observationId);
        replaceConfirmed(hypId, observationId, code, null, "NEW_INDIVIDUAL", reasonNote, actor);
        int rev = bump(draft);
        logChange(hypId, rev, "NEW_INDIVIDUAL", observationId, code,
                reasonNote == null ? "registered new individual" : reasonNote, actor);
        return editResult(hypId, rev, fromRev);
    }

    /**
     * Split: move a subset of observations from one identity to another (new
     * or pre-existing target) inside this hypothesis. Observations are never
     * deleted; each moved row keeps its original identity for audit.
     */
    @Transactional
    public Map<String, Object> split(String hypId, String fromIndividual, String toIndividual,
                                     List<String> observationIds, String reasonNote,
                                     Integer baseRevision, String actor) {
        Hypothesis draft = requireDraft(hypId);
        int fromRev = checkRevision(draft, baseRevision);
        String target = toIndividual;
        if (target == null || target.isBlank()) {
            target = nextIndividualCode();
            jdbc.update("INSERT INTO individual(code,label,marking,pattern_summary,created_batch,created_at)"
                            + " VALUES(?,?,NULL,NULL,?,?)",
                    target, target + " (split)", hypId, Instant.now().toString());
        } else if (observations.individual(target) == null) {
            throw ApiException.badRequest("unknown split target individual: " + target);
        }
        int moved = 0;
        for (String obsId : observationIds == null ? List.<String>of() : observationIds) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM hypothesis_assignment "
                            + "WHERE hyp_id=? AND observation_id=? AND decision='CONFIRMED' "
                            + "AND individual_code=?", Integer.class, hypId, obsId, fromIndividual);
            if (n != null && n > 0) {
                jdbc.update("DELETE FROM hypothesis_assignment WHERE hyp_id=? AND observation_id=? "
                        + "AND decision='CONFIRMED'", hypId, obsId);
                jdbc.update("INSERT INTO hypothesis_assignment(hyp_id,observation_id,individual_code,"
                                + "original_individual,decision,reason_code,reason_note,created_at,created_by)"
                                + " VALUES(?,?,?,?,'SPLIT','SPLIT',?,?,?)",
                        hypId, obsId, target, fromIndividual,
                        reasonNote, Instant.now().toString(), actor == null ? "researcher" : actor);
                moved++;
            }
        }
        int rev = bump(draft);
        logChange(hypId, rev, "SPLIT", null, target,
                moved + " observations split from " + fromIndividual + (reasonNote == null ? ""
                        : " :: " + reasonNote), actor);
        return editResult(hypId, rev, fromRev);
    }

    /**
     * Merge: inside this hypothesis, every confirmed observation of the source
     * identity is reassigned to the target identity. Source individuals remain
     * in the registry (layered history); merge is a hypothesis-level opinion.
     */
    @Transactional
    public Map<String, Object> merge(String hypId, String fromIndividual, String intoIndividual,
                                     String reasonNote, Integer baseRevision, String actor) {
        Hypothesis draft = requireDraft(hypId);
        int fromRev = checkRevision(draft, baseRevision);
        if (observations.individual(fromIndividual) == null
                || observations.individual(intoIndividual) == null) {
            throw ApiException.badRequest("both merge individuals must exist");
        }
        List<Assignment> rows = jdbc.query(
                "SELECT * FROM hypothesis_assignment WHERE hyp_id=? AND decision='CONFIRMED' "
                        + "AND individual_code=?", ASM, hypId, fromIndividual);
        for (Assignment a : rows) {
            jdbc.update("DELETE FROM hypothesis_assignment WHERE id=?", a.id());
            jdbc.update("INSERT INTO hypothesis_assignment(hyp_id,observation_id,individual_code,"
                            + "original_individual,decision,reason_code,reason_note,created_at,created_by)"
                            + " VALUES(?,?,?,?,'MERGED','MERGE',?,?,?)",
                    hypId, a.observationId(), intoIndividual, fromIndividual,
                    reasonNote, Instant.now().toString(), actor == null ? "researcher" : actor);
        }
        int rev = bump(draft);
        logChange(hypId, rev, "MERGE", null, intoIndividual,
                rows.size() + " observations merged from " + fromIndividual + (reasonNote == null ? ""
                        : " :: " + reasonNote), actor);
        return editResult(hypId, rev, fromRev);
    }

    private void replaceConfirmed(String hypId, String observationId, String code,
                                  String original, String reasonCode, String reasonNote,
                                  String actor) {
        jdbc.update("DELETE FROM hypothesis_assignment WHERE hyp_id=? AND observation_id=? "
                + "AND decision='CONFIRMED'", hypId, observationId);
        jdbc.update("INSERT INTO hypothesis_assignment(hyp_id,observation_id,individual_code,"
                        + "original_individual,decision,reason_code,reason_note,created_at,created_by)"
                        + " VALUES(?,?,?,?,'CONFIRMED',?,?,?,?)",
                hypId, observationId, code, original, reasonCode, reasonNote,
                Instant.now().toString(), actor == null ? "researcher" : actor);
    }

    private Map<String, Object> editResult(String hypId, int revision, int fromRevision) {
        Map<String, Object> body = new HashMap<>();
        body.put("hypothesisId", hypId);
        body.put("revision", revision);
        body.put("previousRevision", fromRevision);
        body.put("concurrentChanges", List.of());
        body.put("conflicts", currentConflicts(hypId));
        return body;
    }

    private String nextIndividualCode() {
        List<String> codes = jdbc.queryForList(
                "SELECT code FROM individual WHERE code GLOB 'I-[0-9]*'", String.class);
        int max = codes.stream().mapToInt(c -> {
            try {
                return Integer.parseInt(c.substring(2));
            } catch (NumberFormatException e) {
                return 0;
            }
        }).max().orElse(0);
        return String.format("I-%03d", max + 1);
    }

    // ------------------------------------------------------------ publish

    /**
     * Freeze a draft into an immutable PUBLISHED snapshot, then reopen a fresh
     * draft on the same lineage so review can continue. Publication is
     * idempotent: republishing the same revision returns the existing version.
     */
    @Transactional
    public Map<String, Object> publish(String hypId, Integer baseRevision, String actor) {
        Hypothesis draft = requireDraft(hypId);
        int rev = checkRevision(draft, baseRevision);

        // Only THIS draft's own prior publication can be an idempotent replay;
        // a draft cloned from another lineage's snapshot starts un-published.
        List<Map<String, Object>> prior = jdbc.queryForList(
                "SELECT ph.id AS pub_id, ph.version AS pub_version, p.revision AS pub_revision "
                        + "FROM publication p JOIN hypothesis ph ON ph.id=p.hyp_id "
                        + "JOIN hypothesis d ON d.lineage_id=ph.lineage_id AND d.status='DRAFT' "
                        + "WHERE d.id=? ORDER BY ph.version DESC LIMIT 1", hypId);
        if (!prior.isEmpty()) {
            Object publishedRevision = prior.get(0).get("pub_revision");
            if (((Number) publishedRevision).intValue() == rev) {
                Map<String, Object> same = new HashMap<>();
                same.put("id", prior.get(0).get("pub_id"));
                same.put("version", prior.get(0).get("pub_version"));
                same.put("revision", rev);
                same.put("idempotentReplay", true);
                return same;
            }
        }

        List<Integer> versions = jdbc.queryForList(
                "SELECT version FROM hypothesis WHERE lineage_id=? AND status='PUBLISHED'",
                Integer.class, draft.lineageId());
        int version = versions.stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        String pubId = hypId + "-v" + version;
        String now = Instant.now().toString();
        jdbc.update("INSERT INTO hypothesis(id,lineage_id,status,version,revision,parent_hyp_id,"
                        + "title,created_at,published_at,note) VALUES(?,?,'PUBLISHED',?,?,?,?,?,?,?)",
                pubId, draft.lineageId(), version, rev, draft.parentHypId(),
                draft.title() + " (v" + version + ")", now, now, draft.note());
        cloneAssignments(hypId, pubId);
        jdbc.update("INSERT INTO publication(hyp_id,revision,published_at) VALUES(?,?,?)",
                pubId, rev, now);
        jdbc.update("UPDATE hypothesis SET revision=0 WHERE id=?", hypId);
        jdbc.update("DELETE FROM hypothesis_change WHERE hyp_id=?", hypId);
        logChange(hypId, 0, "REOPEN_AFTER_PUBLISH", null, null,
                "published as " + pubId + " (v" + version + ")", actor);
        Map<String, Object> out = new HashMap<>();
        out.put("id", pubId);
        out.put("version", version);
        out.put("idempotentReplay", false);
        out.put("revision", rev);
        out.put("reopenedDraft", hypId);
        return out;
    }

    public Hypothesis latestPublished(String lineageId) {
        List<Hypothesis> list = jdbc.query(
                HYP_SELECT + "WHERE h.lineage_id=? AND h.status='PUBLISHED' "
                        + "ORDER BY h.version DESC LIMIT 1", HYP, lineageId);
        return list.isEmpty() ? null : list.get(0);
    }

    // ------------------------------------------------------------- mappers

    static final RowMapper<Hypothesis> HYP = (rs, n) -> {
        Integer version = (Integer) rs.getObject("version");
        String id = rs.getString("id");
        Integer confirmed = rs.getObject("confirmed_count", Integer.class);
        Integer denied = rs.getObject("denied_count", Integer.class);
        Integer unresolved = rs.getObject("unresolved_count", Integer.class);
        return new Hypothesis(id, rs.getString("lineage_id"), rs.getString("status"), version,
                rs.getInt("revision"), rs.getString("parent_hyp_id"), rs.getString("title"),
                rs.getString("created_at"), rs.getString("published_at"), rs.getString("note"),
                confirmed == null ? 0 : confirmed, denied == null ? 0 : denied,
                unresolved == null ? 0 : unresolved);
    };

    static final RowMapper<Assignment> ASM = (rs, n) -> new Assignment(
            (Integer) rs.getObject("id"), rs.getString("hyp_id"),
            rs.getString("observation_id"), rs.getString("individual_code"),
            rs.getString("original_individual"), rs.getString("decision"),
            rs.getString("reason_code"), rs.getString("reason_note"),
            rs.getString("created_at"), rs.getString("created_by"));

    static final RowMapper<ChangeLog> CHANGE = (rs, n) -> new ChangeLog(
            rs.getLong("id"), rs.getString("hyp_id"), rs.getInt("revision"),
            rs.getString("action"), rs.getString("observation_id"),
            rs.getString("individual_code"), rs.getString("detail"),
            rs.getString("actor"), rs.getString("created_at"));
}
