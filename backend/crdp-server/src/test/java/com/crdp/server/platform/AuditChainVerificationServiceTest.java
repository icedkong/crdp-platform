package com.crdp.server.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditChainVerifier;
import com.crdp.framework.job.JobScheduler;
import com.crdp.framework.job.TenantJob;
import com.crdp.framework.job.TenantJobResult;
import com.crdp.framework.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E08-T02 / PLT-AUD-009：验链作业的结果落库与断链报错。 断链时按「租户 × 表」追加 BROKEN 记录（含首个断点定位）并让该租户本趟作业失败；
 * 平台库断链同样记录并抛错。真实 MySQL 链路见 {@code AuditChainMySqlIT}。
 */
class AuditChainVerificationServiceTest {

    private final JobScheduler jobScheduler = mock(JobScheduler.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditChainVerifier verifier = mock(AuditChainVerifier.class);

    private final AuditChainVerificationService service =
            new AuditChainVerificationService(jobScheduler, jdbc, verifier);

    @AfterEach
    void cleanContext() {
        TenantContext.clear();
    }

    @Test
    void intactTenantRecordsPassRows() {
        doAnswer(
                        invocation -> {
                            TenantJob job = invocation.getArgument(2);
                            TenantContext.runAsTenant(7L, "demoa", () -> runQuietly(job));
                            return List.<TenantJobResult>of();
                        })
                .when(jobScheduler)
                .runForEachTenant(anyString(), any(LocalDate.class), any());
        when(verifier.verifyTenantDatabase())
                .thenReturn(
                        List.of(
                                new AuditChainVerifier.TableVerification(
                                        "aud_log", 10, 10, List.of())));
        when(verifier.verifyPlatformDatabase()).thenReturn(List.of());

        service.runForDate(LocalDate.of(2027, 1, 18));

        // 租户表记 PASS（tenant_id=7）；平台库三表本例返回空（mock），不落记录
        verify(jdbc)
                .update(
                        ArgumentMatchers.contains("aud_chain_check"),
                        eq(7L),
                        eq("aud_log"),
                        eq(10L),
                        eq(0),
                        eq("PASS"),
                        isNull(),
                        isNull(),
                        isNull());
    }

    @Test
    void brokenChainRecordsBrokenRowAndFailsTheTenantRun() {
        doAnswer(
                        invocation -> {
                            TenantJob job = invocation.getArgument(2);
                            TenantContext.runAsTenant(7L, "demoa", () -> runQuietly(job));
                            return List.<TenantJobResult>of();
                        })
                .when(jobScheduler)
                .runForEachTenant(anyString(), any(LocalDate.class), any());
        AuditChainVerifier.ChainBreak breakRow =
                new AuditChainVerifier.ChainBreak(
                        "aud_login_log",
                        33L,
                        AuditChainVerifier.BreakKind.BAD_HASH,
                        "存量 hash 与按内容重算值不符");
        when(verifier.verifyTenantDatabase())
                .thenReturn(
                        List.of(
                                new AuditChainVerifier.TableVerification(
                                        "aud_login_log", 8, 8, List.of(breakRow))));
        when(verifier.verifyPlatformDatabase()).thenReturn(List.of());

        // 断链 → 抛错（真实 JobScheduler 记 FAILED；这里直接断言报错语义）
        assertThatThrownBy(() -> service.runForDate(LocalDate.of(2027, 1, 18)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demoa");
        verify(jdbc)
                .update(
                        ArgumentMatchers.contains("aud_chain_check"),
                        eq(7L),
                        eq("aud_login_log"),
                        eq(8L),
                        eq(1),
                        eq("BROKEN"),
                        eq(33L),
                        eq("BAD_HASH"),
                        ArgumentMatchers.contains("BAD_HASH#33"));
    }

    @Test
    void brokenPlatformChainIsRecordedAndReported() {
        when(verifier.verifyPlatformDatabase())
                .thenReturn(
                        List.of(
                                new AuditChainVerifier.TableVerification(
                                        "plt_audit_log",
                                        5,
                                        5,
                                        List.of(
                                                new AuditChainVerifier.ChainBreak(
                                                        "plt_audit_log",
                                                        9L,
                                                        AuditChainVerifier.BreakKind.BROKEN_LINK,
                                                        "prev_hash 与实际前序不衔接")))));
        assertThatThrownBy(() -> service.verifyPlatformSide())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("平台库");
        verify(jdbc)
                .update(
                        ArgumentMatchers.contains("aud_chain_check"),
                        isNull(),
                        eq("plt_audit_log"),
                        eq(5L),
                        eq(1),
                        eq("BROKEN"),
                        eq(9L),
                        eq("BROKEN_LINK"),
                        anyString());
        assertThat(TenantContext.current()).isEmpty(); // 平台上下文已清
    }

    private static void runQuietly(TenantJob job) {
        try {
            job.run(LocalDate.of(2027, 1, 18));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
