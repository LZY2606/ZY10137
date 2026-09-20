package com.ecology.idhyp.capture;

import com.ecology.idhyp.hyp.HypothesisService;
import com.ecology.idhyp.obs.Models.CaptureRow;
import com.ecology.idhyp.obs.Models.CaptureSummary;
import com.ecology.idhyp.obs.Models.Hypothesis;
import com.ecology.idhyp.obs.Models.Individual;
import com.ecology.idhyp.obs.Models.Observation;
import com.ecology.idhyp.obs.Models.SurveyPeriod;
import com.ecology.idhyp.obs.Models.SurveySession;
import com.ecology.idhyp.obs.ObservationService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives capture histories from a hypothesis snapshot.
 *
 * <p>Cell semantics per individual x period:
 * <ul>
 *   <li><b>1</b> &mdash; captured: a non-zero observation is CONFIRMED for this
 *       individual and falls inside the period's half-open interval;</li>
 *   <li><b>0</b> &mdash; surveyed, not captured: observers made an effort at a
 *       site the individual ever used during that period, but no capture;</li>
 *   <li><b>NA</b> &mdash; not surveyed: no sessions at the individual's sites
 *       in that period. NA is a missing value and never an observed zero.</li>
 * </ul>
 * Sessions with count_zero=true are explicit "observed zero" records and count
 * as effort. Individuals are restricted to known individuals (the registry).
 */
@Service
public class CaptureService {

    private final ObservationService observations;
    private final HypothesisService hypotheses;

    public CaptureService(ObservationService observations, HypothesisService hypotheses) {
        this.observations = observations;
        this.hypotheses = hypotheses;
    }

    public CaptureSummary summarize(String hypId) {
        Hypothesis h = hypotheses.requireHypothesis(hypId);
        List<SurveyPeriod> periods = observations.periods();
        List<SurveySession> sessions = observations.sessions();
        Map<String, List<AssignmentLite>> byIndividual = confirmedByIndividual(hypId);

        Map<String, Set<String>> everSites = everUsedSites(hypId);

        List<CaptureRow> rows = new ArrayList<>();
        List<Double> perPeriodProbability = new ArrayList<>(periods.size());

        for (Individual ind : observations.individuals()) {
            List<String> cells = new ArrayList<>(periods.size());
            List<AssignmentLite> anchors = byIndividual.getOrDefault(ind.code(), List.of());
            Set<String> indSites = everSites.getOrDefault(ind.code(), Set.of());
            for (SurveyPeriod period : periods) {
                cells.add(cell(period, anchors, sessions, indSites));
            }
            int captured = (int) cells.stream().filter("1"::equals).count();
            rows.add(new CaptureRow(ind.code(), ind.label(), cells, captured));
        }

        for (int i = 0; i < periods.size(); i++) {
            int observed = 0;
            int seen = 0;
            for (CaptureRow row : rows) {
                String cell = row.cells().get(i);
                if (!"NA".equals(cell)) {
                    observed++;
                    if ("1".equals(cell)) {
                        seen++;
                    }
                }
            }
            perPeriodProbability.add(observed == 0 ? null : round((double) seen / observed));
        }

        return new CaptureSummary(h.id(), h.status(), h.version(), periods, rows,
                perPeriodProbability, rows.size(),
                "naive per-period detection fraction: captured(1) / (captured(1)+missed(0)); "
                        + "NA cells excluded from the denominator",
                "基础捕获概率摘要仅用于方法验证，不构成种群评估或生态管理建议。");
    }

    private String cell(SurveyPeriod period, List<AssignmentLite> anchors,
                        List<SurveySession> sessions, Set<String> indSites) {
        boolean captured = anchors.stream().anyMatch(a ->
                a.epoch >= period.startEpoch() && a.epoch < period.endEpoch());
        if (captured) {
            return "1";
        }
        boolean effort = sessions.stream().anyMatch(s -> s.periodCode().equals(period.code())
                && (indSites.isEmpty() || indSites.contains(s.siteCode())));
        return effort ? "0" : "NA";
    }

    private Map<String, List<AssignmentLite>> confirmedByIndividual(String hypId) {
        Map<String, List<AssignmentLite>> out = new LinkedHashMap<>();
        for (var a : hypotheses.confirmed(hypId)) {
            if (a.observationId() == null) {
                continue;
            }
            Observation o = observations.observation(a.observationId());
            if (o != null && !o.zero()) {
                out.computeIfAbsent(a.individualCode(), k -> new ArrayList<>())
                        .add(new AssignmentLite(o.siteCode(), o.observedEpoch()));
            }
        }
        return out;
    }

    /** Sites at which each individual has ever been confirmed (across the hypothesis). */
    private Map<String, Set<String>> everUsedSites(String hypId) {
        Map<String, Set<String>> out = new HashMap<>();
        for (var a : hypotheses.confirmed(hypId)) {
            Observation o = a.observationId() == null ? null
                    : observations.observation(a.observationId());
            if (o != null && !o.zero()) {
                out.computeIfAbsent(a.individualCode(), k -> new HashSet<>()).add(o.siteCode());
            }
        }
        return out;
    }

    public String toCsv(CaptureSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("individual,label");
        for (SurveyPeriod p : summary.periods()) {
            sb.append(',').append(p.code());
        }
        sb.append(",captured\n");
        for (CaptureRow row : summary.rows()) {
            sb.append(row.individualCode()).append(',').append(csv(row.label()));
            for (String cell : row.cells()) {
                sb.append(',').append(cell);
            }
            sb.append(',').append(row.captured()).append('\n');
        }
        sb.append("naive_capture_probability,-");
        for (Double p : summary.naiveCaptureProbability()) {
            sb.append(',').append(p == null ? "NA" : p);
        }
        sb.append("\n# legend: 1=captured; 0=surveyed but not captured; "
                + "NA=not surveyed (missing, excluded from probability denominator)\n");
        sb.append("# basis: ").append(summary.statisticalBasis()).append('\n');
        sb.append("# disclaimer: ").append(summary.disclaimer()).append('\n');
        return sb.toString();
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        return v.contains(",") ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private record AssignmentLite(String siteCode, long epoch) {
    }
}
