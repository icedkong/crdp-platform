package com.crdp.followup.file.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
import com.crdp.followup.file.infra.scan.VirusScanProperties;
import com.crdp.followup.file.infra.scan.VirusScanner;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.job.JobScheduler;
import com.crdp.framework.storage.LocalObjectStorage;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * E22-T15 / D-01 整改：存量补扫作业单元测试（真实 {@link LocalObjectStorage} 桶替身 + 桩扫描器）。 锁定：禁用态 no-op；干净 →
 * CLEAN；检出 → INFECTED + FILE_VIRUS_FOUND 审计（不自动删除）； 对象与台账 SHA-256 不一致 → HASH_MISMATCH + 完整性审计；
 * 扫描不可用异常外抛（作业框架记本租户本趟 FAILED，状态守卫幂等）。
 */
class VirusScanRetroServiceTest {

    @TempDir Path storageRoot;

    private final SysFileMapper files = mock(SysFileMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final JobScheduler jobScheduler = mock(JobScheduler.class);
    private final VirusScanProperties properties = new VirusScanProperties();
    private ObjectStorage storage;

    @BeforeEach
    void tenant() {
        TenantContext.useTenant(1L, "demoa");
        storage = new LocalObjectStorage(storageRoot);
        lenient().when(files.findUnscanned(anyInt())).thenReturn(List.of());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** 扫描禁用（mode=off）：作业 no-op——不查批次、不写结论、不写审计（禁用是配置态不是故障）。 */
    @Test
    void disabledScannerIsNoOp() {
        VirusScanRetroService service =
                new VirusScanRetroService(
                        jobScheduler, files, storage, disabledScanner(), properties, audit);

        service.runForCurrentTenant(LocalDate.of(2026, 11, 14));

        verify(files, never()).findUnscanned(anyInt());
        verify(audit, never()).append(any(AuditEntry.class));
    }

    /** 干净存量：读回对象、SHA-256 核对通过、扫描通过 → CLEAN；汇总审计 FILE_VIRUS_RETRO。 */
    @Test
    void cleanStockMarkedClean() throws IOException {
        byte[] body = pdfBody();
        String key = storage.put("files/202611/a.pdf", body, "application/pdf");
        when(files.findUnscanned(200)).thenReturn(List.of(row(11L, key, body)));
        VirusScanRetroService service =
                new VirusScanRetroService(
                        jobScheduler, files, storage, cleanScanner(), properties, audit);

        service.runForCurrentTenant(LocalDate.of(2026, 11, 14));

        verify(files).markVirusScanResult(11L, "CLEAN");
        verify(audit)
                .append(
                        argThat(
                                entry ->
                                        entry != null
                                                && "FILE_VIRUS_RETRO".equals(entry.action())
                                                && entry.description().contains("干净 1")));
    }

    /** 检出存量：INFECTED + FILE_VIRUS_FOUND 审计；不删除对象（处置口径 = 会诊，台账即待处置清单）。 */
    @Test
    void infectedStockMarkedAndAuditedNotDeleted() throws IOException {
        byte[] body = pdfBody();
        String key = storage.put("files/202611/b.pdf", body, "application/pdf");
        when(files.findUnscanned(200)).thenReturn(List.of(row(12L, key, body)));
        VirusScanRetroService service =
                new VirusScanRetroService(
                        jobScheduler,
                        files,
                        storage,
                        infectedScanner("Win.Test.EICAR.HDB-1"),
                        properties,
                        audit);

        service.runForCurrentTenant(LocalDate.of(2026, 11, 14));

        verify(files).markVirusScanResult(12L, "INFECTED");
        verify(audit)
                .append(
                        argThat(
                                entry ->
                                        entry != null
                                                && "FILE_VIRUS_FOUND".equals(entry.action())
                                                && entry.objectId().equals("12")
                                                && entry.description().contains("不自动删除")));
        // 对象仍在（不自动删除）
        assertEquals(body.length, storage.get(key.substring("t/demoa/".length())).length);
    }

    /** 对象被改动（与台账 SHA-256 不一致）：HASH_MISMATCH + 完整性审计，不猜扫描结论。 */
    @Test
    void tamperedObjectMarkedHashMismatch() throws IOException {
        byte[] body = pdfBody();
        String key = storage.put("files/202611/c.pdf", body, "application/pdf");
        // 上传后对象被改：读回内容与台账哈希不一致
        byte[] tampered = new byte[body.length];
        System.arraycopy(body, 0, tampered, 0, body.length);
        tampered[body.length - 1] ^= 0x01;
        storage.delete(key.substring("t/demoa/".length()));
        storage.put("files/202611/c.pdf", tampered, "application/pdf");
        when(files.findUnscanned(200)).thenReturn(List.of(row(13L, key, body)));
        VirusScanRetroService service =
                new VirusScanRetroService(
                        jobScheduler, files, storage, cleanScanner(), properties, audit);

        service.runForCurrentTenant(LocalDate.of(2026, 11, 14));

        verify(files).markVirusScanResult(13L, "HASH_MISMATCH");
        verify(audit)
                .append(
                        argThat(
                                entry ->
                                        entry != null
                                                && "FILE_VIRUS_HASH_MISMATCH".equals(entry.action())
                                                && entry.objectId().equals("13")));
    }

    /** 扫描不可用：异常外抛（fail closed）——本租户本趟由作业框架记 FAILED，行保持 UNSCANNED 次晚重试。 */
    @Test
    void unavailableScannerAbortsBatch() throws IOException {
        byte[] body = pdfBody();
        String key = storage.put("files/202611/d.pdf", body, "application/pdf");
        when(files.findUnscanned(200)).thenReturn(List.of(row(14L, key, body)));
        VirusScanner unavailable =
                new VirusScanner() {
                    @Override
                    public boolean enabled() {
                        return true;
                    }

                    @Override
                    public ScanResult scan(byte[] file) {
                        throw new ApiException(503, 50300, "病毒扫描服务暂不可用，请稍后重试");
                    }
                };
        VirusScanRetroService service =
                new VirusScanRetroService(
                        jobScheduler, files, storage, unavailable, properties, audit);

        ApiException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        ApiException.class,
                        () -> service.runForCurrentTenant(LocalDate.of(2026, 11, 14)));
        assertEquals(50300, ex.code());
        verify(files, never()).markVirusScanResult(anyLong(), anyString());
    }

    /** 对象缺失 / 键不带本院前缀：保持 UNSCANNED 跳过（不虚构结论），本趟不失败。 */
    @Test
    void missingObjectOrForeignKeySkipped() throws IOException {
        byte[] body = pdfBody();
        String key = storage.put("files/202611/e.pdf", body, "application/pdf");
        SysFileRow missing = row(15L, key, body);
        // 对象存在但键写错前缀（防御：不猜归属）
        SysFileRow foreign = row(16L, "t/other/files/202611/x.pdf", body);
        storage.delete(key.substring("t/demoa/".length()));
        when(files.findUnscanned(200)).thenReturn(List.of(missing, foreign));
        VirusScanRetroService service =
                new VirusScanRetroService(
                        jobScheduler, files, storage, cleanScanner(), properties, audit);

        service.runForCurrentTenant(LocalDate.of(2026, 11, 14));

        verify(files, never()).markVirusScanResult(anyLong(), anyString());
    }

    /** 批量上限来自配置（crdp.file.virus-scan.retro-batch-limit）。 */
    @Test
    void batchLimitComesFromProperties() {
        properties.setRetroBatchLimit(7);
        when(files.findUnscanned(7)).thenReturn(List.of());
        VirusScanRetroService service =
                new VirusScanRetroService(
                        jobScheduler, files, storage, cleanScanner(), properties, audit);

        service.runForCurrentTenant(LocalDate.of(2026, 11, 14));

        verify(files).findUnscanned(7);
    }

    // ---------------------------------------------------------------- 替身与工具

    private static VirusScanner disabledScanner() {
        return com.crdp.followup.file.infra.scan.DisabledVirusScanner.INSTANCE;
    }

    private static VirusScanner cleanScanner() {
        return new VirusScanner() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public ScanResult scan(byte[] body) {
                return ScanResult.clean();
            }
        };
    }

    private static VirusScanner infectedScanner(String virusName) {
        return new VirusScanner() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public ScanResult scan(byte[] body) {
                return ScanResult.infected(virusName);
            }
        };
    }

    private static SysFileRow row(long id, String objectKey, byte[] body) {
        SysFileRow row = new SysFileRow();
        row.setId(id);
        row.setObjectKey(objectKey);
        row.setOriginalName("报告（示例）.pdf");
        row.setSizeBytes(body.length);
        row.setSha256(FileUploadService.sha256Hex(body));
        return row;
    }

    private static byte[] pdfBody() {
        return com.crdp.followup.file.infra.detect.FileTypeDetectorTest.pdf();
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
