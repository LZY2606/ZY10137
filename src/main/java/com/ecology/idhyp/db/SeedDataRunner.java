package com.ecology.idhyp.db;

import com.ecology.idhyp.obs.ObservationService;
import com.ecology.idhyp.support.Times;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Deterministic demo dataset, idempotent: every insert is keyed by fixed codes,
 * and import goes through the same batch API as user uploads.
 */
@Component
public class SeedDataRunner implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final ObservationService observations;

    public SeedDataRunner(JdbcTemplate jdbc, ObservationService observations) {
        this.jdbc = jdbc;
        this.observations = observations;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedSites();
        seedPeriods();
        seedSessions();
        seedIndividuals();
        seedObservations();
        seedPublishedHypothesis();
        seedWorkingDraft();
    }

    private void seedSites() {
        insertSite("S-EAST", "东坡相机", 35.6812, 139.7671);
        insertSite("S-WEST", "西坡相机", 35.6586, 139.7023);
        insertSite("S-SOUTH", "南岭相机", 35.6200, 139.7400);
        insertSite("S-NORTH", "北岭相机", 35.7200, 139.7800);
    }

    private void insertSite(String code, String name, double lat, double lon) {
        if (!exists("SELECT 1 FROM site WHERE code=?", code)) {
            jdbc.update("INSERT INTO site(code,name,lat,lon) VALUES(?,?,?,?)", code, name, lat, lon);
        }
    }

    private void seedPeriods() {
        // Half-open intervals, explicit Asia/Tokyo offset on every endpoint.
        insertPeriod("P1", "调查一期", "2026-03-10T08:00:00+09:00", "2026-03-20T08:00:00+09:00");
        insertPeriod("P2", "调查二期", "2026-03-20T08:00:00+09:00", "2026-03-30T08:00:00+09:00");
        insertPeriod("P3", "调查三期", "2026-03-30T08:00:00+09:00", "2026-04-09T08:00:00+09:00");
        insertPeriod("P4", "调查四期", "2026-04-09T08:00:00+09:00", "2026-04-19T08:00:00+09:00");
        insertPeriod("P5", "调查五期", "2026-04-19T08:00:00+09:00", "2026-04-29T08:00:00+09:00");
    }

    private void insertPeriod(String code, String name, String start, String end) {
        if (!exists("SELECT 1 FROM survey_period WHERE code=?", code)) {
            jdbc.update("INSERT INTO survey_period(code,name,start_at,end_at,start_epoch,end_epoch)"
                    + " VALUES(?,?,?,?,?,?)", code, name, start, end,
                    Times.epoch(start), Times.epoch(end));
        }
    }

    private void seedSessions() {
        insertSession("SE-P1-EAST", "S-EAST", "P1",
                "2026-03-10T08:00:00+09:00", "2026-03-20T08:00:00+09:00", false, null);
        insertSession("SE-P1-NORTH", "S-NORTH", "P1",
                "2026-03-11T07:30:00+09:00", "2026-03-11T12:30:00+09:00", false, null);
        insertSession("SE-P1-WEST", "S-WEST", "P1",
                "2026-03-13T08:00:00+09:00", "2026-03-13T13:00:00+09:00", false, null);
        insertSession("SE-P2-EAST", "S-EAST", "P2",
                "2026-03-20T08:00:00+09:00", "2026-03-21T08:00:00+09:00", false, "换班日");
        insertSession("SE-P2-WEST", "S-WEST", "P2",
                "2026-03-22T11:00:00+09:00", "2026-03-22T16:00:00+09:00", false, null);
        insertSession("SE-P2-WEST-ZERO", "S-WEST", "P2",
                "2026-03-25T08:00:00+09:00", "2026-03-25T13:00:00+09:00", true, "观察为零");
        insertSession("SE-P3-SOUTH", "S-SOUTH", "P3",
                "2026-04-02T08:00:00+09:00", "2026-04-02T14:00:00+09:00", false, null);
        insertSession("SE-P3-EAST-ZERO", "S-EAST", "P3",
                "2026-04-08T08:00:00+09:00", "2026-04-08T12:00:00+09:00", true, "观察为零");
        insertSession("SE-P4-SOUTH", "S-SOUTH", "P4",
                "2026-04-10T08:00:00+09:00", "2026-04-10T15:00:00+09:00", false, null);
        // P5 intentionally has NO sessions: not surveyed (missing) for every individual.
    }

    private void insertSession(String code, String site, String period,
                               String start, String end, boolean zero, String note) {
        if (!exists("SELECT 1 FROM survey_session WHERE code=?", code)) {
            jdbc.update("INSERT INTO survey_session(code,site_code,period_code,start_at,end_at,"
                    + "start_epoch,end_epoch,count_zero,note) VALUES(?,?,?,?,?,?,?,?,?)",
                    code, site, period, start, end, Times.epoch(start), Times.epoch(end),
                    zero ? 1 : 0, note);
        }
    }

    private void seedIndividuals() {
        insertIndividual("I-001", "雪斑 A1", "MK-A100", "左耳缺刻 尾斑四点 长尾", null);
        insertIndividual("I-002", "雪斑 B2", "MK-B200", "右耳豁口 额斑两点 粗尾", null);
        insertIndividual("I-003", "雪斑 C3", "MK-C300", "颈斑双弧 长尾 尾尖黑", null);
        insertIndividual("I-004", "雪斑 D4", "MK-D400", "右前腿伤疤 额斑新月 短尾", null);
    }

    private void insertIndividual(String code, String label, String marking,
                                  String pattern, String batch) {
        if (!exists("SELECT 1 FROM individual WHERE code=?", code)) {
            jdbc.update("INSERT INTO individual(code,label,marking,pattern_summary,created_batch,created_at)"
                    + " VALUES(?,?,?,?,?,?)", code, label, marking, pattern, batch,
                    Instant.now().toString());
        }
    }

    private boolean exists(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return !rows.isEmpty();
    }

    private void seedObservations() {
        if (exists("SELECT 1 FROM import_batch WHERE batch_ref='B1'")) {
            return;
        }
        // Batch B1: ambiguous photo matches, occlusion, an unreachable pair, and
        // an observation exactly on the P1/P2 changeover boundary.
        observations.importBatch("B1", "野外照片导入", List.of(
                obs("o-1001", "S-EAST", "2026-03-12T09:15:00+09:00",
                        "MK-A10", "左耳缺刻 尾斑四点", 0.95, "张"),
                obs("o-1002", "S-EAST", "2026-03-14T18:40:00+09:00",
                        "MK-B2", "额斑两点 粗尾", 0.9, "李"),
                obs("o-1003", "S-NORTH", "2026-03-11T10:05:00+09:00",
                        "MK-A1X0", "左耳缺刻 长尾", 0.72, "张"),
                obs("o-1004", "S-WEST", "2026-03-11T12:00:00+09:00",
                        "MK-A100", "左耳缺刻 尾斑模糊", 0.62, "王"),
                obs("o-1005", "S-WEST", "2026-03-16T07:50:00+09:00",
                        "MK-C30", "颈斑双弧 长尾", 0.8, "王"),
                obs("o-1006", "S-EAST", "2026-03-18T19:10:00+09:00",
                        "MK-C3X0", "颈斑 尾尖黑", 0.55, "李"),
                // Exactly at the P1 -> P2 boundary: belongs to P2 only.
                obs("o-1007", "S-EAST", "2026-03-20T08:00:00+09:00",
                        "MK-B200", "额斑两点 右耳豁口", 0.93, "张"),
                obs("o-1008", "S-WEST", "2026-03-22T12:10:00+09:00",
                        "MK-C300", "颈斑双弧 尾尖黑", 0.82, "王"),
                // Explicit observed-zero: effort present, no animal.
                zero("o-1009", "S-WEST", "2026-03-25T10:00:00+09:00", "王", "蹲守5小时未见"),
                obs("o-1010", "S-SOUTH", "2026-04-02T11:20:00+09:00",
                        "MK-C300", "颈斑双弧 长尾", 0.78, "赵"),
                obs("o-1011", "S-SOUTH", "2026-04-05T06:30:00+09:00",
                        "MK-X999", "通体斑纹散乱 白腹", 0.5, "赵"),
                zero("o-1012", "S-EAST", "2026-04-08T09:30:00+09:00", "李", "相机正常无记录"),
                obs("o-1013", "S-SOUTH", "2026-04-10T09:45:00+09:00",
                        "MK-D4X0", "右前腿伤疤 额斑新月", 0.86, "赵"),
                obs("o-1014", "S-SOUTH", "2026-04-14T17:30:00+09:00",
                        "MK-E500", "额斑新月 短尾", 0.7, "赵"),
                obs("o-1015", "S-NORTH", "2026-04-17T08:10:00+09:00",
                        "MK-D400", "额斑新月 右前腿疤", 0.88, "张"),
                // Blurred duplicate-looking frame; reviewer may deny as duplicate code.
                obs("o-1016", "S-EAST", "2026-04-12T20:00:00+09:00",
                        "MK-?", "模糊斑块 无法辨认", 0.3, "李"),
                zero("o-1017", "S-SOUTH", "2026-04-16T08:00:00+09:00", "赵", "整期零目击")));
    }

    private Map<String, Object> obs(String id, String site, String at, String mark,
                                    String pattern, double confidence, String observer) {
        return Map.of("id", id, "siteCode", site, "observedAt", at,
                "markFragment", mark, "patternSummary", pattern,
                "confidence", confidence, "observer", observer);
    }

    private Map<String, Object> zero(String id, String site, String at,
                                     String observer, String note) {
        return Map.of("id", id, "siteCode", site, "observedAt", at,
                "zero", true, "confidence", 1.0,
                "observer", observer, "note", note);
    }

    private void seedPublishedHypothesis() {
        if (exists("SELECT 1 FROM hypothesis WHERE id='H-SEED-v1'")) {
            return;
        }
        String now = Instant.now().toString();
        jdbc.update("INSERT INTO hypothesis(id,lineage_id,status,version,revision,parent_hyp_id,"
                + "title,created_at,published_at,note) VALUES('H-SEED-v1','L-SEED','PUBLISHED',1,1,"
                + "NULL,'主身份假设',?,?,'初始发布版本')", now, now);
        // Human-confirmed identities. o-1004/o-1006/o-1011/o-1014/o-1016 stay
        // unresolved to preserve ambiguity instead of collapsing to top scores.
        confirmed("H-SEED-v1", "o-1001", "I-001");
        confirmed("H-SEED-v1", "o-1002", "I-002");
        confirmed("H-SEED-v1", "o-1003", "I-001");
        confirmed("H-SEED-v1", "o-1005", "I-003");
        confirmed("H-SEED-v1", "o-1007", "I-002");
        confirmed("H-SEED-v1", "o-1008", "I-003");
        confirmed("H-SEED-v1", "o-1010", "I-003");
        confirmed("H-SEED-v1", "o-1013", "I-004");
        confirmed("H-SEED-v1", "o-1015", "I-004");
        jdbc.update("INSERT INTO publication(hyp_id,revision,published_at) VALUES('H-SEED-v1',1,?)", now);
    }

    private void seedWorkingDraft() {
        if (exists("SELECT 1 FROM hypothesis WHERE lineage_id='L-SEED' AND status='DRAFT'")) {
            return;
        }
        String now = Instant.now().toString();
        jdbc.update("INSERT INTO hypothesis(id,lineage_id,status,version,revision,parent_hyp_id,"
                + "title,created_at,note) VALUES('H-WORK','L-SEED','DRAFT',NULL,1,'H-SEED-v1',"
                + "'主身份假设（审阅草稿）',?,'从 v1 克隆，含一条人工否定')", now);
        // Clone v1 decisions into the working draft.
        jdbc.update("INSERT INTO hypothesis_assignment(hyp_id,observation_id,individual_code,"
                + "original_individual,decision,reason_code,reason_note,created_at,created_by) "
                + "SELECT 'H-WORK',observation_id,individual_code,original_individual,decision,"
                + "reason_code,reason_note,?,created_by FROM hypothesis_assignment WHERE hyp_id='H-SEED-v1'",
                now);
        // A human denial with reason on the blurred frame.
        jdbc.update("INSERT INTO hypothesis_assignment(hyp_id,observation_id,individual_code,"
                + "original_individual,decision,reason_code,reason_note,created_at,created_by)"
                + " VALUES('H-WORK','o-1016',NULL,NULL,'DENIED','DATA_ENTRY_ERROR',"
                + "'模糊重影，疑似重复编码，待原始批次核对',?,'researcher')", now);
        jdbc.update("INSERT INTO hypothesis_change(hyp_id,revision,action,observation_id,"
                + "individual_code,detail,actor,created_at) VALUES('H-WORK',1,'DENY','o-1016',"
                + "NULL,'DATA_ENTRY_ERROR :: 模糊重影','researcher',?)", now);
    }

    private void confirmed(String hypId, String obsId, String individual) {
        jdbc.update("INSERT INTO hypothesis_assignment(hyp_id,observation_id,individual_code,"
                + "original_individual,decision,reason_code,reason_note,created_at,created_by)"
                + " VALUES(?,?,?,NULL,'CONFIRMED','AUTO_CARRIED','种子已发布确认',"
                + "?,'researcher')", hypId, obsId, individual, Instant.now().toString());
    }
}
