package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * E16-T05 端到端 / A-ATT-016、C-SCAN-014（完整应用 + 真实 MySQL，HTTP 直连）。验收原文：按病区隔离。
 *
 * <p>覆盖：全院列表行列（患者脱敏、位置标签含分组、缩略图首附件、电子化状态）；位置筛选含「未指定位置」（无位置行的报告单可查为
 * 未指定位置）；患者关键词（姓名盲索引精确）与文字关键词（名称）；类型 / 报告时间 / 上传人 / 电子化 / 病区筛选与分页校验； 批量查询写 BATCH_QUERY
 * 数据访问审计；按病区隔离（病区范围用户只见本病区，数据范围改写而非查询条件）； 无菜单权限 40300、模块关闭 40301。全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportSearchIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String BASE = "/api/hospital/followup";
    private static final String UPLOAD = "/api/hospital/files";
    private static final String LEGACY = "IT-E16T05";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;

    @BeforeEach
    @AfterEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        jdbc.update(
                                "DELETE v FROM rpt_indicator_value v"
                                        + " JOIN rpt_report r ON r.id = v.report_id"
                                        + " JOIN pt_patient p ON p.id = r.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE f FROM rpt_report_file f"
                                        + " JOIN rpt_report r ON r.id = f.report_id"
                                        + " JOIN pt_patient p ON p.id = r.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
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
                        jdbc.update("DELETE FROM sys_file WHERE original_name LIKE 'IT-E16T05-%'");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                        jdbc.update("DELETE FROM ward WHERE code = 'W-IT-T5'");
                        jdbc.update(
                                "DELETE uw FROM sys_user_ward uw JOIN sys_user u ON u.id = uw.user_id"
                                        + " WHERE u.username LIKE 'IT-T5-%'");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username LIKE 'IT-T5-%'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph"
                                        + " JOIN sys_user u ON u.id = ph.user_id"
                                        + " WHERE u.username LIKE 'IT-T5-%'");
                        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-T5-%'");
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM aud_log WHERE action = 'RPT_CREATE'");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_data_access_log WHERE object_type = 'REPORT'"
                                        + " AND description = '检索全院报告单列表'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 列表行与位置标签（A-ATT-016 位置列 +N
    // 的数据源）

    /** 行：患者脱敏、三种粒度位置标签、缩略图首附件、附件数；无附件行 firstFile=null。 */
    @Test
    void searchReturnsMaskedRowsWithLocationTagsAndFirstFile() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient("demoa", admin, defaultWardId("demoa"), "周报告（示例）");
        long recordId = backfillRecord(admin, patientId, "2026-06-10");
        long fileId = uploadPng(admin, "IT-E16T05-检验报告（示例）.png");
        Map<?, ?> name = labNameWithItems(admin);
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/reports",
                                admin,
                                reportBody(
                                        patientId,
                                        "LAB",
                                        str(name.get("id")),
                                        baselineCategoryKey(admin, patientId),
                                        recordId,
                                        firstSectionKey(admin, patientId),
                                        fileId))),
                "造一份三位置报告单");

        Map<?, ?> page = post(BASE + "/reports/search", admin, Map.of());
        assertEquals(0, code(page), "检索失败：" + page);
        Map<?, ?> data = data(page);
        assertEquals(1L, ((Number) data.get("total")).longValue(), "先只看到本用例的报告单");
        assertEquals(20, ((Number) data.get("pageSize")).intValue(), "默认每页 20（AGENTS §6）");

        Map<?, ?> row = (Map<?, ?>) ((List<?>) data.get("items")).get(0);
        assertEquals("周*）", row.get("patientName"), "行内姓名脱敏（列表不提供逐行明文）");
        assertNotNull(row.get("mrn"), "病案号脱敏值");
        assertEquals("检验", row.get("typeName"));
        assertEquals("血常规", row.get("name"));
        assertEquals("虚构病区一", row.get("wardName"));
        assertEquals(1L, ((Number) row.get("fileCount")).longValue());
        assertEquals("png", ((Map<?, ?>) row.get("firstFile")).get("format"), "缩略图取第一个附件");
        assertEquals(str(fileId), str(((Map<?, ?>) row.get("firstFile")).get("fileId")));
        List<?> locations = (List<?>) row.get("locations");
        assertEquals(3, locations.size(), "三种粒度位置各一标签（前端折叠 +N）");
        assertTrue(
                ((Map<?, ?>) locations.get(0)).get("anchorLabel") != null
                        && ((Map<?, ?>) locations.get(1)).get("recordLabel") != null
                        && ((Map<?, ?>) locations.get(2)).get("anchorLabel") != null,
                "标签带基线分类 / 记录名 / 分组显示名：" + locations);
        assertFalse(data.get("types").toString().isEmpty(), "类型候选随页返回");
        assertFalse(data.get("uploaders").toString().isEmpty(), "上传人候选随页返回");
        assertFalse(data.get("wards").toString().isEmpty(), "病区候选随页返回");

        runInTenant(
                "demoa",
                () ->
                        assertTrue(
                                jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM aud_data_access_log"
                                                        + " WHERE action = 'BATCH_QUERY'"
                                                        + " AND object_type = 'REPORT'"
                                                        + " AND description = '检索全院报告单列表'"
                                                        + " AND result = 'SUCCESS'",
                                                Long.class)
                                        >= 1,
                                "批量查询写 BATCH_QUERY 数据访问审计"));
    }

    // ---------------------------------------------------------------- 位置筛选含「未指定位置」（验收口径）

    /** 无位置报告单（历史迁移 / 模板调整后失去位置）可查为「未指定位置」，行内 locations 为空。 */
    @Test
    void unassignedLocationFilterReturnsReportsWithoutLocations() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient("demoa", admin, defaultWardId("demoa"), "吴报告（示例）");
        Map<?, ?> name = labNameWithItems(admin);
        String categoryKey = baselineCategoryKey(admin, patientId);
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/reports",
                                admin,
                                reportBody(
                                        patientId,
                                        "LAB",
                                        str(name.get("id")),
                                        categoryKey,
                                        null,
                                        null,
                                        null))),
                "先造一份有位置的报告单");
        long unassignedId =
                runInTenant(
                        "demoa",
                        () -> {
                            // A 预置类型名称均必填（V62），第二份同样用 LAB 名称区分靠 report_date
                            Map<?, ?> second =
                                    post(
                                            BASE + "/reports",
                                            admin,
                                            reportBody(
                                                    patientId,
                                                    "LAB",
                                                    str(name.get("id")),
                                                    categoryKey,
                                                    null,
                                                    null,
                                                    null));
                            assertEquals(0, code(second), "再造一份待迁走位置的报告单：" + second);
                            long id = Long.parseLong(str(data(second).get("id")));
                            // 模拟历史迁移 / 模板调整后失去全部位置（进入「未指定位置」组，A-ATT-020）
                            jdbc.update("DELETE FROM rpt_report_link WHERE report_id = ?", id);
                            return id;
                        });

        Map<?, ?> unassigned =
                post(BASE + "/reports/search", admin, Map.of("locationScope", "UNASSIGNED"));
        assertEquals(0, code(unassigned));
        List<?> items = (List<?>) data(unassigned).get("items");
        assertEquals(1, items.size(), "位置=未指定位置 只返回无位置的报告单");
        Map<?, ?> row = (Map<?, ?>) items.get(0);
        assertEquals(str(unassignedId), row.get("id"));
        assertEquals(0, ((List<?>) row.get("locations")).size(), "未指定位置 → 位置列为空清单");

        Map<?, ?> baseline =
                post(BASE + "/reports/search", admin, Map.of("locationScope", "BASELINE"));
        assertEquals(1, ((List<?>) data(baseline).get("items")).size(), "位置=含基线位置 只返回有基线位置的报告单");
        assertEquals(
                40001,
                code(post(BASE + "/reports/search", admin, Map.of("locationScope", "ANYWHERE"))),
                "位置筛选白名单外 40001");
    }

    // ---------------------------------------------------------------- 检索：患者关键词与文字关键词（C-SCAN-014）

    /** 患者姓名盲索引精确命中；文字关键词匹配名称（名称 / 医院 / 所见 / 诊断同机制）。 */
    @Test
    void keywordMatchesPatientNameExactlyAndReportText() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient("demoa", admin, defaultWardId("demoa"), "郑报告（示例）");
        String otherMrn = LEGACY + "-" + (System.nanoTime() % 100000);
        long otherId =
                registeredPatient("demoa", admin, defaultWardId("demoa"), "冯报告（示例）", otherMrn);
        Map<?, ?> name = labNameWithItems(admin);
        String categoryKey = baselineCategoryKey(admin, patientId);
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/reports",
                                admin,
                                reportBody(
                                        patientId,
                                        "LAB",
                                        str(name.get("id")),
                                        categoryKey,
                                        null,
                                        null,
                                        null))));
        String otherCategoryKey = baselineCategoryKey(admin, otherId);
        // A 预置类型名称均必填（V62）；第二名患者的报告单用同名 LAB（同名可多次添加，按报告时间区分）
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/reports",
                                admin,
                                reportBody(
                                        otherId,
                                        "LAB",
                                        str(name.get("id")),
                                        otherCategoryKey,
                                        null,
                                        null,
                                        null))));

        Map<?, ?> byName = post(BASE + "/reports/search", admin, Map.of("keyword", "郑报告（示例）"));
        assertEquals(0, code(byName));
        List<?> nameItems = (List<?>) data(byName).get("items");
        assertEquals(1, nameItems.size(), "按患者姓名精确命中（盲索引相等）");
        assertEquals(str(patientId), ((Map<?, ?>) nameItems.get(0)).get("patientId"));

        Map<?, ?> byMrn = post(BASE + "/reports/search", admin, Map.of("keyword", otherMrn));
        assertEquals(1, ((List<?>) data(byMrn).get("items")).size(), "按病案号精确命中另一名患者（盲索引相等）");

        Map<?, ?> byText = post(BASE + "/reports/search", admin, Map.of("keyword", "血常规"));
        List<?> textItems = (List<?>) data(byText).get("items");
        assertEquals(2, textItems.size(), "关键词匹配报告单名称（两名患者各一份，文字字段 LIKE）");

        Map<?, ?> byFindings = post(BASE + "/reports/search", admin, Map.of("keyword", "各腔室内径正常"));
        assertEquals(2, ((List<?>) data(byFindings).get("items")).size(), "关键词匹配所见文字（不识别图片内文字）");
        assertEquals(
                0L,
                ((Number)
                                data(post(
                                                BASE + "/reports/search",
                                                admin,
                                                Map.of("keyword", "郑报告")))
                                        .get("total"))
                        .longValue(),
                "部分姓名不命中（精确语义）");
    }

    // ---------------------------------------------------------------- 筛选与分页

    /** 类型 / 报告时间 / 上传人 / 电子化状态 / 病区筛选与分页校验。 */
    @Test
    void filtersTypeDateCreatorDigitizeAndWard() {
        String admin = hospitalToken("demoa");
        long wardId = defaultWardId("demoa");
        long patientId = registeredPatient("demoa", admin, wardId, "蒋报告（示例）");
        Map<?, ?> name = labNameWithItems(admin);
        String categoryKey = baselineCategoryKey(admin, patientId);
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/reports",
                                admin,
                                reportBody(
                                        patientId,
                                        "LAB",
                                        str(name.get("id")),
                                        categoryKey,
                                        null,
                                        null,
                                        null))));
        // 电子化状态由 C 的转录 / 手工流转产生；A 行缺省 NULL，直接置值模拟已电子化（C-SCAN-011）
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE rpt_report r JOIN pt_patient p ON p.id = r.patient_id"
                                        + " SET r.digitize_status = 'DONE' WHERE p.id = ?",
                                patientId));
        Map<?, ?> me = data(get("/api/hospital/auth/me", admin));

        assertEquals(
                1,
                ((List<?>)
                                data(post(
                                                BASE + "/reports/search",
                                                admin,
                                                Map.of("typeCode", "LAB")))
                                        .get("items"))
                        .size(),
                "按类型筛选");
        assertEquals(
                0L,
                ((Number)
                                data(post(
                                                BASE + "/reports/search",
                                                admin,
                                                Map.of("typeCode", "ECHO")))
                                        .get("total"))
                        .longValue(),
                "类型无命中返回空页");
        assertEquals(
                1,
                ((List<?>)
                                data(post(
                                                BASE + "/reports/search",
                                                admin,
                                                Map.of(
                                                        "reportDateFrom",
                                                        "2026-10-01",
                                                        "reportDateTo",
                                                        "2026-12-31")))
                                        .get("items"))
                        .size(),
                "报告时间范围命中（2026-10-28）");
        assertEquals(
                0L,
                ((Number)
                                data(post(
                                                BASE + "/reports/search",
                                                admin,
                                                Map.of("reportDateFrom", "2027-01-01")))
                                        .get("total"))
                        .longValue(),
                "报告时间范围外无命中");
        assertEquals(
                1,
                ((List<?>)
                                data(post(
                                                BASE + "/reports/search",
                                                admin,
                                                Map.of("creatorId", str(me.get("userId")))))
                                        .get("items"))
                        .size(),
                "按上传人筛选（本人上传）");
        assertEquals(
                1,
                ((List<?>)
                                data(post(
                                                BASE + "/reports/search",
                                                admin,
                                                Map.of("digitizeStatus", "DONE")))
                                        .get("items"))
                        .size(),
                "按电子化状态筛选（C）");
        assertEquals(
                1,
                ((List<?>)
                                data(post(
                                                BASE + "/reports/search",
                                                admin,
                                                Map.of("wardIds", List.of(wardId))))
                                        .get("items"))
                        .size(),
                "按病区筛选");
        assertEquals(
                0L,
                ((Number)
                                data(post(
                                                BASE + "/reports/search",
                                                admin,
                                                Map.of("uploadedFrom", "2027-01-01")))
                                        .get("total"))
                        .longValue(),
                "按上传日期筛选");
        assertEquals(
                40001,
                code(post(BASE + "/reports/search", admin, Map.of("pageNo", 0))),
                "pageNo 从 1 开始");
        assertEquals(
                40001,
                code(post(BASE + "/reports/search", admin, Map.of("pageSize", 101))),
                "pageSize 上限 100");
        assertEquals(
                40001,
                code(
                        post(
                                BASE + "/reports/search",
                                admin,
                                Map.of(
                                        "reportDateFrom",
                                        "2026-12-31",
                                        "reportDateTo",
                                        "2026-01-01"))),
                "日期区间颠倒 40001");
    }

    // ---------------------------------------------------------------- 按病区隔离（验收）

    /** 病区范围用户（A_WARD_DOCTOR + W-DEMO-1，V123 预置角色含菜单 23）只见本病区报告单； ALL 范围管理员两病区都可见（数据范围改写，非查询条件）。 */
    @Test
    void searchIsIsolatedByWardThroughDataScope() {
        String admin = hospitalToken("demoa");
        long ownWard = defaultWardId("demoa");
        long otherWard = createWard("W-IT-T5", "示例 IT 隔离病区（E16-T05）");
        long ownPatient = registeredPatient("demoa", admin, ownWard, "沈报告（示例）");
        long otherPatient = registeredPatient("demoa", admin, otherWard, "韩报告（示例）");
        Map<?, ?> name = labNameWithItems(admin);
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/reports",
                                admin,
                                reportBody(
                                        ownPatient,
                                        "LAB",
                                        str(name.get("id")),
                                        baselineCategoryKey(admin, ownPatient),
                                        null,
                                        null,
                                        null))));
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/reports",
                                admin,
                                reportBody(
                                        otherPatient,
                                        "LAB",
                                        str(name.get("id")),
                                        baselineCategoryKey(admin, otherPatient),
                                        null,
                                        null,
                                        null))));

        String wardUser = wardDoctorToken(ownWard);
        Map<?, ?> wardPage = post(BASE + "/reports/search", wardUser, Map.of());
        assertEquals(0, code(wardPage));
        List<?> wardItems = (List<?>) data(wardPage).get("items");
        assertTrue(wardItems.size() >= 1, "本病区报告单可见");
        assertTrue(
                wardItems.stream()
                        .noneMatch(
                                item ->
                                        str(((Map<?, ?>) item).get("patientId"))
                                                .equals(str(otherPatient))),
                "其他病区患者的报告单不可见（数据范围改写，而非查询条件）");
        assertTrue(
                wardItems.stream()
                        .anyMatch(
                                item ->
                                        str(((Map<?, ?>) item).get("patientId"))
                                                .equals(str(ownPatient))),
                "本病区患者的报告单可见");

        Map<?, ?> adminPage = post(BASE + "/reports/search", admin, Map.of());
        List<?> adminItems = (List<?>) data(adminPage).get("items");
        assertEquals(
                2,
                adminItems.stream()
                        .filter(
                                item ->
                                        List.of(str(ownPatient), str(otherPatient))
                                                .contains(str(((Map<?, ?>) item).get("patientId"))))
                        .count(),
                "ALL 范围（管理员）两个病区的报告单都可见");
    }

    // ---------------------------------------------------------------- 权限 / 模块

    /** 无菜单权限（FOLLOWUP_DOCTOR 未授菜单 23）40300；模块关闭 40301。 */
    @Test
    void permissionAndModuleGuards() {
        String admin = hospitalToken("demoa");
        String doctor = hospitalToken("demoa", "doctor.demo");
        assertEquals(
                40300,
                code(post(BASE + "/reports/search", doctor, Map.of())),
                "FOLLOWUP_DOCTOR 无 followup:report:view → 40300");
        assertEquals(0, code(post(BASE + "/reports/search", admin, Map.of())));

        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301, code(post(BASE + "/reports/search", admin, Map.of())), "模块关闭 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
        assertEquals(0, code(post(BASE + "/reports/search", admin, Map.of())));
    }

    // ---------------------------------------------------------------- 造数与工具（与 ReportIT 同口径）

    private long registeredPatient(String tenant, String token, long wardId, String name) {
        return registeredPatient(
                tenant, token, wardId, name, LEGACY + "-" + (System.nanoTime() % 100000));
    }

    private long registeredPatient(
            String tenant, String token, long wardId, String name, String legacyNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", legacyNo);
        body.put("name", name);
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

    /** LAB 首个绑定模板的名称（血常规，A 预置 V62）。 */
    private Map<?, ?> labNameWithItems(String token) {
        List<?> names = (List<?>) get(BASE + "/report-names?typeCode=LAB", token).get("data");
        return (Map<?, ?>) names.get(0);
    }

    private Map<String, Object> reportBody(
            long patientId,
            String typeCode,
            String nameId,
            String categoryKey,
            Long recordId,
            String sectionKey,
            Long fileId) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("typeCode", typeCode);
        body.put("nameId", nameId);
        body.put("reportDate", "2026-10-28");
        body.put("findings", "各腔室内径正常（示例）");
        body.put("conclusion", "未见明显异常（示例）");
        java.util.ArrayList<Map<String, Object>> locations = new java.util.ArrayList<>();
        if (categoryKey != null) {
            locations.add(
                    Map.of(
                            "linkType",
                            "baseline",
                            "anchorType",
                            "category",
                            "anchorKey",
                            categoryKey));
        }
        if (recordId != null) {
            Map<String, Object> whole = new HashMap<>();
            whole.put("linkType", "followup_record");
            whole.put("linkRecordId", String.valueOf(recordId));
            whole.put("anchorType", null);
            locations.add(whole);
        }
        if (recordId != null && sectionKey != null) {
            locations.add(
                    Map.of(
                            "linkType",
                            "followup_record",
                            "linkRecordId",
                            String.valueOf(recordId),
                            "anchorType",
                            "section",
                            "anchorKey",
                            sectionKey));
        }
        body.put("locations", locations);
        if (fileId != null) {
            body.put("fileIds", List.of(String.valueOf(fileId)));
        }
        return body;
    }

    /** 位置树基线根第一个分类的锚点（种子 V38 分类 1–8）。 */
    private String baselineCategoryKey(String token, long patientId) {
        Map<?, ?> baselineRoot = locationRoot(token, patientId, "baseline");
        return str(
                ((Map<?, ?>)
                                ((Map<?, ?>) ((List<?>) baselineRoot.get("children")).get(0))
                                        .get("value"))
                        .get("anchorKey"));
    }

    /** 位置树随访根第一条记录的第一个分组锚点。 */
    private String firstSectionKey(String token, long patientId) {
        Map<?, ?> followupRoot = locationRoot(token, patientId, "followup");
        Map<?, ?> recordNode = (Map<?, ?>) ((List<?>) followupRoot.get("children")).get(0);
        Map<?, ?> sectionNode = (Map<?, ?>) ((List<?>) recordNode.get("children")).get(0);
        return str(((Map<?, ?>) sectionNode.get("value")).get("anchorKey"));
    }

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

    /** 病区范围用户：新建 A_WARD_DOCTOR（V123 预置，含菜单 23）+ 指定病区，首登改密后返回令牌。 */
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
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        admin,
                        Map.of(
                                "username",
                                "IT-T5-WD",
                                "displayName",
                                "病区医生（示例）",
                                "phone",
                                "13900001505",
                                "mainDeptId",
                                deptId,
                                "roleIds",
                                List.of(roleId),
                                "wardIds",
                                List.of(wardId)));
        assertEquals(0, code(created), "建病区范围用户失败：" + created);
        String temporary = str(data(created).get("temporaryPassword"));
        String newPassword = "It#E16t5-Pass";
        String token = hospitalToken("demoa", "IT-T5-WD", temporary);
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

    /** 上传一个 PNG（nurse.demo = TENANT_ADMIN，有 followup:scan:upload），返回 fileId。 */
    private long uploadPng(String token, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(
                "file",
                new org.springframework.core.io.ByteArrayResource(png()) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                });
        @SuppressWarnings("unchecked")
        Map<?, ?> response =
                (Map<?, ?>)
                        rest.postForEntity(UPLOAD, new HttpEntity<>(parts, headers), Map.class)
                                .getBody();
        assertNotNull(response, "上传响应体不应为空");
        assertEquals(0, code(response), "上传失败：" + response);
        return Long.parseLong(str(data(response).get("fileId")));
    }

    /** 合法 1×1 PNG（能通过上传白名单与 Tika 解码，与 ReportIT 同一张）。 */
    private static byte[] png() {
        return Base64.getDecoder()
                .decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
                                + "AAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
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

    private long wardIdByCode(String code) {
        return jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code);
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
