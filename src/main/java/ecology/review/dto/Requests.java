package ecology.review.dto;

import java.util.List;

public final class Requests {
  private Requests() {
  }

  public record SiteInput(String code, String name, Double latitude, Double longitude) {
  }

  public record SurveyInput(String code, String name, String zoneId, String startAt, String endAt,
                            List<String> siteCodes) {
  }

  public record IndividualInput(String code, String displayName) {
  }

  public record ObservationInput(String code, String siteCode, String observedAt, Integer uncertaintySeconds,
                                 String markerFragment, String patternSummary, Double confidence,
                                 String observer, String individualCode) {
  }

  public record ImportBatch(String batchId, String source, Boolean baseline,
                            List<SiteInput> sites, List<SurveyInput> surveys,
                            List<IndividualInput> individuals, List<ObservationInput> observations) {
  }

  public record CreateRevisionRequest(Long basedOnRevisionId, String author, String note, String clientKey) {
  }

  public record ActionRequest(Long baseVersion, String author, String reasonCode, String reasonDetail,
                              String clientKey) {
  }

  public record AssignRequest(Long revisionId, Long individualId, Long baseVersion, String author,
                              String reasonCode, String reasonDetail, String clientKey) {
  }

  public record SplitRequest(String displayName, Long baseVersion, String author, String reasonCode,
                             String reasonDetail, String clientKey) {
  }

  public record MergeRequest(Long toIndividualId, Long baseVersion, String author, String reasonCode,
                             String reasonDetail, String clientKey) {
  }

  public record PublishRequest(Long baseVersion, String author, String clientKey, String note) {
  }
}
