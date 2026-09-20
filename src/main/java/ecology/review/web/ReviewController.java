package ecology.review.web;

import ecology.review.dto.Requests.ActionRequest;
import ecology.review.dto.Requests.AssignRequest;
import ecology.review.dto.Requests.CreateRevisionRequest;
import ecology.review.dto.Requests.ImportBatch;
import ecology.review.dto.Requests.MergeRequest;
import ecology.review.dto.Requests.PublishRequest;
import ecology.review.dto.Requests.SplitRequest;
import ecology.review.dto.Responses.CaptureSummary;
import ecology.review.dto.Responses.ImportResponse;
import ecology.review.dto.Responses.PublishResponse;
import ecology.review.dto.Responses.TimelineResponse;
import ecology.review.service.ImportService;
import ecology.review.service.QueryService;
import ecology.review.service.RevisionService;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ReviewController {
  private final ImportService importService;
  private final RevisionService revisionService;
  private final QueryService queryService;

  public ReviewController(ImportService importService, RevisionService revisionService,
                          QueryService queryService) {
    this.importService = importService;
    this.revisionService = revisionService;
    this.queryService = queryService;
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "ok");
  }

  @PostMapping("/imports")
  public ImportResponse importBatch(@RequestBody ImportBatch batch) {
    return importService.importBatch(batch);
  }

  @GetMapping("/timeline")
  public TimelineResponse timeline(@RequestParam(name = "revision_id", required = false) Long revisionId) {
    return queryService.timeline(revisionId);
  }

  @GetMapping("/surveys")
  public Object surveys() {
    return queryService.surveys();
  }

  @GetMapping("/individuals")
  public Object individuals() {
    return queryService.individuals();
  }

  @GetMapping("/revisions")
  public Object revisions() {
    return queryService.revisions();
  }

  @GetMapping("/revisions/{revisionId}")
  public Object revision(@PathVariable Long revisionId) {
    return queryService.revision(revisionId);
  }

  @PostMapping("/revisions")
  public Object createRevision(@RequestBody CreateRevisionRequest request) {
    return revisionService.createRevision(request);
  }

  @PostMapping("/revisions/{revisionId}/publish")
  public PublishResponse publish(@PathVariable Long revisionId, @RequestBody PublishRequest request) {
    return revisionService.publish(revisionId, request);
  }

  @PostMapping("/observations/{observationId}/assign")
  public Object assign(@PathVariable Long observationId, @RequestBody AssignRequest request) {
    return revisionService.assign(request.revisionId() == null ? null : request.revisionId(),
        observationId, request);
  }

  @PostMapping("/revisions/{revisionId}/observations/{observationId}/candidates/{individualId}/confirm")
  public Object confirm(@PathVariable Long revisionId, @PathVariable Long observationId,
                        @PathVariable Long individualId, @RequestBody ActionRequest request) {
    return revisionService.confirm(revisionId, observationId, individualId, request);
  }

  @PostMapping("/revisions/{revisionId}/observations/{observationId}/candidates/{individualId}/deny")
  public Object deny(@PathVariable Long revisionId, @PathVariable Long observationId,
                     @PathVariable Long individualId, @RequestBody ActionRequest request) {
    return revisionService.deny(revisionId, observationId, individualId, request);
  }

  @PostMapping("/revisions/{revisionId}/observations/{observationId}/split")
  public Object split(@PathVariable Long revisionId, @PathVariable Long observationId,
                      @RequestBody SplitRequest request) {
    return revisionService.split(revisionId, observationId, request);
  }

  @PostMapping("/revisions/{revisionId}/observations/{observationId}/merge/{fromIndividualId}")
  public Object merge(@PathVariable Long revisionId, @PathVariable Long observationId,
                      @PathVariable Long fromIndividualId, @RequestBody MergeRequest request) {
    return revisionService.merge(revisionId, observationId, fromIndividualId, request);
  }

  @PostMapping("/revisions/{revisionId}/identities/{fromIndividualId}/merge")
  public Object mergeIdentity(@PathVariable Long revisionId, @PathVariable Long fromIndividualId,
                              @RequestBody MergeRequest request) {
    return revisionService.mergeIdentity(revisionId, fromIndividualId, request);
  }

  @GetMapping("/captures/summary")
  public CaptureSummary captureSummary() {
    return queryService.captureSummary();
  }

  @GetMapping(value = "/captures/history", produces = MediaType.APPLICATION_JSON_VALUE)
  public Object captureHistoryJson() {
    return queryService.captureHistories();
  }

  @GetMapping(value = "/captures/history.csv", produces = "text/csv")
  public ResponseEntity<String> captureHistoryCsv() {
    StringBuilder csv = new StringBuilder();
    csv.append("individual_code,survey_code,site_code,interval_start,interval_end,zone_id,status,capture\n");
    queryService.captureHistories().forEach(history -> history.occasions().forEach(cell -> {
      csv.append(csv(history.individualCode())).append(',')
          .append(csv(cell.surveyCode())).append(',')
          .append(csv(cell.siteCode())).append(',')
          .append(csv(cell.intervalStart())).append(',')
          .append(csv(cell.intervalEnd())).append(',')
          .append(csv(cell.zoneId())).append(',')
          .append(cell.status()).append(',')
          .append(cell.capture() == null ? "NA" : cell.capture()).append('\n');
    }));
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=capture-history.csv")
        .body(csv.toString());
  }

  @GetMapping("/reasons")
  public Map<String, List<String>> reasons() {
    return Map.of("reason_codes", List.of("MARKER_SHED", "DUPLICATE_CODE", "DATA_ENTRY_ERROR",
        "PATTERN_CONFIRMATION", "OBSERVER_REVIEW", "SPLIT_DISTINCT_INDIVIDUAL",
        "MERGE_SAME_INDIVIDUAL"));
  }

  private static String csv(String value) {
    if (value == null) {
      return "";
    }
    return '"' + value.replace("\"", "\"\"") + '"';
  }
}
