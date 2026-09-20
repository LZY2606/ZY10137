package ecology.review.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ecology.review.dto.Requests.ActionRequest;
import ecology.review.dto.Requests.AssignRequest;
import ecology.review.dto.Requests.CreateRevisionRequest;
import ecology.review.dto.Requests.MergeRequest;
import ecology.review.dto.Requests.PublishRequest;
import ecology.review.dto.Requests.SplitRequest;
import ecology.review.dto.Responses.ActionResponse;
import ecology.review.dto.Responses.PublishResponse;
import ecology.review.web.ApiException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RevisionService {
  private static final List<String> REASON_CODES = List.of(
      "MARKER_SHED", "DUPLICATE_CODE", "DATA_ENTRY_ERROR", "PATTERN_CONFIRMATION",
      "OBSERVER_REVIEW", "SPLIT_DISTINCT_INDIVIDUAL", "MERGE_SAME_INDIVIDUAL");

  private final JdbcTemplate db;
  private final ObjectMapper objectMapper;
  private final ScoringService scoringService;

  public RevisionService(JdbcTemplate db, ObjectMapper objectMapper, ScoringService scoringService) {
    this.db = db;
    this.objectMapper = objectMapper;
    this.scoringService = scoringService;
  }

  @Transactional
  public Map<String, Object> createRevision(CreateRevisionRequest request) {
    if (request == null) {
      throw new ApiException(400, "request is required");
    }
    if (request.clientKey() != null && !request.clientKey().isBlank()) {
      Long existing = findRevisionByClientKey(request.clientKey());
      if (existing != null) {
        return db.queryForMap("SELECT * FROM hypothesis_revisions WHERE id = ?", existing);
      }
    }
    Long baseId = request.basedOnRevisionId();
    if (baseId == null) {
      baseId = latestPublishedId();
    }
    Map<String, Object> base = requireRevision(baseId);
    if (!"PUBLISHED".equals(base.get("status"))) {
      throw new ApiException(409, "new revisions must be based on a published revision");
    }
    int nextNo = db.queryForObject(
        "SELECT COALESCE(MAX(revision_no), 0) + 1 FROM hypothesis_revisions", Integer.class);
    String now = Instant.now().toString();
    db.update("""
        INSERT INTO hypothesis_revisions(revision_no, based_on_revision_id, status, note, author,
          client_key, version, created_at)
        VALUES (?,?, 'DRAFT', ?, ?, ?, 1, ?)
        """, nextNo, baseId, request.note(), blankTo(request.author(), "researcher"),
        request.clientKey(), now);
    Long revisionId = db.queryForObject(
        "SELECT id FROM hypothesis_revisions WHERE revision_no = ?", Long.class, nextNo);
    if (request.clientKey() != null && !request.clientKey().isBlank()) {
      db.update("INSERT INTO revision_client_keys(client_key, revision_id) VALUES (?,?)",
          request.clientKey(), revisionId);
    }
    db.update("""
        INSERT INTO revision_items(revision_id, observation_id, individual_id)
        SELECT ?, o.id, pa.individual_id
        FROM observations o
        LEFT JOIN published_assignments pa
          ON pa.observation_id = o.id AND pa.revision_id = ?
        """, revisionId, baseId);
    copyDecisions(baseId, revisionId);
    return db.queryForMap("SELECT * FROM hypothesis_revisions WHERE id = ?", revisionId);
  }

  @Transactional
  public ActionResponse confirm(Long revisionId, Long observationId, Long individualId, ActionRequest request) {
    return candidateDecision(revisionId, observationId, individualId, request, "CONFIRM");
  }

  @Transactional
  public ActionResponse deny(Long revisionId, Long observationId, Long individualId, ActionRequest request) {
    return candidateDecision(revisionId, observationId, individualId, request, "DENY");
  }

  @Transactional
  public ActionResponse assign(Long revisionId, Long observationId, AssignRequest request) {
    requireAction(request.author(), request.reasonCode(), request.baseVersion());
    Long draftId = requireDraft(revisionId);
    Long individualId = request.individualId();
    requireObservation(observationId);
    if (individualId != null) {
      requireUsableIndividual(individualId);
      rejectCandidateIfUnreachable(observationId, individualId);
    }
    touchVersion(draftId, request.baseVersion(), "ASSIGN", individualId, null, observationId,
        request.reasonCode(), request.reasonDetail(), request.author(), request.clientKey(),
        Map.of("individual_id", individualId == null ? "" : individualId));
    db.update("""
        INSERT INTO revision_items(revision_id, observation_id, individual_id) VALUES (?,?,?)
        ON CONFLICT(revision_id, observation_id)
        DO UPDATE SET individual_id = excluded.individual_id
        """, draftId, observationId, individualId);
    if (individualId != null) {
      upsertDecision(draftId, observationId, individualId, "CONFIRMED", request.reasonCode(),
          request.reasonDetail(), request.author());
    }
    int version = currentVersion(draftId);
    return new ActionResponse(draftId, version, observationId, individualId, "ASSIGN");
  }

  @Transactional
  public ActionResponse split(Long revisionId, Long observationId, SplitRequest request) {
    requireAction(request.author(), request.reasonCode(), request.baseVersion());
    Long draftId = requireDraft(revisionId);
    requireObservation(observationId);
    Long fromIndividualId = currentAssignedIndividual(draftId, observationId);
    String code = "P-" + Instant.now().toEpochMilli() + "-" + observationId;
    String displayName = blankTo(request.displayName(), "Proposed identity for " + observationId);
    db.update("""
        INSERT INTO individuals(code, display_name, status, created_revision_id, created_at)
        VALUES (?,?, 'PROPOSED', ?, ?)
        """, code, displayName, draftId, Instant.now().toString());
    Long newIndividualId = db.queryForObject("SELECT id FROM individuals WHERE code = ?", Long.class, code);
    touchVersion(draftId, request.baseVersion(), "SPLIT", fromIndividualId, newIndividualId, observationId,
        request.reasonCode(), request.reasonDetail(), request.author(), request.clientKey(),
        Map.of("display_name", displayName));
    db.update("""
        INSERT INTO revision_items(revision_id, observation_id, individual_id) VALUES (?,?,?)
        ON CONFLICT(revision_id, observation_id)
        DO UPDATE SET individual_id = excluded.individual_id
        """, draftId, observationId, newIndividualId);
    upsertDecision(draftId, observationId, newIndividualId, "CONFIRMED", request.reasonCode(),
        request.reasonDetail(), request.author());
    scoringService.recalculateAll();
    return new ActionResponse(draftId, currentVersion(draftId), observationId, newIndividualId, "SPLIT");
  }

  @Transactional
  public ActionResponse merge(Long revisionId, Long observationId, Long fromIndividualId,
                              MergeRequest request) {
    requireAction(request.author(), request.reasonCode(), request.baseVersion());
    Long draftId = requireDraft(revisionId);
    requireObservation(observationId);
    Long targetId = request.toIndividualId();
    requireUsableIndividual(targetId);
    Long currentId = currentAssignedIndividual(draftId, observationId);
    if (currentId == null || !currentId.equals(fromIndividualId)) {
      throw new ApiException(409, "observation is not currently assigned to the source individual");
    }
    if (targetId.equals(fromIndividualId)) {
      throw new ApiException(400, "target individual must differ from source individual");
    }
    rejectCandidateIfUnreachable(observationId, targetId);
    touchVersion(draftId, request.baseVersion(), "MERGE", fromIndividualId, targetId, observationId,
        request.reasonCode(), request.reasonDetail(), request.author(), request.clientKey(),
        Map.of());
    db.update("UPDATE revision_items SET individual_id = ? WHERE revision_id = ? AND observation_id = ?",
        targetId, draftId, observationId);
    upsertDecision(draftId, observationId, targetId, "CONFIRMED", request.reasonCode(),
        request.reasonDetail(), request.author());
    return new ActionResponse(draftId, currentVersion(draftId), observationId, targetId, "MERGE");
  }

  @Transactional
  public ActionResponse mergeIdentity(Long revisionId, Long fromIndividualId, MergeRequest request) {
    requireAction(request.author(), request.reasonCode(), request.baseVersion());
    Long draftId = requireDraft(revisionId);
    requireUsableIndividual(fromIndividualId);
    Long targetId = request.toIndividualId();
    requireUsableIndividual(targetId);
    if (targetId.equals(fromIndividualId)) {
      throw new ApiException(400, "target individual must differ from source individual");
    }
    List<Long> observationIds = db.queryForList(
        "SELECT observation_id FROM revision_items WHERE revision_id = ? AND individual_id = ? ORDER BY observation_id",
        Long.class, draftId, fromIndividualId);
    if (observationIds.isEmpty()) {
      throw new ApiException(409, "source identity has no observations in this revision");
    }
    for (Long observationId : observationIds) {
      rejectCandidateIfUnreachable(observationId, targetId);
    }
    touchVersion(draftId, request.baseVersion(), "MERGE_IDENTITY", fromIndividualId, targetId, null,
        request.reasonCode(), request.reasonDetail(), request.author(), request.clientKey(),
        Map.of("observation_ids", observationIds));
    db.update("UPDATE revision_items SET individual_id = ? WHERE revision_id = ? AND individual_id = ?",
        targetId, draftId, fromIndividualId);
    for (Long observationId : observationIds) {
      upsertDecision(draftId, observationId, targetId, "CONFIRMED", request.reasonCode(),
          request.reasonDetail(), request.author());
    }
    db.update("UPDATE individuals SET status = 'MERGED', merged_into_id = ? WHERE id = ?",
        targetId, fromIndividualId);
    scoringService.recalculateAll();
    return new ActionResponse(draftId, currentVersion(draftId), null, targetId, "MERGE_IDENTITY");
  }

  @Transactional
  public PublishResponse publish(Long revisionId, PublishRequest request) {
    if (request == null || request.baseVersion() == null) {
      throw new ApiException(400, "base_version is required");
    }
    if (request.clientKey() != null && !request.clientKey().isBlank()) {
      List<Map<String, Object>> reused = db.queryForList(
          "SELECT revision_id, published_at FROM publish_client_keys WHERE client_key = ?",
          request.clientKey());
      if (!reused.isEmpty()) {
        Long reusedId = ((Number) reused.get(0).get("revision_id")).longValue();
        if (!reusedId.equals(revisionId)) {
          throw new ApiException(409, "publish client key belongs to another revision");
        }
        int revisionNo = db.queryForObject("SELECT revision_no FROM hypothesis_revisions WHERE id = ?",
            Integer.class, reusedId);
        int assignments = countAssignments(reusedId);
        return new PublishResponse(reusedId, revisionNo, true,
            (String) reused.get(0).get("published_at"), assignments);
      }
    }
    Long draftId = requireDraft(revisionId);
    ensureVersion(draftId, request.baseVersion());
    List<Map<String, Object>> items = db.queryForList(
        "SELECT observation_id, individual_id FROM revision_items WHERE revision_id = ?", draftId);
    Long totalObservations = db.queryForObject("SELECT COUNT(*) FROM observations", Long.class);
    if ((long) items.size() != totalObservations) {
      throw new ApiException(409, "all observations need an identity assignment or an explicit null review");
    }
    Long unresolved = db.queryForObject(
        "SELECT COUNT(*) FROM revision_items WHERE revision_id = ? AND individual_id IS NULL",
        Long.class, draftId);
    if (unresolved != null && unresolved > 0) {
      throw new ApiException(409, "unresolved observations must be confirmed to an existing or split identity before publication");
    }
    for (Map<String, Object> item : items) {
      Long observationId = ((Number) item.get("observation_id")).longValue();
      Long individualId = item.get("individual_id") == null
          ? null : ((Number) item.get("individual_id")).longValue();
      if (individualId != null) {
        rejectCandidateIfUnreachable(observationId, individualId);
      }
    }
    String now = Instant.now().toString();
    db.update("""
        UPDATE hypothesis_revisions
        SET status = 'PUBLISHED', version = version + 1, published_at = ?, note = COALESCE(?, note)
        WHERE id = ?
        """, now, request.note(), draftId);
    db.update("DELETE FROM published_assignments WHERE revision_id = ?", draftId);
    db.update("""
        INSERT INTO published_assignments(revision_id, observation_id, individual_id)
        SELECT revision_id, observation_id, individual_id FROM revision_items
        WHERE revision_id = ? AND individual_id IS NOT NULL
        """, draftId);
    if (request.clientKey() != null && !request.clientKey().isBlank()) {
      db.update("INSERT INTO publish_client_keys(client_key, revision_id, published_at) VALUES (?,?,?)",
          request.clientKey(), draftId, now);
    }
    scoringService.recalculateAll();
    int revisionNo = db.queryForObject("SELECT revision_no FROM hypothesis_revisions WHERE id = ?",
        Integer.class, draftId);
    return new PublishResponse(draftId, revisionNo, false, now, countAssignments(draftId));
  }

  private ActionResponse candidateDecision(Long revisionId, Long observationId, Long individualId,
                                           ActionRequest request, String operationType) {
    requireAction(request.author(), request.reasonCode(), request.baseVersion());
    Long draftId = requireDraft(revisionId);
    requireObservation(observationId);
    requireUsableIndividual(individualId);
    if ("CONFIRM".equals(operationType)) {
      rejectCandidateIfUnreachable(observationId, individualId);
    }
    String decision = "CONFIRM".equals(operationType) ? "CONFIRMED" : "DENIED";
    touchVersion(draftId, request.baseVersion(), operationType, individualId, null, observationId,
        request.reasonCode(), request.reasonDetail(), request.author(), request.clientKey(),
        Map.of("decision", decision));
    upsertDecision(draftId, observationId, individualId, decision, request.reasonCode(),
        request.reasonDetail(), request.author());
    if ("CONFIRM".equals(operationType)) {
      db.update("""
          INSERT INTO revision_items(revision_id, observation_id, individual_id) VALUES (?,?,?)
          ON CONFLICT(revision_id, observation_id)
          DO UPDATE SET individual_id = excluded.individual_id
          """, draftId, observationId, individualId);
    }
    return new ActionResponse(draftId, currentVersion(draftId), observationId, individualId, operationType);
  }

  private void touchVersion(Long revisionId, Long baseVersion, String operationType,
                            Long fromIndividualId, Long toIndividualId, Long observationId,
                            String reasonCode, String reasonDetail, String author,
                                             String clientKey, Map<String, Object> payload) {
    Map<String, Object> submitted = new HashMap<>(payload);
    submitted.put("operation_type", operationType);
    submitted.put("observation_id", observationId);
    submitted.put("from_individual_id", fromIndividualId);
    submitted.put("to_individual_id", toIndividualId);
    submitted.put("reason_code", reasonCode);
    submitted.put("reason_detail", reasonDetail);
    submitted.put("author", author);
    submitted.put("base_version", baseVersion);
    submitted.put("client_key", clientKey);
    ensureVersion(revisionId, baseVersion, submitted);
    if (clientKey != null && !clientKey.isBlank()) {
      Integer count = db.queryForObject(
          "SELECT COUNT(*) FROM identity_operations WHERE client_key = ?", Integer.class, clientKey);
      if (count != null && count > 0) {
        return;
      }
    }
    int nextVersion = currentVersion(revisionId) + 1;
    db.update("""
        INSERT INTO identity_operations(revision_id, operation_type, from_individual_id,
          to_individual_id, observation_id, reason_code, reason_detail, author, base_version,
          next_version, payload_json, client_key, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, revisionId, operationType, fromIndividualId, toIndividualId, observationId,
        reasonCode, reasonDetail, blankTo(author, "researcher"), baseVersion, nextVersion,
        writeJson(payload), clientKey, Instant.now().toString());
    db.update("UPDATE hypothesis_revisions SET version = ? WHERE id = ?", nextVersion, revisionId);
  }

  private void ensureVersion(Long revisionId, Long baseVersion) {
    ensureVersion(revisionId, baseVersion, Map.of("base_version", baseVersion));
  }

  private void ensureVersion(Long revisionId, Long baseVersion, Map<String, Object> submitted) {
    Integer current = db.queryForObject("SELECT version FROM hypothesis_revisions WHERE id = ?",
        Integer.class, revisionId);
    if (baseVersion == null) {
      throw new ApiException(400, "base_version is required");
    }
    if (current != null && !current.equals(baseVersion.intValue())) {
      Map<String, Object> context = new HashMap<>();
      context.put("current_version", current);
      context.put("current_items", db.queryForList(
          "SELECT observation_id, individual_id FROM revision_items WHERE revision_id = ?", revisionId));
      context.put("recent_operations", db.queryForList("""
          SELECT operation_type, observation_id, from_individual_id, to_individual_id,
                 reason_code, author, base_version, next_version, created_at
          FROM identity_operations WHERE revision_id = ? ORDER BY id DESC LIMIT 10
          """, revisionId));
      String submittedJson = writeJson(submitted);
      String contextJson = writeJson(context);
      db.update("""
          INSERT INTO review_conflicts(revision_id, submitted_base_version, current_version,
            submitted_payload_json, current_context_json, created_at)
          VALUES (?,?,?,?,?,?)
          """, revisionId, baseVersion, current, submittedJson, contextJson, Instant.now().toString());
      throw new ApiException(409, "revision changed since your read", Map.of(
          "current_version", current,
          "submitted_base_version", baseVersion,
          "conflict_context", context,
          "guidance", "refresh the revision, rebase your decision, then resubmit"));
    }
  }

  private void rejectCandidateIfUnreachable(Long observationId, Long individualId) {
    List<Map<String, Object>> scores = db.queryForList("""
        SELECT auto_state, conflict_reason, required_speed_mps, distance_m, elapsed_seconds
        FROM candidate_scores WHERE observation_id = ? AND individual_id = ?
        """, observationId, individualId);
    if (!scores.isEmpty() && "REJECTED".equals(scores.get(0).get("auto_state"))) {
      throw new ApiException(409, "candidate is rejected because the same individual cannot reach the site",
          Map.of("candidate", scores.get(0)));
    }
  }

  private void upsertDecision(Long revisionId, Long observationId, Long individualId, String decision,
                              String reasonCode, String reasonDetail, String author) {
    db.update("""
        INSERT INTO candidate_decisions(revision_id, observation_id, individual_id, decision,
          reason_code, reason_detail, author, created_at)
        VALUES (?,?,?,?,?,?,?,?)
        ON CONFLICT(revision_id, observation_id, individual_id)
        DO UPDATE SET decision = excluded.decision, reason_code = excluded.reason_code,
          reason_detail = excluded.reason_detail, author = excluded.author, created_at = excluded.created_at
        """, revisionId, observationId, individualId, decision, reasonCode, reasonDetail,
        blankTo(author, "researcher"), Instant.now().toString());
  }

  private void copyDecisions(Long sourceRevisionId, Long targetRevisionId) {
    db.update("""
        INSERT INTO candidate_decisions(revision_id, observation_id, individual_id, decision,
          reason_code, reason_detail, author, created_at)
        SELECT ?, observation_id, individual_id, decision, reason_code, reason_detail, author, created_at
        FROM candidate_decisions WHERE revision_id = ?
        """, targetRevisionId, sourceRevisionId);
  }

  private Map<String, Object> requireRevision(Long revisionId) {
    if (revisionId == null) {
      throw new ApiException(400, "revision id is required");
    }
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT * FROM hypothesis_revisions WHERE id = ?", revisionId);
    if (rows.isEmpty()) {
      throw new ApiException(404, "revision not found");
    }
    return rows.get(0);
  }

  private Long requireDraft(Long revisionId) {
    Map<String, Object> revision = requireRevision(revisionId);
    if (!"DRAFT".equals(revision.get("status"))) {
      throw new ApiException(409, "revision is already published");
    }
    return revisionId;
  }

  private void requireObservation(Long observationId) {
    if (observationId == null || db.queryForObject(
        "SELECT COUNT(*) FROM observations WHERE id = ?", Integer.class, observationId) == 0) {
      throw new ApiException(404, "observation not found");
    }
  }

  private void requireUsableIndividual(Long individualId) {
    if (individualId == null) {
      throw new ApiException(400, "individual_id is required");
    }
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT status FROM individuals WHERE id = ?", individualId);
    if (rows.isEmpty()) {
      throw new ApiException(404, "individual not found");
    }
    if ("MERGED".equals(rows.get(0).get("status"))) {
      throw new ApiException(409, "individual has been merged");
    }
  }

  private Long currentAssignedIndividual(Long revisionId, Long observationId) {
    List<Long> ids = db.queryForList(
        "SELECT individual_id FROM revision_items WHERE revision_id = ? AND observation_id = ?",
        Long.class, revisionId, observationId);
    return ids.isEmpty() ? null : ids.get(0);
  }

  private Long latestPublishedId() {
    List<Long> ids = db.queryForList("""
        SELECT id FROM hypothesis_revisions WHERE status = 'PUBLISHED'
        ORDER BY revision_no DESC, id DESC LIMIT 1
        """, Long.class);
    if (ids.isEmpty()) {
      throw new ApiException(409, "publish a baseline revision before creating a review draft");
    }
    return ids.get(0);
  }

  private Long findRevisionByClientKey(String clientKey) {
    List<Long> ids = db.queryForList(
        "SELECT revision_id FROM revision_client_keys WHERE client_key = ?", Long.class, clientKey);
    return ids.isEmpty() ? null : ids.get(0);
  }

  private int currentVersion(Long revisionId) {
    return db.queryForObject("SELECT version FROM hypothesis_revisions WHERE id = ?",
        Integer.class, revisionId);
  }

  private int countAssignments(Long revisionId) {
    Integer count = db.queryForObject(
        "SELECT COUNT(*) FROM published_assignments WHERE revision_id = ? AND individual_id IS NOT NULL",
        Integer.class, revisionId);
    return count == null ? 0 : count;
  }

  private void requireAction(String author, String reasonCode, Long baseVersion) {
    if (baseVersion == null) {
      throw new ApiException(400, "base_version is required");
    }
    if (author == null || author.isBlank()) {
      throw new ApiException(400, "author is required");
    }
    if (reasonCode == null || !REASON_CODES.contains(reasonCode)) {
      throw new ApiException(422, "reason_code must be one of " + REASON_CODES);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
