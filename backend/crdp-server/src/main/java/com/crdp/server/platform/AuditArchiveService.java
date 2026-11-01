package com.crdp.server.platform;

import com.crdp.framework.audit.AuditArchiveProperties;
import com.crdp.framework.audit.AuditArchiver;
import com.crdp.framework.audit.AuditChainTable;
import com.crdp.framework.job.JobScheduler;
import com.crdp.framework.job.TenantJobResult;
import com.crdp.framework.storage.AuditArchiveStorage;
import com.crdp.framework.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 审计归档作业（E08-T03 / PLT-AUD-010，验收：归档对象锁定 15 年、在线保留 1 年）。
 *
 * <p>每日（04:10，北京时间）按租户扇出归档该院三张审计表，再归档平台库三张； 单租户失败不中断其他租户（JobScheduler 保证，job_tenant_run 记执行）。
 * 每个库先做分区维护（补齐未来月份，见 {@link AuditArchiver#ensurePartitions}），再归档到期分区（导出 JSONL.gz + 清单、单次 PUT 写入
 * WORM 桶并设对象级保留 = 归档日 + 15 年、读回校验、登记 aud_archive、DROP PARTITION）。 归档桶只写北京四一份（不做异地副本，已确认）。
 *
 * <p>归档桶未配置（本机 / CI 无云凭证，{@link AuditArchiveStorage#enabled()} = false）时只做分区维护，不上传、不删在线数据； 桶级 WORM
 * 开启与归档专用删分区账号由运维负责（E01-T08 / E22-T08）。对象键前缀：租户 {@code audit/tenant/{编码}}、平台 {@code
 * audit/platform}。
 */
@Service
public class AuditArchiveService {

    static final String JOB_CODE = "aud-archive";

    private static final Logger log = LoggerFactory.getLogger(AuditArchiveService.class);

    private final JobScheduler jobScheduler;
    private final AuditArchiver archiver;
    private final AuditArchiveStorage store;
    private final AuditArchiveProperties properties;

    public AuditArchiveService(
            JobScheduler jobScheduler,
            AuditArchiver archiver,
            AuditArchiveStorage store,
            AuditArchiveProperties properties) {
        this.jobScheduler = jobScheduler;
        this.archiver = archiver;
        this.store = store;
        this.properties = properties;
    }

    /** 作业入口：先各租户库、后平台库。逻辑日取北京时间当天（补跑传历史日期）。 */
    public List<TenantJobResult> runForDate(LocalDate logicalDate) {
        if (!store.enabled()) {
            log.warn("审计归档桶未配置（E10/E22 接入前）：本轮只做分区维护，不归档、不删在线数据（PLT-AUD-010）");
        }
        AuditArchiver.ArchivePolicy policy = properties.toPolicy();
        List<TenantJobResult> results =
                jobScheduler.runForEachTenant(
                        JOB_CODE,
                        logicalDate,
                        date -> {
                            TenantContext.State tenant = TenantContext.require();
                            List<AuditArchiver.PartitionOutcome> outcomes =
                                    archiver.runForDatabase(
                                            AuditChainTable.tenantTables(),
                                            "audit/tenant/" + tenant.tenantCode(),
                                            policy,
                                            logicalDate);
                            requireSuccess(outcomes, "租户 " + tenant.tenantCode());
                        });
        archivePlatformSide(policy, logicalDate);
        return results;
    }

    /** 归档平台库三张审计表（对象前缀 audit/platform）。 */
    public void archivePlatformSide(AuditArchiver.ArchivePolicy policy, LocalDate logicalDate) {
        TenantContext.runAsPlatform(
                () -> {
                    List<AuditArchiver.PartitionOutcome> outcomes =
                            archiver.runForDatabase(
                                    AuditChainTable.platformTables(),
                                    "audit/platform",
                                    policy,
                                    logicalDate);
                    requireSuccess(outcomes, "平台库");
                });
    }

    /** 有失败 / 冲突就抛（作业记 FAILED），全部结果先逐条记日志。 */
    private void requireSuccess(List<AuditArchiver.PartitionOutcome> outcomes, String side) {
        for (AuditArchiver.PartitionOutcome outcome : outcomes) {
            if (outcome.status() == AuditArchiver.OutcomeStatus.CONFLICT
                    || outcome.status() == AuditArchiver.OutcomeStatus.FAILED) {
                log.error(
                        "审计归档未完成（分区保留在线）side={} table={} partition={} status={} detail={}",
                        side,
                        outcome.table(),
                        outcome.partition(),
                        outcome.status(),
                        outcome.detail());
            }
        }
        boolean broken = outcomes.stream().anyMatch(outcome -> !outcome.success());
        if (broken) {
            throw new IllegalStateException(side + " 审计归档存在失败或冲突分区（PLT-AUD-010，详见作业日志）");
        }
    }
}
