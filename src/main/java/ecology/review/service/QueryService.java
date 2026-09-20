package ecology.review.service;

import ecology.review.dto.Responses.CandidateView;
import ecology.review.dto.Responses.CaptureCell;
import ecology.review.dto.Responses.CaptureHistory;
import ecology.review.dto.Responses.CaptureSummary;
import ecology.review.dto.Responses.ConflictView;
import ecology.review.dto.Responses.IndividualView;
import ecology.review.dto.Responses.ObservationView;
import ecology.review.dto.Responses.RevisionItemView;
import ecology.review.dto.Responses.RevisionView;
import ecology.review.dto.Responses.SiteView;
import ecology.review.dto.Responses.SurveyView;
import ecology.review.dto.Responses.TimelineResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QueryService {
  private final JdbcTemplate db;

  public QueryService(JdbcTemplate db) {
    this.db = db;
  }

  @Transactional(readOnly = true)
  public TimelineResponse timeline(Long activeRevisionId) {
    List<SurveyView> surveys = surveys();
    List<IndividualView> individuals = individuals();
    List<RevisionView> revisions = revisions();
    RevisionView activeRevision = activeRevisionId == null ? latestDraftOrPublished() : revision(activeRevisionId);
    List<ObservationView> observations = observations(activeRevision);
    return new TimelineResponse(surveys, observations, individuals, revisions, activeRevision);
  }

  public List<SurveyView> surveys() {
    List<SurveyView> result = new ArrayList<>();
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT * FROM surveys ORDER BY start_at, code");
    for (Map<String, Object> row : rows) {
      Long id = ((Number) row.get("id")).longValue();
      List<String> sites = db.queryForList("""
          SELECT s.code FROM sites s JOIN survey_sites ss ON ss.site_id = s.id
          WHERE ss.survey_id = ? ORDER BY s.code
          """, String.class, id);
      result.add(new SurveyView(id, str(row.get("code")), str(row.get("name")), str(row.get("zone_id")),
          str(row.get("start_at")), str(row.get("end_at")), sites));
    }
    return result;
  }

  public List<IndividualView> individuals() {
    return db.queryForList("SELECT * FROM individuals ORDER BY status, code").stream()
        .map(this::individual).toList();
  }

  public RevisionView revision(Long revisionId) {
    return toRevision(db.queryForMap("SELECT * FROM hypothesis_revisions WHERE id = ?", revisionId));
  }

  public List<RevisionView> revisions() {
    return db.queryForList("SELECT * FROM hypothesis_revisions ORDER BY id").stream()
        .map(this::toRevision).toList();
  }

  public List<ObservationView> observations(RevisionView activeRevision) {
    return db.queryForList("""
        SELECT o.*, s.code AS site_code, s.name AS site_name, s.latitude, s.longitude,
               su.code AS survey_code, su.id AS survey_id
        FROM observations o
        JOIN sites s ON s.id = o.site_id
        JOIN surveys su ON su.id = o.survey_id
        ORDER BY o.observed_at, o.id
        """).stream().map(row -> observation(row, activeRevision)).toList();
  }

  private ObservationView observation(Map<String, Object> row, RevisionView activeRevision) {
    Long observationId = ((Number) row.get("id")).longValue();
    Long latestRevisionId = latestPublishedId();
    Long publishedId = assignedIndividual(latestRevisionId, observationId);
    Long draftId = activeRevision == null ? null : assignedIndividual(activeRevision.id(), observationId);
    List<CandidateView> candidates = candidates(observationId, activeRevision);
    return new ObservationView(observationId, str(row.get("code")), str(row.get("batch_id")),
        str(row.get("site_code")), str(row.get("site_name")),
        ((Number) row.get("latitude")).doubleValue(), ((Number) row.get("longitude")).doubleValue(),
        ((Number) row.get("survey_id")).longValue(), str(row.get("survey_code")),
        str(row.get("observed_at")), ((Number) row.get("uncertainty_seconds")).intValue(),
        str(row.get("marker_fragment")), str(row.get("pattern_summary")),
        ((Number) row.get("confidence")).doubleValue(), str(row.get("observer")),
        publishedId, individualCode(publishedId), draftId, candidates, true);
  }

  private List<CandidateView> candidates(Long observationId, RevisionView activeRevision) {
    List<Map<String, Object>> rows = db.queryForList("""
        SELECT cs.*, i.code AS individual_code, i.display_name
        FROM candidate_scores cs JOIN individuals i ON i.id = cs.individual_id
        WHERE cs.observation_id = ?
        ORDER BY CASE cs.auto_state WHEN 'CANDIDATE' THEN 0 ELSE 1 END, cs.score DESC, i.code
        """, observationId);
    List<CandidateView> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Long individualId = ((Number) row.get("individual_id")).longValue();
      String decision = decision(activeRevision, observationId, individualId);
      String autoState = str(row.get("auto_state"));
      String effective = decision != null ? decision : autoState;
      String reasonCode = null;
      String reasonDetail = null;
      if (activeRevision != null) {
        List<Map<String, Object>> decisions = db.queryForList("""
            SELECT reason_code, reason_detail FROM candidate_decisions
            WHERE revision_id = ? AND observation_id = ? AND individual_id = ?
            """, activeRevision.id(), observationId, individualId);
        if (!decisions.isEmpty()) {
          reasonCode = str(decisions.get(0).get("reason_code"));
          reasonDetail = str(decisions.get(0).get("reason_detail"));
        }
      }
      Long revisionId = latestPublishedId();
      List<String> markers = anchorFeatures("marker_fragment", revisionId, individualId);
      List<String> patterns = anchorFeatures("pattern_summary", revisionId, individualId);
      result.add(new CandidateView(individualId, str(row.get("individual_code")),
          str(row.get("display_name")), ((Number) row.get("score")).doubleValue(),
          autoState, effective, decision, reasonCode, reasonDetail, conflict(row),
          markers, patterns));
    }
    return result;
  }

  private List<String> anchorFeatures(String column, Long revisionId, Long individualId) {
    if (revisionId == null || !column.equals("marker_fragment") && !column.equals("pattern_summary")) {
      return List.of();
    }
    String sql = column.equals("marker_fragment")
        ? """
          SELECT o.marker_fragment
          FROM published_assignments pa JOIN observations o ON o.id = pa.observation_id
          WHERE pa.revision_id = ? AND pa.individual_id = ?
          ORDER BY o.observed_at
          """
        : """
          SELECT o.pattern_summary
          FROM published_assignments pa JOIN observations o ON o.id = pa.observation_id
          WHERE pa.revision_id = ? AND pa.individual_id = ?
          ORDER BY o.observed_at
          """;
    return db.queryForList(sql, String.class, revisionId, individualId).stream()
        .distinct().toList();
  }

  private ConflictView conflict(Map<String, Object> row) {
    if (row.get("distance_m") == null) {
      return null;
    }
    return new ConflictView(((Number) row.get("distance_m")).doubleValue(),
        ((Number) row.get("elapsed_seconds")).doubleValue(),
        ((Number) row.get("required_speed_mps")).doubleValue(),
        ScoringService.MAX_TRAVEL_SPEED_MPS, str(row.get("conflict_reason")));
  }

  private RevisionView toRevision(Map<String, Object> row) {
    Long revisionId = ((Number) row.get("id")).longValue();
    List<RevisionItemView> items = db.queryForList("""
        SELECT ri.observation_id, o.code AS observation_code, ri.individual_id, i.code AS individual_code
        FROM revision_items ri
        JOIN observations o ON o.id = ri.observation_id
        LEFT JOIN individuals i ON i.id = ri.individual_id
        WHERE ri.revision_id = ?
        ORDER BY ri.observation_id
        """, revisionId).stream().map(item -> new RevisionItemView(
        ((Number) item.get("observation_id")).longValue(),
        str(item.get("observation_code")),
        item.get("individual_id") == null ? null : ((Number) item.get("individual_id")).longValue(),
        str(item.get("individual_code")))).toList();
    return new RevisionView(revisionId, ((Number) row.get("revision_no")).intValue(),
        row.get("based_on_revision_id") == null ? null
            : ((Number) row.get("based_on_revision_id")).longValue(),
        str(row.get("status")), str(row.get("note")), str(row.get("author")),
        str(row.get("client_key")), ((Number) row.get("version")).intValue(),
        str(row.get("created_at")), str(row.get("published_at")), items);
  }

  private IndividualView individual(Map<String, Object> row) {
    return new IndividualView(((Number) row.get("id")).longValue(), str(row.get("code")),
        str(row.get("display_name")), str(row.get("status")),
        row.get("merged_into_id") == null ? null : ((Number) row.get("merged_into_id")).longValue());
  }

  @Transactional(readOnly = true)
  public List<CaptureCell> captureOccasions() {
    return db.queryForList("""
        SELECT su.code AS survey_code, su.name AS survey_name, su.zone_id, su.start_at, su.end_at,
               s.code AS site_code, s.name AS site_name,
               COUNT(DISTINCT o.id) AS captures
        FROM survey_sites ss
        JOIN surveys su ON su.id = ss.survey_id
        JOIN sites s ON s.id = ss.site_id
        LEFT JOIN observations o ON o.survey_id = su.id AND o.site_id = s.id
        GROUP BY su.id, s.id
        ORDER BY su.start_at, s.code
        """).stream().map(this::captureCell).toList();
  }

  private CaptureCell captureCell(Map<String, Object> row) {
    int captures = ((Number) row.get("captures")).intValue();
    String status = captures > 0 ? "OBSERVED" : "ZERO";
    return new CaptureCell(str(row.get("survey_code")), str(row.get("survey_name")),
        str(row.get("site_code")), str(row.get("site_name")), status,
        captures, null, null, str(row.get("start_at")), str(row.get("end_at")),
        str(row.get("zone_id")));
  }

  @Transactional(readOnly = true)
  public CaptureSummary captureSummary() {
    List<CaptureCell> cells = captureOccasions();
    int observed = (int) cells.stream().filter(cell -> cell.status().equals("OBSERVED")).count();
    int captures = cells.stream().mapToInt(CaptureCell::capture).sum();
    double probability = cells.isEmpty() ? 0 : (double) observed / cells.size();
    return new CaptureSummary(cells.size(), observed, captures, probability,
        "raw proportion of sampled occasions with at least one observation; unresolved identities and unsurveyed occasions are not inferred as zero captures");
  }

  @Transactional(readOnly = true)
  public List<CaptureHistory> captureHistories() {
    Long revisionId = latestPublishedId();
    List<Map<String, Object>> occasions = db.queryForList("""
        SELECT su.code AS survey_code, su.name AS survey_name, su.zone_id, su.start_at, su.end_at,
               s.code AS site_code, s.name AS site_name,
               CASE WHEN ss.site_id IS NULL THEN 0 ELSE 1 END AS sampled
        FROM surveys su CROSS JOIN sites s
        LEFT JOIN survey_sites ss ON ss.survey_id = su.id AND ss.site_id = s.id
        ORDER BY su.start_at, s.code
        """);
    Map<Long, CaptureHistoryBuilder> builders = new LinkedHashMap<>();
    for (Map<String, Object> individualRow : db.queryForList(
        "SELECT id, code FROM individuals ORDER BY code")) {
      Long individualId = ((Number) individualRow.get("id")).longValue();
      builders.put(individualId, new CaptureHistoryBuilder(individualId, str(individualRow.get("code"))));
    }
    for (Map<String, Object> occasion : occasions) {
      boolean sampled = ((Number) occasion.get("sampled")).intValue() > 0;
      List<Map<String, Object>> assigned = revisionId == null ? List.of() : db.queryForList("""
            SELECT pa.individual_id, i.code AS individual_code
            FROM published_assignments pa
            JOIN observations o ON o.id = pa.observation_id
            JOIN surveys su ON su.id = o.survey_id
            JOIN sites s ON s.id = o.site_id
            JOIN individuals i ON i.id = pa.individual_id
            WHERE pa.revision_id = ? AND su.code = ? AND s.code = ?
            """, revisionId, str(occasion.get("survey_code")), str(occasion.get("site_code")));
      for (CaptureHistoryBuilder builder : builders.values()) {
        Map<String, Object> row = assigned.stream()
            .filter(candidate -> ((Number) candidate.get("individual_id")).longValue() == builder.individualId)
            .findFirst().orElse(null);
        String status;
        Integer capture;
        String individualCode = null;
        if (row != null) {
          status = "OBSERVED";
          capture = 1;
          individualCode = str(row.get("individual_code"));
        } else {
          status = sampled ? "ZERO" : "NOT_OBSERVED";
          capture = sampled ? 0 : null;
        }
        builder.cells.add(new CaptureCell(str(occasion.get("survey_code")),
            str(occasion.get("survey_name")), str(occasion.get("site_code")),
            str(occasion.get("site_name")), status, capture,
            row == null ? null : builder.individualId, individualCode,
            str(occasion.get("start_at")), str(occasion.get("end_at")),
            str(occasion.get("zone_id"))));
      }
    }
    return builders.values().stream()
        .map(builder -> new CaptureHistory(builder.individualId, builder.individualCode, builder.cells))
        .toList();
  }

  private Long latestPublishedId() {
    List<Long> ids = db.queryForList("""
        SELECT id FROM hypothesis_revisions WHERE status = 'PUBLISHED'
        ORDER BY revision_no DESC, id DESC LIMIT 1
        """, Long.class);
    return ids.isEmpty() ? null : ids.get(0);
  }

  private RevisionView latestDraftOrPublished() {
    List<Long> ids = db.queryForList("""
        SELECT id FROM hypothesis_revisions
        ORDER BY CASE status WHEN 'DRAFT' THEN 0 ELSE 1 END, revision_no DESC, id DESC LIMIT 1
        """, Long.class);
    return ids.isEmpty() ? null : revision(ids.get(0));
  }

  private Long assignedIndividual(Long revisionId, Long observationId) {
    if (revisionId == null) {
      return null;
    }
    List<Long> ids = db.queryForList(
        "SELECT individual_id FROM revision_items WHERE revision_id = ? AND observation_id = ?",
        Long.class, revisionId, observationId);
    return ids.isEmpty() ? null : ids.get(0);
  }

  private String decision(RevisionView revision, Long observationId, Long individualId) {
    if (revision == null) {
      return null;
    }
    List<String> decisions = db.queryForList("""
        SELECT decision FROM candidate_decisions
        WHERE revision_id = ? AND observation_id = ? AND individual_id = ?
        """, String.class, revision.id(), observationId, individualId);
    return decisions.isEmpty() ? null : decisions.get(0);
  }

  private String individualCode(Long individualId) {
    if (individualId == null) {
      return null;
    }
    List<String> codes = db.queryForList("SELECT code FROM individuals WHERE id = ?",
        String.class, individualId);
    return codes.isEmpty() ? null : codes.get(0);
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static class CaptureHistoryBuilder {
    private final Long individualId;
    private final String individualCode;
    private final List<CaptureCell> cells = new ArrayList<>();

    private CaptureHistoryBuilder(Long individualId, String individualCode) {
      this.individualId = individualId;
      this.individualCode = individualCode;
    }
  }
}
