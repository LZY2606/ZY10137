package com.ecology.idhyp.web;

import com.ecology.idhyp.capture.CaptureService;
import com.ecology.idhyp.hyp.HypothesisService;
import com.ecology.idhyp.obs.Models.CaptureSummary;
import com.ecology.idhyp.obs.Models.Hypothesis;
import com.ecology.idhyp.obs.Models.Observation;
import com.ecology.idhyp.obs.ObservationService;
import com.ecology.idhyp.support.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ObservationService observations;
    private final HypothesisService hypotheses;
    private final CaptureService capture;

    public ApiController(ObservationService observations, HypothesisService hypotheses,
                         CaptureService capture) {
        this.observations = observations;
        this.hypotheses = hypotheses;
        this.capture = capture;
    }

    // -------------------------------------------------------- references

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Hypothesis context = hypotheses.defaultContext();
        Map<String, Object> body = new HashMap<>();
        body.put("sites", observations.sites());
        body.put("periods", observations.periods());
        body.put("sessions", observations.sessions());
        body.put("individuals", observations.individuals());
        body.put("hypotheses", hypotheses.hypotheses());
        body.put("defaultHypothesisId", context.id());
        body.put("unreachableSpeedKmh", HypothesisService.UNREACHABLE_SPEED_KMH);
        body.put("suspiciousSpeedKmh", HypothesisService.SUSPICIOUS_SPEED_KMH);
        body.put("conflictWindowHours", HypothesisService.CONFLICT_WINDOW_HOURS);
        body.put("disclaimer", "基础捕获概率摘要仅用于方法验证，不作生态管理建议。");
        return body;
    }

    @GetMapping("/observations")
    public List<Observation> observations() {
        return observations.observations();
    }

    @PostMapping("/imports")
    public Map<String, Object> importBatch(@RequestBody Map<String, Object> req) {
        String ref = str(req, "batchRef");
        if (ref == null) {
            throw ApiException.badRequest("batchRef is required");
        }
        Object rows = req.get("observations");
        if (rows != null && !(rows instanceof List)) {
            throw ApiException.badRequest("observations must be a list");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) rows;
        return observations.importBatch(ref, str(req, "note"), list);
    }

    // -------------------------------------------------------- hypotheses

    @GetMapping("/hypotheses")
    public List<Hypothesis> listHypotheses() {
        return hypotheses.hypotheses();
    }

    @GetMapping("/hypotheses/{id}")
    public Map<String, Object> getHypothesis(@PathVariable String id) {
        Map<String, Object> body = new HashMap<>();
        body.put("hypothesis", hypotheses.requireHypothesis(id));
        body.put("assignments", hypotheses.assignments(id));
        body.put("changes", hypotheses.changesAfter(id, null));
        body.put("conflicts", hypotheses.currentConflicts(id));
        return body;
    }

    @PostMapping("/hypotheses")
    public Hypothesis createDraft(@RequestBody Map<String, Object> req) {
        return hypotheses.createDraft(str(req, "lineageId"), str(req, "title"),
                str(req, "note"), str(req, "cloneFromId"), str(req, "actor"));
    }

    @PostMapping("/hypotheses/{id}/publish")
    public Map<String, Object> publish(@PathVariable String id, @RequestBody Map<String, Object> req) {
        return hypotheses.publish(id, asInt(req.get("baseRevision")), str(req, "actor"));
    }

    @GetMapping("/hypotheses/{id}/changes")
    public Object changes(@PathVariable String id,
                          @RequestParam(required = false) Integer afterRevision) {
        hypotheses.requireHypothesis(id);
        return hypotheses.changesAfter(id, afterRevision);
    }

    // ----------------------------------------------------------- edits

    @PostMapping("/hypotheses/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable String id, @RequestBody Map<String, Object> req) {
        return hypotheses.confirm(id, str(req, "observationId"), str(req, "individualCode"),
                str(req, "reasonCode"), str(req, "reasonNote"),
                Boolean.TRUE.equals(req.get("force")), asInt(req.get("baseRevision")),
                str(req, "actor"));
    }

    @PostMapping("/hypotheses/{id}/new-individual")
    public Map<String, Object> confirmNew(@PathVariable String id,
                                          @RequestBody Map<String, Object> req) {
        return hypotheses.confirmNew(id, str(req, "observationId"), str(req, "label"),
                str(req, "marking"), str(req, "patternSummary"), str(req, "reasonNote"),
                asInt(req.get("baseRevision")), str(req, "actor"));
    }

    @PostMapping("/hypotheses/{id}/deny")
    public Map<String, Object> deny(@PathVariable String id, @RequestBody Map<String, Object> req) {
        return hypotheses.deny(id, str(req, "observationId"), str(req, "individualCode"),
                str(req, "reasonCode"), str(req, "reasonNote"),
                asInt(req.get("baseRevision")), str(req, "actor"));
    }

    @PostMapping("/hypotheses/{id}/split")
    public Map<String, Object> split(@PathVariable String id, @RequestBody Map<String, Object> req) {
        @SuppressWarnings("unchecked")
        List<String> obsIds = (List<String>) req.getOrDefault("observationIds", List.of());
        return hypotheses.split(id, str(req, "fromIndividual"), str(req, "toIndividual"),
                obsIds, str(req, "reasonNote"), asInt(req.get("baseRevision")), str(req, "actor"));
    }

    @PostMapping("/hypotheses/{id}/merge")
    public Map<String, Object> merge(@PathVariable String id, @RequestBody Map<String, Object> req) {
        return hypotheses.merge(id, str(req, "fromIndividual"), str(req, "intoIndividual"),
                str(req, "reasonNote"), asInt(req.get("baseRevision")), str(req, "actor"));
    }

    @GetMapping("/hypotheses/{id}/conflicts")
    public Object conflicts(@PathVariable String id) {
        hypotheses.requireHypothesis(id);
        return hypotheses.currentConflicts(id);
    }

    // -------------------------------------------------------- candidates

    @GetMapping("/observations/{obsId}/candidates")
    public Object candidates(@PathVariable String obsId,
                             @RequestParam(required = false) String hypothesisId) {
        String hypId = hypothesisId != null ? hypothesisId : hypotheses.defaultContext().id();
        return Map.of(
                "observation", observations.observation(obsId) == null
                        ? null : observations.observation(obsId),
                "hypothesisId", hypId,
                "rawCandidates", observations.rawCandidates(obsId),
                "candidates", hypotheses.candidateViews(hypId, obsId));
    }

    // --------------------------------------------------------- captures

    @GetMapping("/hypotheses/{id}/capture-history")
    public CaptureSummary captureHistory(@PathVariable String id) {
        return capture.summarize(id);
    }

    @GetMapping(value = "/hypotheses/{id}/capture-history.csv",
            produces = "text/csv; charset=utf-8")
    public ResponseEntity<String> captureCsv(@PathVariable String id) {
        CaptureSummary summary = capture.summarize(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"capture-history-" + id + ".csv\"")
                .contentType(MType())
                .body(capture.toCsv(summary));
    }

    private static MediaType MType() {
        return new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static Integer asInt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(v));
    }
}
