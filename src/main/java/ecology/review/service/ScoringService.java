package ecology.review.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScoringService {
  static final double MAX_TRAVEL_SPEED_MPS = 5.0;

  private final JdbcTemplate db;

  public ScoringService(JdbcTemplate db) {
    this.db = db;
  }

  @Transactional
  public void recalculateAll() {
    Long latestRevisionId = latestPublishedRevisionId();
    List<Long> observationIds = db.queryForList(
        "SELECT id FROM observations ORDER BY observed_at, id", Long.class);
    for (Long observationId : observationIds) {
      recalculateObservation(observationId, latestRevisionId);
    }
  }

  private void recalculateObservation(Long observationId, Long latestRevisionId) {
    Map<String, Object> observation = db.queryForMap("SELECT * FROM observations WHERE id = ?", observationId);
    String marker = (String) observation.get("marker_fragment");
    String pattern = (String) observation.get("pattern_summary");
    double confidence = ((Number) observation.get("confidence")).doubleValue();
    List<Map<String, Object>> individuals = db.queryForList(
        "SELECT * FROM individuals WHERE status IN ('ACTIVE', 'PROPOSED') ORDER BY id");

    db.update("DELETE FROM candidate_scores WHERE observation_id = ?", observationId);
    String now = Instant.now().toString();
    for (Map<String, Object> individual : individuals) {
      Long individualId = ((Number) individual.get("id")).longValue();
      Anchor nearest = nearestPublishedAnchor(observationId, individualId, latestRevisionId);
      double markerScore = Support.jaroWinklerSimilarity(marker, bestMarkerAnchor(individualId, latestRevisionId));
      double patternScore = tokenSimilarityAgainstAnchors(pattern, individualId, latestRevisionId);
      double rawScore = round(0.40 * markerScore + 0.35 * patternScore + 0.25 * confidence);
      String state = "CANDIDATE";
      String conflictReason = null;
      Double distance = null;
      Double elapsedSeconds = null;
      Double requiredSpeed = null;
      if (nearest != null && nearest.conflict()) {
        state = "REJECTED";
        rawScore = Math.min(rawScore, 0.12);
        conflictReason = nearest.reason();
        distance = nearest.distanceMeters();
        elapsedSeconds = nearest.elapsedSeconds();
        requiredSpeed = nearest.requiredSpeedMps();
      }
      db.update("""
          INSERT INTO candidate_scores(observation_id, individual_id, score, auto_state, conflict_reason,
            distance_m, elapsed_seconds, required_speed_mps, calculated_at)
          VALUES (?,?,?,?,?,?,?,?,?)
          """, observationId, individualId, rawScore, state, conflictReason, distance,
          elapsedSeconds, requiredSpeed, now);
    }
  }

  private Anchor nearestPublishedAnchor(Long observationId, Long individualId, Long revisionId) {
    if (revisionId == null) {
      return null;
    }
    Map<String, Object> obs = db.queryForMap("""
        SELECT o.*, s.latitude AS site_lat, s.longitude AS site_lon
        FROM observations o JOIN sites s ON s.id = o.site_id WHERE o.id = ?
        """, observationId);
    List<Map<String, Object>> anchors = db.queryForList("""
        SELECT o.*, s.latitude AS site_lat, s.longitude AS site_lon
        FROM published_assignments pa
        JOIN observations o ON o.id = pa.observation_id
        JOIN sites s ON s.id = o.site_id
        WHERE pa.revision_id = ? AND pa.individual_id = ? AND pa.observation_id <> ?
        """, revisionId, individualId, observationId);
    Anchor nearest = null;
    Anchor worstConflict = null;
    for (Map<String, Object> anchorRow : anchors) {
      double distance = Support.haversineMeters(
          ((Number) obs.get("site_lat")).doubleValue(),
          ((Number) obs.get("site_lon")).doubleValue(),
          ((Number) anchorRow.get("site_lat")).doubleValue(),
          ((Number) anchorRow.get("site_lon")).doubleValue());
      Instant candidateAt = Instant.parse((String) anchorRow.get("observed_at"));
      Instant observedAt = Instant.parse((String) obs.get("observed_at"));
      long uncertainty = ((Number) obs.get("uncertainty_seconds")).longValue()
          + ((Number) anchorRow.get("uncertainty_seconds")).longValue();
      double elapsed = Math.max(0, Math.abs(Duration.between(candidateAt, observedAt).toSeconds()) - uncertainty);
      double requiredSpeed = elapsed == 0 ? Double.POSITIVE_INFINITY : distance / elapsed;
      boolean conflict = distance > 0 && requiredSpeed > MAX_TRAVEL_SPEED_MPS;
      String reason = conflict
          ? "required travel speed %.2f m/s exceeds %.2f m/s".formatted(requiredSpeed, MAX_TRAVEL_SPEED_MPS)
          : null;
      Anchor candidate = new Anchor(distance, elapsed, requiredSpeed, conflict, reason);
      if (nearest == null || candidate.elapsedSeconds() < nearest.elapsedSeconds()) {
        nearest = candidate;
      }
      if (conflict && (worstConflict == null
          || candidate.requiredSpeedMps() > worstConflict.requiredSpeedMps())) {
        worstConflict = candidate;
      }
    }
    return worstConflict == null ? nearest : worstConflict;
  }

  private String bestMarkerAnchor(Long individualId, Long revisionId) {
    if (revisionId == null) {
      return "";
    }
    List<String> markers = db.queryForList("""
        SELECT o.marker_fragment
        FROM published_assignments pa JOIN observations o ON o.id = pa.observation_id
        WHERE pa.revision_id = ? AND pa.individual_id = ?
        """, String.class, revisionId, individualId);
    return markers.isEmpty() ? "" : String.join(" ", markers);
  }

  private double tokenSimilarityAgainstAnchors(String pattern, Long individualId, Long revisionId) {
    if (revisionId == null) {
      return 0;
    }
    List<String> patterns = db.queryForList("""
        SELECT o.pattern_summary
        FROM published_assignments pa JOIN observations o ON o.id = pa.observation_id
        WHERE pa.revision_id = ? AND pa.individual_id = ?
        """, String.class, revisionId, individualId);
    return patterns.stream().mapToDouble(value -> Support.tokenSimilarity(pattern, value)).max().orElse(0);
  }

  private Long latestPublishedRevisionId() {
    List<Long> ids = db.queryForList("""
        SELECT id FROM hypothesis_revisions WHERE status = 'PUBLISHED'
        ORDER BY revision_no DESC, id DESC LIMIT 1
        """, Long.class);
    return ids.isEmpty() ? null : ids.get(0);
  }

  private static double round(double value) {
    return Math.round(value * 1000.0) / 1000.0;
  }

  private record Anchor(double distanceMeters, double elapsedSeconds, double requiredSpeedMps,
                        boolean conflict, String reason) {
  }
}
