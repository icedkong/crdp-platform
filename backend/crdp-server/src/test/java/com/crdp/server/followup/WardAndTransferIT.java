package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
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
 * E13-T08 端到端（tasks.csv 验收：转病区后原病区医生不可见）。场景：
 *
 * <ol>
 *   <li>病区字典（A-WARD-001）：新建（类型 / 别名 / 排序 / 默认负责人）、编码唯一 40902、编辑（编码不可改）、审计；
 *   <li>病区停用（A-WARD-008）：病区下仍有在册患者时不能停用（40901 + DENIED 审计），转出后可停用；
 *   <li>转病区（A-WARD-005，验收原文）：W-DEMO-1 医生把患者转入新病区后——本人（只负责原病区）立即 不可见该患者 （summary / 检索 / 转病区历史全部
 *       40400 或空），管理员可见且 pt_ward_history 保留历史、PATIENT_TRANSFER 审计；
 *   <li>校验与权限：同病区 / 停用目标 / 缺原因 40001；无 followup:ward:edit 40300、无 followup:patient:transfer
 *       40300；followup 模块关闭 40301；双租户病区字典互不可见。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（legacy_no 前缀 IT-E13T08-、病区 W-IT-T8-、角色
 * IT-R-T8-NOTR、用户 IT-U-T8-NT）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WardAndTransferIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E13T08";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE event_type IN"
                                        + " ('PATIENT_TRANSFER','WARD_CREATE','WARD_UPDATE','WARD_DISABLE','WARD_ENABLE')"
                                        + " AND (target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                        + " WHERE legacy_no LIKE 'IT-E13T08-%')"
                                        + " OR target IN (SELECT CONCAT('WARD:', id) FROM ward"
                                        + " WHERE code LIKE 'W-IT-T8-%')"
                                        + " OR detail LIKE '%转病区被拒绝%')");
                        jdbc.update(
                                "DELETE h FROM pt_ward_history h JOIN pt_patient p ON p.id = h.patient_id"
                                        + " WHERE p.legacy_no LIKE 'IT-E13T08-%'");
                        jdbc.update(
                                "DELETE h FROM pt_dept_history h JOIN pt_patient p ON p.id = h.patient_id"
                                        + " WHERE p.legacy_no LIKE 'IT-E13T08-%'");
                        // E21-T21 C 口径登记同建首条植入记录（C-PAT-001）：连带清理电极与器械行
                        jdbc.update(
                                "DELETE il FROM pt_implant_lead il"
                                        + " JOIN pt_implant i ON i.id = il.implant_id"
                                        + " JOIN pt_patient p ON p.id = i.patient_id"
                                        + " WHERE p.legacy_no LIKE 'IT-E13T08-%'");
                        jdbc.update(
                                "DELETE l FROM pt_lead l"
                                        + " JOIN pt_patient p ON p.id = l.patient_id"
                                        + " WHERE p.legacy_no LIKE 'IT-E13T08-%'");
                        jdbc.update(
                                "DELETE i FROM pt_implant i"
                                        + " JOIN pt_patient p ON p.id = i.patient_id"
                                        + " WHERE p.legacy_no LIKE 'IT-E13T08-%'");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE 'IT-E13T08-%'");
                        // E21-T21 用例开启的开关还原为种子默认（关闭），A / B 口径不受影响
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0 WHERE feature_code = 'implant'");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username = 'IT-U-T8-NT'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u"
                                        + " ON u.id = ph.user_id WHERE u.username = 'IT-U-T8-NT'");
                        jdbc.update("DELETE FROM sys_user WHERE username = 'IT-U-T8-NT'");
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                        + " WHERE r.code = 'IT-R-T8-NOTR'");
                        jdbc.update(
                                "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                        + " WHERE r.code = 'IT-R-T8-NOTR'");
                        jdbc.update("DELETE FROM sys_role WHERE code = 'IT-R-T8-NOTR'");
                        jdbc.update("DELETE FROM ward WHERE code LIKE 'W-IT-T8-%'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 病区字典（A-WARD-001）

    @Test
    void wardCrudStoresDictionaryAndWritesAudit() {
        String admin = adminToken();
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/wards",
                        admin,
                        Map.of(
                                "code", "W-IT-T8-A",
                                "name", "示例 IT 心外中心（示例）",
                                "wardType", "center",
                                "aliases", List.of("心外中心", " 心外中心 ", "心外一病区"),
                                "sortNo", 60));
        assertEquals(0, codeOf(created), "新建病区失败：" + created);
        Map<?, ?> row = data(created);
        assertEquals("CENTER", row.get("wardType"), "类型归一为大写枚举");
        assertEquals(List.of("心外中心", "心外一病区"), row.get("aliases"), "别名去空白、去重、保序");
        assertEquals("ENABLED", row.get("status"), "新建即启用");
        long wardId = Long.parseLong(String.valueOf(row.get("id")));

        Map<?, ?> list = get("/api/hospital/followup/wards", admin);
        assertEquals(0, codeOf(list));
        assertTrue(
                ((List<Map<?, ?>>) data(list).get("items"))
                        .stream().anyMatch(item -> "W-IT-T8-A".equals(item.get("code"))),
                "列表包含新建病区");

        // 编码唯一（A-WARD-001「本院唯一」）
        assertEquals(
                40902,
                codeOf(
                        post(
                                "/api/hospital/followup/wards",
                                admin,
                                Map.of(
                                        "code",
                                        "W-IT-T8-A",
                                        "name",
                                        "重复编码（示例）",
                                        "wardType",
                                        "WARD"))),
                "重复编码 40902");
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/followup/wards",
                                admin,
                                Map.of(
                                        "code",
                                        "W-IT-T8-X",
                                        "name",
                                        "类型错误（示例）",
                                        "wardType",
                                        "CLINIC"))),
                "类型只支持病区 / 中心");

        // 编辑：名称与别名可改，编码不可改
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/followup/wards/" + wardId,
                                admin,
                                Map.of(
                                        "code", "W-IT-T8-A",
                                        "name", "示例 IT 心外中心（更名）",
                                        "wardType", "CENTER",
                                        "aliases", List.of("心外中心"),
                                        "sortNo", 61))),
                "编辑病区失败");
        assertEquals(
                40001,
                codeOf(
                        put(
                                "/api/hospital/followup/wards/" + wardId,
                                admin,
                                Map.of(
                                        "code", "W-IT-T8-OTHER",
                                        "name", "示例 IT 心外中心（更名）",
                                        "wardType", "CENTER"))),
                "编码创建后不可改（A-WARD-001）");

        // 成员与患者数（pages.md §10）：W-DEMO-1 有种子分配的演示医生
        Map<?, ?> wardList = get("/api/hospital/followup/wards", admin);
        Map<?, ?> demoWard =
                ((List<Map<?, ?>>) data(wardList).get("items"))
                        .stream()
                                .filter(item -> "W-DEMO-1".equals(item.get("code")))
                                .findFirst()
                                .orElse(null);
        assertNotNull(demoWard, "种子病区在列");
        assertTrue(((Number) demoWard.get("memberCount")).longValue() >= 1, "成员数含演示医生");
        Map<?, ?> members =
                get("/api/hospital/followup/wards/" + demoWard.get("id") + "/members", admin);
        assertEquals(0, codeOf(members));
        assertTrue(((Number) data(members).get("patientCount")).longValue() >= 0);

        runInTenant(
                "demoa",
                () -> {
                    Integer audits =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_log WHERE event_type = 'WARD_CREATE'"
                                            + " AND target = ? AND result = 'SUCCESS'",
                                    Integer.class,
                                    "WARD:" + wardId);
                    assertEquals(1, audits, "新建病区写 WARD_CREATE 审计");
                });
    }

    // ---------------------------------------------------------------- 病区停用（A-WARD-008）

    @Test
    void disableBlockedUntilPatientsTransferredOut() {
        String admin = adminToken();
        long wardB = createWard("W-IT-T8-B", "示例 IT 转出病区（示例）");
        long wardDemo = wardIdByCode("W-DEMO-1");
        String mrn = "IT-MRN-T8021";
        assertEquals(
                0,
                codeOf(post("/api/hospital/followup/patients", admin, body(mrn, wardB))),
                "在 B 病区登记患者");
        long patientId = patientIdByMrn(mrn);

        assertEquals(
                40901,
                codeOf(
                        put(
                                "/api/hospital/followup/wards/" + wardB + "/status",
                                admin,
                                Map.of("status", "DISABLED"))),
                "病区下仍有在册患者时不能停用（A-WARD-008）");

        // 转出后可停用；停用后不再进转病区目标
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/" + patientId + "/transfer",
                                admin,
                                Map.of("wardId", wardDemo, "reason", "病区停用前转出（示例）"))),
                "转出患者失败");
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/followup/wards/" + wardB + "/status",
                                admin,
                                Map.of("status", "DISABLED"))),
                "转出后停用成功");
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/" + patientId + "/transfer",
                                admin,
                                Map.of("wardId", wardB, "reason", "转入已停用病区（示例）"))),
                "停用病区不能转入");

        runInTenant(
                "demoa",
                () -> {
                    Integer denied =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                            + " 'WARD_DISABLE' AND result = 'DENIED' AND error_code"
                                            + " = 40901",
                                    Integer.class);
                    assertTrue(denied >= 1, "被阻止的停用写 DENIED 审计");
                });
    }

    // ---------------------------------------------------------------- 转病区与可见性（A-WARD-005，tasks.csv
    // 验收）

    @Test
    void transferHidesPatientFromOriginalWardDoctor() {
        String doctor = doctorToken();
        String admin = adminToken();
        long wardA = wardIdByCode("W-DEMO-1");
        long wardC = createWard("W-IT-T8-C", "示例 IT 转入病区（示例）");
        String mrn = "IT-MRN-T8031";

        // 演示医生只负责 W-DEMO-1（种子 sys_user_ward），在其病区登记患者
        assertEquals(
                0,
                codeOf(post("/api/hospital/followup/patients", doctor, body(mrn, wardA))),
                "医生在自己病区登记患者失败");
        long patientId = patientIdByMrn(mrn);
        assertEquals(
                0,
                codeOf(get("/api/hospital/followup/patients/" + patientId + "/summary", doctor)),
                "转病区前医生可见患者");

        // 转病区：医生本人操作（A-WARD-005「填写原因后转到新病区」）
        Map<?, ?> transferred =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/transfer",
                        doctor,
                        Map.of("wardId", wardC, "reason", "患者术后转入本病区（示例）"));
        assertEquals(0, codeOf(transferred), "转病区失败：" + transferred);
        assertEquals("示例 IT 转入病区（示例）", data(transferred).get("wardName"), "响应带新病区名称");

        // 验收原文：转病区后原病区医生不可见（summary / 检索 / 历史，立即生效，无需刷新会话）
        assertEquals(
                40400,
                codeOf(get("/api/hospital/followup/patients/" + patientId + "/summary", doctor)),
                "原病区医生立即看不到患者（summary 40400）");
        Map<String, Object> search = new HashMap<>();
        search.put("keyword", mrn);
        Map<?, ?> result = post("/api/hospital/followup/patients/search", doctor, search);
        assertEquals(0, codeOf(result));
        assertEquals(0, ((Number) data(result).get("total")).intValue(), "原病区医生检索不到该患者（病案号精确匹配）");
        assertEquals(
                40400,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/" + patientId + "/ward-history",
                                doctor)),
                "原病区医生查不到转病区历史（不暴露存在）");

        // 全院管理员可见且历史保留（A-WARD-005「保留转病区历史」）
        assertEquals(
                0,
                codeOf(get("/api/hospital/followup/patients/" + patientId + "/summary", admin)),
                "管理员（全部病区）仍可见患者");
        Map<?, ?> history =
                get("/api/hospital/followup/patients/" + patientId + "/ward-history", admin);
        assertEquals(0, codeOf(history));
        List<?> items = (List<?>) data(history).get("items");
        assertEquals(1, items.size(), "转病区历史一条");
        Map<?, ?> entry = (Map<?, ?>) items.get(0);
        assertEquals("虚构病区一", entry.get("fromWardName"));
        assertEquals("示例 IT 转入病区（示例）", entry.get("toWardName"));
        assertEquals("患者术后转入本病区（示例）", entry.get("reason"));

        runInTenant(
                "demoa",
                () -> {
                    Map<String, Object> patient =
                            jdbc.queryForMap(
                                    "SELECT ward_id, version FROM pt_patient WHERE id = ?",
                                    patientId);
                    assertEquals(wardC, ((Number) patient.get("ward_id")).longValue(), "归属列已改");
                    assertTrue(((Number) patient.get("version")).longValue() >= 1, "乐观锁版本递增");
                    Integer historyRows =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM pt_ward_history WHERE patient_id = ?"
                                            + " AND from_ward_id = ? AND to_ward_id = ?",
                                    Integer.class,
                                    patientId,
                                    wardA,
                                    wardC);
                    assertEquals(1, historyRows, "pt_ward_history 只追加一行");
                    Integer audits =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                            + " 'PATIENT_TRANSFER' AND target = ? AND result ="
                                            + " 'SUCCESS'",
                                    Integer.class,
                                    "PATIENT:" + patientId);
                    assertEquals(1, audits, "转病区写 PATIENT_TRANSFER 审计");
                });
    }

    // ---------------------------------------------------------------- 转科室（E21-T21 / C-WARD-006）

    /**
     * E21-T21 端到端（tasks.csv 验收：科室与病区条件 OR 生效）。demoa 开 cfg_feature.implant = 医院 C 口径，用 V137 预置的 C 科室
     * / 病区 / 用户（demoa 库同样落位，OQ-93 ②）：
     *
     * <ol>
     *   <li>登记 C 患者（心内科 + 心内一病区）：负责科室的科主任与负责病区的医生都可见（两分支 OR）；
     *   <li>转科室（心内 → 心外，同一 transfer 入口携带 deptId）：原科室科主任立即不可见、新科室科主任可见；
     *   <li>病区分支不受转科室影响：心内一病区医生仍可见（OR 各分支独立生效——验收原文）；
     *   <li>科室历史（pt_dept_history）与 PATIENT_TRANSFER 审计保留；demob（implant 关闭 = A / B 口径）携带 deptId →
     *       40001。
     * </ol>
     */
    @Test
    void deptTransferKeepsWardBranchVisibleAndHidesOldDeptUsers() {
        String admin = adminToken();
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'implant'"));
        long cardioDept = deptIdByCode("DEPT-C-CARDIO");
        long cvsDept = deptIdByCode("DEPT-C-CVS");
        long card1Ward = wardIdByCode("W-C-CARD1");
        String mrn = "IT-MRN-T8051";

        // C 口径登记（implant 开启 → 最小必填含出生日期、科室、病区与首条植入记录，C-PAT-001）
        Map<String, Object> implant = new HashMap<>();
        implant.put("implantType", "FIRST");
        implant.put("implantDate", "2026-09-08");
        implant.put("deviceType", "DDD");
        implant.put("brand", "MEDTRONIC");
        implant.put("model", "示例型号 T21-01");
        implant.put("serialNo", "DEMO-SN-T21-01");
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
        body.put("medicalRecordNo", mrn);
        body.put("name", "严一心（C 示例）");
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
        body.put("deptId", cardioDept);
        body.put("wardId", card1Ward);
        body.put("legacyNo", LEGACY + "-" + mrn);
        body.put("implant", implant);
        assertEquals(0, codeOf(post("/api/hospital/followup/patients", admin, body)), "C 登记失败");
        long patientId = patientIdByMrn(mrn);

        String cardioDirector = hospitalToken("demoa", "c.cardio.dir", PASSWORD);
        String card1Doctor = hospitalToken("demoa", "c.card1.doc01", PASSWORD);
        String cvsDirector = hospitalToken("demoa", "c.cvs.dir", PASSWORD);
        assertEquals(
                0,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/" + patientId + "/summary",
                                cardioDirector)),
                "转科前：负责心内科的科主任可见（科室分支）");
        assertEquals(
                0,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/" + patientId + "/summary",
                                card1Doctor)),
                "转科前：负责心内一病区的医生可见（病区分支，与科室分支 OR）");
        assertEquals(
                40400,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/" + patientId + "/summary",
                                cvsDirector)),
                "转科前：他科室科主任不可见");

        // 转科室：同一入口携带 deptId（C-WARD-006「沿用 A-WARD-005」；病区不变）
        Map<?, ?> transferred =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/transfer",
                        admin,
                        Map.of("deptId", cvsDept, "reason", "患者转科至心外科（示例）"));
        assertEquals(0, codeOf(transferred), "转科室失败：" + transferred);
        assertEquals("心外科（C 示例）", data(transferred).get("deptName"), "响应带新科室名称");
        assertEquals(
                String.valueOf(card1Ward),
                String.valueOf(data(transferred).get("wardId")),
                "病区不变原样回显");

        // 验收原文：科室与病区条件 OR 生效——转科室只影响科室分支
        assertEquals(
                40400,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/" + patientId + "/summary",
                                cardioDirector)),
                "转科室后原科室科主任立即不可见");
        assertEquals(
                0,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/" + patientId + "/summary",
                                card1Doctor)),
                "转科室后病区医生仍可见（病区分支不受转科室影响，OR 各自独立）");
        assertEquals(
                0,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/" + patientId + "/summary",
                                cvsDirector)),
                "新科室科主任可见");

        // 科室历史（C-WARD-006「保留转科历史」）
        Map<?, ?> history =
                get("/api/hospital/followup/patients/" + patientId + "/dept-history", admin);
        assertEquals(0, codeOf(history));
        List<?> items = (List<?>) data(history).get("items");
        assertEquals(1, items.size(), "转科室历史一条");
        Map<?, ?> entry = (Map<?, ?>) items.get(0);
        assertEquals("心内科（C 示例）", entry.get("fromDeptName"));
        assertEquals("心外科（C 示例）", entry.get("toDeptName"));
        assertEquals("患者转科至心外科（示例）", entry.get("reason"));
        assertEquals(
                40400,
                codeOf(
                        get(
                                "/api/hospital/followup/patients/" + patientId + "/dept-history",
                                cardioDirector)),
                "原科室科主任查不到转科室历史（不暴露存在）");

        runInTenant(
                "demoa",
                () -> {
                    Map<String, Object> patient =
                            jdbc.queryForMap(
                                    "SELECT dept_id, ward_id, version FROM pt_patient WHERE id = ?",
                                    patientId);
                    assertEquals(cvsDept, ((Number) patient.get("dept_id")).longValue(), "科室归属列已改");
                    assertEquals(
                            card1Ward, ((Number) patient.get("ward_id")).longValue(), "病区归属列未动");
                    assertTrue(((Number) patient.get("version")).longValue() >= 1, "乐观锁版本递增");
                    Integer deptHistoryRows =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM pt_dept_history WHERE patient_id = ?"
                                            + " AND from_dept_id = ? AND to_dept_id = ?",
                                    Integer.class,
                                    patientId,
                                    cardioDept,
                                    cvsDept);
                    assertEquals(1, deptHistoryRows, "pt_dept_history 只追加一行");
                    Integer wardHistoryRows =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM pt_ward_history WHERE patient_id = ?",
                                    Integer.class,
                                    patientId);
                    assertEquals(0, wardHistoryRows, "只转科室不写病区历史（两维度独立）");
                });

        // demob（implant 关闭 = A / B 口径）：携带 deptId → 40001（转科室为 C 口径）
        String demobAdmin = hospitalToken("demob", "nurse.demo", PASSWORD);
        String mrnB = "IT-MRN-T8052";
        Map<String, Object> bodyB = new HashMap<>();
        bodyB.put("medicalRecordNo", mrnB);
        bodyB.put("name", "测试患者（示例）");
        bodyB.put("gender", "M");
        bodyB.put("wardId", wardIdByCodeIn("demob", "W-DEMO-1"));
        bodyB.put("surgeryDate", "2026-10-08");
        bodyB.put("admissionDate", "2026-10-01");
        bodyB.put("dischargeDate", "2026-10-15");
        bodyB.put("dischargeStatus", 1);
        bodyB.put("legacyNo", LEGACY + "-" + mrnB);
        assertEquals(
                0,
                codeOf(post("/api/hospital/followup/patients", demobAdmin, bodyB)),
                "demob 登记失败");
        long patientB =
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM pt_patient WHERE legacy_no = ?",
                                        Long.class,
                                        LEGACY + "-" + mrnB));
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/" + patientB + "/transfer",
                                demobAdmin,
                                Map.of("deptId", 1L, "reason", "A 口径不接收转科室（示例）"))),
                "implant 开关关闭携带 deptId → 40001");
    }

    // ---------------------------------------------------------------- 校验与权限

    @Test
    void transferValidationAndPermissions() {
        String admin = adminToken();
        String doctor = doctorToken();
        long wardA = wardIdByCode("W-DEMO-1");
        String mrn = "IT-MRN-T8041";
        assertEquals(0, codeOf(post("/api/hospital/followup/patients", admin, body(mrn, wardA))));
        long patientId = patientIdByMrn(mrn);

        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/" + patientId + "/transfer",
                                admin,
                                Map.of("wardId", wardA, "reason", "同病区（示例）"))),
                "同病区无需转病区");
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/" + patientId + "/transfer",
                                admin,
                                Map.of("wardId", 9_999_999L, "reason", "目标不存在（示例）"))),
                "目标病区不存在");
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/" + patientId + "/transfer",
                                admin,
                                Map.of("wardId", wardA))),
                "原因必填（A-WARD-005）");
        assertEquals(
                40400,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/9999999/transfer",
                                admin,
                                Map.of("wardId", wardA, "reason", "越范围（示例）"))),
                "不存在的患者不暴露存在");

        // 无 followup:ward:edit（演示医生未授病区管理菜单）：字典接口 40300
        assertEquals(
                40300,
                codeOf(get("/api/hospital/followup/wards", doctor)),
                "病区字典维护需 followup:ward:edit");

        // 无 followup:patient:transfer：只有患者列表权限的角色转病区 40300
        long roleId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_role (code, name, built_in, status)"
                                            + " VALUES ('IT-R-T8-NOTR', 'IT 无转病区权限（示例）', 0,"
                                            + " 'ENABLED')");
                            long id =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_role WHERE code = 'IT-R-T8-NOTR'",
                                            Long.class);
                            jdbc.update(
                                    "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 21)",
                                    id);
                            jdbc.update(
                                    "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                                    id);
                            return id;
                        });
        long deptId = deptIdByCode("DEPT-CARDIO");
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        admin,
                        Map.of(
                                "username", "IT-U-T8-NT",
                                "displayName", "IT 无转病区权限用户（示例）",
                                "phone", "13900001381",
                                "mainDeptId", deptId,
                                "roleIds", List.of(roleId)));
        assertEquals(0, codeOf(created), "建用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));
        String token = hospitalToken("demoa", "IT-U-T8-NT", temporary);
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of(
                                        "oldPassword", temporary,
                                        "newPassword", PASSWORD))),
                "改密失败");
        assertEquals(
                40300,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/" + patientId + "/transfer",
                                token,
                                Map.of("wardId", wardA, "reason", "无权限（示例）"))),
                "无 followup:patient:transfer 40300");
    }

    @Test
    void closedFollowupModuleReturns40301() {
        String admin = adminToken();
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301, codeOf(get("/api/hospital/followup/wards", admin)), "模块关闭：病区字典 40301");
            assertEquals(
                    40301,
                    codeOf(
                            post(
                                    "/api/hospital/followup/patients/1/transfer",
                                    admin,
                                    Map.of("wardId", 1L, "reason", "模块关闭（示例）"))),
                    "模块关闭：转病区 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
    }

    // ---------------------------------------------------------------- 租户隔离

    @Test
    void wardDictionaryIsolatedPerTenant() {
        String admin = adminToken();
        createWard("W-IT-T8-D", "示例 IT 隔离病区（示例）");
        String demobAdmin = hospitalToken("demob", "nurse.demo", PASSWORD);
        Map<?, ?> demobList = get("/api/hospital/followup/wards", demobAdmin);
        assertEquals(0, codeOf(demobList));
        assertTrue(
                ((List<Map<?, ?>>) data(demobList).get("items"))
                        .stream().noneMatch(item -> "W-IT-T8-D".equals(item.get("code"))),
                "demoa 的病区编码不出现在 demob（每院独立库）");
    }

    // ---------------------------------------------------------------- 辅助

    private static Map<String, Object> body(String mrn, long wardId) {
        return new HashMap<>(
                Map.of(
                        "medicalRecordNo", mrn,
                        "name", "赵小同（示例）",
                        "gender", "M",
                        "birthDate", "1951-02-02",
                        "wardId", wardId,
                        "surgeryDate", "2026-03-01",
                        "admissionDate", "2026-02-25",
                        "dischargeDate", "2026-03-08",
                        "dischargeStatus", 1,
                        "legacyNo", LEGACY + "-" + mrn));
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
        assertEquals(0, codeOf(response), "登录失败：" + response);
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
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private long wardIdByCode(String code) {
        return runInTenant(
                "demoa",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private long wardIdByCodeIn(String tenant, String code) {
        return runInTenant(
                tenant,
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private long deptIdByCode(String code) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = ?", Long.class, code));
    }

    private long patientIdByMrn(String mrn) {
        // 病案号只存密文 + 盲索引（E13-T01），测试按配对的 legacy_no 前缀取 id
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM pt_patient WHERE legacy_no = ?",
                                Long.class,
                                LEGACY + "-" + mrn));
    }

    private long createWard(String code, String name) {
        String admin = adminToken();
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/wards",
                        admin,
                        Map.of("code", code, "name", name, "wardType", "WARD"));
        assertEquals(0, codeOf(created), "建病区失败：" + created);
        return Long.parseLong(String.valueOf(data(created).get("id")));
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
