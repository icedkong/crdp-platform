package com.crdp.followup.file.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.file.infra.detect.FileTypeDetector;
import com.crdp.followup.file.infra.detect.FileTypeDetectorTest;
import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
import com.crdp.followup.file.infra.mapper.UploadParamMapper;
import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E10-T01 / A-ATT-008、C-SCAN-018：上传校验单元测试（真实 Tika 检测 + 真实策略解析）。
 *
 * <p>锁定任务验收：改扩展名的可执行文件（report.pdf 实为 PE）被拒 40001；此外：白名单外扩展名、 宏 Office / 脚本 /
 * 压缩包内容、扩展名与真实类型不符、空文件、超大小（文档 / 录音两类上限）、 文件名缺扩展名 / 超长全部 40001；合法 PDF 通过并落
 * sys_file（PENDING、SHA-256、真实类型） 且写 FILE_UPLOAD 审计。全部合成样例，不含真实文件。
 */
@ExtendWith(MockitoExtension.class)
class FileUploadServiceTest {

    private static final long USER = 7L;

    private final FileUploadParamDefaults defaults = mock(FileUploadParamDefaults.class);
    private final UploadParamMapper overrides = mock(UploadParamMapper.class);
    private final SysFileMapper files = mock(SysFileMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final FilePolicyService policy = new FilePolicyService(defaults, overrides);
    private final FileUploadService service =
            new FileUploadService(policy, new FileTypeDetector(), files, audit);

    @BeforeEach
    void loginAndConfig() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        lenient().when(overrides.findOverride(anyString())).thenReturn(null);
        lenient()
                .when(defaults.param(anyString()))
                .thenAnswer(invocation -> Optional.<FileUploadParamDefaults.ParamDefault>empty());
        // 覆盖本院上限为小值：文档 1 MB、录音 2 MB，避免测试构造大文件
        lenient().when(overrides.findOverride(FilePolicyService.KEY_MAX_SIZE_MB)).thenReturn("1");
        lenient()
                .when(overrides.findOverride(FilePolicyService.KEY_AUDIO_MAX_SIZE_MB))
                .thenReturn("2");
        lenient()
                .doAnswer(
                        invocation -> {
                            SysFileRow row = invocation.getArgument(0);
                            row.setId(123L);
                            return 1;
                        })
                .when(files)
                .insert(any(SysFileRow.class));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 任务验收：改扩展名的可执行文件被拒（PE 内容伪装成 .pdf）。 */
    @Test
    void renamedExecutableRejected() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.upload("出院小结（示例）.pdf", FileTypeDetectorTest.peExe()));

        assertEquals(40001, ex.code());
        assertTrue(ex.getMessage().contains("真实类型"), "提示按真实类型拒绝：" + ex.getMessage());
        verify(files, never()).insert(any(SysFileRow.class));
    }

    /** ELF 与脚本同样按内容拒绝（不信任扩展名）。 */
    @Test
    void elfAndScriptRejectedByContent() {
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> service.upload("超声（示例）.png", FileTypeDetectorTest.elf()))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> service.upload("说明（示例）.txt", FileTypeDetectorTest.script()))
                        .code(),
                "txt 本身不在白名单");
    }

    /** 白名单外扩展名（exe / 宏 Office / 压缩包）在扩展名一步即拒。 */
    @Test
    void disallowedExtensionRejected() throws IOException {
        assertEquals(40001, codeOf(() -> service.upload("a.exe", FileTypeDetectorTest.peExe())));
        assertEquals(40001, codeOf(() -> service.upload("b.docm", docxBytes())));
        assertEquals(40001, codeOf(() -> service.upload("c.zip", zipBytes())));
        // 本院白名单收窄后，目录内格式也可能未开放（PLT-CFG-010 租户覆盖）
        when(overrides.findOverride(FilePolicyService.KEY_ALLOWED_TYPES)).thenReturn("jpg,png,pdf");
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.upload("通话（示例）.mp3", FileTypeDetectorTest.mp3(64)));
        assertEquals(40001, ex.code());
        assertTrue(ex.getMessage().contains("本院"), "提示本院未开放：" + ex.getMessage());
    }

    /** 扩展名与真实类型不符（白名单内互改）：拒绝。 */
    @Test
    void extensionContentMismatchRejected() {
        ApiException docxAsPdf =
                assertThrows(ApiException.class, () -> service.upload("报告（示例）.pdf", docxBytes()));
        assertEquals(40001, docxAsPdf.code());
        assertTrue(docxAsPdf.getMessage().contains("不符"), docxAsPdf.getMessage());
    }

    /** 空内容与文件名校验。 */
    @Test
    void emptyBodyAndBadNameRejected() {
        assertEquals(40001, codeOf(() -> service.upload("a.pdf", new byte[0])));
        assertEquals(40001, codeOf(() -> service.upload(null, FileTypeDetectorTest.pdf())));
        assertEquals(40001, codeOf(() -> service.upload("无扩展名", FileTypeDetectorTest.pdf())));
        assertEquals(
                40001,
                codeOf(() -> service.upload("x.".repeat(200) + "pdf", FileTypeDetectorTest.pdf())),
                "超长文件名拒绝");
        // 带路径的文件名只取文件名部分（浏览器兼容），仍走同一校验
        ApiException ok =
                assertThrows(
                        ApiException.class,
                        () -> service.upload("/tmp/伪装（示例）.pdf", FileTypeDetectorTest.peExe()));
        assertEquals(40001, ok.code());
    }

    /** 大小限制：文档 1 MB、录音 2 MB（本院覆盖值），超限 40001 且提示 MB。 */
    @Test
    void oversizeRejectedPerCategory() {
        ApiException pdf =
                assertThrows(
                        ApiException.class,
                        () -> service.upload("大文件（示例）.pdf", biggerThan(1024 * 1024)));
        assertEquals(40001, pdf.code());
        assertTrue(pdf.getMessage().contains("1 MB"), pdf.getMessage());

        ApiException mp3 =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.upload(
                                        "录音（示例）.mp3", FileTypeDetectorTest.mp3(3 * 1024 * 1024)));
        assertEquals(40001, mp3.code());
        assertTrue(mp3.getMessage().contains("2 MB"), mp3.getMessage());
    }

    /** 合法 PDF：落 PENDING 台账（对象键、SHA-256、真实类型、上传人）+ FILE_UPLOAD 审计 + 返回视图。 */
    @Test
    void validPdfStoredAndAudited() throws IOException {
        FileUploadService.FileUploadView view =
                service.upload("出院小结（示例）.pdf", FileTypeDetectorTest.pdf());

        assertEquals("123", view.fileId());
        assertEquals("pdf", view.format());
        assertEquals("application/pdf", view.mime());
        assertEquals(FileTypeDetectorTest.pdf().length, view.size());
        assertEquals(64, view.sha256().length());

        ArgumentCaptor<SysFileRow> row = ArgumentCaptor.forClass(SysFileRow.class);
        verify(files).insert(row.capture());
        assertEquals("PENDING", row.getValue().getStatus());
        assertEquals("pdf", row.getValue().getFormatCode());
        assertEquals(USER, row.getValue().getUploaderId());
        assertEquals(view.sha256(), row.getValue().getSha256());
        assertTrue(row.getValue().getObjectKey().startsWith("files/"), "对象键按 files/ 前缀");
        assertTrue(row.getValue().getObjectKey().endsWith(".pdf"));

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("FILE_UPLOAD", event.getValue().eventType());
        assertEquals("sys_file:123", event.getValue().target());
        assertNotNull(event.getValue().detail());
    }

    /** 合成录音（MP3 / AMR）：真实类型识别通过、按录音上限放行。 */
    @Test
    void audioStored() {
        FileUploadService.FileUploadView mp3 =
                service.upload("通话（示例）.mp3", FileTypeDetectorTest.mp3(128));
        assertEquals("mp3", mp3.format());
        assertEquals("audio/mpeg", mp3.mime());

        FileUploadService.FileUploadView amr =
                service.upload("通话（示例）.amr", FileTypeDetectorTest.amr());
        assertEquals("amr", amr.format());
    }

    // ---------------------------------------------------------------- 工具

    private static int codeOf(Runnable call) {
        return assertThrows(ApiException.class, call::run).code();
    }

    private static byte[] biggerThan(int size) {
        byte[] body = new byte[size + 1];
        System.arraycopy(FileTypeDetectorTest.pdf(), 0, body, 0, 64);
        return body;
    }

    private static byte[] zipBytes() {
        try {
            return FileTypeDetectorTest.zip();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] docxBytes() {
        try {
            return FileTypeDetectorTest.docx();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例）",
                USER,
                "doctor.demo",
                "张医生（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of("followup:scan:upload"),
                List.of("followup"),
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
