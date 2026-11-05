package com.crdp.followup.file.app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
import com.crdp.followup.file.infra.preview.FilePreviewTokenStore;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.TokenGenerator;
import com.crdp.framework.storage.LocalObjectStorage;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.nio.file.Path;
import java.time.Duration;
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
 * E10-T04 / A-FU-025、tech-plan §10「预览」单元测试：预览短时链接签发（≤ 5 分钟、写 FILE_PREVIEW 审计）
 * 与公开代理取流（令牌校验、按令牌租户读对象、不存在的文件 40400、限流 42900）。
 *
 * <p>锁定任务验收链路：水印与播放属前端（@crdp/viewer，另有单测），后端保证「预览经应用代理按流返回、不缓存、每次预览写审计」。 双租户用例：令牌绑定签发租户，跨租户对象键前缀对不上
 * → 40400（按前缀隔离的验收口径，E10-T02）。
 */
@ExtendWith(MockitoExtension.class)
class FilePreviewServiceTest {

    private static final long TENANT = 1L;
    private static final String TENANT_CODE = "demoa";
    private static final long USER = 7L;
    private static final long FILE_ID = 9L;

    @TempDir Path storageRoot;

    private final SysFileMapper files = mock(SysFileMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private ObjectStorage storage;
    private FilePreviewTokenStore tokens;
    private FilePreviewService service;

    @BeforeEach
    void loginAndWire() {
        // 公开代理口没有登录令牌 → TokenAuthenticationFilter 在请求线程设置的租户上下文也不存在，
        // 单测里对签发口手工设租户上下文（与 FileUploadServiceTest 同口径）
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(true), null, List.of()));
        TenantContext.useTenant(TENANT, TENANT_CODE);
        storage = new LocalObjectStorage(storageRoot);
        FilePreviewProperties properties = new FilePreviewProperties();
        properties.setTtlSeconds(300);
        properties.setIpHourlyLimit(600);
        tokens = new FilePreviewTokenStore(redis, new TokenGenerator(), properties);
        service =
                new FilePreviewService(
                        files,
                        storage,
                        tokens,
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

    /** 正常签发：短时链接（≤ 5 分钟）、Redis 只存令牌摘要并绑定租户与文件、写 FILE_PREVIEW 审计（含文件与真实类型）。 */
    @Test
    void issuePreviewUrl() {
        stubRow(row("t/demoa/files/202611/x.pdf", "pdf", "application/pdf", "PENDING"));
        service.previewUrl(FILE_ID);

        verify(valueOps)
                .set(
                        argThat((String key) -> key.startsWith("crdp:file-preview:")),
                        eq(TENANT + "|" + TENANT_CODE + "|" + FILE_ID),
                        argThat((Duration ttl) -> !ttl.isNegative() && ttl.getSeconds() <= 300));

        ArgumentCaptor<AuditEntry> captured = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captured.capture());
        AuditEntry entry = captured.getValue();
        assertEquals("FILE_PREVIEW", entry.action());
        assertEquals("sys_file", entry.objectType());
        assertEquals(String.valueOf(FILE_ID), entry.objectId());
        assertTrue(entry.description().contains("复查报告.pdf"));
        assertTrue(entry.description().contains("pdf"));
    }

    /** 签发结果：链接指向公开代理、带格式与原生可播标记（pdf/docx/图片/MP3 可预览，AMR 与 XLSX 只下载）。 */
    @Test
    void grantFields() {
        stubRow(row("t/demoa/audio/202611/x.mp3", "mp3", "audio/mpeg", "PENDING"));
        FilePreviewService.PreviewGrant grant = service.previewUrl(FILE_ID);
        assertTrue(grant.url().startsWith("/api/hospital/files/preview/"));
        assertEquals("mp3", grant.format());
        assertEquals("audio/mpeg", grant.mime());
        assertEquals("复查报告.mp3", grant.originalName());
        assertTrue(grant.inlinePlayable());
    }

    @Test
    void amrIsNotPlayableInline() {
        stubRow(row("t/demoa/audio/202611/x.amr", "amr", "audio/amr", "PENDING"));
        assertFalse(service.previewUrl(FILE_ID).inlinePlayable());
    }

    @Test
    void xlsxIsNotPlayableInline() {
        stubRow(row("t/demoa/files/202611/x.xlsx", "xlsx", null, "PENDING"));
        assertFalse(service.previewUrl(FILE_ID).inlinePlayable());
    }

    /** 文件不存在或已删除：一律 40400（不区分「不存在 / 不可见」，不暴露存在性）。 */
    @Test
    void missingOrDeletedFileRejected() {
        when(files.find(FILE_ID)).thenReturn(null);
        ApiException missing = assertThrows(ApiException.class, () -> service.previewUrl(FILE_ID));
        assertEquals(40400, missing.code());

        stubRow(row("t/demoa/files/202611/x.pdf", "pdf", "application/pdf", "DELETED"));
        ApiException deleted = assertThrows(ApiException.class, () -> service.previewUrl(FILE_ID));
        assertEquals(40400, deleted.code());
        verify(audit, never()).append(any(AuditEntry.class));
    }

    /** 平台账号不能调医院端预览签发：40300。 */
    @Test
    void platformAccountRejected() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(false), null, List.of()));
        ApiException error = assertThrows(ApiException.class, () -> service.previewUrl(FILE_ID));
        assertEquals(40300, error.code());
    }

    /** 配置写大也压回 5 分钟以内（tech-plan §10「短时链接 ≤ 5 分钟」）。 */
    @Test
    void ttlCappedAtFiveMinutes() {
        FilePreviewProperties loose = new FilePreviewProperties();
        loose.setTtlSeconds(3600);
        FilePreviewTokenStore cappedTokens =
                new FilePreviewTokenStore(redis, new TokenGenerator(), loose);
        FilePreviewService capped =
                new FilePreviewService(
                        files,
                        storage,
                        cappedTokens,
                        audit,
                        loose,
                        redis,
                        (FilePreviewService.ContactAudioScopePort) fileId -> {});
        stubRow(row("t/demoa/files/202611/x.pdf", "pdf", "application/pdf", "PENDING"));
        capped.previewUrl(FILE_ID);
        verify(valueOps)
                .set(anyString(), anyString(), argThat((Duration ttl) -> ttl.getSeconds() <= 300));
    }

    // ---------------------------------------------------------------- 代理取流

    /** 有效令牌：按令牌租户读对象（台账查询发生在令牌租户上下文里）、返回原字节与 MIME。 */
    @Test
    void contentByValidToken() {
        byte[] body = "PDF-BYTES".getBytes();
        storage.put("files/202611/x.pdf", body, "application/pdf");
        stubRow(row("t/demoa/files/202611/x.pdf", "pdf", "application/pdf", "PENDING"));
        String token = issuedToken();

        FilePreviewService.PreviewContent content = service.content(token, "10.0.0.8");
        assertArrayEquals(body, content.body());
        assertEquals("application/pdf", content.mime());
        assertEquals("复查报告.pdf", content.originalName());
    }

    /** 台账查询必须发生在令牌绑定的租户上下文里（公开口没有登录令牌，租户只来自令牌）。 */
    @Test
    void contentRunsInTokenTenantContext() {
        storage.put("files/202611/x.pdf", "PDF".getBytes(), "application/pdf");
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

    /** 无效 / 过期令牌 → 40400（不暴露任何文件信息）。 */
    @Test
    void invalidTokenRejected() {
        when(valueOps.get(anyString())).thenReturn(null);
        ApiException error =
                assertThrows(ApiException.class, () -> service.content("bogus-token", "10.0.0.8"));
        assertEquals(40400, error.code());
        verify(files, never()).find(anyLong());
    }

    /** 令牌绑租户 B、台账行对象键在租户 A 前缀下 → 40400（跨租户不可见，按前缀隔离验收）。 */
    @Test
    void crossTenantTokenRejected() {
        storage.put("files/202611/x.pdf", "PDF".getBytes(), "application/pdf");
        stubRow(row("t/demoa/files/202611/x.pdf", "pdf", "application/pdf", "PENDING"));
        String token = issuedToken();
        // 签发后把回读值改成租户 B 的登记（2|demob|9）：后 stub 覆盖前值
        when(valueOps.get(anyString())).thenReturn("2|demob|" + FILE_ID);
        ApiException error =
                assertThrows(ApiException.class, () -> service.content(token, "10.0.0.8"));
        assertEquals(40400, error.code());
    }

    /** 同一 IP 超每小时上限 → 42900（公开口限流，AGENTS.md 第 6 节）。 */
    @Test
    void rateLimited() {
        when(valueOps.increment(argThat((String key) -> key.startsWith("crdp:file-preview:ip:"))))
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

    // ---------------------------------------------------------------- 工具

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    /** 走真实签发路径拿令牌（不重复 stub 台账与对象，测试用例各自的 stub 不被覆盖；回读值 = 合法登记值）。 */
    private String issuedToken() {
        when(valueOps.get(anyString())).thenReturn(TENANT + "|" + TENANT_CODE + "|" + FILE_ID);
        return service.previewUrl(FILE_ID).url().substring("/api/hospital/files/preview/".length());
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
        row.setSha256("deadbeef");
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
                null);
    }
}
