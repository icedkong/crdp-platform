package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E21-T01 端到端（验收原文：一个患者多次植入、多根电极）。场景（demoa 开 cfg_feature.implant，demob 保持关闭）：
 *
 * <ol>
 *   <li>C 登记（C-PAT-001 / OQ-71①）：implant 开关开启时 POST /patients 携带首条植入记录与电极，登记与植入同事务落库；开关关闭时 A
 *       口径不受影响；
 *   <li>多次植入（C-PAT-004）：第二条更晚日期的植入记录接替在用，旧记录自动结束（已更换，结束日期 = 新植入日期），列表当前器械置顶、植入序号按日期升序； 一位患者仅一条在用（uk
 *       生成列约束）；
 *   <li>多根电极（C-PAT-005）：RA / RV / LV 多根电极挂植入记录，部位按腔室过滤，无导线起搏器不录电极；
 *   <li>编辑（PUT）：乐观锁 baseVersion、电极集替换（新增 / 移除）；
 *   <li>权限与隔离：无 followup:implant:edit 40300；开关关闭 40301；越范围患者 40400；写 IMPLANT_CREATE /
 *       IMPLANT_UPDATE 审计； demob（开关关闭）40301。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E21T01-、 病区
 * W-IT-IMPL-B、角色 IT-R-IMPL-*、用户 IT-U-IMPL-*）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImplantRecordIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E21T01";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        jdbc.update(
                                "DELETE il FROM pt_implant_lead il"
                                        + " JOIN pt_patient p ON p.id = il.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE l FROM pt_lead l"
                                        + " JOIN pt_patient p ON p.id = l.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE i FROM pt_implant i"
                                        + " JOIN pt_patient p ON p.id = i.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        // 审计表只读（PLT-AUD-011）：自清理走维护开关（连接级会话变量，用完即清）
                        com.crdp.server.AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE event_type IN ('IMPLANT_CREATE','IMPLANT_UPDATE')"
                                        + " AND target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                        + " WHERE legacy_no LIKE ?)",
                                LEGACY + "-%");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                        // IT 期间开启的开关还原为种子默认（关闭），A / B 租户不受影响
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username LIKE 'IT-U-IMPL-%'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u"
                                        + " ON u.id = ph.user_id WHERE u.username LIKE 'IT-U-IMPL-%'");
                        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-U-IMPL-%'");
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                        + " WHERE r.code LIKE 'IT-R-IMPL-%'");
                        jdbc.update(
                                "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                        + " WHERE r.code LIKE 'IT-R-IMPL-%'");
                        jdbc.update("DELETE FROM sys_role WHERE code LIKE 'IT-R-IMPL-%'");
                        jdbc.update("DELETE FROM ward WHERE code = 'W-IT-IMPL-B'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        // 与 PatientRegistrationIT 同款：最后一个用例的数据不外泄给同库后续 IT
        // （紧随其后的 DashboardCardsIT 按精确条数断言患者状态分布）
        cleanState();
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'"));
        runInTenant(
                "demob",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'"));
    }

    // ---------------------------------------------------------------- 验收：一个患者多次植入、多根电极

    @Test
    void multipleImplantsAndLeadsPerPatient() {
        enableImplant("demoa");
        String admin = adminToken();

        // C 登记：患者 + 首条植入记录（DDD，RA / RV 两根电极）同事务落库
        Map<?, ?> first =
                post(
                        "/api/hospital/followup/patients",
                        admin,
                        registrationBody(
                                "IT-MRN-E21T01-01",
                                "王李张（示例）",
                                "2026-01-05",
                                "DDD",
                                "DEMO-SN-E21T01-1"));
        assertEquals(0, code(first), "C 登记失败：" + first);
        String patientId = String.valueOf(data(first).get("id"));

        Map<?, ?> list = get(implantsPath(patientId), admin);
        assertEquals(0, code(list));
        List<?> items = (List<?>) data(list).get("items");
        assertEquals(1, items.size(), "登记即建首条植入记录");
        Map<?, ?> implant1 = (Map<?, ?>) items.get(0);
        assertTrue((Boolean) implant1.get("current"), "首条在用");
        assertEquals(2, ((List<?>) implant1.get("leads")).size(), "多根电极（RA / RV）");
        assertEquals("双腔起搏器(DDD)", implant1.get("deviceTypeLabel"));

        // 第二次植入（更换，2026-03-02）：旧记录自动结束，当前器械为新器械（C 规格验收示例）
        Map<?, ?> second =
                post(
                        implantsPath(patientId),
                        admin,
                        implantBody(
                                "REPLACE",
                                "2026-03-02",
                                "CRT_D",
                                "DEMO-SN-E21T01-2",
                                List.of(
                                        Map.of("chamber", "RA", "position", "RA_APPENDAGE"),
                                        Map.of("chamber", "RV", "position", "RV_SEPTUM"),
                                        Map.of("chamber", "LV", "position", "CS"))));
        assertEquals(0, code(second), "第二次植入失败：" + second);

        list = get(implantsPath(patientId), admin);
        items = (List<?>) data(list).get("items");
        assertEquals(2, items.size(), "验收：一个患者多次植入");
        Map<?, ?> current = (Map<?, ?>) items.get(0);
        Map<?, ?> replaced = (Map<?, ?>) items.get(1);
        assertTrue((Boolean) current.get("current"), "当前器械置顶");
        assertEquals("CRT_D", current.get("deviceType"));
        assertEquals(3, ((List<?>) current.get("leads")).size(), "三根电极（RA / RV / LV）");
        assertEquals(2, current.get("implantNo"), "植入序号按日期升序");
        assertEquals("REPLACED", replaced.get("status"), "旧器械自动结束为已更换");
        assertEquals("2026-03-02", replaced.get("endDate"), "结束日期 = 新植入日期");

        // 一位患者同一时间仅一条在用：uk 生成列约束兜底
        Long otherActive =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM pt_implant WHERE patient_id = ?"
                                                + " AND status = 'ACTIVE'",
                                        Long.class,
                                        Long.valueOf(patientId)));
        assertEquals(1L, otherActive, "在用唯一（uk_pt_implant_active）");

        // 审计：登记建植与第二次植入各一条 IMPLANT_CREATE
        Long audits =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                                + " 'IMPLANT_CREATE' AND target = ?",
                                        Long.class,
                                        "PATIENT:" + patientId));
        assertEquals(2L, audits, "写 IMPLANT_CREATE 审计");
    }

    @Test
    void editUpdatesImplantAndReplacesLeads() {
        enableImplant("demoa");
        String admin = adminToken();
        String patientId =
                registeredPatientId(
                        admin, "IT-MRN-E21T01-02", "2026-01-05", "DDD", "DEMO-SN-E21T01-3");
        Map<?, ?> implant = firstImplant(admin, patientId);
        String implantId = String.valueOf(implant.get("id"));
        int baseVersion = ((Number) implant.get("version")).intValue();

        // 编辑：改型号 + 电极集替换（RA 保留、RV 移除、新增 LV）
        Map<?, ?> updated =
                rest.exchange(
                                "/api/hospital/followup/implants/" + implantId,
                                HttpMethod.PUT,
                                new HttpEntity<>(
                                        editBody(
                                                baseVersion,
                                                "示例型号 S-02",
                                                List.of(
                                                        keepLead(firstLead(implant, "RA")),
                                                        Map.of("chamber", "LV", "position", "CS"))),
                                        bearer(admin)),
                                Map.class)
                        .getBody();
        assertEquals(0, code(updated), "编辑失败：" + updated);
        List<?> leads = (List<?>) data(updated).get("leads");
        assertEquals(2, leads.size(), "电极集替换：移除 RV、新增 LV");

        // 乐观锁：旧 baseVersion 再提交 → 40900
        Map<?, ?> stale =
                rest.exchange(
                                "/api/hospital/followup/implants/" + implantId,
                                HttpMethod.PUT,
                                new HttpEntity<>(
                                        editBody(baseVersion, "示例型号 S-03", List.of()),
                                        bearer(admin)),
                                Map.class)
                        .getBody();
        assertEquals(40900, code(stale), "baseVersion 冲突 409");
    }

    // ---------------------------------------------------------------- 校验（C-PAT-005）

    @Test
    void editRecordsReasonInFieldHistory() {
        // E23-T24 / UAT-SELF-01（C-PAT-011 / UAT 脚本 B-5）：编辑植入记录必须填写修改原因，
        // 字段级历史行（fe_field_history，PT_IMPLANT）逐行带原因；缺原因 40001 拒绝
        enableImplant("demoa");
        String admin = adminToken();
        String patientId =
                registeredPatientId(
                        admin, "IT-MRN-E23T24-01", "2026-01-05", "DDD", "DEMO-SN-E23T24-1");
        Map<?, ?> implant = firstImplant(admin, patientId);
        String implantId = String.valueOf(implant.get("id"));
        int baseVersion = ((Number) implant.get("version")).intValue();

        // 缺原因 → 40001，不落库
        Map<String, Object> noReason = editBody(baseVersion, "示例型号 S-E23T24", List.of());
        noReason.remove("reason");
        Map<?, ?> rejected =
                rest.exchange(
                                "/api/hospital/followup/implants/" + implantId,
                                HttpMethod.PUT,
                                new HttpEntity<>(noReason, bearer(admin)),
                                Map.class)
                        .getBody();
        assertEquals(40001, code(rejected), "修改植入记录必须填写修改原因：" + rejected);

        // 带原因 → 200，字段级历史行 reason = 修改植入记录：<用户原因>
        Map<String, Object> withReason = editBody(baseVersion, "示例型号 S-E23T24", List.of());
        withReason.put("reason", "型号抄录更正（示例）");
        Map<?, ?> updated =
                rest.exchange(
                                "/api/hospital/followup/implants/" + implantId,
                                HttpMethod.PUT,
                                new HttpEntity<>(withReason, bearer(admin)),
                                Map.class)
                        .getBody();
        assertEquals(0, code(updated), "编辑失败：" + updated);

        List<Map<String, Object>> history =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForList(
                                        "SELECT field_key, new_value, reason FROM fe_field_history"
                                                + " WHERE record_type = 'PT_IMPLANT' AND record_id = ?"
                                                + " ORDER BY id",
                                        Long.parseLong(implantId)));
        assertTrue(history.size() >= 1, "编辑写字段级历史行");
        List<Map<String, Object>> modelRows =
                history.stream().filter(row -> "f106".equals(row.get("field_key"))).toList();
        assertEquals(1, modelRows.size(), "型号稳定键 f106 的历史行：" + history);
        assertEquals(
                "修改植入记录：型号抄录更正（示例）", modelRows.get(0).get("reason"), "历史行带用户填写的修改原因（C-PAT-011）");
        assertTrue(
                history.stream().allMatch(row -> row.get("reason") != null),
                "全部历史行都带原因（本次保存单一原因）：" + history);
    }

    @Test
    void leadlessDeviceAndCrossChamberPositionRejected() {
        enableImplant("demoa");
        String admin = adminToken();
        String patientId =
                registeredPatientId(
                        admin, "IT-MRN-E21T01-03", "2026-01-05", "DDD", "DEMO-SN-E21T01-4");

        assertEquals(
                40001,
                code(
                        post(
                                implantsPath(patientId),
                                admin,
                                implantBody(
                                        "FIRST",
                                        "2026-02-01",
                                        "LEADLESS",
                                        "DEMO-SN-E21T01-5",
                                        List.of(Map.of("chamber", "RV", "position", "RV_APEX"))))),
                "无导线起搏器不录电极");
        assertEquals(
                40001,
                code(
                        post(
                                implantsPath(patientId),
                                admin,
                                implantBody(
                                        "FIRST",
                                        "2026-02-01",
                                        "DDD",
                                        "DEMO-SN-E21T01-6",
                                        List.of(Map.of("chamber", "RA", "position", "RV_APEX"))))),
                "部位按腔室过滤");
        assertEquals(
                40001,
                code(
                        post(
                                implantsPath(patientId),
                                admin,
                                implantBody(
                                        "FIRST",
                                        "2999-01-01",
                                        "DDD",
                                        "DEMO-SN-E21T01-7",
                                        List.of()))),
                "植入日期不能晚于今天");
    }

    // ---------------------------------------------------------------- 权限 / 范围 / 开关 / 双租户

    @Test
    void permissionScopeAndFeatureGatesAreEnforced() {
        enableImplant("demoa");
        String admin = adminToken();
        String patientId =
                registeredPatientId(
                        admin, "IT-MRN-E21T01-04", "2026-01-05", "DDD", "DEMO-SN-E21T01-8");

        // 只授患者列表（21），不授编辑植入记录（228）→ 读 0 / 写 40300
        long viewRoleId = roleWithMenus("IT-R-IMPL-VIEW", List.of(21L));
        String viewToken = newUser("demoa", "IT-U-IMPL-VIEW", viewRoleId);
        assertEquals(0, code(get(implantsPath(patientId), viewToken)), "列表只要求 patient:view");
        assertEquals(
                40300,
                code(
                        post(
                                implantsPath(patientId),
                                viewToken,
                                implantBody(
                                        "FIRST",
                                        "2026-02-01",
                                        "DDD",
                                        "DEMO-SN-E21T01-9",
                                        List.of()))),
                "无 followup:implant:edit 40300");

        // 越范围：doctor.demo 只授权 W-DEMO-1（种子 sys_user_ward），别病区患者 40400
        long otherWard = createWard("W-IT-IMPL-B", "示例 IT 植入隔离病区");
        Map<?, ?> other =
                post(
                        "/api/hospital/followup/patients",
                        admin,
                        registrationBodyTo(
                                "IT-MRN-E21T01-05",
                                "陈褚卫（示例）",
                                otherWard,
                                "2026-01-05",
                                "DDD",
                                "DEMO-SN-E21T01-10"));
        assertEquals(0, code(other), "别病区登记失败：" + other);
        String otherPatientId = String.valueOf(data(other).get("id"));
        assertEquals(40400, code(get(implantsPath(otherPatientId), doctorToken())), "越范围 40400");

        // 开关关闭 → 40301（A / B 默认口径不受影响）
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'"));
        assertEquals(40301, code(get(implantsPath(patientId), admin)), "开关关闭 40301");
        // 开关关闭时 A 登记口径不变：不带植入记录可登记，带植入记录 40001
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/followup/patients",
                                admin,
                                aRegistrationBody(
                                        "IT-MRN-E21T01-06", "蒋沈韩（示例）", wardId("W-DEMO-1")))),
                "A 口径登记不受影响");
        // demob 开关默认关闭 → 40301（租户隔离维度）
        String demobAdmin = hospitalToken("demob", "nurse.demo", PASSWORD);
        assertEquals(40301, code(get(implantsPath(otherPatientId), demobAdmin)), "demob 未开通 40301");
    }

    // ---------------------------------------------------------------- 辅助

    private void enableImplant(String tenant) {
        runInTenant(
                tenant,
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'implant'"));
    }

    private String implantsPath(String patientId) {
        return "/api/hospital/followup/patients/" + patientId + "/implants";
    }

    /** C 登记请求体（implant 开关开启时首条植入记录必填）。 */
    private Map<String, Object> registrationBody(
            String mrn, String name, String implantDate, String deviceType, String serialNo) {
        return registrationBodyTo(mrn, name, wardId("W-DEMO-1"), implantDate, deviceType, serialNo);
    }

    private Map<String, Object> registrationBodyTo(
            String mrn,
            String name,
            long wardId,
            String implantDate,
            String deviceType,
            String serialNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", mrn);
        body.put("name", name);
        body.put("gender", "M");
        body.put("birthDate", "1955-01-01");
        body.put("deptId", deptId("DEPT-CARDIO"));
        body.put("wardId", wardId);
        body.put("legacyNo", LEGACY + "-" + mrn);
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantType", "FIRST");
        implant.put("implantDate", implantDate);
        implant.put("deviceType", deviceType);
        implant.put("brand", "MEDTRONIC");
        implant.put("model", "示例型号 S-01");
        implant.put("serialNo", serialNo);
        implant.put("pacingMode", "DDD");
        implant.put("hospital", "虚构示例医院");
        implant.put("doctor", "示例医生");
        implant.put("indication", "SSS");
        implant.put(
                "leads",
                List.of(
                        Map.of("chamber", "RA", "position", "RA_APPENDAGE"),
                        Map.of("chamber", "RV", "position", "RV_APEX")));
        body.put("implant", implant);
        return body;
    }

    /** A 出院登记请求体（不带植入记录）。 */
    private Map<String, Object> aRegistrationBody(String mrn, String name, long wardId) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", mrn);
        body.put("name", name);
        body.put("gender", "M");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", wardId);
        body.put("surgeryDate", "2026-03-01");
        body.put("admissionDate", "2026-02-25");
        body.put("dischargeDate", "2026-03-08");
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + mrn);
        return body;
    }

    private Map<String, Object> implantBody(
            String type,
            String date,
            String deviceType,
            String serialNo,
            List<Map<String, Object>> leads) {
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantType", type);
        implant.put("implantDate", date);
        implant.put("deviceType", deviceType);
        implant.put("brand", "MEDTRONIC");
        implant.put("model", "示例型号 S-01");
        implant.put("serialNo", serialNo);
        implant.put("pacingMode", "DDD");
        implant.put("hospital", "虚构示例医院");
        implant.put("doctor", "示例医生");
        implant.put("indication", "SSS");
        implant.put("leads", leads);
        return implant;
    }

    private Map<String, Object> editBody(
            int baseVersion, String model, List<Map<String, Object>> leads) {
        Map<String, Object> body = new HashMap<>();
        body.put("baseVersion", baseVersion);
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantType", "FIRST");
        implant.put("implantDate", "2026-01-05");
        implant.put("deviceType", "DDD");
        implant.put("brand", "MEDTRONIC");
        implant.put("model", model);
        implant.put("serialNo", "DEMO-SN-EDIT-1");
        implant.put("pacingMode", "DDD");
        implant.put("hospital", "虚构示例医院");
        implant.put("doctor", "示例医生");
        implant.put("indication", "SSS");
        implant.put("leads", leads);
        body.put("implant", implant);
        body.put("reason", "电极集与型号更正（示例）");
        return body;
    }

    private Map<String, Object> keepLead(Map<?, ?> lead) {
        Map<String, Object> kept = new HashMap<>();
        kept.put("id", lead.get("id"));
        kept.put("chamber", lead.get("chamber"));
        kept.put("position", lead.get("position"));
        kept.put("model", lead.get("model"));
        kept.put("implantDate", lead.get("implantDate"));
        kept.put("status", lead.get("status"));
        return kept;
    }

    private Map<?, ?> firstLead(Map<?, ?> implant, String chamber) {
        return ((List<Map<?, ?>>) implant.get("leads"))
                .stream()
                        .filter(lead -> chamber.equals(lead.get("chamber")))
                        .findFirst()
                        .orElseThrow();
    }

    private String registeredPatientId(
            String token, String mrn, String implantDate, String deviceType, String serialNo) {
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients",
                        token,
                        registrationBody(mrn, "李王张（示例）", implantDate, deviceType, serialNo));
        assertEquals(0, code(created), "登记失败：" + created);
        return String.valueOf(data(created).get("id"));
    }

    private Map<?, ?> firstImplant(String token, String patientId) {
        Map<?, ?> list = get(implantsPath(patientId), token);
        assertEquals(0, code(list), "列表失败：" + list);
        List<?> items = (List<?>) data(list).get("items");
        assertNotNull(items.get(0));
        return (Map<?, ?>) items.get(0);
    }

    private long roleWithMenus(String roleCode, List<Long> menuIds) {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_role (code, name, built_in, status)"
                                    + " VALUES (?, 'IT 植入角色（示例）', 0, 'ENABLED')",
                            roleCode);
                    long roleId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_role WHERE code = ?", Long.class, roleCode);
                    for (Long menuId : menuIds) {
                        jdbc.update(
                                "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)",
                                roleId,
                                menuId);
                    }
                    jdbc.update(
                            "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                            roleId);
                    return roleId;
                });
    }

    private String newUser(String tenant, String username, long roleId) {
        long deptId =
                runInTenant(
                        tenant,
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'",
                                        Long.class));
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        hospitalToken(tenant, "nurse.demo", PASSWORD),
                        Map.of(
                                "username",
                                username,
                                "displayName",
                                "IT 植入用户（示例）",
                                "phone",
                                "13900001501",
                                "mainDeptId",
                                deptId,
                                "roleIds",
                                List.of(roleId)));
        assertEquals(0, code(created), "建用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));
        String token = hospitalToken(tenant, username, temporary);
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", PASSWORD))),
                "改密失败");
        return hospitalToken(tenant, username, PASSWORD);
    }

    private long wardId(String code) {
        return runInTenant(
                "demoa",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private long deptId(String code) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = ?", Long.class, code));
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

    private String adminToken() {
        return hospitalToken("demoa", "nurse.demo", PASSWORD);
    }

    private String doctorToken() {
        return hospitalToken("demoa", "doctor.demo", PASSWORD);
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
