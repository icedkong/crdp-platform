package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.report.app.ReportLocationFallbackService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
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
 * E16-T15 端到端 / A-ATT-021、C-SCAN-021（完整应用 + 真实 MySQL，作业体直调）。
 * 验收原文：分节删除后位置退回到分类或整条记录，锚点不存在时移除并提示「原位置已调整」，均写操作日志；重复执行结果不变。
 *
 * <p>模板调整用直改锚点键模拟（记录固化版本 / 基线当前发布版的快照不在测试里重发布——发布真实模板会全局影响其他用例）， 与 ReportLocationIT
 * 模拟历史迁移删位置行同口径。覆盖：随访分组退回整条（锚点清空 + adjusted=1 + 报告单 location_adjusted=1 + version +1 +
 * RPT_LOCATION_FALLBACK 日志 + 患者内清单 adjusted 标签）；分组退回与既有整条位置合并；基线分类移除（失去全部位置 →
 * 「未指定位置」可检索）；重跑幂等（零写入零日志、版本不动）；用户 add 重新指定后报告单标记清零。全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportLocationFallbackIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String BASE = "/api/hospital/followup";

    private static final String LEGACY = "IT-E16T15";

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private ReportLocationFallbackService fallbackService;

    @BeforeEach
    @AfterEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        jdbc.update(
                                "DELETE l FROM rpt_report_link l"
                                        + " JOIN pt_patient p ON p.id = l.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE r FROM rpt_report r"
                                        + " JOIN pt_patient p ON p.id = r.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE FROM fu_record WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                                LEGACY + "-%");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE action IN ('RPT_LOCATION_FALLBACK',"
                                        + " 'RPT_LOCATION_CHANGE', 'RPT_CREATE')");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_data_access_log WHERE object_type = 'REPORT'");
                    });
        }
    }

    // ---------------------------------------------------------------- 验收：分节删除后退回整条记录

    /** 记录固化版本已不含该分组 → 退回整条：锚点清空、adjusted=1、报告单标记、version+1、操作日志、清单标签带提示；重跑结果不变。 */
    @Test
    void sectionAnchorGoneFallsBackToWholeRecordIdempotently() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient(admin, defaultWardId("demoa"));
        long recordId = backfillRecord(admin, patientId);
        String sectionKey = firstSectionKey(admin, patientId);
        long reportId =
                createReport(
                        admin,
                        patientId,
                        List.of(
                                baselineLoc(baselineCategoryKey(admin, patientId)),
                                sectionLoc(recordId, sectionKey)));
        long sectionLinkId = linkIdOf(reportId, "section");
        long version0 = reportVersion(reportId);
        // 模板调整：该分组从记录固化版本中删除（直改锚点键模拟，锚点不在当前分组清单即视为已删）
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE rpt_report_link SET anchor_key = ? WHERE id = ?",
                                "it-gone-sec",
                                sectionLinkId));

        ReportLocationFallbackService.TenantSummary first = reconcile();
        assertEquals(1, first.reports());
        assertEquals(1, first.fallbacks(), "分组退回整条记录");
        assertEquals(0, first.removals());

        Map<String, Object> link = linkRow(sectionLinkId);
        assertNull(link.get("anchor_type"), "锚点清空 = 整条记录");
        assertNull(link.get("anchor_key"));
        assertEquals(1, ((Number) link.get("adjusted")).intValue(), "位置级「原位置已调整」标记");
        assertEquals(1, reportFlag(reportId), "报告单级「原位置已调整」标记（V66）");
        assertEquals(version0 + 1, reportVersion(reportId), "位置变更 version + 1");
        assertEquals(1, auditCount(reportId, "RPT_LOCATION_FALLBACK", "SUCCESS"), "退回写操作日志");
        assertTrue(
                auditDescription(reportId).contains("退回 1 个"),
                "日志描述带退回清单：" + auditDescription(reportId));
        assertEquals(2, linkCount(reportId), "基线分类位置不受影响");

        // 患者内清单：退回后的位置标签带提示、报告单行带报告单级标记
        Map<?, ?> row =
                reportRowOf(get(BASE + "/patients/" + patientId + "/reports", admin), reportId);
        assertTrue((Boolean) row.get("locationAdjusted"), "清单行带报告单级「原位置已调整」");
        List<?> locations = (List<?>) row.get("locations");
        Map<?, ?> fallbackTag =
                locations.stream()
                        .map(tag -> (Map<?, ?>) tag)
                        .filter(tag -> "followup_record".equals(tag.get("linkType")))
                        .findFirst()
                        .orElseThrow();
        assertTrue((Boolean) fallbackTag.get("adjusted"), "位置标签带退回标记（前端追加「（原位置已调整）」）");
        assertNull(fallbackTag.get("anchorType"), "退回后为整条记录");

        // 重复执行结果不变：零写入、零日志、版本不动
        ReportLocationFallbackService.TenantSummary second = reconcile();
        assertEquals(0, second.reports(), "无陈旧锚点 → 无变更");
        assertEquals(version0 + 1, reportVersion(reportId));
        assertEquals(1, auditCount(reportId, "RPT_LOCATION_FALLBACK", "SUCCESS"), "不重复写日志");
    }

    /** 同一记录的整条位置已在册：分组行移除、既有整条行置退回标记（同一报告单同一位置唯一）。 */
    @Test
    void staleSectionMergesIntoExistingWholeRecordRow() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient(admin, defaultWardId("demoa"));
        long recordId = backfillRecord(admin, patientId);
        long reportId =
                createReport(
                        admin,
                        patientId,
                        List.of(
                                wholeRecordLoc(recordId),
                                sectionLoc(recordId, firstSectionKey(admin, patientId))));
        long sectionLinkId = linkIdOf(reportId, "section");
        long wholeLinkId = linkIdOf(reportId, null);
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE rpt_report_link SET anchor_key = ? WHERE id = ?",
                                "it-gone-sec",
                                sectionLinkId));

        ReportLocationFallbackService.TenantSummary summary = reconcile();
        assertEquals(1, summary.removals(), "分组行按移除落库");
        assertEquals(0, summary.fallbacks(), "整条已在册，不新增行");
        assertEquals(1, linkCount(reportId), "只剩整条位置行");
        assertEquals(1, ((Number) linkRow(wholeLinkId).get("adjusted")).intValue(), "既有整条行置退回标记");
        assertEquals(1, reportFlag(reportId));
        assertTrue(auditDescription(reportId).contains("移除 1 个"), auditDescription(reportId));
    }

    // ---------------------------------------------------------------- 验收：锚点不存在时移除 → 未指定位置

    /** 基线分类已不在当前发布版（基线无上一级可退）→ 移除；失去全部位置的报告单进入「未指定位置」。 */
    @Test
    void staleBaselineCategoryRemovedAndReportEntersUnassigned() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient(admin, defaultWardId("demoa"));
        long reportId =
                createReport(
                        admin,
                        patientId,
                        List.of(baselineLoc(baselineCategoryKey(admin, patientId))));
        long baselineLinkId = linkIdOf(reportId, "category");
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE rpt_report_link SET anchor_key = ? WHERE id = ?",
                                "it-gone-cat",
                                baselineLinkId));

        ReportLocationFallbackService.TenantSummary summary = reconcile();
        assertEquals(1, summary.removals());
        assertEquals(0, summary.fallbacks(), "基线分类无上一级可退，直接移除");
        assertEquals(0, linkCount(reportId), "失去全部位置");
        assertEquals(1, reportFlag(reportId), "报告单级「原位置已调整」提示（V66）");
        assertTrue(auditDescription(reportId).contains("移除 1 个"), auditDescription(reportId));

        Map<?, ?> row =
                reportRowOf(get(BASE + "/patients/" + patientId + "/reports", admin), reportId);
        assertTrue(((List<?>) row.get("locations")).isEmpty(), "患者内清单 locations 为空 = 未指定位置");
        assertTrue((Boolean) row.get("locationAdjusted"));

        Map<String, Object> search = new HashMap<>();
        search.put("locationScope", "UNASSIGNED");
        search.put("pageSize", 100);
        Map<?, ?> unassigned = post(BASE + "/reports/search", admin, search);
        assertEquals(0, code(unassigned));
        assertTrue(
                itemsOf(unassigned).stream()
                        .anyMatch(item -> str(item.get("id")).equals(str(reportId))),
                "失去全部位置的报告单可按「未指定位置」检索");

        // 用户 add 重新指定后：报告单级标记清零（位置级提示随位置行重建不再带）
        Map<String, Object> assign = new HashMap<>();
        assign.put("add", List.of(baselineLoc(baselineCategoryKey(admin, patientId))));
        assertEquals(
                0,
                code(put(BASE + "/reports/" + reportId + "/locations", admin, assign)),
                "补指定位置失败");
        assertEquals(0, reportFlag(reportId), "重新指定后报告单级提示清零");
        assertFalse(
                itemsOf(post(BASE + "/reports/search", admin, search)).stream()
                        .anyMatch(item -> str(item.get("id")).equals(str(reportId))),
                "补指定后退出「未指定位置」");
    }

    /** 随访记录不存在（整条 / 分组都无退回目标）→ 移除位置。 */
    @Test
    void missingRecordRemovesFollowupLinks() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient(admin, defaultWardId("demoa"));
        long recordId = backfillRecord(admin, patientId);
        long reportId =
                createReport(
                        admin,
                        patientId,
                        List.of(
                                wholeRecordLoc(recordId),
                                sectionLoc(recordId, firstSectionKey(admin, patientId))));
        // 模拟记录被清理（fu_record 无删除口，直删行模拟外部迁移 / 数据修复场景）
        runInTenant("demoa", () -> jdbc.update("DELETE FROM fu_record WHERE id = ?", recordId));

        ReportLocationFallbackService.TenantSummary summary = reconcile();
        assertEquals(1, summary.reports());
        assertEquals(2, summary.removals(), "整条与分组行都移除");
        assertEquals(0, linkCount(reportId));
        assertEquals(1, reportFlag(reportId));
        assertTrue(auditDescription(reportId).contains("不存在"), auditDescription(reportId));
    }

    // ---------------------------------------------------------------- 造数与工具（与 ReportLocationIT
    // 同口径）

    private ReportLocationFallbackService.TenantSummary reconcile() {
        return runInTenant("demoa", () -> fallbackService.reconcileCurrentTenant());
    }

    private long registeredPatient(String token, long wardId) {
        Map<String, Object> body = new HashMap<>();
        String legacyNo = LEGACY + "-" + (System.nanoTime() % 100000);
        body.put("medicalRecordNo", legacyNo);
        body.put("name", "何退回（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1950-01-01");
        body.put("wardId", wardId);
        body.put("admissionDate", LocalDate.now().minusDays(23).toString());
        body.put("surgeryDate", LocalDate.now().minusDays(22).toString());
        body.put("dischargeDate", LocalDate.now().minusDays(20).toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", legacyNo);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记患者失败：" + created);
        return Long.parseLong(str(data(created).get("id")));
    }

    /** 历史补录建一条 fu_record（E15-T07 接口；未入组患者须显式指定 formCode），返回 recordId。 */
    private long backfillRecord(String token, long patientId) {
        Map<?, ?> created =
                post(
                        BASE + "/patients/" + patientId + "/records",
                        token,
                        Map.of(
                                "source",
                                "BACKFILL",
                                "visitDate",
                                LocalDate.now().minusMonths(2).toString(),
                                "formCode",
                                "A_ANNUAL_FU"));
        assertEquals(0, code(created), "历史补录失败：" + created);
        return Long.parseLong(str(data(created).get("recordId")));
    }

    /** 建报告单（LAB 首个名称必填，OQ-95③；不挂附件），返回 reportId。 */
    private long createReport(String token, long patientId, List<Map<String, Object>> locations) {
        List<?> names = (List<?>) get(BASE + "/report-names?typeCode=LAB", token).get("data");
        String nameId = str(((Map<?, ?>) names.get(0)).get("id"));
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("typeCode", "LAB");
        body.put("nameId", nameId);
        body.put("reportDate", "2026-11-06");
        body.put("locations", locations);
        Map<?, ?> created = post(BASE + "/reports", token, body);
        assertEquals(0, code(created), "创建报告单失败：" + created);
        return Long.parseLong(str(data(created).get("id")));
    }

    private static Map<String, Object> baselineLoc(String categoryKey) {
        return Map.of("linkType", "baseline", "anchorType", "category", "anchorKey", categoryKey);
    }

    private static Map<String, Object> sectionLoc(long recordId, String sectionKey) {
        return Map.of(
                "linkType",
                "followup_record",
                "linkRecordId",
                String.valueOf(recordId),
                "anchorType",
                "section",
                "anchorKey",
                sectionKey);
    }

    private static Map<String, Object> wholeRecordLoc(long recordId) {
        Map<String, Object> whole = new HashMap<>();
        whole.put("linkType", "followup_record");
        whole.put("linkRecordId", String.valueOf(recordId));
        whole.put("anchorType", null);
        return whole;
    }

    /** 基线首个分类 key（位置树 baseline 根的第一个子节点）。 */
    private String baselineCategoryKey(String token, long patientId) {
        List<?> children = (List<?>) locationRoot(token, patientId, "baseline").get("children");
        return str(((Map<?, ?>) ((Map<?, ?>) children.get(0)).get("value")).get("anchorKey"));
    }

    /** 首条随访记录的首个分组 key。 */
    private String firstSectionKey(String token, long patientId) {
        List<?> records = (List<?>) locationRoot(token, patientId, "followup").get("children");
        Map<?, ?> record = (Map<?, ?>) records.get(0);
        Object firstSection = ((List<?>) record.get("children")).get(0);
        return str(((Map<?, ?>) ((Map<?, ?>) firstSection).get("value")).get("anchorKey"));
    }

    /** 位置树根（baseline / followup），children 由调用方取。 */
    private Map<?, ?> locationRoot(String token, long patientId, String key) {
        List<?> roots =
                (List<?>)
                        get(BASE + "/patients/" + patientId + "/report-locations", token)
                                .get("data");
        return (Map<?, ?>)
                roots.stream()
                        .filter(node -> key.equals(((Map<?, ?>) node).get("key")))
                        .findFirst()
                        .orElseThrow();
    }

    /** 报告单某粒度的位置行 id（anchor_type 匹配；null = 整条）。 */
    private long linkIdOf(long reportId, String anchorType) {
        Long id =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM rpt_report_link WHERE report_id = ?"
                                                + " AND ((? IS NULL AND anchor_type IS NULL) OR anchor_type = ?)",
                                        Long.class,
                                        reportId,
                                        anchorType,
                                        anchorType));
        assertNotNull(id, "应存在 anchor_type=" + anchorType + " 的位置行");
        return id;
    }

    private Map<String, Object> linkRow(long linkId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForMap(
                                "SELECT link_type, anchor_type, anchor_key, adjusted"
                                        + " FROM rpt_report_link WHERE id = ?",
                                linkId));
    }

    private long linkCount(long reportId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?",
                                Long.class,
                                reportId));
    }

    private int reportFlag(long reportId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT location_adjusted FROM rpt_report WHERE id = ?",
                                Integer.class,
                                reportId));
    }

    private long reportVersion(long reportId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT version FROM rpt_report WHERE id = ?",
                                Long.class,
                                reportId));
    }

    private long auditCount(long reportId, String action, String result) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM aud_log WHERE action = ? AND result = ?"
                                        + " AND object_id = ?",
                                Long.class,
                                action,
                                result,
                                String.valueOf(reportId)));
    }

    private String auditDescription(long reportId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT detail FROM aud_log WHERE action = 'RPT_LOCATION_FALLBACK'"
                                        + " AND object_id = ?",
                                String.class,
                                String.valueOf(reportId)));
    }

    private Map<?, ?> reportRowOf(Map<?, ?> page, long reportId) {
        return itemsOf(page).stream()
                .filter(item -> str(item.get("id")).equals(str(reportId)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("报告单不在患者内清单"));
    }

    private String hospitalToken(String tenant) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of(
                                "tenantCode",
                                tenant,
                                "username",
                                "nurse.demo",
                                "password",
                                PASSWORD));
        assertEquals(0, code(response));
        return (String) data(response).get("token");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    private Map<?, ?> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.PUT, path, token, body);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> exchange(
            HttpMethod method, String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return (Map<?, ?>)
                rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        Object code = response == null ? null : response.get("code");
        return code == null ? -1 : Integer.parseInt(code.toString());
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> itemsOf(Map<?, ?> response) {
        return (List<Map<?, ?>>) data(response).get("items");
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long defaultWardId(String tenant) {
        return runInTenant(
                tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantIdOf(tenant), tenant, action::get);
    }

    private long tenantIdOf(String tenant) {
        return "demob".equals(tenant) ? 2L : 1L;
    }
}
