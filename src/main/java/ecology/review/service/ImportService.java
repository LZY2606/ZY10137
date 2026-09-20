package ecology.review.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ecology.review.dto.Requests.ImportBatch;
import ecology.review.dto.Requests.IndividualInput;
import ecology.review.dto.Requests.ObservationInput;
import ecology.review.dto.Requests.SiteInput;
import ecology.review.dto.Requests.SurveyInput;
import ecology.review.dto.Responses.ImportResponse;
import ecology.review.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportService {
  private final JdbcTemplate db;
  private final ObjectMapper objectMapper;
  private final ScoringService scoringService;

  public ImportService(JdbcTemplate db, ObjectMapper objectMapper, ScoringService scoringService) {
    this.db = db;
    this.objectMapper = objectMapper;
    this.scoringService = scoringService;
  }

  @Transactional
  public ImportResponse importBatch(ImportBatch batch) {
    validateBatch(batch);
    String payloadHash = sha256(writeJson(batch));
    List<Map<String, Object>> existing = db.queryForList(
        "SELECT id FROM import_batches WHERE batch_id = ?", batch.batchId());
    if (!existing.isEmpty()) {
      String storedHash = db.queryForObject(
          "SELECT payload_hash FROM import_batches WHERE batch_id = ?", String.class, batch.batchId());
      if (!payloadHash.equals(storedHash)) {
        throw new ApiException(409, "batch_id was already used with a different payload");
      }
      Long revisionId = db.queryForList(
          "SELECT id FROM hypothesis_revisions WHERE client_key = ?", baselineKey(batch.batchId()))
          .stream().findFirst().map(row -> ((Number) row.get("id")).longValue()).orElse(null);
      return new ImportResponse(batch.batchId(), true, 0, 0, 0, 0, revisionId);
    }

    int siteCount = upsertSites(batch.sites() == null ? List.of() : batch.sites());
    int surveyCount = upsertSurveys(batch.surveys() == null ? List.of() : batch.surveys());
    int individualCount = upsertIndividuals(batch.individuals() == null ? List.of() : batch.individuals());
    int observationCount = insertObservations(batch);

    db.update("INSERT INTO import_batches(batch_id, source, payload_hash, imported_at) VALUES (?,?,?,?)",
        batch.batchId(), batch.source(), payloadHash, Instant.now().toString());

    Long baselineRevisionId = null;
    if (Boolean.TRUE.equals(batch.baseline())) {
      baselineRevisionId = publishBaseline(batch.batchId(), batch.source());
    }
    scoringService.recalculateAll();
    return new ImportResponse(batch.batchId(), false, siteCount, surveyCount,
        individualCount, observationCount, baselineRevisionId);
  }

  private void validateBatch(ImportBatch batch) {
    if (batch == null || isBlank(batch.batchId())) {
      throw new ApiException(400, "batch_id is required");
    }
    if (batch.observations() != null) {
      for (ObservationInput observation : batch.observations()) {
        require(observation != null, "observation entries must not be null");
        require(!isBlank(observation.code()), "observation code is required");
        require(!isBlank(observation.siteCode()), "site_code is required for " + observation.code());
        require(!isBlank(observation.observedAt()), "observed_at is required for " + observation.code());
        require(!isBlank(observation.markerFragment()), "marker_fragment is required");
        require(!isBlank(observation.patternSummary()), "pattern_summary is required");
        require(!isBlank(observation.observer()), "observer is required");
        if (observation.confidence() == null || observation.confidence() < 0 || observation.confidence() > 1) {
          throw new ApiException(400, "confidence must be between 0 and 1");
        }
      }
    }
    if (batch.surveys() != null) {
      for (SurveyInput survey : batch.surveys()) {
        require(survey != null, "survey entries must not be null");
        require(!isBlank(survey.code()), "survey code is required");
        require(!isBlank(survey.zoneId()), "survey zone_id is required");
        require(!isBlank(survey.startAt()) && !isBlank(survey.endAt()), "survey bounds are required");
        try {
          Instant start = Support.zonedInstant(survey.startAt(), survey.zoneId());
          Instant end = Support.zonedInstant(survey.endAt(), survey.zoneId());
          if (!end.isAfter(start)) {
            throw new ApiException(400, "survey end must be after start: " + survey.code());
          }
        } catch (DateTimeParseException ex) {
          throw new ApiException(400, "invalid survey time: " + survey.code());
        }
      }
    }
  }

  private int upsertSites(List<SiteInput> sites) {
    for (SiteInput site : sites) {
      require(site != null && !isBlank(site.code()), "site code is required");
      require(site.latitude() != null && site.longitude() != null, "site coordinates are required");
      db.update("""
          INSERT INTO sites(code, name, latitude, longitude) VALUES (?,?,?,?)
          ON CONFLICT(code) DO UPDATE SET name=excluded.name,
            latitude=excluded.latitude, longitude=excluded.longitude
          """, site.code(), blankTo(site.name(), site.code()), site.latitude(), site.longitude());
    }
    return sites.size();
  }

  private int upsertSurveys(List<SurveyInput> surveys) {
    for (SurveyInput survey : surveys) {
      db.update("""
          INSERT INTO surveys(code, name, zone_id, start_at, end_at) VALUES (?,?,?,?,?)
          ON CONFLICT(code) DO UPDATE SET name=excluded.name, zone_id=excluded.zone_id,
            start_at=excluded.start_at, end_at=excluded.end_at
          """, survey.code(), blankTo(survey.name(), survey.code()), survey.zoneId(),
          Support.zonedInstant(survey.startAt(), survey.zoneId()).toString(),
          Support.zonedInstant(survey.endAt(), survey.zoneId()).toString());
      List<String> siteCodes = survey.siteCodes() == null ? List.of() : survey.siteCodes();
      for (String siteCode : siteCodes) {
        Long siteId = requireSite(siteCode);
        db.update("INSERT OR IGNORE INTO survey_sites(survey_id, site_id) VALUES (?,?)",
            surveyId(survey.code()), siteId);
      }
    }
    return surveys.size();
  }

  private int upsertIndividuals(List<IndividualInput> individuals) {
    String now = Instant.now().toString();
    for (IndividualInput individual : individuals) {
      require(individual != null && !isBlank(individual.code()), "individual code is required");
      db.update("""
          INSERT INTO individuals(code, display_name, status, created_at)
          VALUES (?,?, 'ACTIVE', ?)
          ON CONFLICT(code) DO UPDATE SET display_name=excluded.display_name, status='ACTIVE'
          """, individual.code(), blankTo(individual.displayName(), individual.code()), now);
    }
    return individuals.size();
  }

  private int insertObservations(ImportBatch batch) {
    int inserted = 0;
    for (ObservationInput observation : batch.observations()) {
      if (db.queryForObject("SELECT COUNT(*) FROM observations WHERE code = ?", Integer.class,
          observation.code()) > 0) {
        continue;
      }
      Long siteId = requireSite(observation.siteCode());
      Long surveyId = findSurveyAt(siteId, Support.parseInstant(observation.observedAt(), surveyZoneHint(batch, observation)));
      Instant observedAt = Support.parseInstant(observation.observedAt(),
          db.queryForObject("SELECT zone_id FROM surveys WHERE id = ?", String.class, surveyId));
      db.update("""
        INSERT INTO observations(code, batch_id, site_id, survey_id, observed_at, uncertainty_seconds,
            marker_fragment, pattern_summary, confidence, observer, baseline_individual_code)
          VALUES (?,?,?,?,?,?,?,?,?,?,?)
          """, observation.code(), batch.batchId(), siteId, surveyId, observedAt.toString(),
          observation.uncertaintySeconds() == null ? 0 : observation.uncertaintySeconds(),
          observation.markerFragment(), observation.patternSummary(),
          observation.confidence(), observation.observer(), observation.individualCode());
      inserted++;
    }
    return inserted;
  }

  private Long publishBaseline(String batchId, String source) {
    String key = baselineKey(batchId);
    Long existing = db.queryForList("SELECT revision_id FROM revision_client_keys WHERE client_key = ?", key)
        .stream().findFirst().map(row -> ((Number) row.get("revision_id")).longValue()).orElse(null);
    if (existing != null) {
      return existing;
    }
    String now = Instant.now().toString();
    db.update("""
        INSERT INTO hypothesis_revisions(revision_no, based_on_revision_id, status, note, author,
          client_key, version, created_at, published_at)
        VALUES (1, NULL, 'PUBLISHED', ?, 'system', ?, 0, ?, ?)
        """, "Baseline " + blankTo(source, batchId), key, now, now);
    Long revisionId = db.queryForObject("SELECT id FROM hypothesis_revisions WHERE client_key = ?",
        Long.class, key);
    db.update("INSERT INTO revision_client_keys(client_key, revision_id) VALUES (?,?)", key, revisionId);
    db.update("INSERT INTO publish_client_keys(client_key, revision_id, published_at) VALUES (?,?,?)",
        "publish-" + key, revisionId, now);

    List<Map<String, Object>> rows = db.queryForList("""
        SELECT o.id AS observation_id, i.id AS individual_id
        FROM observations o JOIN individuals i ON i.code = o.baseline_individual_code
        WHERE o.batch_id = ?
        """, batchId);
    for (Map<String, Object> row : rows) {
      Long observationId = ((Number) row.get("observation_id")).longValue();
      Long individualId = ((Number) row.get("individual_id")).longValue();
      db.update("INSERT INTO revision_items(revision_id, observation_id, individual_id) VALUES (?,?,?)",
          revisionId, observationId, individualId);
      db.update("INSERT INTO published_assignments(revision_id, observation_id, individual_id) VALUES (?,?,?)",
          revisionId, observationId, individualId);
    }
    return revisionId;
  }

  private Long findSurveyAt(Long siteId, Instant instant) {
    List<Long> surveyIds = db.queryForList("""
        SELECT s.id FROM surveys s
        JOIN survey_sites ss ON ss.survey_id = s.id
        WHERE ss.site_id = ? AND ? >= s.start_at AND ? < s.end_at
        ORDER BY s.start_at
        """, Long.class, siteId, instant.toString(), instant.toString());
    if (!surveyIds.isEmpty()) {
      return surveyIds.get(0);
    }
    surveyIds = db.queryForList("""
        SELECT s.id FROM surveys s
        WHERE ? >= s.start_at AND ? < s.end_at
        ORDER BY (
          SELECT COUNT(*) FROM survey_sites ss WHERE ss.survey_id = s.id AND ss.site_id = ?
        ) DESC, s.start_at
        """, Long.class, instant.toString(), instant.toString(), siteId);
    if (surveyIds.isEmpty()) {
      throw new ApiException(422, "observation at " + instant + " is outside every explicit survey interval");
    }
    Long surveyId = surveyIds.get(0);
    db.update("INSERT OR IGNORE INTO survey_sites(survey_id, site_id) VALUES (?,?)", surveyId, siteId);
    return surveyId;
  }

  private String surveyZoneHint(ImportBatch batch, ObservationInput observation) {
    if (batch.surveys() == null) {
      return "UTC";
    }
    return batch.surveys().stream()
        .filter(survey -> survey.siteCodes() != null && survey.siteCodes().contains(observation.siteCode()))
        .map(SurveyInput::zoneId).findFirst().orElse("UTC");
  }

  private Long requireSite(String code) {
    List<Long> ids = db.queryForList("SELECT id FROM sites WHERE code = ?", Long.class, code);
    if (ids.isEmpty()) {
      throw new ApiException(422, "unknown site: " + code);
    }
    return ids.get(0);
  }

  private Long surveyId(String code) {
    return db.queryForObject("SELECT id FROM surveys WHERE code = ?", Long.class, code);
  }

  private String writeJson(ImportBatch batch) {
    try {
      return objectMapper.writeValueAsString(batch);
    } catch (JsonProcessingException ex) {
      throw new ApiException(400, "invalid import payload");
    }
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String baselineKey(String batchId) {
    return "baseline-" + batchId;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new ApiException(400, message);
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String blankTo(String value, String fallback) {
    return isBlank(value) ? fallback : value;
  }
}
