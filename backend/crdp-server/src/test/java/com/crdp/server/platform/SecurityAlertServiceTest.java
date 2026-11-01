package com.crdp.server.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditAppendedEvent;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.audit.AuditType;
import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E08-T05 / PLT-AUD-012：安全告警规则与站内信派发。 命中规则（异常登录 LOCK、批量导出 EXPORT、权限变更 ROLE_*、平台访问 VIEW_*）即在平台库
 * plt_security_alert 按接收人（启用的 PLATFORM_ADMIN）落站内信派发行（INBOX、PENDING，通道消费随 E09，同 OQ-44 口径）。
 *
 * <p>真库链路（登录锁定 HTTP 端到端、导出 / 权限变更探针）见 {@code SecurityAlertMySqlIT}。
 */
class SecurityAlertServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private final SecurityAlertService service = new SecurityAlertService(jdbc);

    @AfterEach
    void cleanContext() {
        TenantContext.clear();
    }

    private void withPlatformAdmin() {
        when(jdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("id", 11L, "display_name", "平台演示管理员")));
    }

    // ---------------------------------------------------------------- 规则匹配（纯函数）

    @Test
    void lockEventMatchesAbnormalLogin() {
        assertThat(
                        SecurityAlertService.match(
                                AuditEntry.login("doctor.demo").action("LOCK").success().build()))
                .isEqualTo(SecurityAlertService.RULE_ABNORMAL_LOGIN);
    }

    @Test
    void successfulLoginDoesNotMatch() {
        assertThat(SecurityAlertService.match(AuditEntry.login("doctor.demo").success().build()))
                .isNull();
    }

    @Test
    void exportAccessMatchesBatchExport() {
        assertThat(
                        SecurityAlertService.match(
                                AuditEntry.dataAccess("system", "EXPORT").success().build()))
                .isEqualTo(SecurityAlertService.RULE_BATCH_EXPORT);
    }

    @Test
    void deniedExportAndDownloadDoNotMatch() {
        assertThat(
                        SecurityAlertService.match(
                                AuditEntry.dataAccess("system", "EXPORT")
                                        .result(AuditResult.DENIED)
                                        .build()))
                .isNull();
        assertThat(SecurityAlertService.match(AuditEntry.dataAccess("system", "DOWNLOAD").build()))
                .isNull();
    }

    @Test
    void roleOperationMatchesPermissionChange() {
        // 新口径：action 即事件名（ROLE_FIELD_POLICY）
        assertThat(
                        SecurityAlertService.match(
                                AuditEntry.operation("system", "ROLE_FIELD_POLICY")
                                        .eventType("ROLE_FIELD_POLICY")
                                        .success()
                                        .build()))
                .isEqualTo(SecurityAlertService.RULE_PERMISSION_CHANGE);
        // M0 桥接：eventType 是 ROLE_*，action 是 CRUD 动词（RoleAdminService.audit）
        assertThat(
                        SecurityAlertService.match(
                                new AuditEntry.Builder(AuditType.OPERATION)
                                        .action("UPDATE")
                                        .eventType("ROLE_MEMBERS")
                                        .success()
                                        .build()))
                .isEqualTo(SecurityAlertService.RULE_PERMISSION_CHANGE);
        assertThat(
                        SecurityAlertService.match(
                                AuditEntry.operation("system", "USER_UPDATE").build()))
                .isNull();
    }

    @Test
    void rateLimitedPlaintextViewMatchesPlaintextAbuse() {
        // E07-T05：明文查看被频控拒绝（DENIED + reason=RATE_LIMITED）是滥用信号本身（patient.md §5）
        assertThat(
                        SecurityAlertService.match(
                                AuditEntry.dataAccess("followup", "VIEW_PLAIN")
                                        .result(AuditResult.DENIED)
                                        .errorCode(42900)
                                        .reason("RATE_LIMITED")
                                        .build()))
                .isEqualTo(SecurityAlertService.RULE_PLAINTEXT_ABUSE);
        // 正常的明文查看（SUCCESS）不告警；其他拒绝原因（越范围 40400）不告警
        assertThat(
                        SecurityAlertService.match(
                                AuditEntry.dataAccess("followup", "VIEW_PLAIN").success().build()))
                .isNull();
        assertThat(
                        SecurityAlertService.match(
                                AuditEntry.dataAccess("followup", "VIEW_PLAIN")
                                        .result(AuditResult.DENIED)
                                        .reason("PATIENT_NOT_FOUND_OR_OUT_OF_SCOPE")
                                        .build()))
                .isNull();
    }

    @Test
    void platformViewActionMatchesPlatformAccess() {
        assertThat(
                        SecurityAlertService.match(
                                AuditEntry.platformAccess(7L, "audit", "VIEW_RULE_CREATE")
                                        .success()
                                        .build()))
                .isEqualTo(SecurityAlertService.RULE_PLATFORM_ACCESS);
        // 写入侧随 E20-T01：非视角动作（如 PAGE_VIEW 探针）不告警
        assertThat(
                        SecurityAlertService.match(
                                AuditEntry.platformAccess(7L, "audit", "PAGE_VIEW").build()))
                .isNull();
    }

    // ---------------------------------------------------------------- 派发（落库）

    @Test
    void lockInTenantContextWritesInboxNoticesForPlatformAdmins() {
        withPlatformAdmin();
        TenantContext.runAsTenant(
                7L,
                "demoa",
                () ->
                        service.onAuditAppended(
                                new AuditAppendedEvent(
                                        AuditEntry.login("doctor.demo")
                                                .action("LOCK")
                                                .success()
                                                .description("连续失败达到阈值，账号锁定")
                                                .ip("10.99.1.8")
                                                .build())));

        // 告警事实 + 派发行落平台库（channel=INBOX、send_status=PENDING 为 SQL 字面量），
        // 租户来自触发时的租户上下文（登录日志本身不带 tenant_id）
        verify(jdbc)
                .update(
                        contains("INSERT INTO plt_security_alert"),
                        eq(SecurityAlertService.RULE_ABNORMAL_LOGIN),
                        any(), // alert_key（traceId，幂等去重键）
                        eq(7L),
                        any(),
                        eq("doctor.demo"),
                        any(),
                        any(),
                        any(),
                        eq("LOCK"),
                        any(),
                        any(),
                        contains("doctor.demo"),
                        any(),
                        eq(11L),
                        eq("平台演示管理员"));
    }

    @Test
    void exportAlertCarriesTenantIdFromEntryAndRunsInPlatformContext() {
        withPlatformAdmin();
        doAnswer(
                        invocation -> {
                            assertThat(TenantContext.current())
                                    .as("派发必须切平台上下文写平台库")
                                    .map(TenantContext.State::platform)
                                    .contains(true);
                            return 1;
                        })
                .when(jdbc)
                .update(anyString(), any(Object[].class));
        TenantContext.runAsTenant(
                7L,
                "demoa",
                () ->
                        service.onAuditAppended(
                                new AuditAppendedEvent(
                                        AuditEntry.dataAccess("system", "EXPORT")
                                                .tenantId(7L)
                                                .success()
                                                .description("租户管理员导出审计日志（免审批）：操作日志")
                                                .objectType("AUDIT_EXPORT")
                                                .objectId("33")
                                                .build())));

        verify(jdbc)
                .update(
                        contains("INSERT INTO plt_security_alert"),
                        eq(SecurityAlertService.RULE_BATCH_EXPORT),
                        any(), // alert_key（traceId）
                        eq(7L),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq("AUDIT_EXPORT"),
                        eq("33"),
                        contains("导出审计日志"),
                        any(),
                        eq(11L),
                        eq("平台演示管理员"));
    }

    @Test
    void noRecipientSkipsInsertButDoesNotFail() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        assertThatCode(
                        () ->
                                service.onAuditAppended(
                                        new AuditAppendedEvent(
                                                AuditEntry.login("doctor.demo")
                                                        .action("LOCK")
                                                        .success()
                                                        .build())))
                .doesNotThrowAnyException();
        // 没有可用接收人时不落派发行（单表按接收人展开），也不报错——只记 WARN
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void blankTraceIdLandsAsNullAlertKeyToAvoidCrossTriggerDedup() {
        // 回归：程序化直写无 MDC 时 traceId 是空串（TraceIds.current() 兜底返回 ""）。
        // 空串是真实的唯一键值，会把同规则所有无 traceId 的触发错误去重成一条；必须归 NULL（唯一键对 NULL 不去重）
        withPlatformAdmin();
        service.onAuditAppended(
                new AuditAppendedEvent(
                        AuditEntry.operation("system", "ROLE_MEMBERS")
                                .traceId("")
                                .success()
                                .build()));

        verify(jdbc)
                .update(
                        anyString(),
                        eq(SecurityAlertService.RULE_PERMISSION_CHANGE),
                        isNull(), // alert_key：空 traceId 归 NULL
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void alertRepositoryFailureIsSwallowedNotPropagated() {
        withPlatformAdmin();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("platform db down"))
                .when(jdbc)
                .update(anyString(), any(Object[].class));
        assertThatCode(
                        () ->
                                service.onAuditAppended(
                                        new AuditAppendedEvent(
                                                AuditEntry.login("doctor.demo")
                                                        .action("LOCK")
                                                        .success()
                                                        .build())))
                .as("告警派发失败只记 ERROR 日志，不阻断业务（审计已写入）")
                .doesNotThrowAnyException();
    }
}
