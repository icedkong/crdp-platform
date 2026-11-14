package com.crdp.followup.file.app;

import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
import com.crdp.followup.file.infra.scan.VirusScanProperties;
import com.crdp.followup.file.infra.scan.VirusScanner;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.job.JobScheduler;
import com.crdp.framework.job.TenantJobResult;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 存量文件病毒补扫作业（E22-T15 / 等保自查 D-01 整改，tech-plan §10：「用已记录的 SHA-256 对存量文件补扫」）。
 *
 * <p>补扫对象 = {@code sys_file.virus_scan_status='UNSCANNED'} 的未删除行——扫描启用（clamd）前上传的全部存量，
 * 以及启用后仍以禁用态写入的行（不会发生： 启用即同步扫描）。每趟每租户最多处理 {@code crdp.file.virus-scan.retro-batch-limit} 行（默认 200），
 * 大存量按晚逐趟推进。
 *
 * <p>逐行口径：按台账 SHA-256 读回对象并核对完整性——哈希不一致标 HASH_MISMATCH（对象被改动，完整性事件， 不猜结论）；一致再扫描：干净 → CLEAN，检出 →
 * INFECTED + FILE_VIRUS_FOUND 审计（不含患者信息）。检出文件**不自动删除**（临床附件处置 由安全会诊定夺，整改手册 §4）；台账 INFECTED
 * 即为待处置清单。扫描不可用抛 50300——本租户本趟 FAILED， 次晚重试（状态守卫 WHERE virus_scan_status='UNSCANNED' 幂等，无半状态）。
 *
 * <p>注册在 crdp-server（{@code VirusScanRetroScheduler}，每日 03:20）；按租户扇出与 job_tenant_run 记录 由 {@link
 * JobScheduler} 框架保证（ADR 0003）。扫描禁用时本作业为 no-op（不产生 FAILED 记录——禁用是配置态不是故障）。
 */
@Service
public class VirusScanRetroService {

    private static final Logger log = LoggerFactory.getLogger(VirusScanRetroService.class);

    /** job_tenant_run 作业码（ADR 0003：按租户 × 日记 RUNNING/SUCCESS/FAILED）。 */
    static final String JOB_CODE = "file-virus-retro-scan";

    private final JobScheduler jobScheduler;
    private final SysFileMapper files;
    private final ObjectStorage storage;
    private final VirusScanner virusScanner;
    private final VirusScanProperties properties;
    private final AuditLogger audit;

    public VirusScanRetroService(
            JobScheduler jobScheduler,
            SysFileMapper files,
            ObjectStorage storage,
            VirusScanner virusScanner,
            VirusScanProperties properties,
            AuditLogger audit) {
        this.jobScheduler = jobScheduler;
        this.files = files;
        this.storage = storage;
        this.virusScanner = virusScanner;
        this.properties = properties;
        this.audit = audit;
    }

    /** 作业入口（定时注册在 crdp-server）：对全部启用租户补扫一趟。 */
    public List<TenantJobResult> runRetroScan() {
        return jobScheduler.runForEachTenant(JOB_CODE, LocalDate.now(), this::runForCurrentTenant);
    }

    /** 租户上下文内的作业体：禁用态 no-op；启用态按批补扫并回写结论。 */
    void runForCurrentTenant(LocalDate logicalDate) {
        if (!virusScanner.enabled()) {
            log.debug("病毒扫描未启用，补扫作业跳过 tenant={}", TenantContext.require().tenantCode());
            return;
        }
        List<SysFileRow> batch = files.findUnscanned(properties.getRetroBatchLimit());
        if (batch.isEmpty()) {
            return;
        }
        String prefix = "t/" + TenantContext.require().tenantCode() + "/";
        int clean = 0;
        int infected = 0;
        int mismatch = 0;
        for (SysFileRow row : batch) {
            String relativeKey = relativeKeyOf(prefix, row.getObjectKey());
            if (relativeKey == null) {
                log.warn(
                        "补扫跳过：对象键不带本院前缀（不猜测归属）tenant={} fileId={} keyPrefix未匹配",
                        TenantContext.require().tenantCode(),
                        row.getId());
                continue;
            }
            byte[] body = storage.get(relativeKey);
            if (body == null) {
                // 对象缺失：保持 UNSCANNED（数据问题单独排查，不虚构结论）
                log.warn("补扫：对象缺失保持 UNSCANNED fileId={}", row.getId());
                continue;
            }
            if (!FileUploadService.sha256Hex(body).equalsIgnoreCase(row.getSha256())) {
                files.markVirusScanResult(row.getId(), "HASH_MISMATCH");
                mismatch++;
                log.error("补扫：对象与台账 SHA-256 不一致（完整性事件）fileId={}", row.getId());
                audit.append(
                        AuditEntry.operation("followup", "FILE_VIRUS_HASH_MISMATCH")
                                .objectType("SYS_FILE")
                                .objectId(String.valueOf(row.getId()))
                                .description(
                                        "存量补扫发现对象内容与上传台账 SHA-256 不一致（fileId="
                                                + row.getId()
                                                + "，疑似对象被改动，转人工处置）")
                                .result(AuditResult.FAIL)
                                .build());
                continue;
            }
            VirusScanner.ScanResult verdict = virusScanner.scan(body);
            if (!verdict.infected()) {
                files.markVirusScanResult(row.getId(), "CLEAN");
                clean++;
            } else {
                files.markVirusScanResult(row.getId(), "INFECTED");
                infected++;
                log.error("补扫：存量文件检出病毒 fileId={} virus={}", row.getId(), verdict.virusName());
                audit.append(
                        AuditEntry.operation("followup", "FILE_VIRUS_FOUND")
                                .objectType("SYS_FILE")
                                .objectId(String.valueOf(row.getId()))
                                .description(
                                        "存量补扫检出病毒：fileId="
                                                + row.getId()
                                                + "（"
                                                + verdict.virusName()
                                                + "），已标记待处置（不自动删除，处置口径见整改手册）")
                                .result(AuditResult.FAIL)
                                .build());
            }
        }
        log.info(
                "存量病毒补扫完成 tenant={} batch={} clean={} infected={} mismatch={}",
                TenantContext.require().tenantCode(),
                batch.size(),
                clean,
                infected,
                mismatch);
        audit.append(
                AuditEntry.operation("followup", "FILE_VIRUS_RETRO")
                        .objectType("SYS_FILE")
                        .description(
                                "存量文件病毒补扫作业：本趟处理 "
                                        + batch.size()
                                        + " 个（干净 "
                                        + clean
                                        + "、检出 "
                                        + infected
                                        + "、哈希不一致 "
                                        + mismatch
                                        + "，自动）")
                        .afterJson(
                                "{\"batch\":"
                                        + batch.size()
                                        + ",\"clean\":"
                                        + clean
                                        + ",\"infected\":"
                                        + infected
                                        + ",\"mismatch\":"
                                        + mismatch
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
    }

    /** 完整键剥离本院前缀（存储端口按相对键 + 当前租户前缀取对象）；不带前缀的键不猜（返回 null 跳过）。 */
    private static String relativeKeyOf(String prefix, String objectKey) {
        if (objectKey == null
                || !objectKey.startsWith(prefix)
                || objectKey.length() <= prefix.length()) {
            return null;
        }
        return objectKey.substring(prefix.length());
    }
}
