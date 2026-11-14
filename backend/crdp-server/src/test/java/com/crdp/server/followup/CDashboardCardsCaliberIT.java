package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * E21-T28 端到端（tasks.csv 验收「卡片数与列表一致」；类型=测试，与 E19-T08 统计口径核对同模式）： 医院 C 三张首页卡片（E21-T19）的数字
 * 与业务列表逐项核对——同一套虚构数据下，卡片数字 = 用**独立列表接口**（患者列表 / 植入记录 / 患者概览 / 随访时间轴 / 记录详情）重算的期望值：
 *
 * <ul>
 *   <li>器械类型分布（C-DASH-002）：分母 followedCount = 患者列表 status=ACTIVE 检索 total = 指标卡「在随访患者数」； 每类型 / 品牌人数
 *       = 患者列表逐患者取植入记录（GET /patients/{id}/implants，current 行）独立重算；各行人数之和 = 分母， 无在用器械患者归「未登记在用器械」行；
 *   <li>接近 ERI 器械列表（C-DASH-005）：total = 指标卡「接近 ERI 器械数」；命中患者 ⊆ 患者列表 status=ACTIVE； 每行的器械类型显示名 /
 *       最近随访日期 / 下次计划日期 = 患者概览（GET /patients/{id}/summary）device.typeLabel / latestFollowup.date /
 *       nextVisit.planDate； 概览 ERI 标记与卡片 ERI 状态同一 EriEval——状态命中（接近 /
 *       已达）两处文案一致，电池寿命视同命中的行卡片按字典状态展示、概览按「视同接近ERI」展示（口径锁定）；
 *   <li>异常标红记录（C-DASH-006）：total = 指标卡「近 30 天含标红参数的记录数」；每条记录的命中数 = 记录详情 highlightHits（GET
 *       /records/{id}）= 患者时间轴该记录节点的 highlightCount；反向核对：患者列表逐患者时间轴 highlightCount 非 null 的记录数 = 卡片
 *       total；窗口外（updated_at 回拨 40 天）的记录不进卡片与指标卡、但仍在时间轴与记录详情中（列表与卡片差异只在窗口，OQ-107④）；
 *   <li>病区筛选：卡片 wardId 与患者列表 wardIds 筛选同一收窄口径（转病区后双侧同步）。
 * </ul>
 *
 * <p>虚构数据走随包 C_PROGRAM_FU 表单（V90）与 C_PACER_FU_PLAN / C_ICD_FU_PLAN 预置（发布后按 V86 映射自动入组，
 * CDashboardCardsIT 同口径）。门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀
 * IT-E21T28-； 发布态与开关收尾还原，对 A / B 与其他 IT 惰性）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CDashboardCardsCaliberIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E21T28";
    private static final String PACER_CODE = "C_PACER_FU_PLAN";
    private static final String ICD_CODE = "C_ICD_FU_PLAN";

    /** 随包医院 C 随访程控表单（V90；f303 随访日期 / f304 随访时间点 / f323 ERI 状态 / f324 电池寿命）。 */
    private static final String C_FORM = "C_PROGRAM_FU";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    // ---------------------------------------------------------------- 激活（与 CDashboardCardsIT 同口径）

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

    // ---------------------------------------------------------------- 核对一：器械类型分布 vs 患者列表 + 植入记录

    @Test
    void deviceDistMatchesPatientListTotalAndImplantRecordsPerPatient() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(8);
        // 五名在随访患者：DDD×3（美敦力 P1 / P5、雅培 P3）、SSI×1（雅培 P2）、取出器械 P4（未登记在用器械行）
        long p1 = registerPatient(admin, "11", "DDD", "MEDTRONIC", implant);
        registerPatient(admin, "12", "SSI", "ABBOTT", implant);
        registerPatient(admin, "13", "DDD", "ABBOTT", implant);
        long p4 = registerPatient(admin, "14", "DDD", "MEDTRONIC", implant);
        registerPatient(admin, "15", "DDD", "MEDTRONIC", implant);
        assertEquals(
                1,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.update(
                                        "UPDATE pt_implant SET status = 'EXPLANTED', end_date = ?,"
                                                + " end_reason = 'IT 取出（示例）' WHERE patient_id"
                                                + " = ? AND status = 'ACTIVE'",
                                        java.sql.Date.valueOf(implant.plusMonths(3)),
                                        p4)),
                "P4 取出在用器械");

        Map<?, ?> dist = data(get("/api/hospital/followup/dashboard/cards/device_dist", admin));

        // 分母：卡片 = 患者列表 status=ACTIVE 检索 total = 指标卡「在随访患者数」（三处同口径）
        Map<?, ?> activePage = patientSearch(admin, Map.of("status", List.of("ACTIVE")));
        List<Map<?, ?>> activeItems = itemsOf(activePage);
        assertEquals(
                ((Number) dist.get("followedCount")).longValue(),
                ((Number) activePage.get("total")).longValue(),
                "device_dist 分母 = 患者列表在随访患者检索 total");
        assertEquals(
                ((Number) activePage.get("total")).longValue(),
                activeItems.size(),
                "核对规模内在单页（pageSize=200）取全");
        Map<?, ?> kpi = data(get("/api/hospital/followup/dashboard/cards/kpi", admin));
        assertEquals(
                ((Number) dist.get("followedCount")).longValue(),
                number(metric(kpi, "active")),
                "device_dist 分母 = 指标卡「在随访患者数」");

        // 每类型 / 品牌人数：患者列表逐患者取植入记录独立重算（current 行的 deviceType / brand）
        Map<String, Map<String, Long>> expected = new LinkedHashMap<>();
        long noDevice = 0;
        for (Map<?, ?> row : activeItems) {
            Map<?, ?> current = currentImplant(admin, String.valueOf(row.get("id")));
            if (current == null) {
                noDevice++;
                continue;
            }
            expected.computeIfAbsent(
                            String.valueOf(current.get("deviceType")), key -> new LinkedHashMap<>())
                    .merge(
                            current.get("brand") == null
                                    ? null
                                    : String.valueOf(current.get("brand")),
                            1L,
                            Long::sum);
        }
        Map<String, Map<?, ?>> rowsByType = rowsByDeviceType(dist);
        long rowSum = 0;
        for (Map.Entry<String, Map<String, Long>> type : expected.entrySet()) {
            Map<?, ?> cardRow = rowsByType.get(type.getKey());
            assertNotNull(cardRow, "卡片应含类型行：" + type.getKey());
            long expectedCount = type.getValue().values().stream().mapToLong(Long::longValue).sum();
            assertEquals(
                    expectedCount,
                    ((Number) cardRow.get("count")).longValue(),
                    "类型行人数 = 植入记录独立重算（" + type.getKey() + "）");
            for (Map.Entry<String, Long> brand : type.getValue().entrySet()) {
                assertEquals(
                        brand.getValue().longValue(),
                        brandCountOf(cardRow, brand.getKey()),
                        "品牌拆分人数（" + type.getKey() + " × " + brand.getKey() + "）");
            }
            rowSum += expectedCount;
        }
        Map<?, ?> noDeviceRow = rowsByType.get(null);
        assertEquals(
                noDevice,
                noDeviceRow == null ? 0L : ((Number) noDeviceRow.get("count")).longValue(),
                "无在用器械患者归「未登记在用器械」行");
        assertEquals(
                ((Number) dist.get("followedCount")).longValue(),
                rowSum + noDevice,
                "各行人数之和 = 分母（在随访患者只计一次）");
        // 取出患者 P4 的在用器械确已不在（独立核对兜底）
        assertNull(currentImplant(admin, str(p4)));
    }

    // ---------------------------------------------------------------- 核对二：接近 ERI 列表 vs 患者列表 + 患者概览

    @Test
    void eriListMatchesPatientListScopeAndPatientOverviewColumns() {
        String admin = adminToken();
        LocalDate today = LocalDate.now();
        LocalDate implant = today.minusMonths(8);
        // P1 状态命中（接近ERI）；P2 状态命中（已达）；P3 电池寿命视同命中；P4/P5 正常不进列表
        long p1 = registerPatient(admin, "21", "DDD", "MEDTRONIC", implant);
        String r1 = submitProgramRecord(admin, p1, today.minusMonths(1), Map.of("f323", 1), 7);
        long p2 = registerPatient(admin, "22", "SSI", "ABBOTT", implant);
        String r2 = submitProgramRecord(admin, p2, today.minusMonths(1), Map.of("f323", 2), 7);
        long p3 = registerPatient(admin, "23", "DDD", "ABBOTT", implant);
        String r3 =
                submitProgramRecord(
                        admin, p3, today.minusMonths(1), Map.of("f323", 0, "f324", 0.8), 7);
        long p4 = registerPatient(admin, "24", "DDD", "MEDTRONIC", implant);
        submitProgramRecord(admin, p4, today.minusMonths(1), Map.of("f323", 0, "f324", 3.5), 7);
        long p5 = registerPatient(admin, "25", "DDD", "MEDTRONIC", implant);
        submitProgramRecord(admin, p5, today.minusMonths(1), Map.of("f323", 0, "f324", 3.5), 7);

        Map<?, ?> view = data(get("/api/hospital/followup/dashboard/cards/eri_list", admin));
        assertEquals(3L, ((Number) view.get("total")).longValue(), "P1 / P2 / P3 三名命中");
        Map<?, ?> kpi = data(get("/api/hospital/followup/dashboard/cards/kpi", admin));
        assertEquals(
                ((Number) view.get("total")).longValue(),
                number(metric(kpi, "eriNear")),
                "eri_list.total = 指标卡「接近 ERI 器械数」");

        // 命中患者 ⊆ 患者列表 status=ACTIVE（范围与状态同口径）；未命中患者不在列
        Map<String, Map<?, ?>> byPatient = itemsByPatientId(view);
        Map<String, Map<?, ?>> listById = new HashMap<>();
        for (Map<?, ?> row : itemsOf(patientSearch(admin, Map.of("status", List.of("ACTIVE"))))) {
            listById.put(String.valueOf(row.get("id")), row);
        }
        for (String pid : new String[] {str(p1), str(p2), str(p3)}) {
            assertNotNull(byPatient.get(pid), "命中患者在卡片行：" + pid);
            assertNotNull(listById.get(pid), "命中患者出现在患者列表 ACTIVE 检索：" + pid);
        }
        assertFalse(byPatient.containsKey(str(p4)), "正常患者不进列表");
        assertFalse(byPatient.containsKey(str(p5)), "正常患者不进列表");

        // 每行器械 / 下次计划 = 患者概览同名列（不同接口同源）；最近随访日期 = 概览 latestFollowup.date，
        // 概览最近随访块 E15 写入方未落地前为 null（patient.md §1「E15 写入前 null」）——卡片按文档口径
        // 回落证据记录的有效随访日期（stats.md §2「缺失回落证据随访日期」）= 记录详情 visitDate
        Map<String, String> evidenceRecordByPatient = Map.of(str(p1), r1, str(p2), r2, str(p3), r3);
        for (Map.Entry<String, Map<?, ?>> entry : byPatient.entrySet()) {
            Map<?, ?> item = entry.getValue();
            Map<?, ?> summary =
                    data(
                            get(
                                    "/api/hospital/followup/patients/"
                                            + entry.getKey()
                                            + "/summary",
                                    admin));
            Map<?, ?> device = (Map<?, ?>) summary.get("device");
            assertNotNull(device, "概览器械段（implant 开）");
            assertEquals(
                    device.get("typeLabel"),
                    item.get("deviceTypeLabel"),
                    "卡片器械类型显示名 = 概览 device.typeLabel（患者 " + entry.getKey() + "）");
            Map<?, ?> nextVisit = (Map<?, ?>) summary.get("nextVisit");
            String summaryPlanDate =
                    nextVisit == null || nextVisit.get("planDate") == null
                            ? null
                            : String.valueOf(nextVisit.get("planDate"));
            String cardPlanDate =
                    item.get("nextPlanDate") == null
                            ? null
                            : String.valueOf(item.get("nextPlanDate"));
            assertEquals(
                    summaryPlanDate,
                    cardPlanDate,
                    "卡片下次计划日期 = 概览 nextVisit.planDate（患者 " + entry.getKey() + "）");
            Map<?, ?> latest = (Map<?, ?>) summary.get("latestFollowup");
            String summaryVisitDate =
                    latest == null || latest.get("date") == null
                            ? null
                            : String.valueOf(latest.get("date"));
            String cardVisitDate =
                    item.get("lastVisitDate") == null
                            ? null
                            : String.valueOf(item.get("lastVisitDate"));
            if (summaryVisitDate != null) {
                assertEquals(
                        summaryVisitDate,
                        cardVisitDate,
                        "卡片最近随访日期 = 概览 latestFollowup.date（患者 " + entry.getKey() + "）");
            } else {
                Map<?, ?> evidence =
                        data(
                                get(
                                        "/api/hospital/followup/records/"
                                                + evidenceRecordByPatient.get(entry.getKey()),
                                        admin));
                assertEquals(
                        evidence.get("visitDate") == null
                                ? null
                                : String.valueOf(evidence.get("visitDate")),
                        cardVisitDate,
                        "概览最近随访块未写入时（E15 写入方待落地）：卡片最近随访日期 = 证据记录有效随访日期"
                                + "（记录详情 visitDate，患者 "
                                + entry.getKey()
                                + "）");
            }
        }
        // ERI 标记：状态命中（P1 / P2）卡片状态文案 = 概览 ERI 标记；电池视同命中（P3）概览「视同接近ERI」、卡片按字典状态展示（口径锁定）
        assertEquals("接近ERI", byPatient.get(str(p1)).get("eriStatusName"));
        assertEquals(
                byPatient.get(str(p1)).get("eriStatusName"),
                eriLabelOfSummary(admin, p1),
                "状态命中行：卡片 ERI 状态 = 概览 ERI 标记（同一 EriEval）");
        assertEquals("已达ERI(EOI)", byPatient.get(str(p2)).get("eriStatusName"));
        assertEquals(
                byPatient.get(str(p2)).get("eriStatusName"),
                eriLabelOfSummary(admin, p2),
                "已达 ERI 行：卡片 ERI 状态 = 概览 ERI 标记");
        assertEquals(0.8d, ((Number) byPatient.get(str(p3)).get("batteryLife")).doubleValue());
        assertEquals("接近ERI", eriLabelOfSummary(admin, p3), "电池寿命视同命中：概览按「视同接近ERI」展示");
        assertEquals("正常", byPatient.get(str(p3)).get("eriStatusName"), "卡片状态列按字典状态展示");
    }

    // ---------------------------------------------------------------- 核对三：异常标红记录 vs 记录详情 + 时间轴

    @Test
    void abnormalRecordsMatchRecordDetailHighlightHitsAndTimelineCounts() {
        String admin = adminToken();
        LocalDate today = LocalDate.now();
        LocalDate implant = today.minusMonths(8);
        // 三条含标红参数的记录：P1（ERI 状态异常）、P2（已达 EOI）、P3（电池寿命偏低）；P4 正常无命中
        long p1 = registerPatient(admin, "31", "DDD", "MEDTRONIC", implant);
        String r1 = submitProgramRecord(admin, p1, today.minusMonths(1), Map.of("f323", 1), 7);
        long p2 = registerPatient(admin, "32", "SSI", "ABBOTT", implant);
        String r2 = submitProgramRecord(admin, p2, today.minusMonths(1), Map.of("f323", 2), 7);
        long p3 = registerPatient(admin, "33", "DDD", "ABBOTT", implant);
        String r3 =
                submitProgramRecord(
                        admin, p3, today.minusMonths(1), Map.of("f323", 0, "f324", 0.8), 7);
        long p4 = registerPatient(admin, "34", "DDD", "MEDTRONIC", implant);
        String r4 =
                submitProgramRecord(
                        admin, p4, today.minusMonths(1), Map.of("f323", 0, "f324", 3.5), 7);

        Map<?, ?> view =
                data(get("/api/hospital/followup/dashboard/cards/abnormal_records", admin));
        assertEquals(3L, ((Number) view.get("total")).longValue(), "三条标红记录");
        Map<?, ?> kpi = data(get("/api/hospital/followup/dashboard/cards/kpi", admin));
        assertEquals(
                ((Number) view.get("total")).longValue(),
                number(metric(kpi, "highlightRecent")),
                "abnormal_records total = 指标卡「近 30 天含标红参数的记录数」");

        // 每行：命中数 = 记录详情 highlightHits（持久化命中数组）
        Map<String, Integer> hitsByRecord = new LinkedHashMap<>();
        for (Object itemObj : itemsOf(view)) {
            Map<?, ?> item = (Map<?, ?>) itemObj;
            String recordId = String.valueOf(item.get("recordId"));
            List<?> hits = (List<?>) item.get("hits");
            assertFalse(hits.isEmpty(), "卡片行带标红项目与变化率");
            Map<?, ?> detail = data(get("/api/hospital/followup/records/" + recordId, admin));
            assertEquals("SUBMITTED", String.valueOf(detail.get("status")), "卡片行均为已提交记录");
            List<?> detailHits = (List<?>) detail.get("highlightHits");
            assertNotNull(detailHits, "记录详情带持久化命中数组（E17-T04 / E21-T32）");
            assertEquals(
                    hits.size(),
                    detailHits.size(),
                    "卡片标红项数 = 记录详情 highlightHits 数（记录 " + recordId + "）");
            hitsByRecord.put(recordId, hits.size());
        }

        // 反向核对：患者列表逐患者时间轴 highlightCount 非 null 的记录 = 卡片 total（列表侧独立数出的同一批记录）
        Map<String, Integer> timelineHits = new LinkedHashMap<>();
        for (Map<?, ?> row : itemsOf(patientSearch(admin, Map.of("status", List.of("ACTIVE"))))) {
            Map<?, ?> timeline =
                    data(
                            get(
                                    "/api/hospital/followup/patients/"
                                            + row.get("id")
                                            + "/followup-timeline",
                                    admin));
            for (Object nodeObj : itemsOf(timeline)) {
                Map<?, ?> node = (Map<?, ?>) nodeObj;
                if (node.get("recordId") != null && node.get("highlightCount") != null) {
                    timelineHits.put(
                            String.valueOf(node.get("recordId")),
                            ((Number) node.get("highlightCount")).intValue());
                }
            }
        }
        assertEquals(
                ((Number) view.get("total")).longValue(),
                timelineHits.size(),
                "时间轴标红记录数 = 卡片 total");
        assertTrue(
                timelineHits.containsKey(r1)
                        && timelineHits.containsKey(r2)
                        && timelineHits.containsKey(r3),
                "时间轴侧含 r1 / r2 / r3");
        assertFalse(timelineHits.containsKey(r4), "无命中记录不进标红集合（时间轴侧）");
        for (Map.Entry<String, Integer> hit : hitsByRecord.entrySet()) {
            assertEquals(
                    hit.getValue(),
                    timelineHits.get(hit.getKey()),
                    "时间轴节点 highlightCount = 卡片命中数（记录 " + hit.getKey() + "）");
        }

        // 窗口：R3 回拨 40 天（「近 N 天提交」以 updated_at 近似，OQ-107④）→ 30 天卡片与指标卡同步少一、时间轴与详情仍可见
        backdateUpdatedAt(r3, today.minusDays(40));
        Map<?, ?> narrowed =
                data(get("/api/hospital/followup/dashboard/cards/abnormal_records", admin));
        assertEquals(
                2L, ((Number) narrowed.get("total")).longValue(), "窗口外提交的记录不进 30 天卡片（r3 回拨 40 天）");
        Map<?, ?> kpiAfter = data(get("/api/hospital/followup/dashboard/cards/kpi", admin));
        assertEquals(
                2L, number(metric(kpiAfter, "highlightRecent")), "指标卡（固定 30 天）与卡片同步收窄——同一棵条件树");
        Map<?, ?> detail3 = data(get("/api/hospital/followup/records/" + r3, admin));
        assertNotNull(detail3.get("highlightHits"), "窗口外记录的详情仍带命中数组（列表侧不受窗口影响）");
        int timelineCount3 = 0;
        for (Object nodeObj :
                itemsOf(
                        data(
                                get(
                                        "/api/hospital/followup/patients/"
                                                + p3
                                                + "/followup-timeline",
                                        admin)))) {
            Map<?, ?> node = (Map<?, ?>) nodeObj;
            if (r3.equals(String.valueOf(node.get("recordId")))) {
                assertNotNull(node.get("highlightCount"), "时间轴节点仍显示标红数");
                timelineCount3 = ((Number) node.get("highlightCount")).intValue();
            }
        }
        assertTrue(timelineCount3 > 0, "窗口外记录的时间轴节点 highlightCount 仍在");
        // 放宽窗口（90 天）→ 卡片重新含 r3，与时间轴侧一致
        Map<?, ?> widened =
                data(get("/api/hospital/followup/dashboard/cards/abnormal_records?days=90", admin));
        assertEquals(3L, ((Number) widened.get("total")).longValue(), "90 天窗口重新含回拨记录");
    }

    // ---------------------------------------------------------------- 核对四：病区筛选与患者列表同口径

    @Test
    void wardFilterNarrowsCardsAndPatientListConsistently() {
        String admin = adminToken();
        LocalDate implant = LocalDate.now().minusMonths(8);
        // 两名患者：P1 留在 W-DEMO-1（ERI 命中 + 标红），P2 转入 W-A-CVS2（ERI 命中 + 标红）
        long p1 = registerPatient(admin, "41", "DDD", "MEDTRONIC", implant);
        submitProgramRecord(admin, p1, LocalDate.now().minusMonths(1), Map.of("f323", 1), 7);
        long p2 = registerPatient(admin, "42", "SSI", "ABBOTT", implant);
        submitProgramRecord(admin, p2, LocalDate.now().minusMonths(1), Map.of("f323", 2), 7);
        long ward2 = wardIdByCode("W-A-CVS2");
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/followup/patients/" + p2 + "/transfer",
                                admin,
                                Map.of("wardId", ward2, "reason", "IT 转病区核对（示例）"))),
                "转病区失败");

        // 卡片 wardId 收窄 vs 患者列表 wardIds 筛选：随访中患者数同一收窄
        Map<?, ?> ward2Page =
                patientSearch(
                        admin, Map.of("status", List.of("ACTIVE"), "wardIds", List.of(ward2)));
        List<Map<?, ?>> ward2Items = itemsOf(ward2Page);
        assertEquals(1L, ((Number) ward2Page.get("total")).longValue(), "患者列表按病区筛选恰好 P2");
        assertEquals(str(p2), String.valueOf(ward2Items.get(0).get("id")));
        Map<?, ?> dist =
                data(
                        get(
                                "/api/hospital/followup/dashboard/cards/device_dist?wardId="
                                        + ward2,
                                admin));
        assertEquals(
                ((Number) ward2Page.get("total")).longValue(),
                ((Number) dist.get("followedCount")).longValue(),
                "device_dist 分母（病区收窄）= 患者列表同筛选 total");
        assertEquals(
                1L,
                ((Number) rowsByDeviceType(dist).get("SSI").get("count")).longValue(),
                "病区内 P2 的 SSI 行");

        // ERI / 标红卡片病区收窄：P2 命中、P1 不进
        Map<?, ?> eri =
                data(get("/api/hospital/followup/dashboard/cards/eri_list?wardId=" + ward2, admin));
        assertEquals(1L, ((Number) eri.get("total")).longValue(), "病区内 ERI 命中恰 P2");
        assertEquals(List.of(str(p2)), patientIdsOf(eri));
        Map<?, ?> abnormal =
                data(
                        get(
                                "/api/hospital/followup/dashboard/cards/abnormal_records?wardId="
                                        + ward2,
                                admin));
        assertEquals(1L, ((Number) abnormal.get("total")).longValue(), "病区内标红记录恰 P2 一条");
        assertEquals(List.of(str(p2)), patientIdsOf(abnormal), "标红卡片行的患者 = 患者列表同筛选的患者");
    }

    // ---------------------------------------------------------------- 辅助（CDashboardCardsIT 同款口径）

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

    /** 患者检索（status / wardIds 筛选；返回分页 data：items + total）。 */
    private Map<?, ?> patientSearch(String token, Map<String, Object> filters) {
        Map<String, Object> body = new HashMap<>();
        body.put("pageNo", 1);
        body.put("pageSize", 200);
        body.put("filters", filters);
        Map<?, ?> response = post("/api/hospital/followup/patients/search", token, body);
        assertEquals(0, code(response), "患者检索失败：" + response);
        return data(response);
    }

    /** 患者在用（current）植入记录行；无在用器械返回 null。 */
    private Map<?, ?> currentImplant(String token, String patientId) {
        Map<?, ?> list =
                data(get("/api/hospital/followup/patients/" + patientId + "/implants", token));
        for (Object itemObj : itemsOf(list)) {
            Map<?, ?> item = (Map<?, ?>) itemObj;
            if (Boolean.TRUE.equals(item.get("current"))) {
                return item;
            }
        }
        return null;
    }

    /** 概览 ERI 标记（device.eri；护士会话可见）。 */
    private String eriLabelOfSummary(String token, long patientId) {
        Map<?, ?> summary =
                data(get("/api/hospital/followup/patients/" + patientId + "/summary", token));
        return String.valueOf(((Map<?, ?>) summary.get("device")).get("eri"));
    }

    /** 回拨记录 updated_at（「近 N 天提交」以 updated_at 近似，OQ-107④；窗口边界核对用）。 */
    private void backdateUpdatedAt(String recordId, LocalDate updatedAt) {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE fu_record SET updated_at = ? WHERE id = ?",
                                java.sql.Timestamp.valueOf(updatedAt.atStartOfDay()),
                                Long.parseLong(recordId)));
    }

    private static List<Map<?, ?>> itemsOf(Map<?, ?> pageOrList) {
        return (List<Map<?, ?>>) pageOrList.get("items");
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

    /** 分布行按 deviceType 编码索引（null = 「未登记在用器械」行；卡片字段为 rows）。 */
    private static Map<String, Map<?, ?>> rowsByDeviceType(Map<?, ?> dist) {
        Map<String, Map<?, ?>> rows = new HashMap<>();
        for (Object item : (List<?>) dist.get("rows")) {
            Map<?, ?> row = (Map<?, ?>) item;
            rows.put(
                    row.get("deviceType") == null ? null : String.valueOf(row.get("deviceType")),
                    row);
        }
        return rows;
    }

    private static long brandCountOf(Map<?, ?> row, String brand) {
        for (Object brandObj : (List<?>) row.get("brands")) {
            Map<?, ?> item = (Map<?, ?>) brandObj;
            if (Objects.equals(
                    brand, item.get("brand") == null ? null : String.valueOf(item.get("brand")))) {
                return ((Number) item.get("count")).longValue();
            }
        }
        return 0;
    }

    private static Map<String, Map<?, ?>> itemsByPatientId(Map<?, ?> view) {
        Map<String, Map<?, ?>> items = new HashMap<>();
        for (Object item : itemsOf(view)) {
            items.put(String.valueOf(((Map<?, ?>) item).get("patientId")), (Map<?, ?>) item);
        }
        return items;
    }

    private static List<String> patientIdsOf(Map<?, ?> view) {
        List<String> ids = new ArrayList<>();
        for (Object item : itemsOf(view)) {
            ids.add(String.valueOf(((Map<?, ?>) item).get("patientId")));
        }
        return ids;
    }

    private static String str(long value) {
        return String.valueOf(value);
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

    private long wardIdByCode(String code) {
        return runInTenant(
                "demoa",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
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
