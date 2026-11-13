package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.stats.app.AbnormalRecordsService;
import com.crdp.followup.stats.app.DeviceDistService;
import com.crdp.followup.stats.app.EriListService;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * E21-T19 端到端（tasks.csv 验收「三张卡片进卡片目录」）：医院 C 三张首页卡片（C-DASH-002 器械类型分布 / C-DASH-005 接近 ERI 器械列表 /
 * C-DASH-006 异常标红记录）的目录行（followup V96）、布局下发、开关 40301 与「一个口径一个查询」—— eri_list.total = 指标卡「接近 ERI
 * 器械数」、abnormal_records（days=30）.total = 指标卡「近 30 天含标红参数的记录数」、device_dist.followedCount =
 * 指标卡「在随访患者数」；ERI 命中与计划切换 SW-7 / SW-8 同一谓词（最近一条含 ERI 字段的已提交记录，EriEval）。
 *
 * <p>另覆盖：数据范围外会话（病区范围空集）三卡全 0 不报错——EriListMapper 的窗口函数派生表经 DataScope
 * 改写器重写后仍正确（RestrictedScopeRewriter 进派生表内层追加条件）；开关关闭 40301（eri_cycle 关 → eri_list 关，implant 关 →
 * 三卡全关）。 虚构数据走随包 C_PROGRAM_FU 表单（V90）与 C_PACER_FU_PLAN / C_ICD_FU_PLAN 预置（发布后按 V86
 * 映射自动入组，CSwitchAcceptanceIT 同口径）。 门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no
 * 前缀 IT-E21T19-；发布态与开关收尾还原，对 A / B 与其他 IT 惰性）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CDashboardCardsIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E21T19";
    private static final String PACER_CODE = "C_PACER_FU_PLAN";
    private static final String ICD_CODE = "C_ICD_FU_PLAN";

    /** 随包医院 C 随访程控表单（V90；f303 随访日期 / f304 随访时间点 / f323 ERI 状态 / f324 电池寿命）。 */
    private static final String C_FORM = "C_PROGRAM_FU";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeviceDistService deviceDistService;
    @Autowired private EriListService eriListService;
    @Autowired private AbnormalRecordsService abnormalRecordsService;

    // ---------------------------------------------------------------- 激活（与 CSwitchAcceptanceIT
    // 同口径）

    @BeforeAll
    void activatePresetPlans() {
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'implant'");
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'eri_cycle'");
                });
        String admin = adminToken();
        for (String code : new String[] {PACER_CODE, ICD_CODE}) {
            long planId = presetPlanId(code);
            if (!"PUBLISHED".equals(presetPlanStatus(planId))) {
                assertEquals(
                        0,
                        code(
                                post(
                                        "/api/hospital/followup/plans/" + planId + "/publish",
                                        admin,
                                        Map.of())),
                        "发布预置失败：" + code);
            }
        }
    }

    @BeforeEach
    void cleanState() {
        runInTenant("demoa", this::deleteOwnData);
    }

    @AfterAll
    void cleanUp() {
        cleanState();
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "UPDATE fu_plan SET status = 'UNPUBLISHED', published_at = NULL,"
                                    + " published_by = NULL WHERE code IN (?, ?)",
                            PACER_CODE,
                            ICD_CODE);
                    jdbc.update(
                            "DELETE FROM fu_plan_log WHERE plan_code IN (?, ?) AND action ="
                                    + " 'PLAN_PUBLISH'",
                            PACER_CODE,
                            ICD_CODE);
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'");
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'eri_cycle'");
                });
    }

    /** 本 IT 自建数据清理（患者按 legacy_no 前缀全链路；审计删除走维护开关）。 */
    private void deleteOwnData() {
        AuditTableMaintenance.delete(
                jdbc,
                "DELETE FROM aud_log WHERE"
                        + " target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                        + " WHERE legacy_no LIKE '"
                        + LEGACY
                        + "-%')"
                        + " OR target IN (SELECT CONCAT('FU_RECORD:', r.id) FROM fu_record r"
                        + " JOIN pt_patient p ON p.id = r.patient_id WHERE p.legacy_no LIKE '"
                        + LEGACY
                        + "-%')"
                        + " OR target IN (SELECT CONCAT('TASK:', t.id) FROM fu_task t"
                        + " JOIN pt_patient p ON p.id = t.patient_id WHERE p.legacy_no LIKE '"
                        + LEGACY
                        + "-%')"
                        + " OR target IN (SELECT CONCAT('ENROLLMENT:', e.id) FROM fu_enrollment e"
                        + " JOIN pt_patient p ON p.id = e.patient_id WHERE p.legacy_no LIKE '"
                        + LEGACY
                        + "-%')"
                        + " OR (event_type = 'PLAN_PUBLISH' AND target IN (SELECT CONCAT('PLAN:', id)"
                        + " FROM fu_plan WHERE code IN ('"
                        + PACER_CODE
                        + "','"
                        + ICD_CODE
                        + "')))");
        jdbc.update(
                "DELETE fh FROM fe_field_history fh JOIN fu_record r ON r.id = fh.record_id"
                        + " JOIN pt_patient p ON p.id = r.patient_id WHERE fh.record_type ="
                        + " 'FU_RECORD' AND p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE FROM sys_outbox WHERE event_type IN ('TaskOpened', 'TASK_OVERDUE')"
                        + " AND JSON_EXTRACT(payload, '$.patientId') IN"
                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                LEGACY + "-%");
        jdbc.update(
                "DELETE r FROM fu_record r JOIN pt_patient p ON p.id = r.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE e FROM fu_enrollment e JOIN pt_patient p ON p.id = e.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE il FROM pt_implant_lead il JOIN pt_patient p ON p.id = il.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE l FROM pt_lead l JOIN pt_patient p ON p.id = l.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE i FROM pt_implant i JOIN pt_patient p ON p.id = i.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE FROM pt_status_history WHERE patient_id IN"
                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                LEGACY + "-%");
        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
    }

    // ---------------------------------------------------------------- 验收：三张卡片进卡片目录

    @Test
    void catalogSeedsThreeCCardsAndLayoutExposesThemWithFeaturesOn() {
        // 目录行（followup V96）：feature_code 与 stats.md §3 一致（OQ-146①③）
        Map<String, String> featureByCode =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.query(
                                        "SELECT card_code, feature_code FROM dash_card"
                                                + " WHERE card_code IN ('device_dist',"
                                                + " 'eri_list', 'abnormal_records')",
                                        rs -> {
                                            Map<String, String> rows = new HashMap<>();
                                            while (rs.next()) {
                                                rows.put(rs.getString(1), rs.getString(2));
                                            }
                                            return rows;
                                        }));
        assertEquals(
                Map.of(
                        "device_dist", "implant",
                        "eri_list", "eri_cycle",
                        "abnormal_records", "implant"),
                featureByCode,
                "目录三行落库（device_dist / abnormal_records → implant，eri_list → eri_cycle）");

        // 布局下发（目录 ∩ 功能开关 ∩ 权限 × 角色布局 ∩ 用户隐藏）：开关全开 → 三卡出现
        String admin = adminToken();
        Map<?, ?> layout = data(get("/api/hospital/followup/dashboard/layout", admin));
        List<String> codes = cardCodes(layout);
        assertTrue(codes.contains("device_dist"), "布局含器械类型分布");
        assertTrue(codes.contains("eri_list"), "布局含接近 ERI 器械列表");
        assertTrue(codes.contains("abnormal_records"), "布局含异常标红记录");
        // 医院 C 默认顺序位（pages.md §2；OQ-146② 整行排布）：器械分布在随访率之前、ERI / 标红在随访率之后
        assertTrue(codes.indexOf("device_dist") < codes.indexOf("yearly_rate"), "器械类型分布排在随访率之前");
        assertTrue(
                codes.indexOf("eri_list") > codes.indexOf("yearly_rate")
                        && codes.indexOf("abnormal_records") > codes.indexOf("yearly_rate")
                        && codes.indexOf("abnormal_records")
                                < codes.indexOf("baseline_completeness"),
                "ERI 列表与异常标红排在随访率之后、完整度之前");
    }

    // ---------------------------------------------------------------- C-DASH-002 器械类型分布

    @Test
    void deviceDistCountsActivePatientsByTypeWithBrandSplit() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(6);
        registerPatient(admin, "21", "DDD", "MEDTRONIC", implant);
        registerPatient(admin, "22", "DDD", "ABBOTT", implant);
        registerPatient(admin, "23", "SSI", "MEDTRONIC", implant);
        // 取出在用器械（生成列 active_gen 随状态变 NULL）：患者仍在随访，归「未登记在用器械」行
        long replaced = registerPatient(admin, "24", "DDD", "MEDTRONIC", implant);
        int explanted =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.update(
                                        "UPDATE pt_implant SET status = 'EXPLANTED', end_date = ?,"
                                                + " end_reason = 'IT 取出（示例）' WHERE patient_id"
                                                + " = ? AND status = 'ACTIVE'",
                                        java.sql.Date.valueOf(implant.plusMonths(3)),
                                        replaced));
        assertEquals(1, explanted, "应恰取出一条在用器械");

        Map<?, ?> dist = data(get("/api/hospital/followup/dashboard/cards/device_dist", admin));

        assertEquals(4L, ((Number) dist.get("followedCount")).longValue(), "分母 = 在随访患者数");
        Map<String, Map<?, ?>> rows = rowsByLabel(dist);
        // DDD 三名患者之一已取出器械：在用两名（美敦力 / 雅培各一），取出者归「未登记在用器械」
        assertEquals(2L, countOf(rows.get("双腔起搏器(DDD)")));
        assertEquals(
                List.of("美敦力 ×1", "雅培 ×1"),
                brandTexts(rows.get("双腔起搏器(DDD)")),
                "品牌拆分按字典序（可按品牌拆分，C-DASH-002）");
        assertEquals(1L, countOf(rows.get("单腔起搏器(SSI)")));
        assertEquals(1L, countOf(rows.get("未登记在用器械")), "无在用器械患者归占位行");

        // 一个口径一个查询：分母 = 指标卡「在随访患者数」（implant 开 → C 指标集）
        Map<?, ?> kpi = data(get("/api/hospital/followup/dashboard/cards/kpi", admin));
        assertEquals("C", kpi.get("style"));
        assertEquals(4L, number(metric(kpi, "active")), "device_dist 分母与指标卡「在随访患者数」同条件树");
    }

    // ---------------------------------------------------------------- C-DASH-005 接近 ERI 器械列表

    @Test
    void eriListUsesLatestRecordAndMatchesKpiEriNear() {
        String admin = adminToken();
        LocalDate today = LocalDate.now();
        LocalDate implant = today.minusMonths(8);
        // P1：早先接近 ERI、最近一次已更正为正常 → 不进列表（「最近一次 ERI 状态」，EriEval）
        long restored = registerPatient(admin, "31", "DDD", "MEDTRONIC", implant);
        submitProgramRecord(admin, restored, today.minusMonths(2), Map.of("f323", 1), 7);
        submitProgramRecord(admin, restored, today.minusMonths(1), Map.of("f323", 0), 7);
        // P2：最近一次已达 ERI(EOI)
        long eoi = registerPatient(admin, "32", "DDD", "MEDTRONIC", implant);
        submitProgramRecord(admin, eoi, today.minusMonths(1), Map.of("f323", 2), 7);
        // P3：状态正常但电池寿命 0.8 年（≤ 1 视同接近 ERI）
        long battery = registerPatient(admin, "33", "SSI", "ABBOTT", implant);
        submitProgramRecord(
                admin, battery, today.minusMonths(1), Map.of("f323", 0, "f324", 0.8), 7);

        Map<?, ?> view = data(get("/api/hospital/followup/dashboard/cards/eri_list", admin));

        assertEquals(2L, ((Number) view.get("total")).longValue(), "接近 / 已达 / 电池低三名命中两个");
        assertEquals(1d, ((Number) view.get("batteryLifeThreshold")).doubleValue());
        Map<String, Map<?, ?>> byPatient = itemsByPatientId(view);
        Map<?, ?> eoiItem = byPatient.get(String.valueOf(eoi));
        assertNotNull(eoiItem, "已达 ERI 患者在列");
        assertEquals("2", String.valueOf(eoiItem.get("eriStatusCode")));
        assertEquals("已达ERI(EOI)", eoiItem.get("eriStatusName"));
        assertEquals("双腔起搏器(DDD)", eoiItem.get("deviceTypeLabel"), "器械显示名随业务字典");
        assertEquals("美敦力", eoiItem.get("brandLabel"));
        assertEquals(
                today.minusMonths(1).toString(),
                String.valueOf(eoiItem.get("lastVisitDate")),
                "最近随访日期 = 摘要最近随访（E15 提交后重算）");
        assertFalse(byPatient.containsKey(String.valueOf(restored)), "最近一次已更正为正常的患者不进列表");
        Map<?, ?> batteryItem = byPatient.get(String.valueOf(battery));
        assertEquals(0.8d, ((Number) batteryItem.get("batteryLife")).doubleValue());
        assertEquals("单腔起搏器(SSI)", batteryItem.get("deviceTypeLabel"));
        // 姓名脱敏（列表不提供逐行明文）
        assertFalse(String.valueOf(eoiItem.get("name")).contains("端木示例"), "姓名为脱敏值");

        // 一个口径一个查询：total = 指标卡「接近 ERI 器械数」（eri_cycle 开 → 出数）
        Map<?, ?> kpi = data(get("/api/hospital/followup/dashboard/cards/kpi", admin));
        assertEquals(2L, number(metric(kpi, "eriNear")), "eri_list.total 与指标卡「接近 ERI 器械数」同一查询");
    }

    // ---------------------------------------------------------------- C-DASH-006 异常标红记录

    @Test
    void abnormalRecordsShowHitsAndMatchKpiHighlightRecent() {
        String admin = adminToken();
        LocalDate today = LocalDate.now();
        LocalDate implant = today.minusMonths(8);
        // f323 ∈ {1,2} 命中 V82 预置规则 eri_status_abnormal → fu_record.highlight（E17-T04）
        long near = registerPatient(admin, "41", "DDD", "MEDTRONIC", implant);
        submitProgramRecord(admin, near, today.minusMonths(1), Map.of("f323", 1), 7);
        long eoi = registerPatient(admin, "42", "DDD", "MEDTRONIC", implant);
        submitProgramRecord(admin, eoi, today.minusMonths(1), Map.of("f323", 2), 7);
        // 正常且电池不低：无命中 → 不进卡片
        long normal = registerPatient(admin, "43", "DDD", "MEDTRONIC", implant);
        submitProgramRecord(admin, normal, today.minusMonths(1), Map.of("f323", 0, "f324", 3.5), 7);

        Map<?, ?> view =
                data(get("/api/hospital/followup/dashboard/cards/abnormal_records", admin));

        assertEquals(30, ((Number) view.get("days")).intValue(), "窗口缺省 30 天");
        assertEquals(2L, ((Number) view.get("total")).longValue(), "两条含标红参数的记录");
        Map<String, Map<?, ?>> byPatient = itemsByPatientId(view);
        Map<?, ?> hitRow = byPatient.get(String.valueOf(near));
        assertNotNull(hitRow, "标红记录在列");
        List<Map<?, ?>> hits = (List<Map<?, ?>>) hitRow.get("hits");
        assertFalse(hits.isEmpty(), "标红项目与变化率（E17-T04 命中数组）");
        assertTrue(
                hits.stream()
                        .anyMatch(hit -> String.valueOf(hit.get("message")).contains("ERI 状态")),
                "命中规则文案（eri_status_abnormal）");
        assertFalse(byPatient.containsKey(String.valueOf(normal)), "无命中记录不进卡片");
        // 点击进入记录的定位键：recordId 为字符串（患者详情 #tab=followup + #rec=<recordId>）
        assertFalse(String.valueOf(hitRow.get("recordId")).isEmpty());

        // 一个口径一个查询：days=30 的 total = 指标卡「近 30 天含标红参数的记录数」
        Map<?, ?> kpi = data(get("/api/hospital/followup/dashboard/cards/kpi", admin));
        assertEquals(
                2L,
                number(metric(kpi, "highlightRecent")),
                "abnormal_records（days=30）与指标卡 highlightRecent 同一条件树");

        // 窗口收窄到 7 天：提交发生在当下，计数不变；days 非法 40001
        Map<?, ?> week =
                data(get("/api/hospital/followup/dashboard/cards/abnormal_records?days=7", admin));
        assertEquals(7, ((Number) week.get("days")).intValue());
        assertEquals(2L, ((Number) week.get("total")).longValue());
        assertEquals(
                40001,
                code(
                        get(
                                "/api/hospital/followup/dashboard/cards/abnormal_records?days=400",
                                admin)));
    }

    // ---------------------------------------------------------------- 开关 40301（AGENTS §5.7）

    @Test
    void featureOffReturns40301PerCardGate() {
        String admin = adminToken();
        try {
            runInTenant(
                    "demoa",
                    () ->
                            jdbc.update(
                                    "UPDATE cfg_feature SET enabled = 0"
                                            + " WHERE feature_code = 'eri_cycle'"));
            // eri_cycle 关：只关 eri_list（stats.md §3；OQ-146①）
            assertEquals(
                    40301, code(get("/api/hospital/followup/dashboard/cards/eri_list", admin)));
            assertEquals(
                    0,
                    code(get("/api/hospital/followup/dashboard/cards/device_dist", admin)),
                    "implant 仍开：器械分布可用");
            // 指标卡 eriNear 回到占位（available=false）
            Map<?, ?> kpi = data(get("/api/hospital/followup/dashboard/cards/kpi", admin));
            assertFalse(
                    Boolean.TRUE.equals(metric(kpi, "eriNear").get("available")),
                    "eri_cycle 关 → eriNear 占位");

            runInTenant(
                    "demoa",
                    () ->
                            jdbc.update(
                                    "UPDATE cfg_feature SET enabled = 0"
                                            + " WHERE feature_code = 'implant'"));
            assertEquals(
                    40301, code(get("/api/hospital/followup/dashboard/cards/device_dist", admin)));
            assertEquals(
                    40301, code(get("/api/hospital/followup/dashboard/cards/eri_list", admin)));
            assertEquals(
                    40301,
                    code(get("/api/hospital/followup/dashboard/cards/abnormal_records", admin)));
        } finally {
            runInTenant(
                    "demoa",
                    () -> {
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1"
                                        + " WHERE feature_code = 'implant'");
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1"
                                        + " WHERE feature_code = 'eri_cycle'");
                    });
        }
    }

    // ---------------------------------------------------------------- 数据范围外：全 0 不报错（stats.md §1）

    @Test
    void outOfScopeSessionSeesEmptyCardsNotErrors() {
        registerPatient(adminToken(), "51", "DDD", "MEDTRONIC", LocalDate.now().minusMonths(6));
        SessionPrincipal outsider = restrictedPrincipal("demoa");
        DeviceDistService.DeviceDistView dist =
                withHospitalSession(outsider, () -> deviceDistService.deviceDist(null));
        assertEquals(0L, dist.followedCount());
        assertEquals(List.of(), dist.rows());
        EriListService.EriListView eri =
                withHospitalSession(outsider, () -> eriListService.eriList(null));
        assertEquals(0L, eri.total(), "范围外会话：窗口函数派生表经范围改写后空集不报错");
        assertEquals(List.of(), eri.items());
        AbnormalRecordsService.AbnormalRecordsView abnormal =
                withHospitalSession(
                        outsider, () -> abnormalRecordsService.abnormalRecords(null, null));
        assertEquals(0L, abnormal.total());
        assertEquals(List.of(), abnormal.items());
    }

    // ---------------------------------------------------------------- 辅助（CSwitchAcceptanceIT /
    // DashboardCardsIT 同款口径）

    /** C 登记并返回患者 id（implant 开关开启时同建首条植入记录并自动入组）。 */
    private long registerPatient(
            String token, String suffix, String deviceType, String brand, LocalDate implantDate) {
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantType", "FIRST");
        implant.put("implantDate", implantDate.toString());
        implant.put("deviceType", deviceType);
        implant.put("brand", brand);
        implant.put("model", "示例型号 S-01");
        implant.put("serialNo", "DEMO-SN-" + LEGACY + "-" + suffix);
        implant.put("pacingMode", "DDD");
        implant.put("hospital", "虚构示例医院");
        implant.put("doctor", "示例医生");
        implant.put("indication", "SSS");
        implant.put(
                "leads",
                List.of(
                        Map.of("chamber", "RA", "position", "RA_APPENDAGE"),
                        Map.of("chamber", "RV", "position", "RV_APEX")));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + suffix);
        body.put("name", "端木示例（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1955-01-01");
        body.put("deptId", deptId());
        body.put("wardId", wardId());
        body.put("legacyNo", LEGACY + "-" + suffix);
        body.put("implant", implant);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记失败：" + created);
        return Long.parseLong(String.valueOf(data(created).get("id")));
    }

    /**
     * 补录一条随包 C_PROGRAM_FU 程控记录并提交（visitDate 须早于今天且晚于植入日）； extra 为 f323 / f324，timepoint 为
     * c_visit_timepoint 选项编码（整数：6 常规、7 ERI 期）。返回记录 id。
     */
    private String submitProgramRecord(
            String token,
            long patientId,
            LocalDate visitDate,
            Map<String, Object> extra,
            int timepoint) {
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
                                C_FORM));
        assertEquals(0, code(created), "补录创建失败：" + created);
        String recordId = String.valueOf(data(created).get("recordId"));
        Map<String, Object> dataBody = new HashMap<>();
        dataBody.put("f303", visitDate.toString());
        dataBody.put("f304", timepoint);
        dataBody.putAll(extra);
        Map<?, ?> submitted =
                post(
                        "/api/hospital/followup/records/" + recordId + "/submit",
                        token,
                        Map.of("baseVersion", 0, "data", dataBody));
        assertEquals(0, code(submitted), "补录提交失败：" + submitted);
        return recordId;
    }

    private static List<String> cardCodes(Map<?, ?> layout) {
        List<String> codes = new ArrayList<>();
        for (Object item : (List<?>) layout.get("cards")) {
            codes.add(String.valueOf(((Map<?, ?>) item).get("code")));
        }
        return codes;
    }

    private static Map<?, ?> metric(Map<?, ?> kpi, String code) {
        for (Object item : (List<?>) kpi.get("metrics")) {
            if (code.equals(String.valueOf(((Map<?, ?>) item).get("code")))) {
                return (Map<?, ?>) item;
            }
        }
        throw new AssertionError("指标缺失：" + code);
    }

    private static long number(Object metricOrValue) {
        Object value = metricOrValue instanceof Map<?, ?> map ? map.get("value") : metricOrValue;
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static Map<String, Map<?, ?>> rowsByLabel(Map<?, ?> dist) {
        Map<String, Map<?, ?>> rows = new HashMap<>();
        for (Object item : (List<?>) dist.get("rows")) {
            rows.put(String.valueOf(((Map<?, ?>) item).get("label")), (Map<?, ?>) item);
        }
        return rows;
    }

    private static long countOf(Map<?, ?> row) {
        return row == null ? 0 : ((Number) row.get("count")).longValue();
    }

    private static List<String> brandTexts(Map<?, ?> row) {
        List<String> texts = new ArrayList<>();
        for (Object brand : (List<?>) row.get("brands")) {
            Map<?, ?> item = (Map<?, ?>) brand;
            texts.add(item.get("label") + " ×" + item.get("count"));
        }
        return texts;
    }

    private static Map<String, Map<?, ?>> itemsByPatientId(Map<?, ?> view) {
        Map<String, Map<?, ?>> items = new HashMap<>();
        for (Object item : (List<?>) view.get("items")) {
            items.put(String.valueOf(((Map<?, ?>) item).get("patientId")), (Map<?, ?>) item);
        }
        return items;
    }

    private long presetPlanId(String code) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM fu_plan WHERE code = ?", Long.class, code));
    }

    private String presetPlanStatus(long planId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT status FROM fu_plan WHERE id = ?", String.class, planId));
    }

    /** 范围外病区的会话（不存在的病区 id：任何患者都不在范围内）。 */
    private SessionPrincipal restrictedPrincipal(String tenant) {
        long absentWard =
                runInTenant(
                        tenant,
                        () -> jdbc.queryForObject("SELECT MAX(id) + 1000 FROM ward", Long.class));
        return new SessionPrincipal(
                "it-restricted-token",
                "HOSPITAL",
                1L,
                tenant,
                tenant + "（示例）",
                2L,
                "it.outsider",
                "范围外医生（示例）",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of("followup:dashboard:view"),
                List.of("followup"),
                1L,
                ScopeSnapshot.restricted(List.of(absentWard), List.of(), false),
                false,
                null);
    }

    /** 以给定会话执行（同 TokenAuthenticationFilter 的 ThreadLocal 装配方式，DataScopeMaskingIT 口径）。 */
    private <T> T withHospitalSession(
            SessionPrincipal principal, java.util.function.Supplier<T> action) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        try {
            return TenantContext.callAsTenant(
                    principal.tenantId(), principal.tenantCode(), action::get);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private long deptId() {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'", Long.class));
    }

    private long wardId() {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM ward WHERE code = 'W-DEMO-1'", Long.class));
    }

    private String adminToken() {
        return hospitalToken("demoa", "nurse.demo", PASSWORD);
    }

    private String hospitalToken(String tenant, String username, String password) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", password));
        assertEquals(0, code(response), "登录失败：" + response);
        return (String) data(response).get("token");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map<?, ?> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class)
                .getBody();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
