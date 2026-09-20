package ecology.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ecology.review.dto.Requests.ImportBatch;
import ecology.review.dto.Requests.IndividualInput;
import ecology.review.dto.Requests.ObservationInput;
import ecology.review.dto.Requests.SiteInput;
import ecology.review.dto.Requests.SurveyInput;
import ecology.review.service.ImportService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared",
    "app.demo-seed-enabled=false"
})
@AutoConfigureMockMvc
class WildlifeReviewIntegrationTest {
  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private ObjectMapper objectMapper;
  @Autowired
  private ImportService importService;

  @Test
  void preservesAmbiguityRejectsUnreachableCandidateAndAppliesHalfOpenIntervals() throws Exception {
    seed();

    MvcResult timelineResult = mockMvc.perform(get("/api/timeline"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode timeline = objectMapper.readTree(timelineResult.getResponse().getContentAsString());
    JsonNode obs202 = findObservation(timeline, "OBS-202");
    JsonNode alpha = findCandidate(obs202, "A01");
    assertThat(alpha.path("auto_state").asText()).isEqualTo("REJECTED");
    assertThat(alpha.path("conflict").path("required_speed_mps").asDouble()).isGreaterThan(5.0);
    JsonNode obs200 = findObservation(timeline, "OBS-200");
    assertThat(obs200.path("survey_code").asText()).isEqualTo("S2");
    JsonNode boundary = findObservation(timeline, "OBS-102");
    assertThat(boundary.path("survey_code").asText()).isEqualTo("S1");
    assertThat(findObservation(timeline, "OBS-201").path("candidates")).hasSize(3);
  }

  @Test
  void keepsZeroAndNotObservedDistinctInCaptureHistory() throws Exception {
    seed();

    MvcResult result = mockMvc.perform(get("/api/captures/history.csv"))
        .andExpect(status().isOk())
        .andReturn();
    String csv = result.getResponse().getContentAsString();
    assertThat(csv).contains("\"S1\",\"IDLE\"").contains(",ZERO,0");
    assertThat(csv).contains("\"S3\",\"IDLE\"").contains(",NOT_OBSERVED,NA");
    mockMvc.perform(get("/api/captures/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.survey_sites").value(8))
        .andExpect(jsonPath("$.observed_occasions").value(6));
  }

  @Test
  void idempotentImportsPublicationsAndOptimisticConflictPreserveContext() throws Exception {
    seed();

    var first = importService.importBatch(batch());
    var second = importService.importBatch(batch());
    assertThat(second.reused()).isTrue();
    assertThat(second.observations()).isZero();
    assertThat(second.baselineRevisionId()).isEqualTo(first.baselineRevisionId());

    JsonNode draft = createRevision("draft-key-1");
    long draftId = draft.path("id").asLong();
    long obs200 = observationId("OBS-200");
    long alphaId = individualId("A01");

    confirm(draftId, obs200, alphaId, 1, "PATTERN_CONFIRMATION", "op-1");
    MvcResult conflict = mockMvc.perform(post("/api/revisions/{id}/observations/{obs}/candidates/{ind}/deny",
            draftId, observationId("OBS-201"), individualId("B07"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"base_version":1,"author":"second reviewer","reason_code":"DUPLICATE_CODE",
                 "reason_detail":"stale tab","client_key":"op-stale"}
                """))
        .andExpect(status().isConflict())
        .andReturn();
    JsonNode conflictBody = objectMapper.readTree(conflict.getResponse().getContentAsString());
    assertThat(conflictBody.path("current_version").asInt()).isEqualTo(2);
    assertThat(conflictBody.path("conflict_context").path("recent_operations").isArray()).isTrue();

    confirm(draftId, observationId("OBS-201"), individualId("B07"), 2, "DUPLICATE_CODE", "op-2");
    split(draftId, observationId("OBS-202"), 3, "op-3");
    split(draftId, observationId("OBS-203"), 4, "op-4");
    mergeIdentity(draftId, 4, individualId("G12"), 5, "merge-1");

    JsonNode publish1 = publish(draftId, 6, "publish-1");
    JsonNode publish2 = publish(draftId, 7, "publish-1");
    assertThat(publish2.path("reused").asBoolean()).isTrue();
    assertThat(publish1.path("assignments").asInt()).isEqualTo(7);
    assertThat(publish1.path("revision_no").asInt()).isEqualTo(publish2.path("revision_no").asInt());
  }

  private void seed() {
    importService.importBatch(batch());
  }

  private ImportBatch batch() {
    return new ImportBatch(
        "test-batch",
        "integration",
        true,
        List.of(
            new SiteInput("RIVER", "River", 35.6895, 139.6917),
            new SiteInput("RIDGE", "Ridge", 35.9000, 139.9000),
            new SiteInput("MEADOW", "Meadow", 35.4000, 139.3000),
            new SiteInput("IDLE", "Idle", 35.2000, 139.1000)),
        List.of(
            new SurveyInput("S1", "One", "Asia/Tokyo",
                "2026-05-01 08:00", "2026-05-02 08:00", List.of("RIVER", "RIDGE", "IDLE")),
            new SurveyInput("S2", "Two", "Asia/Tokyo",
                "2026-05-02 08:00", "2026-05-03 08:00",
                List.of("RIVER", "RIDGE", "MEADOW", "IDLE")),
            new SurveyInput("S3", "Three", "Asia/Tokyo",
                "2026-05-03 08:00", "2026-05-04 08:00", List.of())),
        List.of(
            new IndividualInput("A01", "Alpha"),
            new IndividualInput("B07", "Beta"),
            new IndividualInput("G12", "Gamma")),
        List.of(
            new ObservationInput("OBS-100", "RIVER", "2026-05-02T07:50+09:00", 60,
                "A01X", "dark flank hook scar", .90, "lin", "A01"),
            new ObservationInput("OBS-101", "MEADOW", "2026-05-01T10:00+09:00", 60,
                "B07", "pale shoulder notch", .84, "lin", "B07"),
            new ObservationInput("OBS-102", "RIDGE", "2026-05-02T07:59:59+09:00", 0,
                "G12", "grey crest broad", .80, "mei", "G12"),
            new ObservationInput("OBS-200", "RIVER", "2026-05-02T08:00+09:00", 30,
                "A01X", "dark flank hook scar blurred", .62, "assistant", null),
            new ObservationInput("OBS-201", "MEADOW", "2026-05-02T08:20+09:00", 45,
                "B07", "pale shoulder unclear duplicate", .55, "assistant", null),
            new ObservationInput("OBS-202", "RIDGE", "2026-05-02T08:30+09:00", 60,
                "A01", "dark flank possible hook", .50, "assistant", null),
            new ObservationInput("OBS-203", "RIVER", "2026-05-02T09:10+09:00", 120,
                "ZZ?", "unknown mottled flank", .30, "visitor", null)));
  }

  private JsonNode createRevision(String key) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/revisions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"based_on_revision_id":null,"author":"lead","note":"review",
                 "client_key":"%s"}
                """.formatted(key)))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private void confirm(long revisionId, long observationId, long individualId, int version,
                       String reason, String key) throws Exception {
    mockMvc.perform(post("/api/revisions/{id}/observations/{obs}/candidates/{ind}/confirm",
            revisionId, observationId, individualId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(action(version, reason, key)))
        .andExpect(status().isOk());
  }

  private void split(long revisionId, long observationId, int version, String key) throws Exception {
    mockMvc.perform(post("/api/revisions/{id}/observations/{obs}/split", revisionId, observationId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"display_name":"New identity","base_version":%d,"author":"lead",
                 "reason_code":"SPLIT_DISTINCT_INDIVIDUAL","reason_detail":"new pattern",
                 "client_key":"%s"}
                """.formatted(version, key)))
        .andExpect(status().isOk());
  }

  private void mergeIdentity(long revisionId, long fromIndividualId, long toIndividualId,
                             int version, String key) throws Exception {
    mockMvc.perform(post("/api/revisions/{id}/identities/{from}/merge", revisionId, fromIndividualId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"to_individual_id":%d,"base_version":%d,"author":"lead",
                 "reason_code":"MERGE_SAME_INDIVIDUAL","reason_detail":"same pattern",
                 "client_key":"%s"}
                """.formatted(toIndividualId, version, key)))
        .andExpect(status().isOk());
  }

  private JsonNode publish(long revisionId, int version, String key) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/revisions/{id}/publish", revisionId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"base_version":%d,"author":"lead","client_key":"%s","note":"done"}
                """.formatted(version, key)))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private String action(int version, String reason, String key) {
    return """
        {"base_version":%d,"author":"lead","reason_code":"%s","reason_detail":"review",
         "client_key":"%s"}
        """.formatted(version, reason, key);
  }

  private long observationId(String code) throws Exception {
    MvcResult result = mockMvc.perform(get("/api/timeline")).andReturn();
    return findObservation(objectMapper.readTree(result.getResponse().getContentAsString()), code)
        .path("id").asLong();
  }

  private long individualId(String code) throws Exception {
    MvcResult result = mockMvc.perform(get("/api/timeline")).andReturn();
    JsonNode individuals = objectMapper.readTree(result.getResponse().getContentAsString()).path("individuals");
    for (JsonNode individual : individuals) {
      if (code.equals(individual.path("code").asText())) {
        return individual.path("id").asLong();
      }
    }
    throw new IllegalStateException(code);
  }

  private JsonNode findObservation(JsonNode timeline, String code) {
    for (JsonNode observation : timeline.path("observations")) {
      if (code.equals(observation.path("code").asText())) {
        return observation;
      }
    }
    throw new IllegalStateException(code);
  }

  private JsonNode findCandidate(JsonNode observation, String individualCode) {
    for (JsonNode candidate : observation.path("candidates")) {
      if (individualCode.equals(candidate.path("individual_code").asText())) {
        return candidate;
      }
    }
    throw new IllegalStateException(individualCode);
  }
}
