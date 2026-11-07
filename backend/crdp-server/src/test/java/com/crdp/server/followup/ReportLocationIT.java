package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
 * E16-T13 端到端 / A-ATT-002、A-ATT-020；C-SCAN-010、C-SCAN-020（完整应用 + 真实 MySQL，HTTP 直连）。
 * 验收原文：上传与修改位置要求至少 1 个位置，锚点不在当前模板版本时拒绝；同一报告单同一位置唯一；增删写操作日志；无位置的报告单可查询为「未指定位置」。
 *
 * <p>覆盖：修改位置增删同场（add 按当前模板版本校验、remove 四元组定位、version +1、写 RPT_LOCATION_CHANGE 操作日志带增加 /
 * 移除清单）；移除最后一个位置 40001「请至少保留 1 个位置」；锚点不在当前模板版本 40001、 重复添加与移除不在册位置 40001、越患者记录
 * 40400；无位置报告单（模拟历史迁移删位置行）患者内清单 locations 为空 + 全院检索 UNASSIGNED 可查，经 add 补指定（「指定位置」）；无上传权限 40300、越病区
 * 40400（写 DENIED 审计）、模块关闭 40301、跨租户 / 已删 40400。全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportLocationIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String BASE = "/api/hospital/followup";
    private static final String LEGACY = "IT-E16T13";

    /** 越病区用例的病区医生（A_WARD_DOCTOR + 指定病区 + 本用例补授上传按钮 231）。 */
    private static final String WARD_USER = "IT-T13-WD";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;

    @BeforeAll
    void setUpOnce() {
        // 231 上传按钮给 A_WARD_DOCTOR——越病区用例要的是「有权限但越范围 → 40400」
        // （TENANT_ADMIN 的 231 由种子 V125 授予，无需用例再补。）
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "INSERT INTO sys_role_menu (role_id, menu_id) SELECT r.id, 231 FROM"
                                        + " sys_role r WHERE r.code = 'A_WARD_DOCTOR' AND NOT EXISTS"
                                        + " (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id"
                                        + " AND rm.menu_id = 231)"));
    }

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
                        jdbc.update("DELETE FROM ward WHERE code = 'W-IT-T13'");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE action IN ('RPT_LOCATION_CHANGE',"
                                        + " 'RPT_CREATE', 'RPT_DELETE')");
                        // 全院检索（UNASSIGNED 用例）写 BATCH_QUERY 数据访问审计
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_data_access_log WHERE object_type = 'REPORT'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
        runInTenant(
                "demoa",
                () -> {
                    // 收回本用例补授的按钮与病区医生，避免影响其他用例 / 后续运行
                    jdbc.update(
                            "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                    + " WHERE r.code = 'A_WARD_DOCTOR' AND rm.menu_id = 231");
                    jdbc.update(
                            "DELETE uw FROM sys_user_ward uw JOIN sys_user u ON u.id = uw.user_id"
                                    + " WHERE u.username = ?",
                            WARD_USER);
                    jdbc.update(
                            "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                    + " WHERE u.username = ?",
                            WARD_USER);
                    jdbc.update("DELETE FROM sys_user WHERE username = ?", WARD_USER);
                });
    }

    // ---------------------------------------------------------------- 验收：增删同场 + 操作日志

    /** 增删同场：新增分组锚点按记录固化版本落行、移除基线分类、version +1、写 RPT_LOCATION_CHANGE 操作日志。 */
    @Test
    void modifyLocationsAddsAndRemovesWithOperationLog() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient(admin, defaultWardId("demoa"));
        long recordId = backfillRecord(admin, patientId, "2026-06-10");
        String categoryKey = baselineCategoryKey(admin, patientId);
        String sectionKey = firstSectionKey(admin, patientId);
        // 初始两个位置：基线分类 + 记录整条；增删同场后 = 整条 + 新增分组（-1 +1 = 2）
        long reportId =
                createReport(
                        admin,
                        patientId,
                        List.of(baselineLoc(categoryKey), wholeRecordLoc(recordId)));

        Map<String, Object> body = new HashMap<>();
        body.put("add", List.of(sectionLoc(recordId, sectionKey)));
        body.put("remove", List.of(baselineLoc(categoryKey)));
        Map<?, ?> changed = put(BASE + "/reports/" + reportId + "/locations", admin, body);
        assertEquals(0, code(changed), "修改位置失败：" + changed);
        Map<?, ?> view = data(changed);
        assertEquals(2, ((Number) view.get("locationCount")).intValue(), "1 个 - 移除 1 + 新增 1");
        List<?> locations = (List<?>) view.get("locations");
        assertTrue(
                locations.stream()
                        .anyMatch(
                                loc -> "section".equals(str(((Map<?, ?>) loc).get("anchorType")))),
                "回读位置含新增分组");

        // 落库：整条记录 + 分组各一行（基线分类已删）；分组锚点版本 = 记录固化版本
        List<Map<String, Object>> rows =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForList(
                                        "SELECT link_type AS linkType, anchor_type AS anchorType,"
                                                + " anchor_key AS anchorKey, template_version AS v"
                                                + " FROM rpt_report_link WHERE report_id = ?"
                                                + " ORDER BY id",
                                        reportId));
        assertEquals(2, rows.size(), "移除的基线分类行已删、新增分组行已插");
        assertEquals("followup_record", rows.get(0).get("linkType"));
        assertNullValue(rows.get(0).get("anchorType"), "整条记录 anchorType 为 NULL");
        assertEquals("section", rows.get(1).get("anchorType"));
        assertEquals(rows.get(0).get("v"), rows.get(1).get("v"), "分组锚点版本与整条记录一致（记录固化版本）");

        // version + 1（V63 列注释「修改位置随 E16-T13」）
        assertEquals(
                1,
                runInTenant(
                                "demoa",
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT version FROM rpt_report WHERE id = ?",
                                                Integer.class,
                                                reportId))
                        .intValue(),
                "位置变更推进乐观锁版本");

        // 操作日志：RPT_LOCATION_CHANGE，描述带增加 / 移除的位置（谁、何时、增加或移除了哪个位置）
        assertTrue(
                runInTenant(
                                "demoa",
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                        + " 'RPT_LOCATION_CHANGE' AND object_id = ?"
                                                        + " AND result = 'SUCCESS' AND detail LIKE"
                                                        + " '%增加 1 个%' AND detail LIKE"
                                                        + " '%移除 1 个%' AND detail LIKE"
                                                        + " '%历史补录 2026-06-10%' AND detail LIKE"
                                                        + " '%基线 · %'",
                                                Long.class, String.valueOf(reportId)))
                        >= 1,
                "增删写操作日志且列出位置标签");
        assertNotNull(
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT before_json FROM aud_log WHERE action ="
                                                + " 'RPT_LOCATION_CHANGE' AND object_id = ?",
                                        String.class,
                                        String.valueOf(reportId))),
                "操作日志 before/after 留位置快照");

        // 患者内清单反映新位置
        Map<?, ?> page = get(BASE + "/patients/" + patientId + "/reports", admin);
        Map<?, ?> row = reportRowOf(page, reportId);
        assertEquals(2, ((List<?>) row.get("locations")).size(), "清单位置数与结果一致");
    }

    // ---------------------------------------------------------------- 验收：至少保留 1 个

    /** 移除最后一个位置 → 40001「请至少保留 1 个位置」，位置行不动、不写操作日志。 */
    @Test
    void modifyLocationsRejectsRemovingEveryPosition() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient(admin, defaultWardId("demoa"));
        String categoryKey = baselineCategoryKey(admin, patientId);
        long reportId = createReport(admin, patientId, List.of(baselineLoc(categoryKey)));

        Map<String, Object> body = new HashMap<>();
        body.put("remove", List.of(baselineLoc(categoryKey)));
        Map<?, ?> rejected = put(BASE + "/reports/" + reportId + "/locations", admin, body);
        assertEquals(40001, code(rejected), "全部移除 40001");
        assertTrue(
                str(rejected.get("message")).contains("请至少保留 1 个位置"), str(rejected.get("message")));

        assertEquals(1, linkCount(reportId), "位置行原样保留（无位置报告单不经本口产生，A-ATT-020「至少保留一个」）");
        assertEquals(
                0,
                runInTenant(
                                "demoa",
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                        + " 'RPT_LOCATION_CHANGE' AND object_id = ?",
                                                Long.class,
                                                String.valueOf(reportId)))
                        .longValue(),
                "被拒的修改不写操作日志");
    }

    // ---------------------------------------------------------------- 验收：锚点版本 / 唯一性校验

    /** 锚点不在当前模板版本 40001；重复添加 / 移除不在册 40001；越患者记录 40400；空清单 40001。 */
    @Test
    void modifyLocationsRejectsStaleAnchorsAndUnknownPositions() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient(admin, defaultWardId("demoa"));
        long recordId = backfillRecord(admin, patientId, "2026-06-10");
        String categoryKey = baselineCategoryKey(admin, patientId);
        String sectionKey = firstSectionKey(admin, patientId);
        long reportId = createReport(admin, patientId, List.of(baselineLoc(categoryKey)));

        Map<String, Object> staleCategory = new HashMap<>();
        staleCategory.put("add", List.of(baselineLoc("ghost-category-it")));
        assertEquals(
                40001,
                code(put(BASE + "/reports/" + reportId + "/locations", admin, staleCategory)),
                "基线锚点不在当前模板版本 → 拒绝");

        Map<String, Object> staleSection = new HashMap<>();
        staleSection.put("add", List.of(sectionLoc(recordId, "ghost-section-it")));
        assertEquals(
                40001,
                code(put(BASE + "/reports/" + reportId + "/locations", admin, staleSection)),
                "随访分组锚点不在该记录固化版本 → 拒绝");

        Map<String, Object> duplicate = new HashMap<>();
        duplicate.put("add", List.of(baselineLoc(categoryKey)));
        Map<?, ?> dupRejected = put(BASE + "/reports/" + reportId + "/locations", admin, duplicate);
        assertEquals(40001, code(dupRejected), "同一报告单同一位置唯一：重复添加 40001");
        assertTrue(
                str(dupRejected.get("message")).contains("已在报告单上"),
                str(dupRejected.get("message")));

        Map<String, Object> unknownRemove = new HashMap<>();
        unknownRemove.put(
                "remove", List.of(baselineLoc(otherBaselineKey(admin, patientId, categoryKey))));
        Map<?, ?> removeRejected =
                put(BASE + "/reports/" + reportId + "/locations", admin, unknownRemove);
        assertEquals(40001, code(removeRejected), "移除不在册位置 40001");
        assertTrue(
                str(removeRejected.get("message")).contains("不在报告单上"),
                str(removeRejected.get("message")));

        // 越患者记录：第二位患者的记录 id 加到第一位患者的报告单 → 40400（不区分不存在 / 他人 / 越范围）
        long otherPatient = registeredPatient(admin, defaultWardId("demoa"));
        long otherRecord = backfillRecord(admin, otherPatient, "2026-05-01");
        Map<String, Object> foreign = new HashMap<>();
        foreign.put("add", List.of(wholeRecordLoc(otherRecord)));
        assertEquals(
                40400,
                code(put(BASE + "/reports/" + reportId + "/locations", admin, foreign)),
                "越患者记录 40400");

        Map<String, Object> empty = new HashMap<>();
        empty.put("add", List.of());
        empty.put("remove", List.of());
        assertEquals(
                40001,
                code(put(BASE + "/reports/" + reportId + "/locations", admin, empty)),
                "add / remove 都为空 40001");

        assertEquals(1, linkCount(reportId), "全部拒绝后位置行不变");
    }

    // ---------------------------------------------------------------- 验收：未指定位置

    /**
     * 无位置报告单（模拟历史迁移 E18-T08 / 模板调整 E16-T15 删去位置行）：患者内清单 locations 为空、 全院检索 UNASSIGNED 可查（「未指定位置」）；经
     * add 补指定（A-ATT-020「指定位置」）后不再出现在 UNASSIGNED。
     */
    @Test
    void unassignedReportIsQueryableAndCanBeAssigned() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient(admin, defaultWardId("demoa"));
        String categoryKey = baselineCategoryKey(admin, patientId);
        long reportId = createReport(admin, patientId, List.of(baselineLoc(categoryKey)));
        // 模拟历史迁移：位置行被迁走（E18-T08：原关联基线的进入未指定位置）
        runInTenant(
                "demoa",
                () -> jdbc.update("DELETE FROM rpt_report_link WHERE report_id = ?", reportId));

        Map<?, ?> page = get(BASE + "/patients/" + patientId + "/reports", admin);
        Map<?, ?> row = reportRowOf(page, reportId);
        assertTrue(((List<?>) row.get("locations")).isEmpty(), "无位置报告单在患者内清单 locations 为空");

        Map<String, Object> search = new HashMap<>();
        search.put("locationScope", "UNASSIGNED");
        search.put("pageSize", 100);
        Map<?, ?> unassigned = post(BASE + "/reports/search", admin, search);
        assertEquals(0, code(unassigned), "全院检索失败：" + unassigned);
        assertTrue(
                searchItems(unassigned).stream()
                        .anyMatch(item -> str(item.get("id")).equals(str(reportId))),
                "无位置报告单可按「未指定位置」检索");

        // 「指定位置」：经 add 补上第一个位置
        Map<String, Object> assign = new HashMap<>();
        assign.put("add", List.of(baselineLoc(categoryKey)));
        Map<?, ?> assigned = put(BASE + "/reports/" + reportId + "/locations", admin, assign);
        assertEquals(0, code(assigned), "补指定位置失败：" + assigned);
        assertEquals(1, ((Number) data(assigned).get("locationCount")).intValue());

        Map<?, ?> after = post(BASE + "/reports/search", admin, search);
        assertFalse(
                searchItems(after).stream()
                        .anyMatch(item -> str(item.get("id")).equals(str(reportId))),
                "补指定后不再属于「未指定位置」");
    }

    // ---------------------------------------------------------------- 权限 / 范围 / 模块 / 租户

    /** 无上传权限 40300；越病区 40400（写 DENIED 审计）；模块关闭 40301；跨租户 / 已删报告单 40400。 */
    @Test
    void guardsPermissionScopeModuleAndTenant() {
        String admin = hospitalToken("demoa");
        long ownWard = defaultWardId("demoa");
        long otherWard = createWard("W-IT-T13", "示例 IT 位置隔离病区（E16-T13）");
        long patientId = registeredPatient(admin, otherWard);
        String categoryKey = baselineCategoryKey(admin, patientId);
        long reportId = createReport(admin, patientId, List.of(baselineLoc(categoryKey)));

        String doctor = hospitalToken("demoa", "doctor.demo");
        Map<String, Object> add = new HashMap<>();
        add.put("add", List.of(baselineLoc(categoryKey)));
        assertEquals(
                40300,
                code(put(BASE + "/reports/" + reportId + "/locations", doctor, add)),
                "FOLLOWUP_DOCTOR 无 followup:scan:upload → 40300");

        // 越病区：患者落在其他病区，本病区范围的病区医生（有上传按钮 231）→ 40400 + DENIED 审计
        String wardUser = wardDoctorToken(ownWard);
        assertEquals(
                40400,
                code(put(BASE + "/reports/" + reportId + "/locations", wardUser, add)),
                "越病区修改位置 40400（不暴露存在）");
        assertTrue(
                runInTenant(
                                "demoa",
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                        + " 'RPT_LOCATION_CHANGE' AND result ="
                                                        + " 'DENIED' AND object_id = ?",
                                                Long.class,
                                                String.valueOf(reportId)))
                        >= 1,
                "越范围修改位置写 DENIED 审计");

        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    code(put(BASE + "/reports/" + reportId + "/locations", admin, add)),
                    "模块关闭 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 恢复（示例）"));
        }

        String tenantB = hospitalToken("demob");
        assertEquals(
                40400,
                code(put(BASE + "/reports/" + reportId + "/locations", tenantB, add)),
                "跨租户修改位置 40400");

        // 已删报告单：删除（E16-T09）后修改位置 → 40400
        Map<?, ?> deleted =
                delete(BASE + "/reports/" + reportId, admin, Map.of("reason", "IT 用例删除（示例）"));
        assertEquals(0, code(deleted), "删除报告单失败：" + deleted);
        assertEquals(
                40400,
                code(put(BASE + "/reports/" + reportId + "/locations", admin, add)),
                "已删报告单修改位置 40400");
    }

    // ---------------------------------------------------------------- 造数与工具（与 ReportDeletionIT
    // 同口径）

    private long registeredPatient(String token, long wardId) {
        Map<String, Object> body = new HashMap<>();
        String legacyNo = LEGACY + "-" + (System.nanoTime() % 100000);
        body.put("medicalRecordNo", legacyNo);
        body.put("name", "何位置（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
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
    private long backfillRecord(String token, long patientId, String visitDate) {
        Map<?, ?> created =
                post(
                        BASE + "/patients/" + patientId + "/records",
                        token,
                        Map.of(
                                "source",
                                "BACKFILL",
                                "visitDate",
                                visitDate,
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
        body.put("reportDate", "2026-10-28");
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

    /** 基线的另一个分类 key（不在报告单上的移除用例）。 */
    private String otherBaselineKey(String token, long patientId, String exclude) {
        List<?> children = (List<?>) locationRoot(token, patientId, "baseline").get("children");
        return children.stream()
                .map(node -> (Map<?, ?>) node)
                .filter(
                        node ->
                                !exclude.equals(
                                        str(((Map<?, ?>) node.get("value")).get("anchorKey"))))
                .map(node -> str(((Map<?, ?>) node.get("value")).get("anchorKey")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("基线模板应有 ≥ 2 个分类"));
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

    private long linkCount(long reportId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?",
                                Long.class,
                                reportId));
    }

    private Map<?, ?> reportRowOf(Map<?, ?> page, long reportId) {
        return itemsOf(page).stream()
                .filter(item -> str(item.get("id")).equals(str(reportId)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("报告单不在患者内清单"));
    }

    private static List<Map<?, ?>> searchItems(Map<?, ?> response) {
        return itemsOf(response);
    }

    private static void assertNullValue(Object value, String message) {
        assertTrue(value == null, message);
    }

    /** 建病区医生（A_WARD_DOCTOR + 指定病区；上传按钮 231 已在 BeforeAll 补授），返回登录令牌。 */
    private String wardDoctorToken(long wardId) {
        String admin = hospitalToken("demoa");
        long deptId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'",
                                        Long.class));
        long roleId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_role WHERE code = 'A_WARD_DOCTOR'",
                                        Long.class));
        jdbcUpdateDeleteUser();
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        admin,
                        Map.of(
                                "username",
                                WARD_USER,
                                "displayName",
                                "病区医生（示例）",
                                "phone",
                                "13900001613",
                                "mainDeptId",
                                deptId,
                                "roleIds",
                                List.of(roleId),
                                "wardIds",
                                List.of(wardId)));
        assertEquals(0, code(created), "建病区范围用户失败：" + created);
        String temporary = str(data(created).get("temporaryPassword"));
        String newPassword = "It#E16t13-Pass";
        String token = hospitalToken("demoa", WARD_USER, temporary);
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", newPassword))),
                "首登改密失败");
        return token;
    }

    /** 用户可能已在上次运行残留（AfterAll 删除；BeforeEach 兜底清一次）。 */
    private void jdbcUpdateDeleteUser() {
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "DELETE uw FROM sys_user_ward uw JOIN sys_user u ON u.id = uw.user_id"
                                    + " WHERE u.username = ?",
                            WARD_USER);
                    jdbc.update(
                            "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                    + " WHERE u.username = ?",
                            WARD_USER);
                    jdbc.update("DELETE FROM sys_user WHERE username = ?", WARD_USER);
                });
    }

    private long createWard(String code, String name) {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO ward (code, name, status) VALUES (?, ?, 'ENABLED')",
                            code,
                            name);
                    return jdbc.queryForObject(
                            "SELECT id FROM ward WHERE code = ?", Long.class, code);
                });
    }

    private long defaultWardId(String tenant) {
        return runInTenant(
                tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    private String hospitalToken(String tenant) {
        return hospitalToken(tenant, "nurse.demo");
    }

    private String hospitalToken(String tenant, String username) {
        return hospitalToken(tenant, username, PASSWORD);
    }

    private String hospitalToken(String tenant, String username, String password) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", password));
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

    private Map<?, ?> delete(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.DELETE, path, token, body);
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
