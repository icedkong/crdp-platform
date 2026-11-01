package com.crdp.server.platform;

import com.crdp.framework.audit.AuditChainVerifier;
import com.crdp.framework.job.JobScheduler;
import com.crdp.framework.job.TenantJobResult;
import com.crdp.framework.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 审计哈希链验链作业（E08-T02 / PLT-AUD-009，验收：篡改任一条验链即报错）。
 *
 * <p>每日（03:40，北京时间）按租户扇出校验该院三张审计表，再校验平台库三张； 单租户失败不中断其他租户（JobScheduler 保证，job_tenant_run 记执行）。结果按「租户
 * × 表」追加写入平台库 aud_chain_check（PASS / BROKEN + 首个断点定位）， 「最近一次校验结果」查询接口随 E08-T04 落地（audit.md §3）。
 *
 * <p>断链告警（PLT-AUD-009）：发现断链即 ERROR 日志（含租户、表、断点 id 与类型）+ aud_chain_check 记 BROKEN + 本趟作业标
 * FAILED；站内信通道接入随 E08-T05 / E09（NotifySender 目前为占位实现，强发只会把断链换成 500）。
 */
@Service
public class AuditChainVerificationService {

    static final String JOB_CODE = "aud-chain-verify";

    private static final Logger log = LoggerFactory.getLogger(AuditChainVerificationService.class);

    private final JobScheduler jobScheduler;

    private final JdbcTemplate platformJdbc;

    private final AuditChainVerifier verifier;

    public AuditChainVerificationService(
            JobScheduler jobScheduler, JdbcTemplate jdbc, AuditChainVerifier verifier) {
        this.jobScheduler = jobScheduler;
        this.platformJdbc = jdbc;
        this.verifier = verifier;
    }

    /** 作业入口：先各租户库、后平台库。逻辑日取北京时间当天（调度器传入）。 */
    public List<TenantJobResult> runForDate(LocalDate logicalDate) {
        List<TenantJobResult> results =
                jobScheduler.runForEachTenant(
                        JOB_CODE,
                        logicalDate,
                        date -> {
                            TenantContext.State tenant = TenantContext.require();
                            verifyAndRecord(tenant.tenantId(), tenant.tenantCode());
                        });
        verifyPlatformSide();
        return results;
    }

    /** 校验平台库三张审计表并记结果（tenant_id 为 NULL 表示平台库自身）。 */
    public void verifyPlatformSide() {
        TenantContext.runAsPlatform(
                () -> {
                    List<AuditChainVerifier.TableVerification> tables =
                            verifier.verifyPlatformDatabase();
                    boolean broken = tables.stream().anyMatch(table -> !table.intact());
                    recordAll(null, tables);
                    if (broken) {
                        throw new IllegalStateException("平台库审计哈希链校验发现断链（PLT-AUD-009）");
                    }
                });
    }

    /** 在租户上下文内校验该院库并切平台上下文记结果；发现断链抛出（作业记 FAILED）。 */
    private void verifyAndRecord(long tenantId, String tenantCode) {
        List<AuditChainVerifier.TableVerification> tables = verifier.verifyTenantDatabase();
        TenantContext.runAsPlatform(() -> recordAll(tenantId, tables));
        boolean broken = tables.stream().anyMatch(table -> !table.intact());
        if (broken) {
            throw new IllegalStateException("租户 " + tenantCode + " 审计哈希链校验发现断链（PLT-AUD-009）");
        }
    }

    private void recordAll(Long tenantId, List<AuditChainVerifier.TableVerification> tables) {
        for (AuditChainVerifier.TableVerification table : tables) {
            AuditChainVerifier.ChainBreak first =
                    table.breaks().isEmpty() ? null : table.breaks().get(0);
            platformJdbc.update(
                    """
                    INSERT INTO aud_chain_check
                      (tenant_id, table_name, hashed_rows, break_count, status,
                       first_break_id, first_break_kind, detail)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    tenantId,
                    table.table(),
                    table.hashedRows(),
                    table.breaks().size(),
                    table.intact() ? "PASS" : "BROKEN",
                    first == null ? null : first.id(),
                    first == null ? null : first.kind().name(),
                    clip(table.breaks()));
            if (!table.intact()) {
                log.error(
                        "审计哈希链校验发现断链（PLT-AUD-009）tenantId={} table={} hashedRows={} breaks={}",
                        tenantId,
                        table.table(),
                        table.hashedRows(),
                        table.breaks());
            }
        }
    }

    private static String clip(List<AuditChainVerifier.ChainBreak> breaks) {
        if (breaks.isEmpty()) {
            return null;
        }
        StringBuilder detail = new StringBuilder();
        for (AuditChainVerifier.ChainBreak breakRow : breaks) {
            if (detail.length() > 0) {
                detail.append("; ");
            }
            detail.append(breakRow.kind().name())
                    .append('#')
                    .append(breakRow.id())
                    .append(' ')
                    .append(breakRow.detail());
        }
        return detail.length() <= 512 ? detail.toString() : detail.substring(0, 512);
    }
}
