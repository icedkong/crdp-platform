package com.crdp.followup.file.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.crdp.followup.file.infra.mapper.*;
import com.crdp.followup.file.infra.preview.FilePreviewTokenStore;
import com.crdp.framework.audit.*;
import com.crdp.framework.security.*;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;

/** OQR-07：授权绑定、文件版本、当前范围、渲染失败关闭和原流边界。所有身份 / 文件均为示例。 */
class FilePreviewServiceTest {
    private final SysFileMapper files = mock(SysFileMapper.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final FilePreviewTokenStore tokens = mock(FilePreviewTokenStore.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final FilePreviewService.ContactAudioScopePort audio =
            mock(FilePreviewService.ContactAudioScopePort.class);
    private final ReportFileScopePort report = mock(ReportFileScopePort.class);
    private final PreviewRenderer renderer = mock(PreviewRenderer.class);
    private final PreviewAccessService access = mock(PreviewAccessService.class);
    private final FilePreviewProperties properties = new FilePreviewProperties();
    private final AtomicReference<FilePreviewTokenStore.Grant> grant = new AtomicReference<>();
    private FilePreviewService service;
    private SysFileRow row;
    private static final byte[] SOURCE = "original-file".getBytes(),
            WATERMARKED = "watermarked-file".getBytes();

    static SessionPrincipal actor(String token, long user, long tenant) {
        return new SessionPrincipal(
                token,
                "HOSPITAL",
                tenant,
                "demo" + tenant,
                "医院（示例）",
                user,
                "doctor.example",
                "医生（示例）",
                List.of(),
                List.of(),
                List.of("followup:patient:view", "followup:scan:upload"),
                List.of("followup"),
                1,
                ScopeSnapshot.allowAll(),
                false,
                null);
    }

    @BeforeEach
    void setup() {
        TenantContext.useTenant(129L, "demo129");
        row = new SysFileRow();
        row.setId(9L);
        row.setObjectKey("t/demo129/reports/file.pdf");
        row.setOriginalName("报告（示例）.pdf");
        row.setFormatCode("pdf");
        row.setMimeType("application/pdf");
        row.setSha256("0".repeat(64));
        row.setSizeBytes(SOURCE.length);
        row.setStatus("PENDING");
        row.setUploaderId(501L);
        when(files.find(9L)).thenReturn(row);
        when(access.current()).thenReturn(actor("session-one", 501, 129));
        when(tokens.issue(any()))
                .thenAnswer(
                        call -> {
                            grant.set(call.getArgument(0));
                            return "preview-token";
                        });
        when(tokens.resolve("preview-token")).thenAnswer(call -> Optional.ofNullable(grant.get()));
        when(storage.get("reports/file.pdf")).thenReturn(SOURCE);
        when(redis.opsForValue()).thenReturn(values);
        when(renderer.render(any(), anyString(), anyString()))
                .thenReturn(new PreviewRenderer.Rendered(WATERMARKED, "application/pdf", "pdf"));
        service =
                new FilePreviewService(
                        files,
                        storage,
                        tokens,
                        audit,
                        properties,
                        redis,
                        audio,
                        report,
                        renderer,
                        access);
    }

    @AfterEach
    void clean() {
        TenantContext.clear();
    }

    @Test
    void issueBindsSessionVersionIdentityAndClampsTtl() {
        properties.setTtlSeconds(900);
        var result = service.previewUrl(9);
        var bound = grant.get();
        assertThat(result.expiresAtEpochSecond())
                .isBetween(
                        Instant.now().getEpochSecond() + 298, Instant.now().getEpochSecond() + 300);
        assertThat(bound.sessionId()).isEqualTo(actor("session-one", 501, 129).sessionId());
        assertThat(bound.userId()).isEqualTo(501);
        assertThat(bound.tenantId()).isEqualTo(129);
        assertThat(bound.sha256()).isEqualTo(row.getSha256());
        assertThat(bound.watermark())
                .contains("医生（示例）（doctor.example）", "医院（示例）")
                .matches(".* · \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
        assertThat(result.originalName()).endsWith("（水印预览）.pdf");
    }

    @Test
    void visualContentIsRenderedAndNeverReturnsOriginal() {
        service.previewUrl(9);
        assertThat(service.content("preview-token", null).body())
                .isEqualTo(WATERMARKED)
                .isNotEqualTo(SOURCE);
        verify(renderer).render(SOURCE, "pdf", grant.get().watermark());
        verify(access, times(3)).current();
    }

    @Test
    void docxGrantDescribesPdfCopy() {
        row.setFormatCode("docx");
        row.setOriginalName("记录（示例）.docx");
        var result = service.previewUrl(9);
        assertThat(result.format()).isEqualTo("pdf");
        assertThat(result.mime()).isEqualTo("application/pdf");
        assertThat(result.originalName()).endsWith(".pdf");
        service.content("preview-token", null);
        verify(renderer).render(SOURCE, "docx", grant.get().watermark());
    }

    @Test
    void imageGrantDescribesPngCopy() {
        row.setFormatCode("jpg");
        assertThat(service.previewUrl(9).mime()).isEqualTo("image/png");
    }

    @Test
    void audioUsesAuthenticatedOriginalStreamWithoutRendering() {
        row.setFormatCode("mp3");
        service.previewUrl(9);
        assertThat(service.content("preview-token", null).body()).isEqualTo(SOURCE);
        verifyNoInteractions(renderer);
    }

    @Test
    void unsupportedFormatsCannotUsePreviewAsDownload() {
        for (String format : List.of("amr", "xlsx")) {
            row.setFormatCode(format);
            assertThat(service.previewUrl(9).inlinePlayable()).isFalse();
            assertCode(40001, () -> service.content("preview-token", null));
        }
        verifyNoInteractions(renderer);
        verify(storage, never()).get(anyString());
    }

    @Test
    void sessionMismatchDeniesEvenSameUser() {
        service.previewUrl(9);
        when(access.current()).thenReturn(actor("second-session", 501, 129));
        assertCode(40400, () -> service.content("preview-token", null));
        verifyNoInteractions(renderer);
    }

    @Test
    void anotherUserCannotReplayGrant() {
        service.previewUrl(9);
        when(access.current()).thenReturn(actor("session-one", 502, 129));
        assertCode(40400, () -> service.content("preview-token", null));
    }

    @Test
    void tenantMismatchDoesNotSwitchToGrantTenant() {
        service.previewUrl(9);
        when(access.current()).thenReturn(actor("session-one", 501, 130));
        assertCode(40400, () -> service.content("preview-token", null));
        assertThat(TenantContext.require().tenantId()).isEqualTo(129);
    }

    @Test
    void deletedOrAbnormalFileRejectsBothIssuanceAndOldGrant() {
        service.previewUrl(9);
        for (String scan : List.of("INFECTED", "HASH_MISMATCH")) {
            row.setVirusScanStatus(scan);
            assertCode(40400, () -> service.previewUrl(9));
            assertCode(40400, () -> service.content("preview-token", null));
        }
        row.setVirusScanStatus("CLEAN");
        row.setStatus("DELETED");
        assertCode(40400, () -> service.content("preview-token", null));
    }

    @Test
    void missingAndWrongPrefixAreHidden() {
        when(files.find(9L)).thenReturn(null);
        assertCode(40400, () -> service.previewUrl(9));
        when(files.find(9L)).thenReturn(row);
        row.setObjectKey("t/other/file");
        assertCode(40400, () -> service.previewUrl(9));
    }

    @Test
    void fileVersionChangeRejectsOldGrant() {
        service.previewUrl(9);
        row.setSha256("1".repeat(64));
        assertCode(40400, () -> service.content("preview-token", null));
        verifyNoInteractions(renderer);
    }

    @Test
    void changedObjectAndLengthRejectOldGrant() {
        service.previewUrl(9);
        row.setSizeBytes(999);
        assertCode(40400, () -> service.content("preview-token", null));
        row.setSizeBytes(SOURCE.length);
        row.setObjectKey("t/demo129/other");
        assertCode(40400, () -> service.content("preview-token", null));
    }

    @Test
    void currentRangeIsRecheckedBeforeReading() {
        service.previewUrl(9);
        when(report.requireReportFileReadable(9)).thenThrow(new ApiException(404, 40400, "不可见"));
        assertCode(40400, () -> service.content("preview-token", null));
        verify(storage, never()).get(anyString());
    }

    @Test
    void revocationDuringRenderingPreventsDelivery() {
        service.previewUrl(9);
        when(access.current())
                .thenReturn(actor("session-one", 501, 129))
                .thenThrow(new ApiException(401, 40100, "会话失效"));
        assertCode(40100, () -> service.content("preview-token", null));
        verify(renderer).render(any(), any(), any());
    }

    @Test
    void expiredGrantAfterRenderingPreventsDelivery() {
        service.previewUrl(9);
        when(tokens.resolve("preview-token"))
                .thenReturn(Optional.of(grant.get()), Optional.empty());
        assertCode(40400, () -> service.content("preview-token", null));
    }

    @Test
    void invalidGrantAndMissingObjectReject() {
        assertCode(40400, () -> service.content("invalid", null));
        service.previewUrl(9);
        when(storage.get(anyString())).thenReturn(null);
        assertCode(40400, () -> service.content("preview-token", null));
    }

    @Test
    void conversionFailureNeverFallsBackToOriginal() {
        service.previewUrl(9);
        when(renderer.render(any(), any(), any())).thenThrow(PreviewRenderer.unavailable());
        assertCode(50301, () -> service.content("preview-token", null));
    }

    @Test
    void largeFileRejectedBeforeLoading() {
        row.setSizeBytes(PreviewRenderer.MAX_BYTES + 1);
        service.previewUrl(9);
        assertCode(50301, () -> service.content("preview-token", null));
        verify(storage, never()).get(anyString());
    }

    @Test
    void uploaderGuardAndLinkedPermissionAreIndependent() {
        row.setUploaderId(502);
        assertCode(40400, () -> service.previewUrl(9));
        when(report.requireReportFileReadable(9))
                .thenReturn(new ReportFileScopePort.ReportFileView(20, "TYPE", "类型（示例）", "名称（示例）"));
        service.previewUrl(9);
    }

    @Test
    void changedIdentityProducesDifferentWatermark() {
        service.previewUrl(9);
        String first = grant.get().watermark();
        when(access.current())
                .thenReturn(
                        new SessionPrincipal(
                                "session-two",
                                "HOSPITAL",
                                129L,
                                "demo129",
                                "医院（示例）",
                                501L,
                                "another.example",
                                "另一医生（示例）",
                                List.of(),
                                List.of(),
                                List.of("followup:patient:view", "followup:scan:upload"),
                                List.of("followup"),
                                1,
                                ScopeSnapshot.allowAll(),
                                false,
                                null));
        service.previewUrl(9);
        assertThat(grant.get().watermark()).isNotEqualTo(first).contains("另一医生（示例）");
    }

    @Test
    void ipRateLimitIsEnforced() {
        when(values.increment(anyString())).thenReturn(601L);
        assertCode(42900, () -> service.content("preview-token", "127.0.0.1"));
    }

    private static void assertCode(int code, Runnable work) {
        assertThatThrownBy(work::run)
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(code));
    }
}
