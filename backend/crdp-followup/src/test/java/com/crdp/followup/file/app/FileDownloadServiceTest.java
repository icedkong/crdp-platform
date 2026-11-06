package com.crdp.followup.file.app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.file.infra.download.FileDownloadSigner;
import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditType;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.storage.LocalObjectStorage;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E10-T05 / tech-plan §10「下载」单元测试：签发 5 分钟签名链接（每次签发写 DOWNLOAD 数据访问审计，含文件 SHA-256）
 * 与公开取流口（验签、按签名租户读对象、过期 / 伪造 / 跨租户一律 40400、限流 42900）。
 *
 * <p>锁定任务验收：**链接短时有效**（到期时间进签名，篡改 / 过期不可用）+ **每次下载记审计**（A-ATT-006：谁、何时、哪个文件； 报告单关联随 E16
 * rpt_report_file 补进审计描述，OQ-62 ① 同口径）。
 */
@ExtendWith(MockitoExtension.class)
class FileDownloadServiceTest {

    private static final long TENANT = 1L;
    private static final String TENANT_CODE = "demoa";
    private static final long USER = 7L;
    private static final long FILE_ID = 9L;
    private static final String SHA256 = "a".repeat(64);

    private static final String URL_PREFIX = "/api/hospital/files/download/";

    /** 32 字节测试密钥（Base64）。 */
    private static final String KEY =
            Base64.getEncoder().encodeToString("k".repeat(32).getBytes(StandardCharsets.UTF_8));

    @TempDir Path storageRoot;

    private final SysFileMapper files = mock(SysFileMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private ObjectStorage storage;
    private FileDownloadSigner signer;
    private FileDownloadService service;

    @BeforeEach
    void loginAndWire() {
        // 公开取流口没有登录令牌 → 与 FilePreviewServiceTest 同口径，单测里手工设会话与租户上下文
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(true), null, List.of()));
        TenantContext.useTenant(TENANT, TENANT_CODE);
        storage = new LocalObjectStorage(storageRoot);
        FileDownloadProperties properties = new FileDownloadProperties();
        properties.setTtlSeconds(300);
        properties.setIpHourlyLimit(600);
        properties.setSigningKey(KEY);
        signer = new FileDownloadSigner(properties.getSigningKey());
        service =
                new FileDownloadService(
                        files,
                        storage,
                        signer,
                        audit,
                        properties,
                        redis,
                        (FilePreviewService.ContactAudioScopePort) fileId -> {});
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- 签发

    /** 正常签发：短时链接（≤ 5 分钟）、到期时间进签名、写 DOWNLOAD 数据访问审计（含文件 SHA-256）。 */
    @Test
    void issueDownloadUrl() {
        stubRow(row("t/demoa/files/202611/x.pdf", "pdf", "application/pdf", "PENDING"));

        FileDownloadService.DownloadGrant grant = service.downloadUrl(FILE_ID);

        assertTrue(grant.url().startsWith(URL_PREFIX));
        long ttl = grant.expiresAtEpochSecond() - Instant.now().getEpochSecond();
        assertTrue(ttl > 0 && ttl <= 300, "链接短时有效（≤ 5 分钟）：ttl=" + ttl);

        ArgumentCaptor<AuditEntry> captured = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captured.capture());
        AuditEntry entry = captured.getValue();
        assertEquals(AuditType.DATA_ACCESS, entry.type());
        assertEquals("DOWNLOAD", entry.action());
        assertEquals("DOWNLOAD", entry.accessMethod());
        assertEquals("sys_file", entry.objectType());
        assertEquals(String.valueOf(FILE_ID), entry.objectId());
        assertEquals(SHA256, entry.fileHash());
        assertTrue(entry.description().contains("复查报告.pdf"));
        assertTrue(entry.description().contains("pdf"));
    }

    /** 签发结果带展示所需字段（格式、MIME、原名、大小）。 */
    @Test
    void grantFields() {
        stubRow(row("t/demoa/audio/202611/x.mp3", "mp3", "audio/mpeg", "PENDING"));
        FileDownloadService.DownloadGrant grant = service.downloadUrl(FILE_ID);
        assertEquals("mp3", grant.format());
        assertEquals("audio/mpeg", grant.mime());
        assertEquals("复查报告.mp3", grant.originalName());
        assertEquals(8, grant.sizeBytes());
    }

    /** 文件不存在或已删除：一律 40400（不暴露存在性），且不写审计。 */
    @Test
    void missingOrDeletedFileRejected() {
        when(files.find(FILE_ID)).thenReturn(null);
        ApiException missing = assertThrows(ApiException.class, () -> service.downloadUrl(FILE_ID));
        assertEquals(40400, missing.code());

        stubRow(row("t/demoa/files/202611/x.pdf", "pdf", "application/pdf", "DELETED"));
        ApiException deleted = assertThrows(ApiException.class, () -> service.downloadUrl(FILE_ID));
        assertEquals(40400, deleted.code());
        verify(audit, never()).append(any(AuditEntry.class));
    }

    /** 平台账号不能调医院端下载签发：40300。 */
    @Test
    void platformAccountRejected() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(false), null, List.of()));
        ApiException error = assertThrows(ApiException.class, () -> service.downloadUrl(FILE_ID));
        assertEquals(40300, error.code());
    }

    /** 配置写大也压回 5 分钟以内（tech-plan §10「5 分钟有效」）。 */
    @Test
    void ttlCappedAtFiveMinutes() {
        FileDownloadProperties loose = new FileDownloadProperties();
        loose.setTtlSeconds(3600);
        loose.setSigningKey(KEY);
        FileDownloadService capped =
                new FileDownloadService(
                        files,
                        storage,
                        new FileDownloadSigner(loose.getSigningKey()),
                        audit,
                        loose,
                        redis,
                        (FilePreviewService.ContactAudioScopePort) fileId -> {});
        stubRow(row("t/demoa/files/202611/x.pdf", "pdf", "application/pdf", "PENDING"));

        FileDownloadService.DownloadGrant grant = capped.downloadUrl(FILE_ID);
        long ttl = grant.expiresAtEpochSecond() - Instant.now().getEpochSecond();
        assertTrue(ttl <= 300, "配置写大按 5 分钟执行：ttl=" + ttl);
    }

    // ---------------------------------------------------------------- 公开取流

    /** 有效签名链接：按签名租户读对象（台账查询发生在签名租户上下文里）、返回原字节与 MIME。 */
    @Test
    void contentByValidToken() {
        byte[] body = "PDF-BYTES".getBytes(StandardCharsets.UTF_8);
        storage.put("files/202611/x.pdf", body, "application/pdf");
        stubRow(row("t/demoa/files/202611/x.pdf", "pdf", "application/pdf", "PENDING"));
        String token = issuedToken();

        FileDownloadService.DownloadContent content = service.content(token, "10.0.0.8");
        assertArrayEquals(body, content.body());
        assertEquals("application/pdf", content.mime());
        assertEquals("复查报告.pdf", content.originalName());
    }

    /** 台账查询必须发生在签名绑定的租户上下文里（公开口没有登录令牌，租户只来自签名）。 */
    @Test
    void contentRunsInTokenTenantContext() {
        storage.put(
                "files/202611/x.pdf", "PDF".getBytes(StandardCharsets.UTF_8), "application/pdf");
        when(files.find(FILE_ID))
                .thenAnswer(
                        invocation -> {
                            assertTrue(TenantContext.current().isPresent());
                            assertEquals(
                                    TENANT_CODE,
                                    TenantContext.current().orElseThrow().tenantCode());
                            return row(
                                    "t/demoa/files/202611/x.pdf",
                                    "pdf",
                                    "application/pdf",
                                    "PENDING");
                        });
        service.content(issuedToken(), "10.0.0.8");
    }

    /** 伪造 / 过期链接 → 40400，不查台账（不暴露存在性）。 */
    @Test
    void invalidTokenRejected() {
        ApiException error =
                assertThrows(ApiException.class, () -> service.content("bogus-token", "10.0.0.8"));
        assertEquals(40400, error.code());
        verify(files, never()).find(anyLong());

        String expired = signer.sign(TENANT, TENANT_CODE, FILE_ID, Instant.now().minusSeconds(1));
        ApiException gone =
                assertThrows(ApiException.class, () -> service.content(expired, "10.0.0.8"));
        assertEquals(40400, gone.code());
        verify(files, never()).find(anyLong());
    }

    /** 签名绑定租户 A、台账行对象键在租户 B 前缀下 → 40400（按前缀隔离，E10-T02 口径）。 */
    @Test
    void crossTenantLedgerRejected() {
        storage.put(
                "files/202611/x.pdf", "PDF".getBytes(StandardCharsets.UTF_8), "application/pdf");
        stubRow(row("t/demob/files/202611/x.pdf", "pdf", "application/pdf", "PENDING"));
        ApiException error =
                assertThrows(ApiException.class, () -> service.content(issuedToken(), "10.0.0.8"));
        assertEquals(40400, error.code());
    }

    /** 同一 IP 超每小时上限 → 42900（公开口限流，AGENTS.md 第 6 节）。 */
    @Test
    void rateLimited() {
        when(valueOps.increment(argThat((String key) -> key.startsWith("crdp:file-download:ip:"))))
                .thenReturn(601L);
        ApiException error =
                assertThrows(ApiException.class, () -> service.content("any-token", "10.0.0.8"));
        assertEquals(42900, error.code());
        verify(files, never()).find(anyLong());
    }

    /** 对象缺失（台账在、对象不在）→ 40400，不返回半截内容。 */
    @Test
    void missingObjectRejected() {
        stubRow(row("t/demoa/files/202611/gone.pdf", "pdf", "application/pdf", "PENDING"));
        ApiException error =
                assertThrows(ApiException.class, () -> service.content(issuedToken(), "10.0.0.8"));
        assertEquals(40400, error.code());
    }

    /** 签发后软删除的文件：窗口内链接也不可用（台账状态优先于签名）。 */
    @Test
    void deletedWithinWindowRejected() {
        storage.put(
                "files/202611/x.pdf", "PDF".getBytes(StandardCharsets.UTF_8), "application/pdf");
        stubRow(row("t/demoa/files/202611/x.pdf", "pdf", "application/pdf", "PENDING"));
        String token = issuedToken();
        // 签发后文件被软删除（后 stub 覆盖前值）：链接仍在窗口内也必须拒绝
        stubRow(row("t/demoa/files/202611/x.pdf", "pdf", "application/pdf", "DELETED"));
        ApiException error =
                assertThrows(ApiException.class, () -> service.content(token, "10.0.0.8"));
        assertEquals(40400, error.code());
    }

    // ---------------------------------------------------------------- 工具

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    /** 走真实签发路径拿令牌（签名真实往返，不 stub 任何存储）。 */
    private String issuedToken() {
        return service.downloadUrl(FILE_ID).url().substring(URL_PREFIX.length());
    }

    private void stubRow(SysFileRow row) {
        lenient().when(files.find(FILE_ID)).thenReturn(row);
    }

    private static SysFileRow row(String objectKey, String format, String mime, String status) {
        SysFileRow row = new SysFileRow();
        row.setId(FILE_ID);
        row.setObjectKey(objectKey);
        row.setOriginalName("复查报告." + format);
        row.setFormatCode(format);
        row.setMimeType(mime);
        row.setSizeBytes(8);
        row.setSha256(SHA256);
        row.setStatus(status);
        row.setUploaderId(USER);
        return row;
    }

    private static SessionPrincipal principal(boolean hospital) {
        return new SessionPrincipal(
                "token",
                hospital ? "HOSPITAL" : "PLATFORM",
                hospital ? TENANT : null,
                hospital ? TENANT_CODE : null,
                hospital ? "医院 A（示例租户）" : null,
                USER,
                "doctor.demo",
                "王医生（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of(),
                hospital ? List.of("followup") : List.of(),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                0L,
                null);
    }
}
