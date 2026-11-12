package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E21-T09 端到端 / C-FU-018：随访序号自动编号与重排。验收原文（tasks.csv「补录插入后序号自动重排」； hospital-c-spec §5「补录 2021-06
 * 的记录后，该患者原第 3 次及之后的随访序号自动加 1，变更历史中有重排记录」）。
 *
 * <p>链路（followup V95 列 sequence_no + 开关 record_sequence_no）：补录三条（早 → 晚）依次编号 1..3 → 补录插入一条更早的中间日期记录
 * → 插入点之后（含原第 2 / 3 次）序号 +1、内部记录号（fu_record.id）不变、version 不动 （重排不是编辑）→ 每行变化落
 * fe_field_history（field_key = sequence_no、source = SYSTEM_RECALC）→ 详情 sequenceNo 读重排落库值。全部虚构数据，门控
 * CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SequenceNumberIT {

    private static final String TENANT_A = "demoa";
    private static final long TENANT_A_ID = 1L;
    private static final String PASSWORD = "Demo@2026";
    private static final String DOCTOR = "doctor.demo";
    private static final String LEGACY = "IT-E21T09";
    private static final String FORM = "A_ANNUAL_FU";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        runInTenantA(
                () -> {
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 1 WHERE feature_code ="
                                    + " 'record_sequence_no'");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE (event_type IN"
                                    + " ('RECORD_SUBMIT', 'RECORD_BACKFILL_CREATE')"
                                    + " AND target IN (SELECT CONCAT('FU_RECORD:', r.id)"
                                    + " FROM fu_record r JOIN pt_patient p"
                                    + " ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))");
                    jdbc.update(
                            "DELETE fh FROM fe_field_history fh"
                                    + " JOIN fu_record r ON r.id = fh.record_id"
                                    + " JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE fh.record_type = 'FU_RECORD'"
                                    + " AND p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE r FROM fu_record r JOIN pt_patient p"
                                    + " ON p.id = r.patient_id WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
        // 恢复开关关闭（V95 默认 0，A 租户不编号），避免污染其余 IT 的租户状态
        runInTenantA(
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0 WHERE feature_code ="
                                        + " 'record_sequence_no'"));
    }

    // ---------------------------------------------------------------- 用例

    /**
     * 验收位：补录插入后序号自动重排。三条补录（today-90 / -30 / -10）依次编号 1 / 2 / 3 → 补录插入 today-60 → 插入记录第 2、原第 2 / 3
     * 次顺延 3 / 4，第 1 次不动；重排逐行入变更历史（旧值 → 新值）， 内部记录号与 version 不变。
     */
    @Test
    void backfillInsertReordersLaterSequenceNumbersWithHistory() {
        LocalDate today = today();
        String doctor = login(DOCTOR);
        long patientId = registerPatient(doctor, "P1", today);

        String first = submitBackfill(doctor, patientId, today.minusDays(90));
        String second = submitBackfill(doctor, patientId, today.minusDays(30));
        String third = submitBackfill(doctor, patientId, today.minusDays(10));
        assertEquals(1, sequenceNoOf(first), "最早一条第 1");
        assertEquals(2, sequenceNoOf(second));
        assertEquals(3, sequenceNoOf(third));
        assertEquals(1, versionOf(second), "提交后 version = 1（DRAFT 0 → 提交 +1）");

        // 补录插入更早的中间日期（today-60）：落在第 1 与第 2 之间
        String inserted = submitBackfill(doctor, patientId, today.minusDays(60));

        assertEquals(2, sequenceNoOf(inserted), "插入记录编号 2（首编）");
        assertEquals(1, sequenceNoOf(first), "插入点之前不动");
        assertEquals(3, sequenceNoOf(second), "原第 2 次自动加 1");
        assertEquals(4, sequenceNoOf(third), "原第 3 次自动加 1");
        assertEquals(1, versionOf(second), "重排不是编辑：version 不动（内部记录号不变）");
        // 详情读侧（E21-T11 sequenceNo → 落库值优先）：HTTP 口径与库内一致
        assertEquals(3, detailSequenceNo(doctor, second));
        assertEquals(4, detailSequenceNo(doctor, third));

        // 变更历史：插入触发的重排逐行留痕（旧值 → 新值）；第 1 次无重排行（只有自身首编）
        assertTrue(
                hasHistory(inserted, null, 2),
                "插入记录首编留痕（旧值 null → 2）；实际行：" + historyDump(inserted));
        assertTrue(hasHistory(second, 2, 3), "原第 2 次重排留痕（2 → 3）；实际行：" + historyDump(second));
        assertTrue(hasHistory(third, 3, 4), "原第 3 次重排留痕（3 → 4）");
        assertEquals(
                "SYSTEM_RECALC",
                runInTenantA(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT source FROM fe_field_history"
                                                + " WHERE record_type = 'FU_RECORD'"
                                                + " AND record_id = ? AND field_key = 'sequence_no'"
                                                + " AND CAST(old_value AS CHAR) = '2'"
                                                + " ORDER BY id DESC LIMIT 1",
                                        String.class,
                                        Long.parseLong(second))),
                "重排来源 = 系统重算");
    }

    /** 提交即编号（自动编号 1..N）：每条已提交记录首编留痕（旧值 null → N），详情与库内一致。 */
    @Test
    void submitNumbersAscendingAndWritesFirstAssignment() {
        LocalDate today = today();
        String doctor = login(DOCTOR);
        long patientId = registerPatient(doctor, "P2", today);

        String earlier = submitBackfill(doctor, patientId, today.minusDays(30));
        String latest = submitBackfill(doctor, patientId, today.minusDays(5));

        assertEquals(1, sequenceNoOf(earlier));
        assertEquals(2, sequenceNoOf(latest));
        assertTrue(hasHistory(earlier, null, 1), "首编留痕（null → 1）");
        assertTrue(hasHistory(latest, null, 2), "首编留痕（null → 2）");
    }

    // ---------------------------------------------------------------- 装配

    private long registerPatient(String token, String suffix, LocalDate today) {
        long wardId =
                runInTenantA(() -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + suffix);
        body.put("name", "周序号（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1956-02-02");
        body.put("wardId", wardId);
        body.put("admissionDate", today.minusDays(100).toString());
        body.put("surgeryDate", today.minusDays(99).toString());
        body.put("dischargeDate", today.minusDays(98).toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + suffix);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    /** 建补录草稿并提交（HTTP 正口，A-FU-014；必填字段口径与 CompareHighlightIT 一致）。 */
    private String submitBackfill(String token, long patientId, LocalDate visitDate) {
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        token,
                        Map.of(
                                "source",
                                "BACKFILL",
                                "visitDate",
                                visitDate.toString(),
                                "formCode",
                                FORM));
        assertEquals(0, code(created), "创建补录草稿失败：" + created);
        String recordId = String.valueOf(data(created).get("recordId"));
        Map<String, Object> form = new HashMap<>();
        form.put("f214", visitDate.toString());
        form.put("f215", 0);
        form.put("f213", 1);
        form.put("f236", 1);
        form.put("f237", visitDate.toString());
        Map<?, ?> submitted =
                post(
                        "/api/hospital/followup/records/" + recordId + "/submit",
                        token,
                        Map.of("baseVersion", 0, "data", form));
        assertEquals(0, code(submitted), "提交补录失败：" + submitted);
        return recordId;
    }

    /** fu_record.sequence_no（租户库直读）。 */
    private int sequenceNoOf(String recordId) {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                "SELECT sequence_no FROM fu_record WHERE id = ?",
                                Integer.class,
                                Long.parseLong(recordId)));
    }

    /** fu_record.version（重排不动 version 的断言位）。 */
    private int versionOf(String recordId) {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                "SELECT version FROM fu_record WHERE id = ?",
                                Integer.class,
                                Long.parseLong(recordId)));
    }

    /** 详情 sequenceNo（GET /records/{id}；开关开启的已提交记录优先落库值）。 */
    private int detailSequenceNo(String token, String recordId) {
        Map<?, ?> detail = get("/api/hospital/followup/records/" + recordId, token);
        assertEquals(0, code(detail), "记录详情失败：" + detail);
        return ((Number) data(detail).get("sequenceNo")).intValue();
    }

    /** 该记录 sequence_no 留痕行数（首编 + 重排各一行）。 */
    private int historyRowsOf(String recordId) {
        return runInTenantA(
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM fe_field_history"
                                        + " WHERE record_type = 'FU_RECORD' AND record_id = ?"
                                        + " AND field_key = 'sequence_no'",
                                Integer.class,
                                Long.parseLong(recordId)));
    }

    /**
     * 是否存在「旧值 → 新值」的 sequence_no 留痕行（值在 Java 侧比较——JSON 列 CAST 文本口径， null 端为 SQL NULL，避免 JDBC 对 null
     * 占位符绑定的方言差异）。
     */
    private boolean hasHistory(String recordId, Integer oldValue, Integer newValue) {
        List<Map<String, Object>> rows =
                runInTenantA(
                        () ->
                                jdbc.queryForList(
                                        "SELECT CAST(old_value AS CHAR) AS oldv,"
                                                + " CAST(new_value AS CHAR) AS newv"
                                                + " FROM fe_field_history"
                                                + " WHERE record_type = 'FU_RECORD' AND record_id = ?"
                                                + " AND field_key = 'sequence_no'",
                                        Long.parseLong(recordId)));
        for (Map<String, Object> row : rows) {
            if (Objects.equals(text(row.get("oldv")), textOf(oldValue))
                    && Objects.equals(text(row.get("newv")), textOf(newValue))) {
                return true;
            }
        }
        return false;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String textOf(Integer value) {
        return value == null ? null : value.toString();
    }

    /** 调试：该记录全部 sequence_no 留痕行（field_key / old / new / source 拼接）。 */
    private String historyDump(String recordId) {
        return String.valueOf(
                runInTenantA(
                        () ->
                                jdbc.queryForList(
                                        "SELECT CONCAT_WS('|', field_key,"
                                                + " CAST(old_value AS CHAR), CAST(new_value AS CHAR),"
                                                + " source) FROM fe_field_history"
                                                + " WHERE record_type = 'FU_RECORD' AND record_id = ?",
                                        String.class,
                                        Long.parseLong(recordId))));
    }

    private void runInTenantA(Runnable action) {
        TenantContext.runAsTenant(TENANT_A_ID, TENANT_A, action);
    }

    private <T> T runInTenantA(java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_A_ID, TENANT_A, action::get);
    }

    private String login(String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", TENANT_A, "username", username, "password", PASSWORD));
        assertEquals(0, code(response), "登录失败：" + response);
        return (String) data(response).get("token");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    private Map<?, ?> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    private Map<?, ?> exchange(
            HttpMethod method, String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        Map<?, ?> response = rest.exchange(path, method, entity, Map.class).getBody();
        return response == null ? Map.of() : response;
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static LocalDate today() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
    }
}
