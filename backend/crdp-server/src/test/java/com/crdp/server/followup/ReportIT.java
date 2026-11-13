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
 * E16-T02 端到端 / A-ATT-001、A-ATT-002；C-SCAN-001、C-SCAN-010（完整应用 + 真实 MySQL，HTTP 直连）。 验收原文：位置至少选 1
 * 个、可多选（基线分类 / 随访记录 / 随访分节）；在页内上传时预选当前位置 （预选由前端 LocationPicker preset 承接，本 IT 锁定树数据源与位置落库口径）。
 *
 * <p>覆盖：创建报告单（三种粒度位置各一行、附件挂台账 ATTACHED、指标快照带 ↑/↓、写 RPT_CREATE 审计）； 位置为空 40001、锚点不存在 40001、越患者记录
 * 40400、植入记录位置 40001（E21 前）；患者内清单 scope 过滤与位置标签；类型 / 名称被报告单引用后删除 40901（OQ-95②）；无上传权限 40300、越范围 /
 * 跨租户 40400、模块关闭 40301。全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String BASE = "/api/hospital/followup";
    private static final String UPLOAD = "/api/hospital/files";
    private static final String LEGACY = "IT-E16T02";

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
                        jdbc.update("DELETE FROM sys_file WHERE original_name LIKE 'IT-E16T02-%'");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM aud_log WHERE action = 'RPT_CREATE'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：位置至少 1 个、可多选（三种粒度）

    /** 创建：位置三种粒度各一行（基线分类 / 随访记录整条 / 随访分节）、附件挂台账、指标带 ↑、留审计。 */
    @Test
    void createWithThreeGranularityLocationsAndAudit() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient("demoa", admin);
        long recordId = backfillRecord(admin, patientId, "2026-06-10");
        long fileId = uploadPng(admin, "IT-E16T02-检验报告（示例）.png");

        // 位置树（components.md §8）：基线资料（A_BASELINE 9 分类）+ 随访记录（整条 + 分组）
        Map<?, ?> baselineRoot = locationRoot(admin, patientId, "baseline");
        List<?> baselineChildren = (List<?>) baselineRoot.get("children");
        assertEquals(
                8,
                baselineChildren.size(),
                "基线分类 = A_BASELINE 模板分类（种子 V38 分类 1–8；pages.md §4.2 示意含并入的出院带药）");
        Map<?, ?> firstCategory = (Map<?, ?>) baselineChildren.get(1);
        String categoryKey = str(((Map<?, ?>) firstCategory.get("value")).get("anchorKey"));

        Map<?, ?> followupRoot = locationRoot(admin, patientId, "followup");
        List<?> records = (List<?>) followupRoot.get("children");
        assertEquals(1, records.size(), "患者有 1 条随访记录（历史补录）");
        Map<?, ?> recordNode = (Map<?, ?>) records.get(0);
        assertTrue(
                str(recordNode.get("title")).startsWith("历史补录 2026-06-10"),
                "无任务记录按来源 + 实际日期命名：" + recordNode.get("title"));
        List<?> sections = (List<?>) recordNode.get("children");
        assertFalse(sections.isEmpty(), "随访分组 = 该记录模板分类");
        Map<?, ?> sectionNode = (Map<?, ?>) sections.get(0);
        String sectionKey = str(((Map<?, ?>) sectionNode.get("value")).get("anchorKey"));
        String recordIdText = str(((Map<?, ?>) recordNode.get("value")).get("linkRecordId"));

        Map<?, ?> name = labNameWithItems(admin);
        Map<?, ?> created =
                post(
                        BASE + "/reports",
                        admin,
                        reportBody(
                                patientId,
                                "LAB",
                                str(name.get("id")),
                                categoryKey,
                                recordIdText,
                                sectionKey,
                                fileId,
                                List.of(
                                        Map.of(
                                                "itemCode",
                                                "WBC",
                                                "result",
                                                "3.2",
                                                "refRange",
                                                "3.5–9.5"),
                                        Map.of(
                                                "itemCode",
                                                "PLT",
                                                "result",
                                                "400",
                                                "refRange",
                                                "125–350"))));
        assertEquals(0, code(created), "创建失败：" + created);
        assertEquals(3, ((Number) data(created).get("locationCount")).intValue(), "三种粒度位置各一行");
        assertEquals(1, ((Number) data(created).get("fileCount")).intValue());

        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            3L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_report_link l JOIN rpt_report r ON r.id = l.report_id"
                                            + " WHERE r.patient_id = ?",
                                    Long.class,
                                    patientId),
                            "三种粒度位置各一行（A-ATT-002：基线分类 / 随访记录 / 随访分节）");
                    assertEquals(
                            "baseline",
                            jdbc.queryForObject(
                                    "SELECT link_type FROM rpt_report_link WHERE anchor_type = 'category'"
                                            + " AND report_id IN (SELECT id FROM rpt_report WHERE patient_id = ?)",
                                    String.class,
                                    patientId));
                    assertEquals(
                            "ATTACHED",
                            jdbc.queryForObject(
                                    "SELECT status FROM sys_file WHERE id = ?",
                                    String.class,
                                    fileId),
                            "附件挂上报告单后置 ATTACHED（与沟通录音同口径）");
                    assertEquals(
                            "DOWN",
                            jdbc.queryForObject(
                                    "SELECT flag FROM rpt_indicator_value WHERE item_code = 'WBC'",
                                    String.class),
                            "WBC 3.2 低于 3.5–9.5 → ↓");
                    assertEquals(
                            "UP",
                            jdbc.queryForObject(
                                    "SELECT flag FROM rpt_indicator_value WHERE item_code = 'PLT'",
                                    String.class),
                            "PLT 400 超过 125–350 → ↑");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action = 'RPT_CREATE'",
                                            Long.class)
                                    >= 1,
                            "创建写 RPT_CREATE 审计");
                });

        // 患者内清单：位置标签（「基线 · 分类」「历史补录 · 分组」）与附件数
        Map<?, ?> page = data(get(BASE + "/patients/" + patientId + "/reports", admin));
        List<?> items = (List<?>) page.get("items");
        assertEquals(1, items.size());
        Map<?, ?> row = (Map<?, ?>) items.get(0);
        assertEquals("检验", row.get("typeName"));
        assertEquals("血常规", row.get("name"), "名称快照（A-ATT-011）");
        assertEquals(1L, ((Number) row.get("fileCount")).longValue());
        List<?> locations = (List<?>) row.get("locations");
        assertEquals(3, locations.size());
        assertNotNull(
                ((Map<?, ?>) locations.get(0)).get("anchorLabel"),
                "位置标签带分类 / 分组显示名：" + locations.get(0));

        // scope 过滤（左栏附件导航口径，E16-T14 消费）：baseline 只留含基线位置的报告单
        Map<?, ?> baselinePage =
                data(get(BASE + "/patients/" + patientId + "/reports?scope=baseline", admin));
        assertEquals(1, ((List<?>) baselinePage.get("items")).size());
    }

    /** 位置校验：空 40001；锚点不存在 40001；越患者记录 40400；植入记录位置 40001（E21 前）。 */
    @Test
    void createRejectsInvalidLocations() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient("demoa", admin);
        long recordId = backfillRecord(admin, patientId, "2026-06-10");
        Map<?, ?> baselineRoot = locationRoot(admin, patientId, "baseline");
        String categoryKey =
                str(
                        ((Map<?, ?>)
                                        ((Map<?, ?>)
                                                        ((List<?>) baselineRoot.get("children"))
                                                                .get(0))
                                                .get("value"))
                                .get("anchorKey"));
        Map<?, ?> name = labNameWithItems(admin);

        Map<?, ?> empty =
                post(
                        BASE + "/reports",
                        admin,
                        reportBody(
                                patientId,
                                "LAB",
                                str(name.get("id")),
                                null,
                                null,
                                null,
                                null,
                                List.of()));
        assertTrue(
                empty.get("code").toString().equals("40001")
                        && str(empty.get("message")).contains("请至少选择 1 个位置"),
                "位置为空 40001：" + empty);

        Map<?, ?> badAnchor =
                post(
                        BASE + "/reports",
                        admin,
                        reportBody(
                                patientId,
                                "LAB",
                                str(name.get("id")),
                                "not-a-category",
                                null,
                                null,
                                null,
                                null));
        assertEquals(40001, code(badAnchor), "基线分类不存在");

        Map<?, ?> badSection =
                post(
                        BASE + "/reports",
                        admin,
                        reportBody(
                                patientId,
                                "LAB",
                                str(name.get("id")),
                                categoryKey,
                                "999999999",
                                "ghost",
                                null,
                                null));
        assertEquals(40400, code(badSection), "越患者 / 不存在的随访记录 40400（不暴露存在）");

        Map<String, Object> implantLocation = new HashMap<>();
        implantLocation.put("linkType", "implant_record");
        implantLocation.put("linkRecordId", "5");
        implantLocation.put("anchorType", null);
        Map<?, ?> implant =
                post(
                        BASE + "/reports",
                        admin,
                        Map.of(
                                "patientId",
                                patientId,
                                "typeCode",
                                "LAB",
                                "nameId",
                                nameIdOfLab(admin),
                                "reportDate",
                                "2026-10-28",
                                "locations",
                                List.of(implantLocation)));
        // E21-T32 起植入位置开放：本例 demoa 未开 implant 开关 → 40301（AGENTS §5.7）；开放形态见
        // CReportImplantLocationIT
        assertEquals(40301, code(implant), "implant 开关关闭植入位置 40301：" + implant);
    }

    // ---------------------------------------------------------------- 配置删除守卫（OQ-95②）

    /** 报告单引用类型 / 名称后：删除 40901（可停用不可删），被拒写 DENIED 审计。 */
    @Test
    void configDeleteBlockedWhileReferencedByReports() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient("demoa", admin);
        Map<?, ?> baselineRoot = locationRoot(admin, patientId, "baseline");
        String categoryKey =
                str(
                        ((Map<?, ?>)
                                        ((Map<?, ?>)
                                                        ((List<?>) baselineRoot.get("children"))
                                                                .get(0))
                                                .get("value"))
                                .get("anchorKey"));
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
                                        categoryKey,
                                        null,
                                        null,
                                        null,
                                        null))),
                "先造一份检验报告单");

        long labTypeId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM rpt_type WHERE code = 'LAB'", Long.class));
        assertEquals(
                40901, code(delete(BASE + "/report-config/types/" + labTypeId, admin)), "类型被引用不能删");
        assertEquals(
                40901,
                code(delete(BASE + "/report-config/names/" + name.get("id"), admin)),
                "名称被引用不能删");

        runInTenant(
                "demoa",
                () ->
                        assertTrue(
                                jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM aud_log WHERE action LIKE 'RPT_%_DELETE'"
                                                        + " AND result = 'DENIED'",
                                                Long.class)
                                        >= 2,
                                "类型与名称删除各写一条 DENIED 审计"));
    }

    // ---------------------------------------------------------------- 权限 / 范围 / 模块 / 租户隔离

    /** 无上传权限 40300；跨租户患者 40400；模块关闭 40301（开启后恢复）。 */
    @Test
    void permissionScopeAndModuleGuards() {
        String admin = hospitalToken("demoa");
        String doctor = hospitalToken("demoa", "doctor.demo");
        long patientId = registeredPatient("demoa", admin);
        Map<?, ?> baselineRoot = locationRoot(admin, patientId, "baseline");
        String categoryKey =
                str(
                        ((Map<?, ?>)
                                        ((Map<?, ?>)
                                                        ((List<?>) baselineRoot.get("children"))
                                                                .get(0))
                                                .get("value"))
                                .get("anchorKey"));

        assertEquals(
                40300,
                code(
                        post(
                                BASE + "/reports",
                                doctor,
                                reportBody(
                                        patientId,
                                        "OTHER",
                                        null,
                                        categoryKey,
                                        null,
                                        null,
                                        null,
                                        null))),
                "FOLLOWUP_DOCTOR 无 followup:scan:upload → 40300");
        assertEquals(
                0,
                code(get(BASE + "/patients/" + patientId + "/reports", doctor)),
                "读清单只要 followup:patient:view");

        String tenantB = hospitalToken("demob");
        assertEquals(
                40400,
                code(
                        post(
                                BASE + "/reports",
                                tenantB,
                                reportBody(
                                        patientId,
                                        "OTHER",
                                        null,
                                        categoryKey,
                                        null,
                                        null,
                                        null,
                                        null))),
                "跨租户患者 40400（独立租户库）");

        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    code(get(BASE + "/patients/" + patientId + "/report-locations", admin)),
                    "模块关闭 40301");
            assertEquals(40301, code(post(BASE + "/reports", admin, Map.of())), "模块关闭创建 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
        assertEquals(0, code(get(BASE + "/patients/" + patientId + "/report-locations", admin)));
    }

    /**
     * E16-T03 批量上传 / A-ATT-014、A-ATT-004；C-SCAN-006、C-SCAN-007。验收原文：单份最多 10 个文件，超限拦截。 覆盖：MERGE 11
     * 个文件 40001 拦截（demoa 每份上限 10）且不落库；ONE_PER_FILE 3 个文件 → 3 份报告单 （逐份调整名称 / 报告时间生效）、文件挂台账
     * ATTACHED；每份 RPT_CREATE + 一条 RPT_BATCH_CREATE 审计； 无上传权限 40300。
     */
    @Test
    void batchCreateOnePerFileAndMergeOverLimit() {
        String admin = hospitalToken("demoa");
        String doctor = hospitalToken("demoa", "doctor.demo");
        long patientId = registeredPatient("demoa", admin);
        Map<?, ?> baselineRoot = locationRoot(admin, patientId, "baseline");
        String categoryKey =
                str(
                        ((Map<?, ?>)
                                        ((Map<?, ?>)
                                                        ((List<?>) baselineRoot.get("children"))
                                                                .get(0))
                                                .get("value"))
                                .get("anchorKey"));
        Map<?, ?> name = labNameWithItems(admin);

        List<String> eleven = new java.util.ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            eleven.add(String.valueOf(uploadPng(admin, "IT-E16T03-合并-" + i + "（示例）.png")));
        }
        Map<?, ?> merge =
                post(
                        BASE + "/reports/batch-create",
                        admin,
                        batchBody(
                                patientId,
                                "MERGE",
                                "LAB",
                                str(name.get("id")),
                                categoryKey,
                                eleven,
                                null));
        assertEquals(40001, code(merge), "MERGE 11 个文件超过每份 10 个 → 拦截：" + merge);
        assertTrue(str(merge.get("message")).contains("最多 10 个"), str(merge.get("message")));
        runInTenant(
                "demoa",
                () ->
                        assertEquals(
                                0L,
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM rpt_report WHERE patient_id = ?",
                                        Long.class,
                                        patientId),
                                "超限拦截后不落任何报告单"));

        long f1 = uploadPng(admin, "IT-E16T03-一份-1（示例）.png");
        long f2 = uploadPng(admin, "IT-E16T03-一份-2（示例）.png");
        long f3 = uploadPng(admin, "IT-E16T03-一份-3（示例）.png");
        Map<?, ?> other = otherLabName(admin);
        Map<?, ?> created =
                post(
                        BASE + "/reports/batch-create",
                        admin,
                        batchBody(
                                patientId,
                                "ONE_PER_FILE",
                                "LAB",
                                str(name.get("id")),
                                categoryKey,
                                List.of(String.valueOf(f1), String.valueOf(f2), String.valueOf(f3)),
                                List.of(
                                        Map.of(
                                                "fileId",
                                                String.valueOf(f2),
                                                "nameId",
                                                str(other.get("id")),
                                                "reportDate",
                                                "2026-09-09"))));
        assertEquals(0, code(created), "批量创建失败：" + created);
        Map<?, ?> view = data(created);
        assertEquals("ONE_PER_FILE", str(view.get("mode")));
        assertEquals(3, ((Number) view.get("reportCount")).intValue(), "每个文件一份");
        assertEquals(3, ((Number) view.get("fileCount")).intValue());
        List<?> reports = (List<?>) view.get("reports");
        assertEquals("血常规", str(((Map<?, ?>) reports.get(0)).get("name")), "公共名称");
        assertEquals("肝肾功能", str(((Map<?, ?>) reports.get(1)).get("name")), "逐份调整名称（A-ATT-014）");
        assertEquals("2026-09-09", str(((Map<?, ?>) reports.get(1)).get("reportDate")), "逐份调整报告时间");

        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            3L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_report WHERE patient_id = ?",
                                    Long.class,
                                    patientId),
                            "3 个文件各成一份报告单");
                    assertEquals(
                            3L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_report_file WHERE patient_id = ?"
                                            + " AND file_id IN (?, ?, ?)",
                                    Long.class,
                                    patientId,
                                    f1,
                                    f2,
                                    f3),
                            "每个文件挂到各自报告单");
                    assertEquals(
                            "ATTACHED",
                            jdbc.queryForObject(
                                    "SELECT status FROM sys_file WHERE id = ?", String.class, f2),
                            "挂台账后置 ATTACHED");
                    assertEquals(
                            3L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_report r"
                                            + " JOIN rpt_report_file f ON f.report_id = r.id"
                                            + " WHERE r.patient_id = ? AND f.file_id IN (?, ?, ?)",
                                    Long.class,
                                    patientId,
                                    f1,
                                    f2,
                                    f3),
                            "每份报告单 1 个文件");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action = 'RPT_CREATE'",
                                            Long.class)
                                    >= 3,
                            "每份报告单各写一条 RPT_CREATE");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action = 'RPT_BATCH_CREATE'",
                                            Long.class)
                                    >= 1,
                            "批量汇总写 RPT_BATCH_CREATE 审计");
                });

        assertEquals(
                40300,
                code(
                        post(
                                BASE + "/reports/batch-create",
                                doctor,
                                batchBody(
                                        patientId,
                                        "MERGE",
                                        "LAB",
                                        null,
                                        categoryKey,
                                        List.of(String.valueOf(f1)),
                                        null))),
                "FOLLOWUP_DOCTOR 无 followup:scan:upload → 40300");
    }

    // ---------------------------------------------------------------- 造数与工具（与 ContactLogIT 同口径）

    private long registeredPatient(String tenant, String token) {
        Map<String, Object> body = new HashMap<>();
        String legacyNo = LEGACY + "-" + (System.nanoTime() % 100000);
        body.put("medicalRecordNo", legacyNo);
        body.put("name", "周报告（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", defaultWardId(tenant));
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

    /** LAB 首个名称 id（位置守卫用例：LAB 名称必填，缺 nameId 会先命中 40001 名称校验）。 */
    private String nameIdOfLab(String token) {
        return String.valueOf(labNameWithItems(token).get("id"));
    }

    /** LAB 首个绑定模板的名称（血常规，A 预置，V62）；目录接口 data 直接是清单。 */
    private Map<?, ?> labNameWithItems(String token) {
        List<?> names = (List<?>) get(BASE + "/report-names?typeCode=LAB", token).get("data");
        return (Map<?, ?>) names.get(0);
    }

    /** 批量上传第二个 LAB 名称（肝肾功能，A 预置 V62），逐份调整用。 */
    private Map<?, ?> otherLabName(String token) {
        List<?> names = (List<?>) get(BASE + "/report-names?typeCode=LAB", token).get("data");
        return names.stream()
                .map(node -> (Map<?, ?>) node)
                .filter(node -> "肝肾功能".equals(str(node.get("name"))))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("LAB 预置名称缺「肝肾功能」"));
    }

    /** 批量上传请求体（E16-T03；items 可空 = 全部沿用公共值）。 */
    private Map<String, Object> batchBody(
            long patientId,
            String mode,
            String typeCode,
            String nameId,
            String categoryKey,
            List<String> fileIds,
            List<Map<String, Object>> items) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("mode", mode);
        body.put("typeCode", typeCode);
        body.put("nameId", nameId);
        body.put("reportDate", "2026-10-28");
        body.put(
                "locations",
                List.of(
                        Map.of(
                                "linkType",
                                "baseline",
                                "anchorType",
                                "category",
                                "anchorKey",
                                categoryKey)));
        body.put("fileIds", fileIds);
        if (items != null) {
            body.put("items", items);
        }
        return body;
    }

    private Map<String, Object> reportBody(
            long patientId,
            String typeCode,
            String nameId,
            String categoryKey,
            String recordId,
            String sectionKey,
            Long fileId,
            List<Map<String, Object>> indicators) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("typeCode", typeCode);
        body.put("nameId", nameId);
        body.put("reportDate", "2026-10-28");
        body.put("findings", "各腔室内径正常（示例）");
        body.put("conclusion", "未见明显异常（示例）");
        body.put("note", "门诊复查（示例）");
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
            // 整条记录（anchorType=null，Map.of 不容 null 用 HashMap）；分组是额外一个位置
            Map<String, Object> whole = new HashMap<>();
            whole.put("linkType", "followup_record");
            whole.put("linkRecordId", recordId);
            whole.put("anchorType", null);
            locations.add(whole);
        }
        if (recordId != null && sectionKey != null) {
            locations.add(
                    Map.of(
                            "linkType",
                            "followup_record",
                            "linkRecordId",
                            recordId,
                            "anchorType",
                            "section",
                            "anchorKey",
                            sectionKey));
        }
        if (!locations.isEmpty()) {
            body.put("locations", locations);
        }
        if (fileId != null) {
            body.put("fileIds", List.of(String.valueOf(fileId)));
        }
        if (indicators != null && !indicators.isEmpty()) {
            body.put("indicators", indicators);
        }
        return body;
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

    /** 合法 1×1 PNG（能通过上传白名单与 Tika 解码，与 ContactLogIT 同一张）。 */
    private static byte[] png() {
        return Base64.getDecoder()
                .decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
                                + "AAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    }

    private long defaultWardId(String tenant) {
        return runInTenant(
                tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    private String hospitalToken(String tenant) {
        return hospitalToken(tenant, "nurse.demo");
    }

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
        assertEquals(0, code(response));
        return (String) data(response).get("token");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    private Map<?, ?> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    private Map<?, ?> delete(String path, String token) {
        return exchange(HttpMethod.DELETE, path, token, null);
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
        return (List<Map<?, ?>>) data(response);
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
