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
 * E21-T02 端到端（验收原文：每种变更保留历史；C-ACC-06）。场景（demoa 开 cfg_feature.implant）：
 *
 * <ol>
 *   <li>升级（C-PAT-006 器械更换流程）：旧器械自动结束为已更换、结束日期 = 新植入日期；RA / RV
 *       电极沿用——同一电极行挂到新植入记录下且保留原植入日期（验收「沿用的电极趋势连续」的数据面），LV 新植日期 = 手术日期；
 *   <li>电极修正（LEAD_REVISION）：脉冲发生器不变（器械四字段一致，改类型 40001），旧记录同样结束；废弃留置电极保留在旧记录下；
 *   <li>「其他」类型须填说明（40001）；在用电极缺处置 40001；目标不在用 40900；
 *   <li>取出：在用记录置已取出、在用电极逐根显式处置；取出后无在用器械；
 *   <li>权限与隔离：无 followup:implant:edit 40300；越范围患者 40400；开关关闭 40301；写 IMPLANT_REPLACE /
 *       IMPLANT_EXPLANT 审计。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E21T02-、病区 W-IT-IMPL-R、角色
 * IT-R-IMPL-R*、用户 IT-U-IMPL-R*）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImplantReplaceIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E21T02";

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
                        com.crdp.server.AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE event_type IN"
                                        + " ('IMPLANT_CREATE','IMPLANT_REPLACE','IMPLANT_EXPLANT')"
                                        + " AND target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                        + " WHERE legacy_no LIKE ?)",
                                LEGACY + "-%");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                        jdbc.update(
                                "DELETE uw FROM sys_user_ward uw JOIN sys_user u ON u.id = uw.user_id"
                                        + " WHERE u.username LIKE 'IT-U-IMPL-R%'");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username LIKE 'IT-U-IMPL-R%'");
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                        + " WHERE r.code IN ('IT-R-IMPL-RV', 'IT-R-IMPL-RW')");
                        jdbc.update(
                                "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                        + " WHERE r.code IN ('IT-R-IMPL-RV', 'IT-R-IMPL-RW')");
                        jdbc.update(
                                "DELETE FROM sys_role WHERE code IN ('IT-R-IMPL-RV', 'IT-R-IMPL-RW')");
                        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-U-IMPL-R%'");
                        jdbc.update("DELETE FROM ward WHERE code = 'W-IT-IMPL-R'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () ->
                            jdbc.update(
                                    "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'"));
        }
    }

    // ---------------------------------------------------------------- 验收：每种变更保留历史

    @Test
    void upgradeKeepsOldDeviceAndLeadHistory() {
        enableImplant();
        String admin = adminToken();
        String patientId = registeredPatientId(admin, "IT-MRN-E21T02-01", "DEMO-SN-E21T02-1");
        Map<?, ?> first = firstImplant(admin, patientId);
        String implantId = String.valueOf(first.get("id"));
        String raLeadId = String.valueOf(leadOf(first, "RA").get("id"));
        String rvLeadId = String.valueOf(leadOf(first, "RV").get("id"));

        // 升级 CRT-D：RA / RV 沿用（保留原植入日期），LV 新植（C 规格验收示例）
        Map<?, ?> upgraded =
                post(
                        replacePath(implantId),
                        admin,
                        replaceBody(
                                version(first),
                                "UPGRADE",
                                null,
                                "2026-03-10",
                                "CRT_D",
                                "DEMO-SN-E21T02-2",
                                List.of(action(raLeadId, "KEEP"), action(rvLeadId, "KEEP")),
                                List.of(Map.of("chamber", "LV", "position", "CS"))));
        assertEquals(0, code(upgraded), "升级失败：" + upgraded);
        assertTrue((Boolean) data(upgraded).get("current"), "新器械为当前器械");

        Map<?, ?> list = get(implantsPath(patientId), admin);
        List<?> items = (List<?>) data(list).get("items");
        assertEquals(2, items.size(), "档案显示两条植入记录");
        Map<?, ?> current = (Map<?, ?>) items.get(0);
        Map<?, ?> replaced = (Map<?, ?>) items.get(1);
        assertEquals("CRT_D", current.get("deviceType"));
        assertEquals("UPGRADE", current.get("implantType"));
        assertEquals("REPLACED", replaced.get("status"), "旧器械自动标为已更换");
        assertEquals("2026-03-10", replaced.get("endDate"), "结束日期 = 新植入日期");
        assertEquals(2, ((List<?>) replaced.get("leads")).size(), "旧记录保留其电极（历史）");

        // 沿用电极：同一 id、原植入日期 2026-01-05 显示在新器械下；新植 LV 日期 = 手术日期
        Map<?, ?> keptRa = leadOf(current, "RA");
        assertEquals(raLeadId, String.valueOf(keptRa.get("id")), "沿用 = 同一电极行");
        assertEquals("2026-01-05", keptRa.get("implantDate"), "沿用电极保留原植入日期");
        assertEquals("2026-03-10", leadOf(current, "LV").get("implantDate"), "新植电极日期 = 手术日期");
        assertEquals("ACTIVE", leadOf(current, "LV").get("status"), "新植电极在用");

        // 审计：IMPLANT_REPLACE
        Long audits =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                                + " 'IMPLANT_REPLACE' AND target = ?",
                                        Long.class,
                                        "PATIENT:" + patientId));
        assertEquals(1L, audits, "写 IMPLANT_REPLACE 审计");
    }

    @Test
    void leadRevisionKeepsGeneratorAndAbandonedLeadHistory() {
        enableImplant();
        String admin = adminToken();
        String patientId = registeredPatientId(admin, "IT-MRN-E21T02-02", "DEMO-SN-E21T02-3");
        Map<?, ?> first = firstImplant(admin, patientId);
        String implantId = String.valueOf(first.get("id"));
        String raLeadId = String.valueOf(leadOf(first, "RA").get("id"));
        String rvLeadId = String.valueOf(leadOf(first, "RV").get("id"));

        // 器械字段不一致（改类型）→ 40001（电极修正不改变脉冲发生器）
        assertEquals(
                40001,
                code(
                        post(
                                replacePath(implantId),
                                admin,
                                replaceBody(
                                        version(first),
                                        "LEAD_REVISION",
                                        null,
                                        "2026-04-01",
                                        "CRT_D",
                                        "DEMO-SN-E21T02-3",
                                        List.of(action(raLeadId, "KEEP"), action(rvLeadId, "KEEP")),
                                        List.of()))),
                "电极修正不改变脉冲发生器");

        // 电极修正：RA 沿用、RV 废弃留置、新植 RV 间隔电极（新电极从修正日期起比较参数）
        Map<?, ?> revised =
                post(
                        replacePath(implantId),
                        admin,
                        replaceBody(
                                version(first),
                                "LEAD_REVISION",
                                null,
                                "2026-04-01",
                                "DDD",
                                "DEMO-SN-E21T02-3",
                                List.of(action(raLeadId, "KEEP"), action(rvLeadId, "ABANDON")),
                                List.of(Map.of("chamber", "RV", "position", "RV_SEPTUM"))));
        assertEquals(0, code(revised), "电极修正失败：" + revised);
        assertEquals("LEAD_REVISION", data(revised).get("implantType"));

        Map<?, ?> list = get(implantsPath(patientId), admin);
        List<?> items = (List<?>) data(list).get("items");
        Map<?, ?> current = (Map<?, ?>) items.get(0);
        Map<?, ?> old = (Map<?, ?>) items.get(1);
        assertEquals("REPLACED", old.get("status"), "电极修正同样结束旧记录（SW-5 新增电极修正记录）");
        assertEquals("2026-04-01", old.get("endDate"));
        // 新记录下：RA 沿用（原日期）+ 新 RV；旧记录下：RA 历史关联 + RV 废弃留置
        assertEquals("2026-01-05", leadOf(current, "RA").get("implantDate"));
        assertEquals("2026-04-01", leadOf(current, "RV").get("implantDate"));
        assertEquals("ABANDONED", leadOf(old, "RV").get("status"), "旧电极标废弃留置并保留在旧记录下");
        assertEquals("ACTIVE", leadOf(old, "RA").get("status"));
    }

    @Test
    void otherTypeRequiresNoteAndMissingLeadActionRejected() {
        enableImplant();
        String admin = adminToken();
        String patientId = registeredPatientId(admin, "IT-MRN-E21T02-03", "DEMO-SN-E21T02-4");
        Map<?, ?> first = firstImplant(admin, patientId);
        String implantId = String.valueOf(first.get("id"));
        String raLeadId = String.valueOf(leadOf(first, "RA").get("id"));
        String rvLeadId = String.valueOf(leadOf(first, "RV").get("id"));

        assertEquals(
                40001,
                code(
                        post(
                                replacePath(implantId),
                                admin,
                                replaceBody(
                                        version(first),
                                        "OTHER",
                                        null,
                                        "2026-03-10",
                                        "DDD",
                                        "DEMO-SN-E21T02-5",
                                        List.of(action(raLeadId, "KEEP"), action(rvLeadId, "KEEP")),
                                        List.of()))),
                "「其他」类型须填说明");
        assertEquals(
                40001,
                code(
                        post(
                                replacePath(implantId),
                                admin,
                                replaceBody(
                                        version(first),
                                        "REPLACE",
                                        null,
                                        "2026-03-10",
                                        "DDD",
                                        "DEMO-SN-E21T02-5",
                                        List.of(action(raLeadId, "KEEP")),
                                        List.of()))),
                "在用电极必须逐根显式处置");
        // 补说明后成功，note 回读
        Map<?, ?> other =
                post(
                        replacePath(implantId),
                        admin,
                        replaceBody(
                                version(first),
                                "OTHER",
                                "电极脱位再次手术（示例）",
                                "2026-03-10",
                                "DDD",
                                "DEMO-SN-E21T02-5",
                                List.of(action(raLeadId, "KEEP"), action(rvLeadId, "EXTRACT")),
                                List.of()));
        assertEquals(0, code(other), "「其他」带说明可保存：" + other);
        assertEquals("电极脱位再次手术（示例）", data(other).get("note"));
        // 已拔除电极保留在旧记录下（历史）
        List<?> after = (List<?>) data(get(implantsPath(patientId), admin)).get("items");
        Map<?, ?> oldRecord = (Map<?, ?>) after.get(1);
        assertEquals("EXTRACTED", leadOf(oldRecord, "RV").get("status"), "旧电极标已拔除");
        assertEquals("OTHER", ((Map<?, ?>) after.get(0)).get("implantType"));
    }

    @Test
    void explantEndsDeviceAndDisposesLeads() {
        enableImplant();
        String admin = adminToken();
        String patientId = registeredPatientId(admin, "IT-MRN-E21T02-04", "DEMO-SN-E21T02-6");
        Map<?, ?> first = firstImplant(admin, patientId);
        String implantId = String.valueOf(first.get("id"));
        String raLeadId = String.valueOf(leadOf(first, "RA").get("id"));
        String rvLeadId = String.valueOf(leadOf(first, "RV").get("id"));

        // 在用电极未处置 → 40001；KEEP 不属于取出 → 40001
        Map<String, Object> bare = new HashMap<>();
        bare.put("baseVersion", version(first));
        bare.put("date", "2026-05-01");
        bare.put("reason", "感染取出（示例）");
        bare.put("leadActions", List.of());
        assertEquals(40001, code(post(explantPath(implantId), admin, bare)), "在用电极须逐根处置");
        Map<String, Object> keep = new HashMap<>(bare);
        keep.put("leadActions", List.of(action(raLeadId, "KEEP"), action(rvLeadId, "EXTRACT")));
        assertEquals(40001, code(post(explantPath(implantId), admin, keep)), "取出不产生沿用电极");

        Map<String, Object> body = new HashMap<>(bare);
        body.put("leadActions", List.of(action(raLeadId, "EXTRACT"), action(rvLeadId, "ABANDON")));
        Map<?, ?> explanted = post(explantPath(implantId), admin, body);
        assertEquals(0, code(explanted), "取出失败：" + explanted);
        assertEquals("EXPLANTED", data(explanted).get("status"));
        assertEquals("2026-05-01", data(explanted).get("endDate"));
        assertEquals(false, data(explanted).get("current"));

        // 取出后无在用器械；再次取出（已不在用）→ 40900
        Long activeCount =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM pt_implant WHERE patient_id = ?"
                                                + " AND status = 'ACTIVE'",
                                        Long.class,
                                        Long.valueOf(patientId)));
        assertEquals(0L, activeCount, "取出后无在用器械");
        Map<?, ?> again = post(explantPath(implantId), admin, body);
        assertEquals(40900, code(again), "只有在用器械可以取出");
    }

    // ---------------------------------------------------------------- 权限 / 范围 / 开关

    @Test
    void permissionScopeAndFeatureGatesAreEnforced() {
        enableImplant();
        String admin = adminToken();
        String patientId = registeredPatientId(admin, "IT-MRN-E21T02-05", "DEMO-SN-E21T02-7");
        Map<?, ?> first = firstImplant(admin, patientId);
        String implantId = String.valueOf(first.get("id"));
        String raLeadId = String.valueOf(leadOf(first, "RA").get("id"));
        String rvLeadId = String.valueOf(leadOf(first, "RV").get("id"));

        // 只授患者列表（21）→ 更换 40300（写按钮 228 followup:implant:edit）
        long viewRoleId = roleWithMenus("IT-R-IMPL-RV", List.of(21L));
        String viewToken = newUser("IT-U-IMPL-RV", viewRoleId);
        assertEquals(
                40300,
                code(
                        post(
                                replacePath(implantId),
                                viewToken,
                                replaceBody(
                                        version(first),
                                        "REPLACE",
                                        null,
                                        "2026-03-10",
                                        "DDD",
                                        "DEMO-SN-E21T02-8",
                                        List.of(action(raLeadId, "KEEP"), action(rvLeadId, "KEEP")),
                                        List.of()))),
                "无 followup:implant:edit 40300");

        // 越范围：有 228（implant:edit）但病区范围只有 W-DEMO-1 的用户，对别病区患者 40400
        // （doctor.demo 未授 228，先撞 40300；故另建 WARD 范围 + 21/228 角色）
        long wardScopedRole = wardScopedRoleWithEdit();
        newUser("IT-U-IMPL-RW", wardScopedRole);
        bindWard("IT-U-IMPL-RW", "W-DEMO-1");
        // 范围快照随登录构建（HospitalDirectory 读 sys_user_ward），绑病区后重新登录
        String wardScopedToken = hospitalToken("demoa", "IT-U-IMPL-RW", PASSWORD);
        long otherWard = createWard("W-IT-IMPL-R", "示例 IT 更换隔离病区（示例）");
        Map<String, Object> otherBody =
                registrationBodyTo("IT-MRN-E21T02-06", "周吴郑（示例）", otherWard, "DEMO-SN-E21T02-9");
        Map<?, ?> other = post("/api/hospital/followup/patients", admin, otherBody);
        assertEquals(0, code(other), "别病区登记失败：" + other);
        String otherPatientId = String.valueOf(data(other).get("id"));
        Map<?, ?> otherFirst = firstImplant(admin, otherPatientId);
        assertEquals(
                40400,
                code(
                        post(
                                replacePath(String.valueOf(otherFirst.get("id"))),
                                wardScopedToken,
                                replaceBody(
                                        version(otherFirst),
                                        "REPLACE",
                                        null,
                                        "2026-03-10",
                                        "DDD",
                                        "DEMO-SN-E21T02-10",
                                        List.of(
                                                action(
                                                        String.valueOf(
                                                                leadOf(otherFirst, "RA").get("id")),
                                                        "KEEP"),
                                                action(
                                                        String.valueOf(
                                                                leadOf(otherFirst, "RV").get("id")),
                                                        "KEEP")),
                                        List.of()))),
                "越范围 40400（不暴露对象存在）");

        // 开关关闭 → 40301
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'"));
        assertEquals(
                40301, code(post(replacePath(implantId), admin, bare(implantId))), "开关关闭 40301");
    }

    // ---------------------------------------------------------------- 辅助

    private void enableImplant() {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'implant'"));
    }

    private String implantsPath(String patientId) {
        return "/api/hospital/followup/patients/" + patientId + "/implants";
    }

    private String replacePath(String implantId) {
        return "/api/hospital/followup/implants/" + implantId + "/replace";
    }

    private String explantPath(String implantId) {
        return "/api/hospital/followup/implants/" + implantId + "/explant";
    }

    private static Map<String, Object> action(String leadId, String action) {
        Map<String, Object> row = new HashMap<>();
        row.put("leadId", leadId);
        row.put("action", action);
        return row;
    }

    /** 更换请求体。 */
    private Map<String, Object> replaceBody(
            int baseVersion,
            String type,
            String note,
            String date,
            String deviceType,
            String serialNo,
            List<Map<String, Object>> leadActions,
            List<Map<String, Object>> newLeads) {
        Map<String, Object> body = new HashMap<>();
        body.put("baseVersion", baseVersion);
        body.put("type", type);
        body.put("note", note);
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantDate", date);
        implant.put("deviceType", deviceType);
        implant.put("brand", "MEDTRONIC");
        implant.put("model", "示例型号 S-01");
        implant.put("serialNo", serialNo);
        implant.put("pacingMode", "DDD");
        implant.put("hospital", "虚构示例医院");
        implant.put("doctor", "示例医生");
        implant.put("indication", "SSS");
        body.put("implant", implant);
        body.put("leadActions", leadActions);
        body.put("newLeads", newLeads);
        return body;
    }

    private Map<String, Object> bare(String implantId) {
        Map<String, Object> body = new HashMap<>();
        body.put("baseVersion", 0);
        body.put("type", "REPLACE");
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantDate", "2026-03-10");
        implant.put("deviceType", "DDD");
        body.put("implant", implant);
        body.put("leadActions", List.of());
        body.put("newLeads", List.of());
        return body;
    }

    private Map<String, Object> registrationBodyTo(
            String mrn, String name, long wardId, String serialNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", mrn);
        body.put("name", name);
        body.put("gender", "M");
        body.put("birthDate", "1955-01-01");
        body.put("deptId", deptId());
        body.put("wardId", wardId);
        body.put("legacyNo", LEGACY + "-" + mrn);
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantType", "FIRST");
        implant.put("implantDate", "2026-01-05");
        implant.put("deviceType", "DDD");
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

    private String registeredPatientId(String token, String mrn, String serialNo) {
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients",
                        token,
                        registrationBodyTo(mrn, "冯陈褚（示例）", wardId("W-DEMO-1"), serialNo));
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

    private static Map<?, ?> leadOf(Map<?, ?> implant, String chamber) {
        return ((List<Map<?, ?>>) implant.get("leads"))
                .stream()
                        .filter(lead -> chamber.equals(lead.get("chamber")))
                        .findFirst()
                        .orElseThrow();
    }

    private static int version(Map<?, ?> implant) {
        return ((Number) implant.get("version")).intValue();
    }

    private long roleWithMenus(String roleCode, List<Long> menuIds) {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_role (code, name, built_in, status)"
                                    + " VALUES (?, 'IT 更换角色（示例）', 0, 'ENABLED')",
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

    /** 病区范围 + 患者查看（21）+ 植入编辑（228）角色（用户再经 sys_user_ward 绑 W-DEMO-1）。 */
    private long wardScopedRoleWithEdit() {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_role (code, name, built_in, status)"
                                    + " VALUES ('IT-R-IMPL-RW', 'IT 更换病区角色（示例）', 0, 'ENABLED')");
                    long roleId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_role WHERE code = 'IT-R-IMPL-RW'",
                                    Long.class);
                    jdbc.update(
                            "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 21)", roleId);
                    jdbc.update(
                            "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 228)", roleId);
                    jdbc.update(
                            "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'WARD')",
                            roleId);
                    return roleId;
                });
    }

    private String newUser(String username, long roleId) {
        return newUser(username, roleId, "1390000160" + (username.endsWith("W") ? "2" : "1"));
    }

    private String newUser(String username, long roleId, String phone) {
        long dept =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'",
                                        Long.class));
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        hospitalToken("demoa", "nurse.demo", PASSWORD),
                        Map.of(
                                "username",
                                username,
                                "displayName",
                                "IT 更换用户（示例）",
                                "phone",
                                phone,
                                "mainDeptId",
                                dept,
                                "roleIds",
                                List.of(roleId)));
        assertEquals(0, code(created), "建用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));
        String token = hospitalToken("demoa", username, temporary);
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", PASSWORD))),
                "改密失败");
        return hospitalToken("demoa", username, PASSWORD);
    }

    private long wardId(String code) {
        return runInTenant(
                "demoa",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private long deptId() {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'", Long.class));
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

    /** 把用户绑定到病区（WARD 范围的数据面，登录时读 sys_user_ward）。 */
    private void bindWard(String username, String wardCode) {
        runInTenant(
                "demoa",
                () -> {
                    long userId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_user WHERE username = ?",
                                    Long.class,
                                    username);
                    long ward =
                            jdbc.queryForObject(
                                    "SELECT id FROM ward WHERE code = ?", Long.class, wardCode);
                    jdbc.update(
                            "INSERT INTO sys_user_ward (user_id, ward_id) VALUES (?, ?)",
                            userId,
                            ward);
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
