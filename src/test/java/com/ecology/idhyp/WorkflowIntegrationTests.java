package com.ecology.idhyp;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.Alphanumeric.class)
class WorkflowIntegrationTests {

    static Path dbFile;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        dbFile = Files.createTempFile("idh-test-", ".db");
        Files.deleteIfExists(dbFile);
        registry.add("idh.db.path", () -> dbFile.toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://127.0.0.1:" + port + "/api" + path;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getBody(String path) {
        return rest.getForObject(url(path), Map.class);
    }

    private Map<?, ?> overview() {
        return getBody("/overview");
    }

    // -------------------------------------------------------------- 1. seed

    @Test
    void t01_seedDataLoadedWithPeriodsSitesAndIndividuals() {
        Map<?, ?> ov = overview();
        assertEquals(4, ((List<?>) ov.get("sites")).size());
        assertEquals(5, ((List<?>) ov.get("periods")).size());
        assertTrue(((List<?>) ov.get("individuals")).size() >= 4);
        assertNotNull(ov.get("defaultHypothesisId"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> obs = rest.getForObject(url("/observations"), List.class);
        List<Map<String, Object>> zeros = obs.stream().filter(o -> Boolean.TRUE.equals(o.get("zero"))).toList();
        assertFalse(zeros.isEmpty(), "seed includes explicit observed-zero records");
    }

    // ----------------------------------------------------- 2. ambiguity

    @Test
    void t02_ambiguousObservationKeepsMultipleCandidatesAndNewOption() {
        String hypId = (String) overview().get("defaultHypothesisId");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = rest.getForObject(
                url("/observations/o-1002/candidates?hypothesisId=" + hypId), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) resp.get("candidates");
        long known = candidates.stream().filter(c -> !"#NEW#".equals(c.get("individualCode"))).count();
        assertTrue(known >= 2, "a fuzzy photo may match several known individuals");
        assertTrue(candidates.stream().anyMatch(c -> "#NEW#".equals(c.get("individualCode"))),
                "possible new individual must remain an option");

        // Raw scores and human decisions are layered: seed has NOT collapsed o-1004.
        @SuppressWarnings("unchecked")
        Map<String, Object> hyp = getBody("/hypotheses/" + hypId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignments = (List<Map<String, Object>>) hyp.get("assignments");
        assertFalse(assignments.stream().anyMatch(a -> "o-1004".equals(a.get("observationId"))
                && "CONFIRMED".equals(a.get("decision"))),
                "unresolved observation is not silently assigned to the top score");
    }

    // ------------------------------------------------- 3. unreachable pair

    @Test
    void t03_unreachableCandidateIsRejectedButObservationSurvives() {
        String hypId = (String) overview().get("defaultHypothesisId");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = rest.getForObject(
                url("/observations/o-1004/candidates?hypothesisId=" + hypId), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) resp.get("candidates");
        Map<String, Object> i001 = candidates.stream()
                .filter(c -> "I-001".equals(c.get("individualCode"))).findFirst().orElseThrow();
        assertEquals("REJECTED", i001.get("status"), "same animal cannot cover ~9 km in 1h55m");
        assertEquals(0.0, ((Number) i001.get("adjustedScore")).doubleValue());
        assertNotNull(i001.get("conflict"));
        // The raw automatic score is preserved untouched.
        assertTrue(((Number) i001.get("rawScore")).doubleValue() > 0);

        // Forcing a blocked confirm returns 422 with conflicts, observation is not deleted.
        Map<String, Object> blocked = Map.of(
                "observationId", "o-1004", "individualCode", "I-001",
                "baseRevision", 1, "force", false);
        ResponseEntity<Map> blockedRes = rest.postForEntity(
                url("/hypotheses/" + hypId + "/confirm"), new HttpEntity<>(blocked), Map.class);
        assertEquals(422, blockedRes.getStatusCode().value());
        assertNotNull(blockedRes.getBody().get("conflicts"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allObs = rest.getForObject(url("/observations"), List.class);
        assertNotNull(allObs.stream().filter(o -> "o-1004".equals(o.get("id")))
                .findFirst().orElse(null));
    }

    // ------------------------------------------------- 4. half-open boundary

    @Test
    void t04_boundaryObservationBelongsOnlyToLaterPeriod() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> obs = rest.getForObject(url("/observations"), List.class);
        Map<String, Object> boundary = obs.stream().filter(o -> "o-1007".equals(o.get("id")))
                .findFirst().orElseThrow();
        assertEquals("P2", boundary.get("periodCode"),
                "an instant exactly at the changeover belongs to the later period only");
        Map<String, Object> p1 = period("P1");
        Map<String, Object> p2 = period("P2");
        assertEquals(p1.get("endEpoch"), p2.get("startEpoch"));
        long epoch = ((Number) boundary.get("observedEpoch")).longValue();
        assertEquals(((Number) p1.get("endEpoch")).longValue(), epoch);
        assertFalse(epoch >= ((Number) p1.get("startEpoch")).longValue()
                && epoch < ((Number) p1.get("endEpoch")).longValue());
        assertTrue(epoch >= ((Number) p2.get("startEpoch")).longValue()
                && epoch < ((Number) p2.get("endEpoch")).longValue());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> period(String code) {
        return ((List<Map<String, Object>>) overview().get("periods")).stream()
                .filter(p -> code.equals(p.get("code"))).findFirst().orElseThrow();
    }

    // ------------------------------------------ 5. zero vs missing periods

    @Test
    void t05_zeroObservedAndNotSurveyedAreDistinctCaptureCells() {
        // Published v1: I-001 captured in P1 (east+north), no P2/P3/P4 sessions
        // at its sites (only west/east zero sessions exist in P2/P3): its cells
        // must distinguish 0 (east zero session in P3) from NA (P4, P5).
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = getBody("/hypotheses/H-SEED-v1/capture-history");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) summary.get("rows");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> periods = (List<Map<String, Object>>) summary.get("periods");
        int p3 = indexOfPeriod(periods, "P3");
        int p4 = indexOfPeriod(periods, "P4");
        int p5 = indexOfPeriod(periods, "P5");
        Map<String, Object> i001 = rows.stream().filter(r -> "I-001".equals(r.get("individualCode")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> cells = (List<String>) i001.get("cells");
        assertEquals("0", cells.get(p3), "effort at an ever-used site, no capture -> 0");
        assertEquals("NA", cells.get(p4), "no sessions at ever-used sites -> NA");
        assertEquals("NA", cells.get(p5), "period P5 has no sessions at all -> NA");

        @SuppressWarnings("unchecked")
        List<Number> probs = (List<Number>) summary.get("naiveCaptureProbability");
        assertNull(probs.get(p5), "a fully unsurveyed period yields no probability (NA)");
        assertNotNull(probs.get(p1Index(periods)));

        String csv = rest.getForObject(url("/hypotheses/H-SEED-v1/capture-history.csv"), String.class);
        assertTrue(csv.contains("NA"));
        assertTrue(csv.contains("1=captured"));
        assertTrue(csv.contains("不构成种群评估") || csv.contains("方法验证"));
    }

    private int indexOfPeriod(List<Map<String, Object>> periods, String code) {
        for (int i = 0; i < periods.size(); i++) {
            if (code.equals(periods.get(i).get("code"))) {
                return i;
            }
        }
        throw new IllegalStateException(code);
    }

    private int p1Index(List<Map<String, Object>> periods) {
        return indexOfPeriod(periods, "P1");
    }

    // -------------------------------------------------------- 6. idempotency

    @Test
    void t06_importBatchIsIdempotent() {
        Map<String, Object> payload = Map.of(
                "batchRef", "B-TEST-IDEMPOTENT",
                "note", "idempotency test",
                "observations", List.of(Map.of(
                        "id", "o-idem-1", "siteCode", "S-EAST",
                        "observedAt", "2026-04-23T10:00:00+09:00",
                        "markFragment", "MK-A10", "patternSummary", "左耳缺刻",
                        "confidence", 0.8, "observer", "test")));
        ResponseEntity<Map> first = rest.postForEntity(url("/imports"),
                new HttpEntity<>(payload), Map.class);
        ResponseEntity<Map> second = rest.postForEntity(url("/imports"),
                new HttpEntity<>(payload), Map.class);
        assertEquals(200, first.getStatusCode().value());
        assertEquals(1, ((Number) first.getBody().get("inserted")).intValue());
        assertEquals(Boolean.TRUE, second.getBody().get("idempotentReplay"));
        assertEquals(0, ((Number) second.getBody().get("inserted")).intValue());
    }

    // ------------------------------------------------- 7. optimistic locking

    @Test
    void t07_staleBaseRevisionConflictsAndKeepsBothContexts() {
        // Fresh draft cloned from the published v1 so revisions are deterministic.
        Map<String, Object> draftReq = Map.of(
                "lineageId", "L-TEST-CONCURRENCY",
                "title", "并发测试草稿", "cloneFromId", "H-SEED-v1");
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = rest.postForObject(url("/hypotheses"),
                new HttpEntity<>(draftReq), Map.class);
        String id = (String) draft.get("id");

        // Reviewer A edits first (base revision 0 -> 1).
        Map<String, Object> a = Map.of("observationId", "o-1002", "individualCode", "I-002",
                "baseRevision", 0, "reasonCode", "MANUAL_REVIEW");
        ResponseEntity<Map> ra = rest.postForEntity(url("/hypotheses/" + id + "/confirm"),
                new HttpEntity<>(a), Map.class);
        assertEquals(200, ra.getStatusCode().value());
        assertEquals(1, ((Number) ra.getBody().get("revision")).intValue());

        // Reviewer B still holds base revision 0: 409 with the other change attached.
        Map<String, Object> b = Map.of("observationId", "o-1008", "individualCode", "I-003",
                "baseRevision", 0, "reasonCode", "MANUAL_REVIEW");
        ResponseEntity<Map> rb = rest.postForEntity(url("/hypotheses/" + id + "/confirm"),
                new HttpEntity<>(b), Map.class);
        assertEquals(409, rb.getStatusCode().value());
        assertEquals(1, ((Number) rb.getBody().get("currentRevision")).intValue());
        assertEquals(0, ((Number) rb.getBody().get("yourBaseRevision")).intValue());
        assertTrue(Boolean.TRUE.equals(rb.getBody().get("retainYourChange")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> concurrent =
                (List<Map<String, Object>>) rb.getBody().get("concurrentChanges");
        assertTrue(concurrent.stream().anyMatch(c -> "CONFIRM".equals(c.get("action"))
                && "o-1002".equals(c.get("observationId"))));

        // B rebases to revision 1 and the same change succeeds (both edits retained).
        Map<String, Object> bRebased = Map.of("observationId", "o-1008", "individualCode", "I-003",
                "baseRevision", 1, "reasonCode", "MANUAL_REVIEW");
        ResponseEntity<Map> rb2 = rest.postForEntity(url("/hypotheses/" + id + "/confirm"),
                new HttpEntity<>(bRebased), Map.class);
        assertEquals(200, rb2.getStatusCode().value());
        assertEquals(2, ((Number) rb2.getBody().get("revision")).intValue());
    }

    // --------------------------------------------------- 8. publish semantics

    @Test
    void t08_publishSnapshotsVersionsAndIsIdempotent() {
        Map<String, Object> draftReq = Map.of(
                "lineageId", "L-TEST-PUBLISH",
                "title", "发布测试草稿", "cloneFromId", "H-SEED-v1");
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = rest.postForObject(url("/hypotheses"),
                new HttpEntity<>(draftReq), Map.class);
        String id = (String) draft.get("id");

        Map<String, Object> pub = Map.of("baseRevision", 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> r1 = rest.postForObject(url("/hypotheses/" + id + "/publish"),
                new HttpEntity<>(pub), Map.class);
        assertEquals(Boolean.FALSE, r1.get("idempotentReplay"));
        assertEquals(1, ((Number) r1.get("version")).intValue());

        // Draft reopened at revision 0; publishing the same draft revision again
        // must return the same version rather than creating v2.
        @SuppressWarnings("unchecked")
        Map<String, Object> r2 = rest.postForObject(url("/hypotheses/" + id + "/publish"),
                new HttpEntity<>(pub), Map.class);
        assertEquals(Boolean.TRUE, r2.get("idempotentReplay"));
        assertEquals(1, ((Number) r2.get("version")).intValue());

        // The published snapshot is immutable.
        Map<String, Object> edit = Map.of("observationId", "o-1002", "individualCode", "I-002",
                "baseRevision", 0);
        ResponseEntity<Map> blocked = rest.postForEntity(
                url("/hypotheses/" + r1.get("id") + "/confirm"),
                new HttpEntity<>(edit), Map.class);
        assertEquals(400, blocked.getStatusCode().value());

        // Within a published hypothesis each observation maps to at most one individual.
        @SuppressWarnings("unchecked")
        Map<String, Object> snap = getBody("/hypotheses/" + r1.get("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignments =
                (List<Map<String, Object>>) snap.get("assignments");
        long confirmed = assignments.stream().filter(a -> "CONFIRMED".equals(a.get("decision"))).count();
        long distinct = assignments.stream().filter(a -> "CONFIRMED".equals(a.get("decision")))
                .map(a -> a.get("observationId")).distinct().count();
        assertEquals(confirmed, distinct, "one observation belongs to at most one individual");
    }

    // ----------------------------------------------- 9. deny with reason codes

    @Test
    void t09_denyRequiresReasonAndKeepsRawScores() {
        Map<String, Object> draftReq = Map.of("lineageId", "L-TEST-DENY",
                "title", "否定测试草稿", "cloneFromId", "H-SEED-v1");
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = rest.postForObject(url("/hypotheses"),
                new HttpEntity<>(draftReq), Map.class);
        String id = (String) draft.get("id");

        Map<String, Object> noReason = Map.of("observationId", "o-1011",
                "individualCode", "I-003", "baseRevision", 0);
        assertEquals(400, rest.postForEntity(url("/hypotheses/" + id + "/deny"),
                new HttpEntity<>(noReason), Map.class).getStatusCode().value());

        Map<String, Object> ok = Map.of("observationId", "o-1011", "individualCode", "I-003",
                "reasonCode", "MARK_LOST", "reasonNote", "标记脱落导致错配", "baseRevision", 0);
        assertEquals(200, rest.postForEntity(url("/hypotheses/" + id + "/deny"),
                new HttpEntity<>(ok), Map.class).getStatusCode().value());

        @SuppressWarnings("unchecked")
        Map<String, Object> hyp = getBody("/hypotheses/" + id);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignments =
                (List<Map<String, Object>>) hyp.get("assignments");
        assertTrue(assignments.stream().anyMatch(a -> "DENIED".equals(a.get("decision"))
                && "MARK_LOST".equals(a.get("reasonCode"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> candidates = rest.getForObject(
                url("/observations/o-1011/candidates?hypothesisId=" + id), Map.class);
        assertFalse(((List<?>) candidates.get("rawCandidates")).isEmpty(),
                "raw automatic scores are preserved beneath the human denial");
    }
}
