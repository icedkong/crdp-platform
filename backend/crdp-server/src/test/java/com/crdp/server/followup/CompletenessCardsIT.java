package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.stats.app.CompletenessStatsService;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * E19-T05 端到端（tasks.csv 验收「可钻取到缺失字段」；口径 Q39 / Q40）。A 年度形态（虚构）： 向 A_BASELINE v1 快照注入两个必填 JSON
 * 字段（f9t5a / f9t5b，用后即删），患者类型 IT_E19T05 隔离出 3 名患者——甲全填（100%）、乙未保存分节（0%）、丙半填（50%）：
 *
 * <ul>
 *   <li>基线卡（A-DASH-010）：平均值 / 中位数 50.0、五段分布 1/0/0/1/1、分类明细（基本信息 50.0）、 最常缺失字段 f9t5b（缺 2，66.7%）→
 *       f9t5a（缺 1）——按缺失人数降序；
 *   <li>钻取（验收）：POST /patients/search 带 completenessDrill（kind=BASELINE + fieldKey）→ total =
 *       卡片该字段缺失人数（同一计算）；随访卡同口径（kind=FOLLOWUP）；
 *   <li>随访卡（A-DASH-011 / Q40）：任务录入记录（年度循环节点 → 随访年度 = occurrence）与历史导入锚点记录都计入； 计划外（UNPLANNED）不计；
 *       按随访年度一行（记录数 / 记录级平均）；时间范围默认按实际随访日期（任务记录在表单字段 f214 解析、 导入记录取 visit_date 列）；
 *   <li>数据范围：范围外会话全 0（@DataScope，不报错）；双租户：demob 不计 demoa 患者；参数校验 40001。
 * </ul>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（前缀 IT-E19T05 / f9t5 /
 * PLAN-IT-E19T05）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompletenessCardsIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E19T05";
    private static final String PLAN_CODE = "PLAN-IT-E19T05";
    private static final String PATIENT_TYPE = "IT_E19T05";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private CompletenessStatsService completenessStats;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        // 单计划世界：关掉 E23-T02 预置计划的常驻自动入组（口径同 DashboardRateCardsIT）
                        jdbc.update(
                                "UPDATE fu_plan SET auto_enroll = 0 WHERE code = 'A_ANNUAL_FU_PLAN'");
                        // 注入的必填 JSON 字段用后即删（快照表只在本测试内追加）
                        jdbc.update(
                                "DELETE FROM fe_form_version_field WHERE form_code = 'A_BASELINE'"
                                        + " AND field_key IN ('f9t5a', 'f9t5b')");
                        jdbc.update(
                                "DELETE FROM pt_baseline_section WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE FROM fu_record WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE e FROM fu_enrollment e JOIN pt_patient p"
                                        + " ON p.id = e.patient_id WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE n FROM fu_plan_node n JOIN fu_plan p ON p.id = n.plan_id"
                                        + " WHERE p.code LIKE ?",
                                PLAN_CODE + "%");
                        jdbc.update("DELETE FROM fu_plan WHERE code LIKE ?", PLAN_CODE + "%");
                        jdbc.update(
                                "DELETE FROM pt_patient_summary WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE FROM pt_status_history WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                                LEGACY + "-%");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：基线卡 + 缺失字段钻取

    @Test
    void baselineCardAggregatesAndDrillsToMissingFieldPatients() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        injectRequiredBaselineFields();
        long full = patientOfType(admin, "F1");
        long empty = patientOfType(admin, "F2");
        long half = patientOfType(admin, "F3");
        insertBaselineSection(full, "{\"f9t5a\":\"已答（示例）\",\"f9t5b\":\"已答（示例）\"}");
        insertBaselineSection(half, "{\"f9t5a\":\"已答（示例）\"}");
        // 患者乙不保存任何分节 → 基线完整度 0%（未保存分节按全缺失计入）

        Map<?, ?> card =
                data(
                        get(
                                cardPath("baseline_completeness") + "&patientType=" + PATIENT_TYPE,
                                admin));

        assertEquals(3L, num(card.get("patientCount")), "分母 = 范围内全部患者（含未保存分节的乙）");
        assertEquals(50.0, card.get("averagePct"), "患者值 100 / 0 / 50 → 平均 50.0（每人权重相同）");
        assertEquals(50.0, card.get("medianPct"), "中位数 50.0");
        List<?> bands = (List<?>) card.get("distribution");
        assertEquals(
                List.of(1L, 0L, 0L, 1L, 1L),
                bands.stream().map(band -> num(((Map<?, ?>) band).get("count"))).toList(),
                "五段分布：100% ×1、50–69% ×1、低于 50% ×1");
        List<?> categories = (List<?>) card.get("categories");
        assertEquals(1, categories.size(), "只列有应填字段的分类（注入字段都属 basic）");
        assertEquals("基本信息", ((Map<?, ?>) categories.get(0)).get("label"));
        assertEquals(50.0, ((Map<?, ?>) categories.get(0)).get("averagePct"), "分类内同算法平均");
        List<?> top = (List<?>) card.get("topMissing");
        assertEquals(
                List.of("f9t5b", "f9t5a"),
                top.stream().map(item -> ((Map<?, ?>) item).get("fieldKey")).toList(),
                "最常缺失字段按缺失人数降序（可钻取到缺失字段）");
        assertEquals(2L, num(((Map<?, ?>) top.get(0)).get("missingCount")), "f9t5b 缺乙、丙两人");
        assertEquals(66.7, ((Map<?, ?>) top.get(0)).get("missingRatePct"), "缺失率 = 2/3");

        // 钻取（验收）：缺该字段的患者列表 —— 卡片缺失人数 = 列表 total（同一计算）
        Map<?, ?> drilled =
                post(
                        "/api/hospital/followup/patients/search",
                        admin,
                        searchBody(
                                Map.of(
                                        "kind", "BASELINE",
                                        "fieldKey", "f9t5b",
                                        "patientType", PATIENT_TYPE)));
        assertEquals(0, codeOf(drilled), "钻取检索成功");
        assertEquals(2L, num(data(drilled).get("total")), "缺 f9t5b 的患者 = 乙、丙（与卡片 missingCount 一致）");
        Map<?, ?> drilledA =
                post(
                        "/api/hospital/followup/patients/search",
                        admin,
                        searchBody(
                                Map.of(
                                        "kind", "BASELINE",
                                        "fieldKey", "f9t5a",
                                        "patientType", PATIENT_TYPE)));
        assertEquals(1L, num(data(drilledA).get("total")), "缺 f9t5a 的患者 = 只有乙");

        // 时间范围按出院日期（默认 DISCHARGE）：只留 2026-06 出院的甲 → 平均 100
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "UPDATE pt_patient SET discharge_date = ? WHERE id = ?",
                            LocalDate.of(2026, 6, 1),
                            full);
                    jdbc.update(
                            "UPDATE pt_patient SET discharge_date = ? WHERE id IN (?, ?)",
                            LocalDate.of(2025, 6, 1),
                            empty,
                            half);
                });
        Map<?, ?> timeFiltered =
                data(
                        get(
                                cardPath("baseline_completeness")
                                        + "&patientType="
                                        + PATIENT_TYPE
                                        + "&from=2026-01-01&to=2026-12-31",
                                admin));
        assertEquals(1L, num(timeFiltered.get("patientCount")), "时间范围（出院日期 2026）只留甲");
        assertEquals(100.0, timeFiltered.get("averagePct"));
    }

    // ---------------------------------------------------------------- 验收：随访卡 + 钻取（Q40）

    @Test
    void followupCardCountsAnnualRecordsAndDrills() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedAnnualPlan(admin);
        long nodeId = nodeIdOf("demoa", planId);
        long patientA = patientOfType(admin, "G1");
        long patientB = patientOfType(admin, "G2");
        // 甲：任务录入记录（年度循环节点 → 随访年度 = occurrence = 1；实际随访日期在表单字段 f214）
        long taskRecord = insertTaskSubmittedRecord("demoa", patientA, planId, nodeId);
        // 甲：计划外记录不计（Q40「计划外随访记录首期不计入」）
        insertUnplannedRecord(patientA);
        // 乙：历史导入锚点记录（followup_year = 1；visit_date 落列）
        insertHistImportRecord(
                "demoa", patientB, planId, nodeId, LocalDate.of(2025, 6, 1), "{\"f215\":0}");

        Map<?, ?> card =
                data(
                        get(
                                cardPath("followup_completeness") + "&patientType=" + PATIENT_TYPE,
                                admin));

        assertEquals(2L, num(card.get("recordCount")), "已统计记录 = 甲任务记录 + 乙导入记录（计划外不计）");
        assertEquals(2L, num(card.get("patientCount")));
        assertEquals(
                75.0, card.get("averagePct"), "甲 100%（f214+f215 已填）、乙 50%（缺 f214）→ 按患者平均 75.0");
        List<?> years = (List<?>) card.get("years");
        assertEquals(1, years.size(), "只列有已完成记录的年度（都是第 1 年）");
        Map<?, ?> year1 = (Map<?, ?>) years.get(0);
        assertEquals("第1年", year1.get("label"));
        assertEquals(2L, num(year1.get("recordCount")));
        assertEquals(75.0, year1.get("averagePct"), "年度行 = 记录级平均");
        List<?> top = (List<?>) card.get("topMissing");
        assertEquals(
                List.of("f214"),
                top.stream().map(item -> ((Map<?, ?>) item).get("fieldKey")).toList(),
                "最常缺失字段 = f214（乙的导入记录缺，甲已填）");
        assertEquals(1L, num(((Map<?, ?>) top.get(0)).get("missingCount")));
        assertEquals(50.0, ((Map<?, ?>) top.get(0)).get("missingRatePct"), "缺失率 = 1/2 记录");

        // 钻取（验收）：缺 f214 的患者 = 乙
        Map<?, ?> drilled =
                post(
                        "/api/hospital/followup/patients/search",
                        admin,
                        searchBody(
                                Map.of(
                                        "kind", "FOLLOWUP",
                                        "fieldKey", "f214",
                                        "patientType", PATIENT_TYPE)));
        assertEquals(0, codeOf(drilled));
        assertEquals(1L, num(data(drilled).get("total")), "缺 f214 的患者 = 乙（与卡片 missingCount 一致）");

        // 时间范围按实际随访日期（默认 VISIT）：甲的 f214 = 2026-03-10（表单字段解析）、乙 = 2025-06-01（列）
        assertTrue(taskRecord > 0);
        Map<?, ?> visitFiltered =
                data(
                        get(
                                cardPath("followup_completeness")
                                        + "&patientType="
                                        + PATIENT_TYPE
                                        + "&from=2026-01-01&to=2026-12-31",
                                admin));
        assertEquals(1L, num(visitFiltered.get("recordCount")), "2026 年窗口只留甲（f214 解析生效）");
        assertEquals(100.0, visitFiltered.get("averagePct"));

        // 随访年度筛选（year=2 无记录 → 空卡；yearOptions 仍为全量候选）
        Map<?, ?> yearFiltered =
                data(
                        get(
                                cardPath("followup_completeness")
                                        + "&patientType="
                                        + PATIENT_TYPE
                                        + "&year=2",
                                admin));
        assertEquals(0L, num(yearFiltered.get("recordCount")), "第 2 年无记录 → 空卡不报错");
        assertEquals(1, ((List<?>) yearFiltered.get("yearOptions")).size(), "年度候选仍为全量（第 1 年）");
    }

    // ---------------------------------------------------------------- 数据范围 / 双租户 / 校验

    /** 范围外会话：两张完整度卡都返回 0 计数（stats.md §1「范围为空 → 全 0，不报错」）。 */
    @Test
    void outOfScopeSessionSeesZeroCounts() {
        SessionPrincipal outsider = restrictedPrincipal("demoa");
        CompletenessStatsService.Query query =
                new CompletenessStatsService.Query(null, null, null, null, null, null);
        var baseline = withHospitalSession(outsider, () -> completenessStats.baselineCard(query));
        assertEquals(0L, baseline.patientCount());
        var followup = withHospitalSession(outsider, () -> completenessStats.followupCard(query));
        assertEquals(0L, followup.recordCount());
        assertEquals(0L, followup.patientCount());
    }

    /** 双租户：demob 卡片只算本院患者（demoa 的 IT_E19T05 患者不进 demob 计数）。 */
    @Test
    void tenantIsolationOnCompletenessCounts() {
        String adminA = hospitalToken("demoa", "nurse.demo", PASSWORD);
        injectRequiredBaselineFields();
        patientOfType(adminA, "H1");

        Map<?, ?> demobBaseline =
                data(
                        get(
                                cardPath("baseline_completeness") + "&patientType=" + PATIENT_TYPE,
                                hospitalToken("demob", "nurse.demo", PASSWORD)));
        assertEquals(0L, num(demobBaseline.get("patientCount")), "demob 卡 = 本院患者（demoa 不进）");
        Map<?, ?> demobFollowup =
                data(
                        get(
                                cardPath("followup_completeness") + "&patientType=" + PATIENT_TYPE,
                                hospitalToken("demob", "nurse.demo", PASSWORD)));
        assertEquals(0L, num(demobFollowup.get("recordCount")));
    }

    @Test
    void invalidCardCodeAndParametersRejected() {
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        assertEquals(40001, codeOf(get(cardPath("no_such_card"), admin).getBody()));
        assertEquals(
                40001,
                codeOf(
                        get(cardPath("baseline_completeness") + "&timeField=VISIT", admin)
                                .getBody()),
                "基线卡时间维度只有 DISCHARGE / REGISTERED");
        assertEquals(
                40001,
                codeOf(get(cardPath("followup_completeness") + "&year=0", admin).getBody()),
                "随访年度从 1 起");
        assertEquals(
                40001,
                codeOf(
                        get(
                                        cardPath("baseline_completeness")
                                                + "&from=2026-06-30&to=2026-01-01",
                                        admin)
                                .getBody()),
                "from 晚于 to");
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/search",
                                admin,
                                searchBody(Map.of("kind", "SIDEWAYS", "fieldKey", "f9t5a")))),
                "钻取 kind 白名单");
    }

    // ---------------------------------------------------------------- 数据准备（全部虚构）

    /** 向 A_BASELINE v1 快照注入两个必填 JSON 字段（basic 分类；用后由 cleanState 删除）。 */
    private void injectRequiredBaselineFields() {
        runInTenant(
                "demoa",
                () -> {
                    for (String key : List.of("f9t5a", "f9t5b")) {
                        jdbc.update(
                                "INSERT INTO fe_form_version_field"
                                        + " (version_id, form_code, version, field_key, field_code,"
                                        + " label, category_key, type, required, sensitive_flag,"
                                        + " storage_type, sort_no)"
                                        + " SELECT v.id, 'A_BASELINE', 1, ?, ?, ?, 'basic', 'text',"
                                        + " 1, 0, 'json', 950"
                                        + " FROM fe_form_version v"
                                        + " WHERE v.form_code = 'A_BASELINE' AND v.version = 1",
                                key,
                                key,
                                "完整度示例字段（示例）");
                    }
                });
    }

    /** 登记患者并归入隔离用患者类型 IT_E19T05（卡片按 patientType 筛选只算本用例患者）。 */
    private long patientOfType(String token, String suffix) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", "IT-E19T05-MRN-" + suffix);
        body.put("name", "孙示例（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1950-06-01");
        body.put("wardId", minWard());
        body.put("admissionDate", "2026-05-18");
        body.put("surgeryDate", "2026-05-25");
        body.put("dischargeDate", "2026-06-01");
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + suffix);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记患者失败：" + created);
        long id = Long.parseLong((String) data(created).get("id"));
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE pt_patient SET patient_type = ? WHERE id = ?",
                                PATIENT_TYPE,
                                id));
        return id;
    }

    private long minWard() {
        return runInTenant(
                "demoa", () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    /** 基线分节（basic 一节；data 键 = 注入的必填字段）。 */
    private void insertBaselineSection(long patientId, String dataJson) {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "INSERT INTO pt_baseline_section"
                                        + " (patient_id, form_code, category_key, template_version,"
                                        + " data, version, has_draft)"
                                        + " VALUES (?, 'A_BASELINE', 'basic', 1, ?, 1, 0)",
                                patientId,
                                dataJson));
    }

    /** A 年度形态计划（CYCLE + YEAR → 年度节点，同 DashboardRateCardsIT）。 */
    private long createPublishedAnnualPlan(String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", PLAN_CODE + "-A");
        body.put("name", "示例 IT 完整度年度计划（" + PLAN_CODE + "）");
        body.put("startDateField", "DISCHARGE_DATE");
        body.put("cycleType", "ONGOING");
        long planId = idOf(post("/api/hospital/followup/plans", token, body));

        Map<String, Object> subplan = new HashMap<>();
        subplan.put("key", "s1");
        subplan.put("name", "年度随访（示例）");
        subplan.put("repeatMode", "CYCLE");
        subplan.put("cycleValue", 1);
        subplan.put("cycleUnit", "YEAR");
        subplan.put("sortNo", 10);
        Map<String, Object> node = new HashMap<>();
        node.put("key", "n1");
        node.put("parentKey", "s1");
        node.put("name", "电话随访（示例）");
        node.put("basis", "PLAN_START");
        node.put("offsetValue", 1);
        node.put("offsetUnit", "YEAR");
        node.put("remindValue", 7);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", 7);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", 7);
        node.put("windowAfterUnit", "DAY");
        node.put("taskType", "FORM");
        node.put("ownerType", "WARD_DEFAULT");
        node.put("formCode", "A_ANNUAL_FU");
        node.put("passedWindowAction", "BACKFILL");
        node.put("sortNo", 20);
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/followup/plans/" + planId + "/nodes",
                                token,
                                Map.of("baseVersion", 0, "nodes", List.of(subplan, node)))),
                "保存节点失败");
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/followup/plans/" + planId + "/publish",
                                token,
                                Map.of())),
                "发布失败");
        return planId;
    }

    /**
     * 任务录入的已提交记录（随访年度 = 年度节点的 occurrence）：fu_task 完成行 + fu_record(task_id, SUBMITTED)；实际随访日期只写在表单字段
     * f214（visit_date 列为 NULL）。
     */
    private long insertTaskSubmittedRecord(
            String tenant, long patientId, long planId, long nodeId) {
        long enrollmentId = enrollBackdated(tenant, patientId, planId);
        LocalDate planDate = LocalDate.of(2026, 3, 15);
        return runInTenant(
                tenant,
                () -> {
                    jdbc.update(
                            "INSERT INTO fu_task (enrollment_id, patient_id, plan_id, node_id,"
                                    + " occurrence, task_type, form_code, owner_type, plan_date,"
                                    + " remind_start, window_start, window_end, open_date,"
                                    + " create_type, status, complete_kind, followup_seq,"
                                    + " created_logical_date)"
                                    + " VALUES (?, ?, ?, ?, 1, 'FORM', 'A_ANNUAL_FU',"
                                    + " 'WARD_DEFAULT', ?, ?, ?, ?, ?, 'NORMAL', 'COMPLETED',"
                                    + " 'ON_TIME', 0, ?)",
                            enrollmentId,
                            patientId,
                            planId,
                            nodeId,
                            planDate,
                            planDate.minusDays(14),
                            planDate.minusDays(7),
                            planDate.plusDays(7),
                            planDate.minusDays(14),
                            LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")));
                    long taskId =
                            jdbc.queryForObject(
                                    "SELECT MAX(id) FROM fu_task WHERE enrollment_id = ?",
                                    Long.class,
                                    enrollmentId);
                    jdbc.update(
                            "INSERT INTO fu_record (patient_id, task_id, source, form_code,"
                                    + " template_version, status, data)"
                                    + " VALUES (?, ?, 'TASK', 'A_ANNUAL_FU', 1, 'SUBMITTED',"
                                    + " JSON_OBJECT('f214', '2026-03-10', 'f215', 0))",
                            patientId,
                            taskId);
                    return jdbc.queryForObject(
                            "SELECT MAX(id) FROM fu_record WHERE task_id = ?", Long.class, taskId);
                });
    }

    /** 计划外记录（UNPLANNED、已提交）——Q40 不计入随访完整度。 */
    private void insertUnplannedRecord(long patientId) {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "INSERT INTO fu_record (patient_id, source, visit_date, form_code,"
                                        + " template_version, status, data)"
                                        + " VALUES (?, 'UNPLANNED', '2026-05-01', 'A_ANNUAL_FU', 1,"
                                        + " 'SUBMITTED', JSON_OBJECT('f214', '2026-05-01', 'f215', 0))",
                                patientId));
    }

    /** 历史导入锚点记录（enrollment + node + occurrence = 1、followup_year = 1、visit_date 列）。 */
    private void insertHistImportRecord(
            String tenant,
            long patientId,
            long planId,
            long nodeId,
            LocalDate visitDate,
            String dataJson) {
        long enrollmentId = enrollBackdated(tenant, patientId, planId);
        runInTenant(
                tenant,
                () ->
                        jdbc.update(
                                "INSERT INTO fu_record (patient_id, enrollment_id, node_id,"
                                        + " occurrence, followup_year, source, visit_date, form_code,"
                                        + " template_version, status, data)"
                                        + " VALUES (?, ?, ?, 1, 1, 'HIST_IMPORT', ?, 'A_ANNUAL_FU',"
                                        + " 1, 'SUBMITTED', ?)",
                                patientId,
                                enrollmentId,
                                nodeId,
                                visitDate,
                                dataJson));
    }

    private long enrollBackdated(String tenant, long patientId, long planId) {
        return runInTenant(
                tenant,
                () -> {
                    long enrollmentId =
                            Long.parseLong(enrollmentService.enroll(patientId, planId, null).id());
                    jdbc.update("DELETE FROM fu_task WHERE enrollment_id = ?", enrollmentId);
                    return enrollmentId;
                });
    }

    private long nodeIdOf(String tenant, long planId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT MIN(n.id) FROM fu_plan_node n WHERE n.plan_id = ?"
                                        + " AND n.node_type = 'NODE'",
                                Long.class,
                                planId));
    }

    // ---------------------------------------------------------------- HTTP 与会话辅助（同
    // DashboardRateCardsIT 口径）

    private static String cardPath(String cardCode) {
        return "/api/hospital/followup/dashboard/cards/" + cardCode + "?";
    }

    /** 患者检索请求体（completenessDrill = 完整度缺失字段钻取）。 */
    private static Map<String, Object> searchBody(Map<String, Object> drill) {
        Map<String, Object> body = new HashMap<>();
        body.put("pageNo", 1);
        body.put("pageSize", 20);
        body.put("completenessDrill", drill);
        return body;
    }

    private String hospitalToken(String tenant, String username, String password) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", password));
        assertEquals(0, codeOf(response), "登录失败：" + response);
        return (String) data(response).get("token");
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.callAsTenant(
                "demob".equals(tenant) ? 2L : 1L,
                tenant,
                () -> {
                    action.run();
                    return null;
                });
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    @SuppressWarnings("unchecked")
    private static <T> T data(ResponseEntity<Map> response) {
        return (T) response.getBody().get("data");
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static long idOf(Map<?, ?> response) {
        return Long.parseLong((String) data(response).get("id"));
    }

    private static long num(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
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
                List.of(),
                List.of("followup"),
                1L,
                ScopeSnapshot.restricted(List.of(absentWard), List.of(), false),
                false,
                null);
    }

    /** 以给定会话执行（同 TokenAuthenticationFilter 的 ThreadLocal 装配方式）。 */
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
}
