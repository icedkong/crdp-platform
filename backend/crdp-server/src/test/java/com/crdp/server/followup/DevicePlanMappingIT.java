package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * E21-T05 端到端（tasks.csv 验收：按器械类型进入对应计划；C-FU-003）。场景（demoa 开 cfg_feature.implant，demob 保持关闭）：
 *
 * <ol>
 *   <li>验收主链：保存（登记同建）植入记录后按「起搏器类型 → 计划」映射自动入组，起算日 = 植入日期（fu_enrollment.start_date 快照）——DDD →
 *       普通计划、ICD → ICD 计划、未映射类型不入组；
 *   <li>映射配置接口：GET 合并字典类型（含未映射项与计划族名）；PUT 整体替换并写 PLAN_MAPPING_SAVE 审计； 不存在的计划族 / 字典外类型 40001；无
 *       followup:plan:edit 40300；implant 开关关闭（含 demob）40301；
 *   <li>升级到他族计划（E21-T06 起）：患者已在普通计划族在组时，CRT-D（映射到 ICD 计划）触发计划切换——原入组结束（SWITCHED）、按新类型转入 ICD
 *       计划（完整切换用例见 PlanSwitchIT）；
 *   <li>手工改选计划（C-FU-003「也可手工改选」）：批量入组入口入组 IMPLANT_DATE 起算的计划，起算快照由任务创建侧按在用器械回填（resolveStartDate 的
 *       IMPLANT_DATE 分支）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 IT-E21T05-PACER / -ICD、legacy_no 前缀
 * IT-E21T05-）；V86 预置映射行在清理时快照、收尾还原（PUT 整体替换会重写本表）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DevicePlanMappingIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E21T05";
    private static final String PACER_CODE = "IT-E21T05-PACER";
    private static final String ICD_CODE = "IT-E21T05-ICD";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    /** V86 预置映射行快照（PUT 整体替换前留存，收尾还原）。 */
    private List<Map<String, Object>> mappingSnapshot;

    @BeforeAll
    void snapshotPresetMapping() {
        mappingSnapshot =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForList(
                                        "SELECT device_type, plan_code, enabled FROM fu_plan_mapping"
                                                + " ORDER BY device_type"));
        assertFalse(mappingSnapshot.isEmpty(), "V86 预置映射行应存在");
    }

    @BeforeEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE"
                                    + " (target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                    + " WHERE legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (event_type LIKE 'PLAN_%'"
                                    + " AND (detail LIKE '%IT-E21T05%'"
                                    + " OR target IN (SELECT CONCAT('PLAN:', id) FROM fu_plan"
                                    + " WHERE code IN ('"
                                    + PACER_CODE
                                    + "','"
                                    + ICD_CODE
                                    + "'))))"
                                    + " OR target = 'PLAN_MAPPING:device-mapping'");
                    jdbc.update(
                            "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE e FROM fu_enrollment e JOIN pt_patient p"
                                    + " ON p.id = e.patient_id WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE il FROM pt_implant_lead il"
                                    + " JOIN pt_patient p ON p.id = il.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE l FROM pt_lead l JOIN pt_patient p ON p.id = l.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE i FROM pt_implant i"
                                    + " JOIN pt_patient p ON p.id = i.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    jdbc.update(
                            "DELETE n FROM fu_plan_node n JOIN fu_plan p ON p.id = n.plan_id"
                                    + " WHERE p.code IN (?, ?)",
                            PACER_CODE,
                            ICD_CODE);
                    jdbc.update("DELETE FROM fu_plan WHERE code IN (?, ?)", PACER_CODE, ICD_CODE);
                    jdbc.update(
                            "DELETE FROM fu_plan_mapping WHERE plan_code IN (?, ?)",
                            PACER_CODE,
                            ICD_CODE);
                    // 还原 V86 预置映射（用例里 PUT 整体替换会清表）
                    jdbc.update("DELETE FROM fu_plan_mapping");
                    for (Map<String, Object> row : mappingSnapshot) {
                        jdbc.update(
                                "INSERT INTO fu_plan_mapping (device_type, plan_code, enabled)"
                                        + " VALUES (?, ?, ?)",
                                row.get("device_type"),
                                row.get("plan_code"),
                                row.get("enabled"));
                    }
                    // IT 期间开启的开关还原为种子默认（关闭），A / B 租户不受影响
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'");
                    // IT 期间创建的角色 / 用户（权限用例）
                    jdbc.update(
                            "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                    + " WHERE u.username LIKE 'IT-U-E21T05%'");
                    jdbc.update(
                            "DELETE ph FROM sys_password_history ph JOIN sys_user u"
                                    + " ON u.id = ph.user_id WHERE u.username LIKE 'IT-U-E21T05%'");
                    jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-U-E21T05%'");
                    jdbc.update(
                            "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                    + " WHERE r.code LIKE 'IT-R-E21T05%'");
                    jdbc.update(
                            "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                    + " WHERE r.code LIKE 'IT-R-E21T05%'");
                    jdbc.update("DELETE FROM sys_role WHERE code LIKE 'IT-R-E21T05%'");
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：按器械类型进入对应计划

    @Test
    void implantSaveEnrollsIntoMappedPlanWithImplantDate() {
        enableImplant();
        String admin = adminToken();
        long pacerPlan = publishedPlan(admin, PACER_CODE, "示例 IT 普通起搏器术后随访");
        publishedPlan(admin, ICD_CODE, "示例 IT ICD/CRT-D 术后随访");
        assertEquals(
                0, code(put("/api/hospital/followup/plans/device-mapping", admin, mappingBody())));

        // DDD → 普通计划：登记同建首条植入记录，同一事务自动入组，起算日 = 植入日期（C-FU-003）
        long dddPatient = registerPatient(admin, "01", "DDD", "2026-01-05");
        Map<String, Object> dddEnrollment = enrollmentOf(dddPatient);
        assertNotNull(dddEnrollment, "按器械类型进入对应计划（DDD → 普通计划）");
        assertEquals(PACER_CODE, dddEnrollment.get("plan_code"));
        assertEquals("ACTIVE", dddEnrollment.get("status"));
        assertEquals("NORMAL", dddEnrollment.get("mode"));
        assertEquals(
                java.sql.Date.valueOf("2026-01-05"),
                dddEnrollment.get("start_date"),
                "起算日 = 植入日期（入组快照）");

        // ICD → ICD/CRT-D 计划（C-FU-002 的类型划分）
        long icdPatient = registerPatient(admin, "02", "ICD", "2026-02-10");
        assertEquals(ICD_CODE, enrollmentOf(icdPatient).get("plan_code"), "ICD → ICD/CRT-D 计划");

        // 未映射类型（本用例 PUT 未包含 LEADLESS）不入组
        long untyped = registerPatient(admin, "03", "LEADLESS", "2026-03-01");
        assertNull(enrollmentOf(untyped), "未映射类型不自动入组");

        // 历史建档（2019 植入）：同样按映射入组，起算快照 = 历史植入日期；已过窗口的任务策略沿用 E14-T05（Q14 不算逾期）
        long historic = registerPatient(admin, "04", "DDD", "2019-06-15");
        Map<String, Object> historicEnrollment = enrollmentOf(historic);
        assertNotNull(historicEnrollment, "历史患者建档照常按映射入组（C-PAT-008）");
        assertEquals(java.sql.Date.valueOf("2019-06-15"), historicEnrollment.get("start_date"));

        // 保存映射写 PLAN_MAPPING_SAVE 审计（审计只追加，清理走维护开关）
        Long audits =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                                + " 'PLAN_MAPPING_SAVE'",
                                        Long.class));
        assertTrue(audits >= 1, "写 PLAN_MAPPING_SAVE 审计");

        // 计划与映射存在性防御：入组行确实挂在映射解析到的计划版本上
        assertEquals(
                pacerPlan,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT plan_id FROM fu_enrollment WHERE patient_id = ?",
                                        Long.class,
                                        dddPatient)),
                "入组固定在映射解析到的已发布版本（A-FU-019）");
    }

    // ---------------------------------------------------------------- 升级到他族：切换随 E21-T06 已接入

    @Test
    void upgradeToOtherFamilySwitchesPlanWithoutDoubleEnroll() {
        enableImplant();
        String admin = adminToken();
        publishedPlan(admin, PACER_CODE, "示例 IT 普通起搏器术后随访");
        publishedPlan(admin, ICD_CODE, "示例 IT ICD/CRT-D 术后随访");
        assertEquals(
                0, code(put("/api/hospital/followup/plans/device-mapping", admin, mappingBody())));

        // DDD 已入普通计划 → 升级为 CRT-D（映射到 ICD 计划）：E21-T06 计划切换——原入组结束、转 ICD 计划
        long patientId = registerPatient(admin, "11", "DDD", "2026-01-05");
        long pacerEnrollmentId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM fu_enrollment WHERE patient_id = ?"
                                                + " AND status IN ('ACTIVE','PAUSED')",
                                        Long.class,
                                        patientId));
        Map<?, ?> implant = firstImplant(admin, patientId);
        List<Map<String, Object>> leadActions = new ArrayList<>();
        for (Object lead : (List<?>) implant.get("leads")) {
            leadActions.add(
                    Map.of(
                            "leadId",
                            String.valueOf(((Map<?, ?>) lead).get("id")),
                            "action",
                            "KEEP"));
        }
        Map<?, ?> upgraded =
                post(
                        "/api/hospital/followup/implants/" + implant.get("id") + "/replace",
                        admin,
                        replaceBody(
                                ((Number) implant.get("version")).intValue(),
                                "UPGRADE",
                                "2026-03-02",
                                "CRT_D",
                                leadActions));
        assertEquals(0, code(upgraded), "升级失败：" + upgraded);

        List<Map<String, Object>> enrollments =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForList(
                                        "SELECT plan_code, status, start_date FROM fu_enrollment"
                                                + " WHERE patient_id = ? AND status IN"
                                                + " ('ACTIVE','PAUSED')",
                                        patientId));
        assertEquals(1, enrollments.size(), "不产生双计划并行任务");
        assertEquals(ICD_CODE, enrollments.get(0).get("plan_code"), "升级切换转入 ICD 计划");
        assertEquals(
                java.sql.Date.valueOf("2026-03-02"),
                enrollments.get(0).get("start_date"),
                "起算日 = 升级日期");
        // 原普通入组结束：SWITCHED + 切换原因（C-FU-007；详细行为由 PlanSwitchIT 锁定）
        Map<String, Object> ended =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT status, switch_reason FROM fu_enrollment WHERE id = ?",
                                        DevicePlanMappingIT::rowOf,
                                        pacerEnrollmentId));
        assertEquals("SWITCHED", ended.get("status"), "原入组结束（E21-T06）");
        assertTrue(
                String.valueOf(ended.get("switch_reason")).contains("升级"), "switch_reason 记切换原因");
    }

    private static Map<String, Object> rowOf(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Map<String, Object> row = new HashMap<>();
        row.put("status", rs.getString("status"));
        row.put("switch_reason", rs.getString("switch_reason"));
        return row;
    }

    // ---------------------------------------------------------------- 映射配置接口

    @Test
    void mappingConfigApiValidatesPermissionsAndFeature() {
        enableImplant();
        String admin = adminToken();
        publishedPlan(admin, PACER_CODE, "示例 IT 普通起搏器术后随访");

        // GET：合并字典类型（含未映射项）与计划族名一并下发
        Map<?, ?> view = get("/api/hospital/followup/plans/device-mapping", admin);
        assertEquals(0, code(view));
        List<?> items = (List<?>) data(view).get("items");
        assertTrue(items.size() >= 7, "起搏器类型全量下发（V70 预置 7 项）");
        Map<?, ?> ddd =
                items.stream()
                        .map(item -> (Map<?, ?>) item)
                        .filter(item -> "DDD".equals(item.get("deviceType")))
                        .findFirst()
                        .orElseThrow();
        assertEquals("双腔起搏器(DDD)", ddd.get("deviceTypeLabel"));
        assertEquals("C_PACER_FU_PLAN", ddd.get("planCode"), "V86 预置映射");

        // PUT：不存在的计划族 / 字典外类型 40001（整组拒绝、不落库）
        assertEquals(
                40001,
                code(
                        put(
                                "/api/hospital/followup/plans/device-mapping",
                                admin,
                                Map.of(
                                        "items",
                                        List.of(
                                                Map.of(
                                                        "deviceType",
                                                        "DDD",
                                                        "planCode",
                                                        "NO_SUCH_PLAN",
                                                        "enabled",
                                                        true))))),
                "计划族不存在 40001");
        assertEquals(
                40001,
                code(
                        put(
                                "/api/hospital/followup/plans/device-mapping",
                                admin,
                                Map.of(
                                        "items",
                                        List.of(
                                                Map.of(
                                                        "deviceType",
                                                        "NOT_A_TYPE",
                                                        "planCode",
                                                        PACER_CODE,
                                                        "enabled",
                                                        true))))),
                "字典外类型 40001");

        // 无 followup:plan:edit（只授患者列表 21）→ 40300
        String viewer = newUserWithMenus(List.of(21L));
        assertEquals(
                40300,
                code(get("/api/hospital/followup/plans/device-mapping", viewer)),
                "无 plan:edit 40300");

        // implant 开关关闭 → 40301（demob 种子即关闭，租户隔离维度）
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'"));
        assertEquals(
                40301,
                code(get("/api/hospital/followup/plans/device-mapping", admin)),
                "开关关闭 40301");
        String demobAdmin = hospitalToken("demob", "nurse.demo", PASSWORD);
        assertEquals(
                40301,
                code(get("/api/hospital/followup/plans/device-mapping", demobAdmin)),
                "demob 未开通 40301");
    }

    // ---------------------------------------------------------------- 手工改选计划（起算字段解析）

    @Test
    void manualEnrollmentResolvesStartDateFromActiveImplant() {
        enableImplant();
        String admin = adminToken();
        long planId = publishedPlan(admin, PACER_CODE, "示例 IT 普通起搏器术后随访");
        // 不配置任何映射（清空），走手工改选：批量入组入口（E13-T07，单患者）
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/followup/plans/device-mapping",
                                admin,
                                Map.of("items", List.of()))),
                "清空映射失败");

        long patientId = registerPatient(admin, "21", "LEADLESS", "2026-04-01");
        assertNull(enrollmentOf(patientId), "未映射类型不自动入组");
        Map<?, ?> enrolled =
                post(
                        "/api/hospital/followup/plans/" + planId + "/enrollments/batch",
                        admin,
                        Map.of("patientIds", List.of(patientId)));
        assertEquals(0, code(enrolled), "批量入组失败：" + enrolled);
        assertEquals(1, ((Number) data(enrolled).get("succeeded")).intValue());

        // 起算快照为空 → 入组即建时按 IMPLANT_DATE 解析在用器械植入日期回填（resolveStartDate 分支）
        Map<String, Object> enrollment = enrollmentOf(patientId);
        assertNotNull(enrollment, "手工改选入组成功（C-FU-003「也可手工改选计划」）");
        assertEquals(
                java.sql.Date.valueOf("2026-04-01"),
                enrollment.get("start_date"),
                "起算日期按在用器械植入日期解析回填");
    }

    // ---------------------------------------------------------------- 辅助

    private void enableImplant() {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'implant'"));
    }

    /** 映射保存体：DDD / CRT_D → 普通计划，ICD → ICD 计划（LEADLESS 故意不映射，用例内断言未映射不入组）。 */
    private static Map<String, Object> mappingBody() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String type : new String[] {"SSI", "DDD", "CRT_P", "HBP_LBBP"}) {
            items.add(Map.of("deviceType", type, "planCode", PACER_CODE, "enabled", true));
        }
        for (String type : new String[] {"ICD", "CRT_D"}) {
            items.add(Map.of("deviceType", type, "planCode", ICD_CODE, "enabled", true));
        }
        return Map.of("items", items);
    }

    /** 植入起算的已发布计划（API 创建 + 一个 SKIP 节点 + 发布；本 IT 不断言任务排期细节）。 */
    private long publishedPlan(String token, String code, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("startDateField", "IMPLANT_DATE");
        body.put("cycleType", "ONGOING");
        body.put("autoEnroll", false);
        long planId = idOf(post("/api/hospital/followup/plans", token, body));

        Map<String, Object> subplan = new HashMap<>();
        subplan.put("key", "s1");
        subplan.put("name", "术后随访（示例）");
        subplan.put("repeatMode", "FIXED");
        subplan.put("repeatCount", 1);
        subplan.put("sortNo", 10);
        Map<String, Object> node = new HashMap<>();
        node.put("key", "n1");
        node.put("parentKey", "s1");
        node.put("name", "术后 3 个月（示例）");
        node.put("basis", "PLAN_START");
        node.put("offsetValue", 3);
        node.put("offsetUnit", "MONTH");
        node.put("remindValue", 14);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", 14);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", 14);
        node.put("windowAfterUnit", "DAY");
        node.put("taskType", "FORM");
        node.put("ownerType", "WARD_DEFAULT");
        node.put("formCode", "fu_postop_v1");
        node.put("passedWindowAction", "SKIP");
        node.put("sortNo", 20);
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/followup/plans/" + planId + "/nodes",
                                token,
                                Map.of("baseVersion", 0, "nodes", List.of(subplan, node)))),
                "保存节点失败");
        assertEquals(
                0,
                code(post("/api/hospital/followup/plans/" + planId + "/publish", token, Map.of())),
                "发布失败");
        return planId;
    }

    /** C 登记并返回患者 id（implant 开关开启时同建首条植入记录，ImplantRecordIT 同款请求体）。 */
    private long registerPatient(
            String token, String suffix, String deviceType, String implantDate) {
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantType", "FIRST");
        implant.put("implantDate", implantDate);
        implant.put("deviceType", deviceType);
        implant.put("brand", "MEDTRONIC");
        implant.put("model", "示例型号 S-01");
        implant.put("serialNo", "DEMO-SN-" + LEGACY + "-" + suffix);
        implant.put("pacingMode", "DDD");
        implant.put("hospital", "虚构示例医院");
        implant.put("doctor", "示例医生");
        implant.put("indication", "SSS");
        if (!"LEADLESS".equals(deviceType)) {
            implant.put(
                    "leads",
                    List.of(
                            Map.of("chamber", "RA", "position", "RA_APPENDAGE"),
                            Map.of("chamber", "RV", "position", "RV_APEX")));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + suffix);
        body.put("name", "欧阳示例（示例）");
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

    /** 升级请求体（沿用电极逐根 KEEP + 新植 LV，ImplantReplaceIT 同款形态）。 */
    private static Map<String, Object> replaceBody(
            int baseVersion,
            String type,
            String date,
            String deviceType,
            List<Map<String, Object>> leadActions) {
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantDate", date);
        implant.put("deviceType", deviceType);
        implant.put("brand", "MEDTRONIC");
        implant.put("model", "示例型号 S-02");
        implant.put("serialNo", "DEMO-SN-" + LEGACY + "-UP");
        implant.put("pacingMode", "DDD");
        implant.put("hospital", "虚构示例医院");
        implant.put("doctor", "示例医生");
        implant.put("indication", "CRT_IND");
        return Map.of(
                "baseVersion",
                baseVersion,
                "type",
                type,
                "implant",
                implant,
                "leadActions",
                leadActions,
                "newLeads",
                List.of(Map.of("chamber", "LV", "position", "CS")));
    }

    private Map<?, ?> firstImplant(String token, long patientId) {
        Map<?, ?> list = get("/api/hospital/followup/patients/" + patientId + "/implants", token);
        assertEquals(0, code(list));
        List<?> items = (List<?>) data(list).get("items");
        return (Map<?, ?>) items.get(0);
    }

    private Map<String, Object> enrollmentOf(long patientId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.query(
                                "SELECT * FROM fu_enrollment WHERE patient_id = ?"
                                        + " AND status IN ('ACTIVE','PAUSED') LIMIT 1",
                                rs -> rs.next() ? resultMap(rs) : null,
                                patientId));
    }

    private static Map<String, Object> resultMap(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        Map<String, Object> row = new HashMap<>();
        int columns = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= columns; i++) {
            row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
        }
        return row;
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

    private String newUserWithMenus(List<Long> menuIds) {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_role (code, name, built_in, status)"
                                    + " VALUES ('IT-R-E21T05', 'IT 映射角色（示例）', 0, 'ENABLED')");
                    long roleId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_role WHERE code = 'IT-R-E21T05'",
                                    Long.class);
                    for (Long menuId : menuIds) {
                        jdbc.update(
                                "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)",
                                roleId,
                                menuId);
                    }
                    jdbc.update(
                            "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                            roleId);
                    long deptId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'",
                                    Long.class);
                    Map<?, ?> created =
                            post(
                                    "/api/hospital/system/users",
                                    hospitalToken("demoa", "nurse.demo", PASSWORD),
                                    Map.of(
                                            "username",
                                            "IT-U-E21T05",
                                            "displayName",
                                            "IT 映射用户（示例）",
                                            "phone",
                                            "13900001601",
                                            "mainDeptId",
                                            deptId,
                                            "roleIds",
                                            List.of(roleId)));
                    assertEquals(0, code(created), "建用户失败：" + created);
                    String temporary = String.valueOf(data(created).get("temporaryPassword"));
                    String token = hospitalToken("demoa", "IT-U-E21T05", temporary);
                    assertEquals(
                            0,
                            code(
                                    post(
                                            "/api/hospital/auth/password",
                                            token,
                                            Map.of(
                                                    "oldPassword",
                                                    temporary,
                                                    "newPassword",
                                                    PASSWORD))),
                            "改密失败");
                    return hospitalToken("demoa", "IT-U-E21T05", PASSWORD);
                });
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

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
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

    private static long idOf(Map<?, ?> response) {
        return Long.parseLong(String.valueOf(data(response).get("id")));
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(1L, tenant, action::get);
    }
}
