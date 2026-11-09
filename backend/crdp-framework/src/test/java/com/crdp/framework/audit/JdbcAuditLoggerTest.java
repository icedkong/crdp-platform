package com.crdp.framework.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E08-T01 / PLT-AUD-001~004：审计写入组件。四类日志（操作、登录、数据访问、平台访问）按规格字段写入对应的
 * 只追加表；敏感与关键事件写失败整体失败（50000），普通操作失败只告警不回滚；入库前自动补全操作者与请求上下文。
 *
 * <p>E08-T02 起插入经 {@link AuditChainWriter} 封存（prev_hash / hash）；本测试用桩替身捕获「表 + SQL + 参数」， 封存计算本身见
 * {@link AuditChainWriterTest}。
 */
class JdbcAuditLoggerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditChainWriter chain = mock(AuditChainWriter.class);
    private final List<String> sqls = new ArrayList<>();
    private final List<List<Object>> argRows = new ArrayList<>();
    private final List<AuditAppendedEvent> events = new ArrayList<>();
    private JdbcAuditLogger logger;

    @BeforeEach
    void setUp() {
        doAnswer(
                        invocation -> {
                            sqls.add((String) invocation.getArgument(1));
                            argRows.add(new ArrayList<>(invocation.getArgument(2)));
                            return 1L;
                        })
                .when(chain)
                .insert(any(), anyString(), any());
        logger = new JdbcAuditLogger(jdbc, chain, event -> events.add((AuditAppendedEvent) event));
        SessionPrincipal principal =
                new SessionPrincipal(
                        "token-1",
                        "HOSPITAL",
                        7L,
                        "demoa",
                        "示例医院",
                        42L,
                        "doctor.demo",
                        "张医生（示例）",
                        List.of("DOCTOR"),
                        List.of(),
                        List.of(),
                        List.of(),
                        1L,
                        ScopeSnapshot.allowAll(),
                        false,
                        null);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private List<Object> lastArgs() {
        return argRows.get(argRows.size() - 1);
    }

    // ---------------------------------------------------------------- 视角会话匿名（E20-T06）

    /** 视角会话写医院侧操作日志：操作者匿名「平台支持人员 会话号」，不落平台账号与姓名（platform-spec §8 已确认）。 */
    @Test
    void viewSessionOperationAnonymizesOperator() {
        SessionPrincipal view =
                new SessionPrincipal(
                        "view-token-anon",
                        "TENANT_VIEW",
                        7L,
                        "demoa",
                        "示例医院",
                        5L,
                        "platform.admin",
                        "平台管理员（示例）",
                        List.of("PLATFORM_ADMIN"),
                        List.of(),
                        List.of(),
                        List.of(),
                        1L,
                        ScopeSnapshot.allowAll(),
                        false,
                        null,
                        "127.0.0.1",
                        0L,
                        0L,
                        900L,
                        7200L,
                        com.crdp.framework.fieldpolicy.FieldPolicySnapshot.defaults());
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(view, null, List.of()));
        TenantContext.useTenant(7L, "demoa");
        String sessionNo = SessionPrincipal.sessionIdOf("view-token-anon");

        logger.append(AuditEntry.operation("base", "USER_UPDATE").description("代为修改用户").build());

        assertThat(sqls.get(0)).contains("INSERT INTO aud_log");
        assertThat(lastArgs())
                .contains("platform_view", "平台支持人员 " + sessionNo)
                .doesNotContain("platform.admin", "平台管理员（示例）");
        assertThat(lastArgs()).contains(sessionNo);
    }

    /** 平台访问日志（VIEW_*）在视角会话里保留真实平台账号身份（平台侧记录，医院不可见）。 */
    @Test
    void viewSessionPlatformAccessKeepsRealIdentity() {
        SessionPrincipal view =
                new SessionPrincipal(
                        "view-token-keep",
                        "TENANT_VIEW",
                        7L,
                        "demoa",
                        "示例医院",
                        5L,
                        "platform.admin",
                        "平台管理员（示例）",
                        List.of("PLATFORM_ADMIN"),
                        List.of(),
                        List.of(),
                        List.of(),
                        1L,
                        ScopeSnapshot.allowAll(),
                        false,
                        null,
                        "127.0.0.1",
                        0L,
                        0L,
                        900L,
                        7200L,
                        com.crdp.framework.fieldpolicy.FieldPolicySnapshot.defaults());
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(view, null, List.of()));
        TenantContext.useTenant(7L, "demoa");

        logger.append(
                AuditEntry.platformAccess(7L, "base", "VIEW_PROXY_CONFIG")
                        .username("platform.admin")
                        .description("代为操作")
                        .build());

        assertThat(sqls.get(0)).contains("aud_platform_access_log");
        assertThat(lastArgs()).contains("platform.admin", "平台管理员（示例）");
    }

    // ---------------------------------------------------------------- 操作日志（PLT-AUD-001）

    @Test
    void operationEntryWritesSpecColumnsToAudLog() {
        TenantContext.useTenant(7L, "demoa");
        logger.append(
                AuditEntry.operation("followup", "PATIENT_UPDATE")
                        .description("修改患者档案")
                        .objectType("patient")
                        .objectId("1001")
                        .beforeJson("{\"ward\":\"A\"}")
                        .afterJson("{\"ward\":\"B\"}")
                        .build());

        assertThat(sqls.get(0)).contains("INSERT INTO aud_log");
        // 规格通用字段：模块、操作类型、对象、描述、结果、操作者四件套、前后值
        assertThat(lastArgs())
                .contains(
                        "followup",
                        "PATIENT_UPDATE",
                        "patient",
                        "1001",
                        "修改患者档案",
                        "SUCCESS",
                        42L,
                        "doctor.demo",
                        "张医生（示例）",
                        "HOSPITAL",
                        "DOCTOR",
                        "{\"ward\":\"A\"}",
                        "{\"ward\":\"B\"}");
    }

    @Test
    void operationWriteFailureIsWarnedButNotThrown() {
        TenantContext.useTenant(7L, "demoa");
        doThrow(new BadSqlGrammarException("audit", "INSERT", null))
                .when(chain)
                .insert(any(), anyString(), any());
        assertThatCode(() -> logger.append(AuditEntry.operation("system", "ROLE_UPDATE").build()))
                .doesNotThrowAnyException();
        // 失败重试：一次失败后重试一次，两次都失败才放弃（普通操作只告警）
        verify(chain, times(2)).insert(any(), anyString(), any());
    }

    // ---------------------------------------------------------------- 登录日志（PLT-AUD-002）

    @Test
    void loginEntryWritesExtensionColumnsToAudLoginLog() {
        TenantContext.useTenant(7L, "demoa");
        logger.append(
                AuditEntry.login("doctor.demo")
                        .success()
                        .description("登录成功")
                        .loginMethod("PASSWORD")
                        .tenantIdentifyMethod("INPUT")
                        .secondFactorResult("PASS")
                        .sessionId("abcd1234abcd1234")
                        .ip("10.1.1.8")
                        .userAgent("Mozilla/5.0")
                        .build());

        assertThat(sqls.get(0)).contains("INSERT INTO aud_login_log");
        // 登录扩展字段：登录方式、租户识别方式、第二因素结果、失败原因（成功为 null）、会话 ID
        assertThat(lastArgs())
                .containsSubsequence(
                        "doctor.demo",
                        1,
                        "登录成功",
                        "PASSWORD",
                        "INPUT",
                        "PASS",
                        "abcd1234abcd1234",
                        "10.1.1.8",
                        "Mozilla/5.0");
    }

    @Test
    void loginAttemptBridgeWritesFailReason() {
        TenantContext.useTenant(7L, "demoa");
        logger.loginAttempt("doctor.demo", false, "bad-password");

        assertThat(sqls.get(0)).contains("INSERT INTO aud_login_log");
        assertThat(lastArgs()).contains("doctor.demo", 0, "FAIL", "bad-password");
    }

    @Test
    void platformLoginGoesToPlatformSide() {
        TenantContext.usePlatform();
        logger.append(AuditEntry.login("admin.demo").success().build());

        assertThat(sqls.get(0)).contains("INSERT INTO aud_login_log");
    }

    @Test
    void loginWriteFailureFailsWholeOperation() {
        TenantContext.useTenant(7L, "demoa");
        doThrow(new BadSqlGrammarException("audit", "INSERT", null))
                .when(chain)
                .insert(any(), anyString(), any());
        assertThatThrownBy(() -> logger.append(AuditEntry.login("doctor.demo").success().build()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(50000));
    }

    // ---------------------------------------------------------------- 数据访问日志（PLT-AUD-003）

    @Test
    void dataAccessEntryWritesExtensionColumns() {
        TenantContext.useTenant(7L, "demoa");
        logger.append(
                AuditEntry.dataAccess("patient", "VIEW_PLAIN")
                        .description("查看患者手机号明文")
                        .objectType("patient")
                        .objectId("1001")
                        .recordCount(1L)
                        .queryDigest("id=1001")
                        .build());

        assertThat(sqls.get(0)).contains("INSERT INTO aud_data_access_log");
        // 数据访问扩展字段：访问方式、涉及记录数、查询条件摘要、导出文件哈希
        assertThat(lastArgs())
                .contains("VIEW_PLAIN", "patient", "1001", 1L, "id=1001", null, "SUCCESS");
    }

    @Test
    void dataAccessWriteFailureFailsWholeOperation() {
        TenantContext.useTenant(7L, "demoa");
        doThrow(new BadSqlGrammarException("audit", "INSERT", null))
                .when(chain)
                .insert(any(), anyString(), any());
        assertThatThrownBy(() -> logger.append(AuditEntry.dataAccess("patient", "EXPORT").build()))
                .isInstanceOf(ApiException.class);
    }

    // ---------------------------------------------------------------- 平台访问日志（PLT-AUD-004）

    @Test
    void platformAccessEntryWritesPlatformOnlyTable() {
        // 平台访问记录只存平台库：即使在租户上下文里调用也要自路由到平台库
        TenantContext.useTenant(7L, "demoa");
        logger.append(
                AuditEntry.platformAccess(7L, "patient", "PAGE_VIEW")
                        .description("租户视角查看患者页")
                        .viewSessionId("vs-2026-001")
                        .page("/patient/list")
                        .build());

        assertThat(sqls.get(0)).contains("INSERT INTO aud_platform_access_log");
        assertThat(lastArgs())
                .contains(7L, "patient", "PAGE_VIEW", "vs-2026-001", "/patient/list", "SUCCESS");
    }

    @Test
    void platformAccessWriteFailureFailsWholeOperation() {
        TenantContext.usePlatform();
        doThrow(new BadSqlGrammarException("audit", "INSERT", null))
                .when(chain)
                .insert(any(), anyString(), any());
        assertThatThrownBy(
                        () ->
                                logger.append(
                                        AuditEntry.platformAccess(7L, "patient", "DENIED")
                                                .result(AuditResult.DENIED)
                                                .build()))
                .isInstanceOf(ApiException.class);
    }

    // ---------------------------------------------------------------- 遗留桥接（record）

    @Test
    void legacyRecordBridgeKeepsEventTypeAndActionAndEnrichesSpecColumns() {
        TenantContext.useTenant(7L, "demoa");
        logger.record(new AuditEvent("ROLE_UPDATE", 42L, "UPDATE", "sys_role:9", "修改角色：随访员（示例）"));

        assertThat(sqls.get(0)).contains("INSERT INTO aud_log");
        // M0 的 event_type（事件名）与 action（CRUD 动词）两列语义保留（既有用例依赖）
        assertThat(lastArgs())
                .containsSubsequence("ROLE_UPDATE", 42L, "UPDATE", "sys_role:9", "修改角色：随访员（示例）");
        assertThat(lastArgs()).contains("doctor.demo", "张医生（示例）", "HOSPITAL", "DOCTOR", "SUCCESS");
    }

    @Test
    void legacyRecordBridgeOnPlatformWritesPltAuditLogWithSpecColumns() {
        TenantContext.usePlatform();
        logger.record(
                new AuditEvent(
                        "TENANT_STATUS", 1L, "UPDATE", "plt_tenant:demoa", "状态 TRIAL→ENABLED"));

        assertThat(sqls.get(0)).contains("INSERT INTO plt_audit_log");
        assertThat(lastArgs()).contains("TENANT_STATUS", "UPDATE", "plt_tenant:demoa", "SUCCESS");
    }

    @Test
    void legacyRecordBridgeResolvesTenantIdFromPltTenantTarget() {
        TenantContext.usePlatform();
        // M0 target（plt_tenant:编码）由写入服务反查补 tenant_id（PLT-TEN-022 按租户维度检索）
        org.mockito.Mockito.when(
                        jdbc.queryForObject(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.eq(Long.class),
                                org.mockito.ArgumentMatchers.eq("demoa")))
                .thenReturn(5L);
        logger.record(
                new AuditEvent(
                        "TENANT_STATUS", 1L, "UPDATE", "plt_tenant:demoa", "状态 TRIAL→ENABLED"));

        assertThat(lastArgs()).containsSubsequence("TENANT_STATUS", 1L, 5L);
    }

    // ---------------------------------------------------------------- 自动补全与结果语义

    @Test
    void missingActorFieldsAreEnrichedFromCurrentSession() {
        TenantContext.useTenant(7L, "demoa");
        logger.append(AuditEntry.operation("system", "PARAM_UPDATE").build());

        assertThat(lastArgs()).contains(42L, "doctor.demo", "张医生（示例）", "HOSPITAL", "DOCTOR");
    }

    @Test
    void resultDefaultsToSuccessAndDeniedIsKept() {
        TenantContext.useTenant(7L, "demoa");
        logger.append(
                AuditEntry.operation("system", "ROLE_GRANT")
                        .result(AuditResult.DENIED)
                        .errorCode(40300)
                        .reason("缺少权限 tenant:role:grant")
                        .build());

        assertThat(lastArgs()).contains("DENIED", 40300, "缺少权限 tenant:role:grant");
    }

    @Test
    void loginEntryWithoutTenantContextGoesPlatformSide() {
        // 租户未识别的医院登录失败（unknown-tenant）写平台库登录日志，不抛缺上下文异常
        logger.loginAttempt("nobody.demo", false, "unknown-tenant");

        assertThat(sqls.get(0)).contains("INSERT INTO aud_login_log");
        assertThat(lastArgs()).contains("nobody.demo", 0, "unknown-tenant");
    }

    // ---------------------------------------------------------------- 写入后事件（E08-T05 安全告警入口）

    @Test
    void appendedEventIsPublishedAfterSuccessfulWrite() {
        TenantContext.useTenant(7L, "demoa");
        logger.append(AuditEntry.login("doctor.demo").action("LOCK").success().build());

        assertThat(events).hasSize(1);
        // 事件携带补全后的入口（result / traceId 缺省值已填），供规则评估（SecurityAlertService）
        assertThat(events.get(0).entry().action()).isEqualTo("LOCK");
        assertThat(events.get(0).entry().result()).isEqualTo(AuditResult.SUCCESS);
        assertThat(events.get(0).entry().traceId()).isNotNull();
        // 遗留桥接同样经 append 发布（record → append）
        logger.record(new AuditEvent("ROLE_MEMBERS", 42L, "UPDATE", "sys_role:9", "调整成员（示例）"));
        assertThat(events).hasSize(2);
        assertThat(events.get(1).entry().eventType()).isEqualTo("ROLE_MEMBERS");
    }

    @Test
    void noEventIsPublishedWhenWriteFails() {
        TenantContext.useTenant(7L, "demoa");
        doThrow(new BadSqlGrammarException("audit", "INSERT", null))
                .when(chain)
                .insert(any(), anyString(), any());
        assertThatThrownBy(() -> logger.append(AuditEntry.login("doctor.demo").success().build()))
                .isInstanceOf(ApiException.class);
        assertThat(events).isEmpty();
    }

    // ---------------------------------------------------------------- 插入语句与表一一对应（防串列）

    @Test
    void everyInsertHasMatchingPlaceholderCount() {
        TenantContext.useTenant(7L, "demoa");
        logger.append(AuditEntry.operation("system", "A").build());
        logger.append(AuditEntry.login("u").success().build());
        logger.append(AuditEntry.dataAccess("patient", "BATCH_QUERY").build());
        logger.append(AuditEntry.platformAccess(7L, "patient", "PAGE_VIEW").build());
        TenantContext.usePlatform();
        logger.append(AuditEntry.operation("platform", "B").build());
        logger.append(AuditEntry.login("p").success().build());

        for (int i = 0; i < sqls.size(); i++) {
            long placeholders = sqls.get(i).chars().filter(ch -> ch == '?').count();
            // 契约：业务参数 + prev_hash、hash 两个封存参数（由 AuditChainWriter 补齐）
            assertThat(argRows.get(i))
                    .as("第 %d 条插入 %s", i, sqls.get(i))
                    .hasSize((int) placeholders - 2);
            assertThat(sqls.get(i)).contains("prev_hash").contains("hash");
        }
    }
}
