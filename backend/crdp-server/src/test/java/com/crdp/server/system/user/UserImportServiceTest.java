package com.crdp.server.system.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.excel.SimpleXlsx;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.system.user.UserImportService.ImportReport;
import com.crdp.server.system.user.UserImportService.RowResult;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * E06-T08 / PLT-ORG-007 单元测试（mock JDBC）：清单列拆分、文件级错误（表头 / 空数据 / 行数上限 / 非 xlsx）、逐行校验（复用手工新建口径 + 文件内重复
 * + 库内已存在 + 引用存在性 + 模板角色）、成功行落库 参数（首登强制改密、密文手机号、角色 / 负责病区 / 负责科室写入、初始密码只落哈希不回显）、单行失败不影响其他行、作业级审计。
 * 真实 MySQL 的端到端（HTTP + 权限 + 租户隔离）由 {@code UserImportIT} 覆盖。 夹具全部虚构（手机号为 139-0000 虚构测试号段）。
 */
class UserImportServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long DEPT_ID = 101L;
    private static final long WARD_ID = 201L;
    private static final long ROLE_ID = 31L;
    private static final long TEMPLATE_ROLE_ID = 32L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final SecurityProperties securityProperties = new SecurityProperties();
    private final AuditLogger audit = mock(AuditLogger.class);
    // E05-T09 / PLT-ORG-010：配额拦截（默认放行；满额用例里对指定动作抛 40901）
    private final com.crdp.server.platform.TenantQuotaGuard quotaGuard =
            mock(com.crdp.server.platform.TenantQuotaGuard.class);

    private UserImportService service;

    @BeforeEach
    void setUp() {
        service =
                new UserImportService(
                        jdbc, crypto, passwordEncoder, securityProperties, audit, quotaGuard);
        TenantContext.useTenant(TENANT_ID, "demoa");
        stubDirectory(
                List.of(
                        Map.of(
                                "id",
                                ROLE_ID,
                                "code",
                                "FOLLOWUP_DOCTOR",
                                "name",
                                "随访医生",
                                "role_type",
                                "CUSTOM"),
                        Map.of(
                                "id",
                                TEMPLATE_ROLE_ID,
                                "code",
                                "FOLLOWUP_NURSE",
                                "name",
                                "随访员",
                                "role_type",
                                "TEMPLATE")),
                List.of(Map.of("id", DEPT_ID, "code", "DEPT-CARDIO")),
                List.of(Map.of("id", WARD_ID, "code", "W-DEMO-1")),
                List.of(Map.of("id", 9L, "username", "nurse.demo")),
                List.of("idx-13900000017"));
        lenient().when(crypto.encrypt(anyString(), anyString())).thenReturn("cipher");
        // 盲索引按手机号区分，供「库内已绑定」用例精确命中
        lenient()
                .when(crypto.blindIndex(anyString(), anyString()))
                .thenAnswer(invocation -> "idx-" + invocation.getArgument(1));
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("argon2-hash");
        lenient()
                .when(
                        jdbc.queryForList(
                                contains("WHERE username ="), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(55L));
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- 清单列拆分（纯函数）

    @Test
    void splitCodesToleratesSemicolonsAndWhitespace() {
        assertEquals(List.of(), UserImportService.splitCodes(null));
        assertEquals(List.of(), UserImportService.splitCodes("  "));
        assertEquals(
                List.of("FOLLOWUP_DOCTOR", "FOLLOWUP_CRC"),
                UserImportService.splitCodes(" FOLLOWUP_DOCTOR；FOLLOWUP_CRC ; "));
        assertEquals(List.of("A", "A"), UserImportService.splitCodes("A;A"), "重复段保留，由引用解析收敛");
    }

    // ---------------------------------------------------------------- 文件级错误

    @Test
    void rejectsNonXlsxPayload() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.importUsers(new ByteArrayInputStream("not xlsx".getBytes())));
        assertEquals(40001, ex.code());
        assertTrue(ex.getMessage().startsWith("文件无法解析"));
    }

    @Test
    void rejectsMismatchedHeaderAndEmptyData() {
        ApiException header =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.importUsers(
                                        xlsx(
                                                List.of("账号", "姓名", "错误表头"),
                                                List.of(List.of("a", "b", "c")))));
        assertEquals(40001, header.code());
        assertTrue(header.getMessage().contains("表头与模板不一致"));

        ApiException empty =
                assertThrows(
                        ApiException.class,
                        () -> service.importUsers(xlsx(UserImportHeaders.EXPECTED, List.of())));
        assertEquals(40001, empty.code());
        assertEquals("文件没有数据行", empty.getMessage());
    }

    @Test
    void rejectsMoreThanMaxRows() {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < UserImportService.MAX_ROWS + 1; i++) {
            rows.add(
                    List.of(
                            "it.bulk." + i,
                            "批量（示例）",
                            "",
                            "1390000" + String.format("%04d", i),
                            "DEPT-CARDIO",
                            "FOLLOWUP_DOCTOR",
                            "",
                            ""));
        }
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.importUsers(xlsx(UserImportHeaders.EXPECTED, rows)));
        assertEquals(40001, ex.code());
        assertTrue(ex.getMessage().contains("单次最多导入 " + UserImportService.MAX_ROWS + " 行"));
    }

    // ---------------------------------------------------------------- 成功行落库

    @Test
    void createsUserWithRolesWardsAndScopesAndForcesFirstLoginChange() {
        ImportReport report =
                service.importUsers(
                        xlsx(
                                UserImportHeaders.EXPECTED,
                                List.of(
                                        List.of(
                                                "it.nurse.a",
                                                "李护士（示例）",
                                                "S-1001",
                                                "13900000001",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_DOCTOR",
                                                "W-DEMO-1",
                                                "DEPT-CARDIO"))));
        assertEquals(1, report.total());
        assertEquals(1, report.created());
        assertEquals(0, report.failed());
        RowResult row = report.rows().get(0);
        assertEquals(2, row.rowNo(), "行号按 Excel 习惯含表头从 1 计");
        assertEquals("it.nurse.a", row.username());
        assertEquals("CREATED", row.action());
        assertNull(row.message());

        ArgumentCaptor<Object[]> userArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc)
                .update(
                        contains("INSERT INTO sys_user"),
                        eq("it.nurse.a"),
                        eq("李护士（示例）"),
                        eq("argon2-hash"),
                        eq("S-1001"),
                        eq(DEPT_ID),
                        eq("cipher"),
                        eq("idx-13900000001"));
        verify(jdbc).update(contains("sys_user_role"), eq(55L), eq(ROLE_ID));
        verify(jdbc).update(contains("sys_user_ward"), eq(55L), eq(WARD_ID));
        verify(jdbc).update(contains("sys_user_dept_scope"), eq(55L), eq(DEPT_ID));
        verify(jdbc, never()).update(contains("UPDATE sys_user"), any(Object[].class));
    }

    /** E05-T09 / PLT-ORG-010：批量导入也是新建——配额满额的行按行失败（带原因），后续行照常校验落库， 且失败行不触发 INSERT。 */
    @Test
    void quotaFullRowFailsAloneAndReportsReason() {
        org.mockito.Mockito.doThrow(
                        new com.crdp.framework.web.ApiException(
                                409, 40901, "已达用户数上限（5），无法导入用户，请联系平台调整配额"))
                .when(quotaGuard)
                .assertUserQuotaAvailable(jdbc, "导入用户");
        ImportReport report =
                service.importUsers(
                        xlsx(
                                UserImportHeaders.EXPECTED,
                                List.of(
                                        List.of(
                                                "it.nurse.full",
                                                "李护士（示例）",
                                                "S-1001",
                                                "13900000001",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_DOCTOR",
                                                "",
                                                ""))));
        assertEquals(1, report.total());
        assertEquals(0, report.created());
        assertEquals(1, report.failed());
        RowResult row = report.rows().get(0);
        assertEquals("FAILED", row.action());
        assertEquals("已达用户数上限（5），无法导入用户，请联系平台调整配额", row.message());
        verify(jdbc, never()).update(contains("INSERT INTO sys_user"), any(Object[].class));
    }

    @Test
    void blankStaffNoFallsToNullAndDuplicateCodesCollapse() {
        ImportReport report =
                service.importUsers(
                        xlsx(
                                UserImportHeaders.EXPECTED,
                                List.of(
                                        List.of(
                                                "it.doctor.b",
                                                "王医生（示例）",
                                                "",
                                                "13900000002",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_DOCTOR；FOLLOWUP_DOCTOR",
                                                "W-DEMO-1；W-DEMO-1",
                                                ""))));
        assertEquals(1, report.created(), "重复编码只写一次：" + report.rows());
        verify(jdbc)
                .update(
                        contains("INSERT INTO sys_user"),
                        eq("it.doctor.b"),
                        eq("王医生（示例）"),
                        eq("argon2-hash"),
                        isNull(),
                        eq(DEPT_ID),
                        eq("cipher"),
                        eq("idx-13900000002"));
        verify(jdbc).update(contains("sys_user_role"), eq(55L), eq(ROLE_ID));
        verify(jdbc).update(contains("sys_user_ward"), eq(55L), eq(WARD_ID));
    }

    @Test
    void recordsJobLevelAuditWithoutPlainTextPhone() {
        service.importUsers(
                xlsx(
                        UserImportHeaders.EXPECTED,
                        List.of(
                                List.of(
                                        "it.nurse.a",
                                        "李护士（示例）",
                                        "",
                                        "13900000001",
                                        "DEPT-CARDIO",
                                        "FOLLOWUP_DOCTOR",
                                        "",
                                        ""))));
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertEquals("USER_IMPORT", event.eventType());
        assertEquals("sys_user:import", event.target());
        assertTrue(event.detail().contains("新增 1"), event.detail());
        assertTrue(event.detail().contains("失败 0"), event.detail());
        assertTrue(!event.detail().contains("13900000001"), "审计摘要不含手机号明文");
    }

    // ---------------------------------------------------------------- 逐行校验：单行失败不影响其他行

    @Test
    void rowFailuresDoNotAffectOtherRowsAndEachReportsReason() {
        ImportReport report =
                service.importUsers(
                        xlsx(
                                UserImportHeaders.EXPECTED,
                                List.of(
                                        // 行 2：成功基准行
                                        List.of(
                                                "it.ok",
                                                "赵护士（示例）",
                                                "",
                                                "13900000003",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_DOCTOR",
                                                "",
                                                ""),
                                        // 行 3：账号字符集非法
                                        List.of(
                                                "张三（示例）",
                                                "张三（示例）",
                                                "",
                                                "13900000004",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_DOCTOR",
                                                "",
                                                ""),
                                        // 行 4：姓名缺失
                                        List.of(
                                                "it.name",
                                                "",
                                                "",
                                                "13900000005",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_DOCTOR",
                                                "",
                                                ""),
                                        // 行 5：手机号格式错误
                                        List.of(
                                                "it.phone",
                                                "钱医生（示例）",
                                                "",
                                                "12345",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_DOCTOR",
                                                "",
                                                ""),
                                        // 行 6：角色缺失
                                        List.of(
                                                "it.role",
                                                "孙医生（示例）",
                                                "",
                                                "13900000006",
                                                "DEPT-CARDIO",
                                                "",
                                                "",
                                                ""),
                                        // 行 7：主科室不存在
                                        List.of(
                                                "it.dept",
                                                "周医生（示例）",
                                                "",
                                                "13900000007",
                                                "DEPT-NOT-EXIST",
                                                "FOLLOWUP_DOCTOR",
                                                "",
                                                ""),
                                        // 行 8：角色不存在
                                        List.of(
                                                "it.role.unknown",
                                                "吴医生（示例）",
                                                "",
                                                "13900000008",
                                                "DEPT-CARDIO",
                                                "ROLE-NOT-EXIST",
                                                "",
                                                ""),
                                        // 行 9：模板角色
                                        List.of(
                                                "it.role.template",
                                                "郑医生（示例）",
                                                "",
                                                "13900000009",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_NURSE",
                                                "",
                                                ""),
                                        // 行 10：负责病区不存在
                                        List.of(
                                                "it.ward",
                                                "冯医生（示例）",
                                                "",
                                                "13900000010",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_DOCTOR",
                                                "W-NOT-EXIST",
                                                ""),
                                        // 行 11：负责科室不存在
                                        List.of(
                                                "it.scope",
                                                "陈医生（示例）",
                                                "",
                                                "13900000011",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_DOCTOR",
                                                "",
                                                "DEPT-NOT-EXIST"),
                                        // 行 12：工号过长
                                        List.of(
                                                "it.staff",
                                                "褚医生（示例）",
                                                "a".repeat(65),
                                                "13900000012",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_DOCTOR",
                                                "",
                                                ""))));
        assertEquals(11, report.total());
        assertEquals(1, report.created(), "只有基准行成功：" + report.rows());
        assertEquals(10, report.failed());

        Map<Integer, String> errors = new LinkedHashMap<>();
        for (RowResult row : report.rows()) {
            if ("FAILED".equals(row.action())) {
                errors.put(row.rowNo(), row.message());
            }
        }
        assertTrue(errors.get(3).contains("账号"), errors.toString());
        assertTrue(errors.get(4).contains("姓名必填"), errors.toString());
        assertTrue(errors.get(5).contains("手机号格式不正确"), errors.toString());
        assertTrue(errors.get(6).contains("角色编码必填"), errors.toString());
        assertEquals("主科室不存在：DEPT-NOT-EXIST", errors.get(7));
        assertEquals("角色不存在：ROLE-NOT-EXIST", errors.get(8));
        assertTrue(errors.get(9).contains("模板角色"), errors.toString());
        assertEquals("负责病区不存在：W-NOT-EXIST", errors.get(10));
        assertEquals("负责科室不存在：DEPT-NOT-EXIST", errors.get(11));
        assertTrue(errors.get(12).contains("工号"), errors.toString());
    }

    @Test
    void duplicatesInsideFileAndAgainstDatabaseAreRejected() {
        ImportReport report =
                service.importUsers(
                        xlsx(
                                UserImportHeaders.EXPECTED,
                                List.of(
                                        // 行 2 / 行 3：账号在文件内重复（两行都失败）
                                        List.of(
                                                "it.dup",
                                                "重复甲（示例）",
                                                "",
                                                "13900000013",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_DOCTOR",
                                                "",
                                                ""),
                                        List.of(
                                                "it.dup",
                                                "重复乙（示例）",
                                                "",
                                                "13900000014",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_DOCTOR",
                                                "",
                                                ""),
                                        // 行 4 / 行 5：手机号在文件内重复
                                        List.of(
                                                "it.phone.a",
                                                "甲（示例）",
                                                "",
                                                "13900000015",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_DOCTOR",
                                                "",
                                                ""),
                                        List.of(
                                                "it.phone.b",
                                                "乙（示例）",
                                                "",
                                                "13900000015",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_DOCTOR",
                                                "",
                                                ""),
                                        // 行 6：账号库内已存在
                                        List.of(
                                                "nurse.demo",
                                                "既有账号（示例）",
                                                "",
                                                "13900000016",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_DOCTOR",
                                                "",
                                                ""),
                                        // 行 7：手机号库内已绑定
                                        List.of(
                                                "it.phone.existing",
                                                "既有手机号（示例）",
                                                "",
                                                "13900000017",
                                                "DEPT-CARDIO",
                                                "FOLLOWUP_DOCTOR",
                                                "",
                                                ""))));
        assertEquals(6, report.total());
        assertEquals(0, report.created(), "全部行都命中重复规则：" + report.rows());

        Map<Integer, String> errors = new LinkedHashMap<>();
        for (RowResult row : report.rows()) {
            errors.put(row.rowNo(), row.message());
        }
        assertEquals("账号在文件内重复：it.dup", errors.get(2));
        assertEquals("账号在文件内重复：it.dup", errors.get(3));
        assertEquals("手机号在文件内重复：139****0015", errors.get(4));
        assertEquals("手机号在文件内重复：139****0015", errors.get(5));
        assertTrue(errors.get(6).startsWith("账号已存在：nurse.demo"), errors.toString());
        assertTrue(errors.get(7).startsWith("该手机号已绑定本院其他账号：139****0017"), errors.toString());
        verify(jdbc, never()).update(contains("INSERT INTO sys_user"), any(Object[].class));
    }

    // ---------------------------------------------------------------- 辅助

    /** 与后端模板表头同构（避免测试依赖生产常量的可见性）。 */
    private static final class UserImportHeaders {
        static final List<String> EXPECTED =
                List.of("账号", "姓名", "工号", "手机号", "主科室编码", "角色编码", "负责病区编码", "负责科室编码");
    }

    private static InputStream xlsx(List<String> headers, List<List<String>> rows) {
        return new ByteArrayInputStream(SimpleXlsx.write(headers, rows));
    }

    /** 目录与库内既有数据快照：角色、科室、病区、既有账号、既有手机号盲索引。 */
    private void stubDirectory(
            List<Map<String, Object>> roles,
            List<Map<String, Object>> depts,
            List<Map<String, Object>> wards,
            List<Map<String, Object>> existingUsers,
            List<String> existingPhoneIndexes) {
        lenient()
                .when(jdbc.queryForList(contains("FROM sys_role"), any(Object[].class)))
                .thenReturn(roles);
        lenient()
                .when(jdbc.queryForList(contains("FROM sys_dept"), any(Object[].class)))
                .thenReturn(depts);
        lenient()
                .when(jdbc.queryForList(contains("FROM ward"), any(Object[].class)))
                .thenReturn(wards);
        lenient()
                .when(jdbc.queryForList(contains("FROM sys_user"), any(Object[].class)))
                .thenReturn(existingUsers);
        lenient()
                .when(jdbc.queryForList(contains("phone_idx"), eq(String.class)))
                .thenReturn(existingPhoneIndexes);
        lenient()
                .when(jdbc.queryForList(contains("cfg_param"), eq(String.class)))
                .thenReturn(List.of());
    }
}
