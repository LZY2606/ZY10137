package ecology.review.dto;

import java.util.List;

public final class Responses {
  private Responses() {
  }

  public record SiteView(Long id, String code, String name, double latitude, double longitude) {
  }

  public record SurveyView(Long id, String code, String name, String zoneId, String startAt, String endAt,
                           List<String> siteCodes) {
  }

  public record IndividualView(Long id, String code, String displayName, String status,
                               Long mergedIntoId) {
  }

  public record ConflictView(double distanceM, double elapsedSeconds, double requiredSpeedMps,
                             double maxAllowedSpeedMps, String reason) {
  }

  public record CandidateView(Long individualId, String individualCode, String displayName,
                              Double score, String autoState, String effectiveState,
                              String decision, String reasonCode, String reasonDetail,
                              ConflictView conflict, List<String> anchorMarkers,
                              List<String> anchorPatterns) {
  }

  public record ObservationView(Long id, String code, String batchId, String siteCode, String siteName,
                                double latitude, double longitude, Long surveyId, String surveyCode,
                                String observedAt, int uncertaintySeconds, String markerFragment,
                                String patternSummary, double confidence, String observer,
                                Long publishedIndividualId, String publishedIndividualCode,
                                Long draftIndividualId, List<CandidateView> candidates,
                                boolean newIndividualCandidate) {
  }

  public record RevisionItemView(Long observationId, String observationCode, Long individualId,
                                 String individualCode) {
  }

  public record RevisionView(Long id, int revisionNo, Long basedOnRevisionId, String status, String note,
                             String author, String clientKey, int version, String createdAt,
                             String publishedAt, List<RevisionItemView> items) {
  }

  public record ImportResponse(String batchId, boolean reused, int sites, int surveys,
                               int individuals, int observations, Long baselineRevisionId) {
  }

  public record ActionResponse(Long revisionId, int version, Long observationId, Long individualId,
                               String operationType) {
  }

  public record PublishResponse(Long revisionId, int revisionNo, boolean reused, String publishedAt,
                                int assignments) {
  }

  public record CaptureCell(String surveyCode, String surveyName, String siteCode, String siteName,
                            String status, Integer capture, Long individualId, String individualCode,
                            String intervalStart, String intervalEnd, String zoneId) {
  }

  public record CaptureSummary(int surveySites, int observedOccasions, int captures,
                               double rawCaptureProbability, String interpretation) {
  }

  public record CaptureHistory(Long individualId, String individualCode, List<CaptureCell> occasions) {
  }

  public record TimelineResponse(List<SurveyView> surveys, List<ObservationView> observations,
                                 List<IndividualView> individuals, List<RevisionView> revisions,
                                 RevisionView activeRevision) {
  }
}
