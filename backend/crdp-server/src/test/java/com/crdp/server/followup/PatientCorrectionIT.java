package com.crdp.server.followup;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.crdp.followup.task.app.StartDateRecalculationService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.platform.TenantModuleService;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** OQR-04：真实 HTTP / 双租户 / 加密、权限与版本冲突 / 日期变更失败回滚；仅虚构数据。 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class PatientCorrectionIT {
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantModuleService modules;
    @MockitoSpyBean StartDateRecalculationService dates;
    private final String marker = "OQR04（示例）-" + UUID.randomUUID();
    private final List<long[]> patients = new ArrayList<>();
    private final List<Long> plans = new ArrayList<>();

    private <T> T tenant(long id, Supplier<T> action) {
        return TenantContext.callAsTenant(id, id == 1 ? "demoa" : "demob", action);
    }

    private <T> T platform(Supplier<T> action) {
        return TenantContext.callAsPlatform(action);
    }

    private Map<?, ?> request(HttpMethod method, String path, String token, Object body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        var result =
                rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class).getBody();
        assertThat(result).isNotNull();
        return result;
    }

    private int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private Map<?, ?> data(Map<?, ?> response) {
        assertThat(code(response)).as("响应 %s", response).isZero();
        return (Map<?, ?>) response.get("data");
    }

    private String login(long id, String username) {
        return data(request(
                        HttpMethod.POST,
                        "/api/hospital/auth/login",
                        null,
                        Map.of(
                                "tenantCode",
                                id == 1 ? "demoa" : "demob",
                                "username",
                                username,
                                "password",
                                "Demo@2026")))
                .get("token")
                .toString();
    }

    private long create(long id, String suffix) {
        String token = login(id, "nurse.demo");
        Long ward =
                tenant(
                        id,
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM ward WHERE status='ENABLED' ORDER BY id LIMIT 1",
                                        Long.class));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", marker + suffix);
        body.put("name", "登记姓名（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1950-01-01");
        body.put("wardId", ward);
        body.put("surgeryDate", "2020-02-02");
        body.put("admissionDate", "2020-02-01");
        body.put("dischargeDate", "2020-02-05");
        body.put("dischargeStatus", 1);
        body.put("legacyNo", marker);
        long patient =
                Long.parseLong(
                        data(request(
                                        HttpMethod.POST,
                                        "/api/hospital/followup/patients",
                                        token,
                                        body))
                                .get("id")
                                .toString());
        patients.add(new long[] {id, patient});
        return patient;
    }

    private String base(long id) {
        return "/api/hospital/followup/patients/" + id;
    }

    private Map<String, Object> command(
            long version, Map<String, Object> values, boolean confirmed) {
        return Map.of(
                "baseVersion",
                version,
                "changes",
                values,
                "reason",
                "资料核实更正（示例）",
                "confirmed",
                confirmed);
    }

    private Map<?, ?> view(long patient, String token) {
        return data(request(HttpMethod.GET, base(patient) + "/registration", token, null));
    }

    private long version(Map<?, ?> view) {
        return Long.parseLong(view.get("version").toString());
    }

    private void grantCorrection(long id) {
        platform(
                () -> {
                    jdbc.update(
                            "INSERT INTO plt_tenant_scope(tenant_id,menu_id,assignable,reason) VALUES (?,9501,1,'纠错测试（示例）') ON DUPLICATE KEY UPDATE assignable=1",
                            id);
                    return null;
                });
        tenant(
                id,
                () -> {
                    jdbc.update(
                            "INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT id,9501 FROM sys_role WHERE code IN ('TENANT_ADMIN','FOLLOWUP_DOCTOR')");
                    return null;
                });
    }

    private final Map<Long, List<Long>> originalRoleGrants = new HashMap<>();
    private final Map<Long, List<Map<String, Object>>> originalScopes = new HashMap<>();

    @BeforeEach
    void snapshotGrants() {
        for (long id : List.of(1L, 2L)) {
            originalRoleGrants.put(
                    id,
                    tenant(
                            id,
                            () ->
                                    jdbc.queryForList(
                                            "SELECT role_id FROM sys_role_menu WHERE menu_id=9501",
                                            Long.class)));
            originalScopes.put(
                    id,
                    platform(
                            () ->
                                    jdbc.queryForList(
                                            "SELECT assignable,reason,set_by,set_at FROM plt_tenant_scope WHERE tenant_id=? AND menu_id=9501",
                                            id)));
            tenant(
                    id,
                    () -> {
                        jdbc.update("DELETE FROM sys_role_menu WHERE menu_id=9501");
                        return null;
                    });
            platform(
                    () -> {
                        jdbc.update(
                                "DELETE FROM plt_tenant_scope WHERE tenant_id=? AND menu_id=9501",
                                id);
                        return null;
                    });
        }
    }

    @AfterEach
    void cleanup() {
        reset(dates);
        for (var item : patients)
            tenant(
                    item[0],
                    () -> {
                        long id = item[1];
                        jdbc.update(
                                "DELETE FROM fe_field_history WHERE record_type='PT_PATIENT' AND record_id=?",
                                id);
                        jdbc.update("DELETE FROM fu_contact_log WHERE patient_id=?", id);
                        jdbc.update("DELETE FROM fu_task WHERE patient_id=?", id);
                        jdbc.update("DELETE FROM fu_enrollment WHERE patient_id=?", id);
                        jdbc.update("DELETE FROM pt_baseline_lock WHERE patient_id=?", id);
                        jdbc.update("DELETE FROM pt_status_history WHERE patient_id=?", id);
                        jdbc.update("DELETE FROM pt_patient_summary WHERE patient_id=?", id);
                        jdbc.update(
                                "DELETE FROM pt_patient WHERE id=? AND legacy_no=?", id, marker);
                        return null;
                    });
        tenant(
                1,
                () -> {
                    for (Long plan : plans) {
                        jdbc.update("DELETE FROM fu_plan_node WHERE plan_id=?", plan);
                        jdbc.update("DELETE FROM fu_plan WHERE id=?", plan);
                    }
                    return null;
                });
        for (long id : List.of(1L, 2L)) {
            tenant(
                    id,
                    () -> {
                        jdbc.update("DELETE FROM sys_role_menu WHERE menu_id=9501");
                        for (Long role : originalRoleGrants.getOrDefault(id, List.of()))
                            jdbc.update(
                                    "INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (?,9501)",
                                    role);
                        return null;
                    });
            platform(
                    () -> {
                        jdbc.update(
                                "DELETE FROM plt_tenant_scope WHERE tenant_id=? AND menu_id=9501",
                                id);
                        for (var old : originalScopes.getOrDefault(id, List.of()))
                            jdbc.update(
                                    "INSERT INTO plt_tenant_scope(tenant_id,menu_id,assignable,reason,set_by,set_at) VALUES (?,9501,?,?,?,?)",
                                    id,
                                    old.get("assignable"),
                                    old.get("reason"),
                                    old.get("set_by"),
                                    old.get("set_at"));
                        return null;
                    });
        }
    }

    @Test
    void ordinaryCorrectionPreservesPatientIdentityAndHistoryInBothTenants() {
        for (long id : List.of(1L, 2L)) {
            long patient = create(id, "shared");
            String token = login(id, "nurse.demo");
            var old =
                    tenant(
                            id,
                            () ->
                                    jdbc.queryForMap(
                                            "SELECT name_cipher,mrn_cipher FROM pt_patient WHERE id=?",
                                            patient));
            var before = view(patient, token);
            var corrected =
                    data(
                            request(
                                    HttpMethod.PUT,
                                    base(patient),
                                    token,
                                    command(
                                            version(before),
                                            Map.of("dischargeDate", "2020-02-06"),
                                            false)));
            assertThat(corrected.get("patientId").toString()).isEqualTo(String.valueOf(patient));
            assertThat(version(corrected)).isEqualTo(version(before) + 1);
            assertThat(
                            tenant(
                                    id,
                                    () ->
                                            jdbc.queryForMap(
                                                    "SELECT name_cipher,mrn_cipher FROM pt_patient WHERE id=?",
                                                    patient)))
                    .isEqualTo(old);
            assertThat(
                            tenant(
                                    id,
                                    () ->
                                            jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM fe_field_history WHERE record_type='PT_PATIENT' AND record_id=?",
                                                    Long.class,
                                                    patient)))
                    .isEqualTo(1L);
            assertThat(view(patient, token).toString())
                    .doesNotContain("登记姓名（示例）", marker + "shared");
        }
    }

    @Test
    void sensitiveCorrectionRequiresIndependentPermissionAndConfirmation() {
        long patient = create(1, "identity");
        String normal = login(1, "nurse.demo");
        long version = version(view(patient, normal));
        var change = command(version, Map.of("name", "更正姓名（示例）"), true);
        assertThat(code(request(HttpMethod.PUT, base(patient) + "/identity", normal, change)))
                .isEqualTo(40300);
        assertThat(code(request(HttpMethod.PUT, base(patient), normal, change))).isEqualTo(40001);
        grantCorrection(1);
        String token = login(1, "nurse.demo");
        assertThat(
                        code(
                                request(
                                        HttpMethod.PUT,
                                        base(patient) + "/identity",
                                        token,
                                        command(version, Map.of("name", "更正姓名（示例）"), false))))
                .isEqualTo(40001);
        var result = data(request(HttpMethod.PUT, base(patient) + "/identity", token, change));
        assertThat(result.toString()).doesNotContain("更正姓名（示例）");
        String history =
                tenant(
                                1,
                                () ->
                                        jdbc.queryForList(
                                                "SELECT old_value,new_value FROM fe_field_history WHERE record_type='PT_PATIENT' AND record_id=?",
                                                patient))
                        .toString();
        assertThat(history).contains("digest").doesNotContain("登记姓名（示例）", "更正姓名（示例）");
        assertThat(
                        data(request(HttpMethod.GET, base(patient) + "/registration", token, null))
                                .get("identity")
                                .toString())
                .contains("更*");
    }

    @Test
    void duplicateVersionInvalidDatesReadonlyFieldsAndLockRejectWithoutChanges() {
        long patient = create(1, "invalid");
        String token = login(1, "nurse.demo");
        long v = version(view(patient, token));
        assertThat(
                        code(
                                request(
                                        HttpMethod.PUT,
                                        base(patient),
                                        token,
                                        command(v + 1, Map.of("gender", "F"), false))))
                .isEqualTo(40900);
        assertThat(
                        code(
                                request(
                                        HttpMethod.PUT,
                                        base(patient),
                                        token,
                                        command(v, Map.of("admissionDate", "2030-01-01"), false))))
                .isEqualTo(40001);
        assertThat(
                        code(
                                request(
                                        HttpMethod.PUT,
                                        base(patient),
                                        token,
                                        command(v, Map.of("followupStatus", "DEAD"), false))))
                .isEqualTo(40001);
        assertThat(
                        code(
                                request(
                                        HttpMethod.PUT,
                                        base(patient),
                                        token,
                                        command(v, Map.of("wardId", 99), false))))
                .isEqualTo(40001);
        tenant(
                1,
                () -> {
                    jdbc.update(
                            "INSERT INTO pt_baseline_lock(patient_id,locked) VALUES (?,1) ON DUPLICATE KEY UPDATE locked=1",
                            patient);
                    return null;
                });
        assertThat(
                        code(
                                request(
                                        HttpMethod.PUT,
                                        base(patient),
                                        token,
                                        command(v, Map.of("gender", "F"), false))))
                .isEqualTo(40901);
        assertThat(version(view(patient, token))).isEqualTo(v);
    }

    @Test
    void recalculationFailureRollsBackEntityVersionAndFieldHistory() {
        long patient = create(1, "rollback");
        String token = login(1, "nurse.demo");
        long v = version(view(patient, token));
        tenant(
                1,
                () -> {
                    doThrow(new ApiException(409, 40901, "重算失败（示例）"))
                            .when(dates)
                            .recalculateOnBaseDateChange(eq(patient), any());
                    return null;
                });
        assertThat(
                        code(
                                request(
                                        HttpMethod.PUT,
                                        base(patient),
                                        token,
                                        command(v, Map.of("dischargeDate", "2020-02-07"), false))))
                .isEqualTo(40901);
        assertThat(version(view(patient, token))).isEqualTo(v);
        assertThat(
                        tenant(
                                1,
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT discharge_date FROM pt_patient WHERE id=?",
                                                String.class,
                                                patient)))
                .isEqualTo("2020-02-05");
        assertThat(
                        tenant(
                                1,
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM fe_field_history WHERE record_type='PT_PATIENT' AND record_id=?",
                                                Long.class,
                                                patient)))
                .isZero();
    }

    @Test
    void dateCorrectionReschedulesOnlyUncontactedTasksAndKeepsHistoricalReferences() {
        long patient = create(1, "task-history");
        String token = login(1, "nurse.demo");
        long[] taskIds =
                tenant(
                        1,
                        () -> {
                            jdbc.update(
                                    "INSERT INTO fu_plan(code,name,version,status,start_date_field,cycle_type) VALUES (?,'纠错排期（示例）',1,'PUBLISHED','DISCHARGE_DATE','ONGOING')",
                                    marker);
                            long plan =
                                    jdbc.queryForObject(
                                            "SELECT id FROM fu_plan WHERE code=?",
                                            Long.class,
                                            marker);
                            plans.add(plan);
                            jdbc.update(
                                    "INSERT INTO fu_plan_node(plan_id,node_type,name,repeat_mode,cycle_value,cycle_unit) VALUES (?,'SUBPLAN','年度（示例）','CYCLE',1,'YEAR')",
                                    plan);
                            long parent =
                                    jdbc.queryForObject(
                                            "SELECT id FROM fu_plan_node WHERE plan_id=?",
                                            Long.class,
                                            plan);
                            jdbc.update(
                                    "INSERT INTO fu_plan_node(plan_id,parent_id,node_type,name,basis,offset_value,offset_unit,task_type) VALUES (?,?,'NODE','节点（示例）','PLAN_START',1,'YEAR','REMINDER')",
                                    plan,
                                    parent);
                            long node =
                                    jdbc.queryForObject(
                                            "SELECT id FROM fu_plan_node WHERE plan_id=? AND parent_id IS NOT NULL",
                                            Long.class,
                                            plan);
                            jdbc.update(
                                    "INSERT INTO fu_enrollment(patient_id,plan_id,plan_code,start_date) VALUES (?,?,?,'2020-02-05')",
                                    patient,
                                    plan,
                                    marker);
                            long enrollment =
                                    jdbc.queryForObject(
                                            "SELECT id FROM fu_enrollment WHERE patient_id=? AND plan_id=?",
                                            Long.class,
                                            patient,
                                            plan);
                            long[] ids = new long[4];
                            int index = 0;
                            for (int occurrence : List.of(1, 2, 3, 20)) {
                                String date = (2020 + occurrence) + "-02-05";
                                jdbc.update(
                                        "INSERT INTO fu_task(enrollment_id,patient_id,plan_id,node_id,occurrence,task_type,plan_date,remind_start,window_start,window_end,open_date,created_logical_date,status) VALUES (?,?,?,?,?,'REMINDER',?,?,?,?,?,?,?)",
                                        enrollment,
                                        patient,
                                        plan,
                                        node,
                                        occurrence,
                                        date,
                                        date,
                                        date,
                                        date,
                                        date,
                                        date,
                                        occurrence == 3 ? "COMPLETED" : "PENDING");
                                ids[index++] =
                                        jdbc.queryForObject(
                                                "SELECT id FROM fu_task WHERE enrollment_id=? AND occurrence=?",
                                                Long.class,
                                                enrollment,
                                                occurrence);
                            }
                            jdbc.update(
                                    "INSERT INTO fu_contact_log(patient_id,task_id,method,result,contact_time,created_by) VALUES (?,?,'PHONE','REACHED',NOW(3),1)",
                                    patient,
                                    ids[1]);
                            return ids;
                        });
        long version = version(view(patient, token));
        data(
                request(
                        HttpMethod.PUT,
                        base(patient),
                        token,
                        command(version, Map.of("dischargeDate", "2020-02-06"), false)));
        tenant(
                1,
                () -> {
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT plan_date FROM fu_task WHERE id=?",
                                            String.class,
                                            taskIds[0]))
                            .isEqualTo("2021-02-06");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT plan_date FROM fu_task WHERE id=?",
                                            String.class,
                                            taskIds[1]))
                            .isEqualTo("2022-02-05");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT plan_date FROM fu_task WHERE id=?",
                                            String.class,
                                            taskIds[2]))
                            .isEqualTo("2023-02-05");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_task WHERE id=?",
                                            Long.class,
                                            taskIds[3]))
                            .isZero();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_contact_log WHERE task_id=?",
                                            Long.class,
                                            taskIds[1]))
                            .isEqualTo(1L);
                    return null;
                });
    }

    @Test
    void implantFeatureSelectsCMetadataAndRequiresBirthDate() {
        long patient = create(1, "c-metadata");
        String token = login(1, "nurse.demo");
        Boolean old =
                tenant(
                        1,
                        () ->
                                jdbc.queryForObject(
                                        "SELECT enabled FROM cfg_feature WHERE feature_code='implant'",
                                        Boolean.class));
        try {
            tenant(
                    1,
                    () -> {
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled=1 WHERE feature_code='implant'");
                        return null;
                    });
            var current = view(patient, token);
            assertThat(((Map<?, ?>) current.get("schema")).get("code")).isEqualTo("PT_REG_C");
            Map<String, Object> missing = new HashMap<>();
            missing.put("birthDate", null);
            assertThat(
                            code(
                                    request(
                                            HttpMethod.PUT,
                                            base(patient),
                                            token,
                                            command(version(current), missing, false))))
                    .isEqualTo(40001);
            data(
                    request(
                            HttpMethod.PUT,
                            base(patient),
                            token,
                            command(version(current), Map.of("birthDate", "1951-01-01"), false)));
        } finally {
            tenant(
                    1,
                    () -> {
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled=? WHERE feature_code='implant'",
                                old);
                        return null;
                    });
        }
    }

    @Test
    void duplicateMrnRejectedAndIdentityChangesNeverCrossTenantDatabases() {
        long first = create(1, "same");
        long other = create(1, "other");
        long second = create(2, "same");
        String beforeOther =
                tenant(
                        2,
                        () ->
                                jdbc.queryForObject(
                                        "SELECT mrn_cipher FROM pt_patient WHERE id=?",
                                        String.class,
                                        second));
        grantCorrection(1);
        String token = login(1, "nurse.demo");
        long v = version(view(first, token));
        assertThat(
                        code(
                                request(
                                        HttpMethod.PUT,
                                        base(first) + "/identity",
                                        token,
                                        command(
                                                v,
                                                Map.of("medicalRecordNo", marker + "other"),
                                                true))))
                .isEqualTo(40902);
        data(
                request(
                        HttpMethod.PUT,
                        base(first) + "/identity",
                        token,
                        command(v, Map.of("medicalRecordNo", marker + "new"), true)));
        var a =
                tenant(
                        1,
                        () ->
                                jdbc.queryForObject(
                                        "SELECT mrn_blind FROM pt_patient WHERE id=?",
                                        String.class,
                                        first));
        var b =
                tenant(
                        2,
                        () ->
                                jdbc.queryForObject(
                                        "SELECT mrn_blind FROM pt_patient WHERE id=?",
                                        String.class,
                                        second));
        assertThat(a).isNotEqualTo(b);
        assertThat(
                        tenant(
                                2,
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT mrn_cipher FROM pt_patient WHERE id=?",
                                                String.class,
                                                second)))
                .isEqualTo(beforeOther);
        assertThat(version(view(other, token))).isZero();
    }

    @Test
    void missingAndOutOfScopePatientReturn404AndModuleDisabledIs40301() {
        String token = login(1, "doctor.demo");
        assertThat(
                        code(
                                request(
                                        HttpMethod.GET,
                                        base(Long.MAX_VALUE) + "/registration",
                                        token,
                                        null)))
                .isEqualTo(40400);
        long patient = create(1, "scope");
        // 登记主体先由护士建好，再切到没有任何数据范围的测试会话，SQL 层默认拒绝。
        String username = "oqr04_" + UUID.randomUUID().toString().substring(0, 8);
        tenant(
                1,
                () -> {
                    var hash =
                            new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(
                                            16, 32, 1, 65536, 3)
                                    .encode("Demo@2026");
                    jdbc.update(
                            "INSERT INTO sys_role(code,name,built_in,status) VALUES (?,'纠错空范围角色（示例）',0,'ENABLED')",
                            username);
                    jdbc.update(
                            "INSERT INTO sys_user(username,display_name,password_hash,status,must_change_password) VALUES (?,'纠错医生（示例）',?,'ENABLED',0)",
                            username,
                            hash);
                    jdbc.update(
                            "INSERT INTO sys_user_role(user_id,role_id) SELECT u.id,r.id FROM sys_user u,sys_role r WHERE u.username=? AND r.code=?",
                            username,
                            username);
                    jdbc.update(
                            "INSERT INTO sys_role_menu(role_id,menu_id) SELECT id,21 FROM sys_role WHERE code=?",
                            username);
                    jdbc.update(
                            "INSERT INTO sys_role_menu(role_id,menu_id) SELECT id,214 FROM sys_role WHERE code=?",
                            username);
                    return null;
                });
        try {
            String empty = login(1, username);
            assertThat(
                            code(
                                    request(
                                            HttpMethod.PUT,
                                            base(patient),
                                            empty,
                                            command(0, Map.of("gender", "F"), false))))
                    .isEqualTo(40400);
            assertThat(code(request(HttpMethod.GET, base(patient) + "/registration", empty, null)))
                    .isEqualTo(40400);
        } finally {
            tenant(
                    1,
                    () -> {
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id=ur.user_id WHERE u.username=?",
                                username);
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id=rm.role_id WHERE r.code=?",
                                username);
                        jdbc.update("DELETE FROM sys_user WHERE username=?", username);
                        jdbc.update("DELETE FROM sys_role WHERE code=?", username);
                        return null;
                    });
        }
        String oldState =
                platform(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT status FROM plt_tenant_module WHERE tenant_id=1 AND module_code='followup'",
                                        String.class));
        try {
            platform(
                    () -> {
                        jdbc.update(
                                "UPDATE plt_tenant_module SET status='CLOSED' WHERE tenant_id=1 AND module_code='followup'");
                        return null;
                    });
            String off = login(1, "nurse.demo");
            assertThat(code(request(HttpMethod.GET, base(patient) + "/registration", off, null)))
                    .isEqualTo(40301);
        } finally {
            platform(
                    () -> {
                        jdbc.update(
                                "UPDATE plt_tenant_module SET status=? WHERE tenant_id=1 AND module_code='followup'",
                                oldState);
                        return null;
                    });
        }
    }
}
