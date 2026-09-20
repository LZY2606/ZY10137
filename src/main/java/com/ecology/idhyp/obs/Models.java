package com.ecology.idhyp.obs;

import java.time.Instant;
import java.util.List;

public final class Models {
    private Models() {
    }

    public record Site(String code, String name, double lat, double lon) {
    }

    public record SurveyPeriod(String code, String name, String startAt, String endAt,
                               long startEpoch, long endEpoch) {
    }

    public record SurveySession(String code, String siteCode, String periodCode,
                                String startAt, String endAt, boolean countZero, String note) {
    }

    public record Individual(String code, String label, String marking,
                             String patternSummary, String createdAt) {
    }

    public record Observation(String id, String batchRef, String siteCode, String observedAt,
                              long observedEpoch, String periodCode, String markFragment,
                              String patternSummary, double confidence, boolean zero,
                              String observer, String note) {
    }

    public record RawCandidate(String observationId, String individualCode, double rawScore,
                               double markComponent, double patternComponent) {
    }

    public record ConflictCheck(double distanceKm, long gapHours, double speedKmh,
                                String anchorObservationId, String anchorSiteCode,
                                String anchorObservedAt, String level) {
    }

    public record CandidateView(String individualCode, String label, double rawScore,
                                double adjustedScore, double markComponent, double patternComponent,
                                String status, String reason, ConflictCheck conflict) {
    }

    public record Hypothesis(String id, String lineageId, String status, Integer version,
                             int revision, String parentHypId, String title,
                             String createdAt, String publishedAt, String note,
                             int confirmedCount, int deniedCount, int unresolvedCount) {
    }

    public record Assignment(Integer id, String hypId, String observationId,
                             String individualCode, String originalIndividual,
                             String decision, String reasonCode, String reasonNote,
                             String createdAt, String createdBy) {
    }

    public record ChangeLog(long id, String hypId, int revision, String action,
                            String observationId, String individualCode, String detail,
                            String actor, String createdAt) {
    }

    public record CaptureRow(String individualCode, String label,
                             List<String> cells, int captured) {
    }

    public record CaptureSummary(String hypId, String status, Integer version,
                                 List<SurveyPeriod> periods,
                                 List<CaptureRow> rows,
                                 List<Double> naiveCaptureProbability,
                                 int individualsConsidered,
                                 String statisticalBasis, String disclaimer) {
    }

    public static String isoNow() {
        return Instant.now().toString();
    }
}
