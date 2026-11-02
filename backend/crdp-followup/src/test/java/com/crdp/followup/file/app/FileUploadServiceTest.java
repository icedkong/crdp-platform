package com.crdp.followup.file.app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.file.infra.detect.FileTypeDetector;
import com.crdp.followup.file.infra.detect.FileTypeDetectorTest;
import com.crdp.followup.file.infra.image.ImageThumbnailGenerator;
import com.crdp.followup.file.infra.image.ImageThumbnailGeneratorTest;
import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
import com.crdp.followup.file.infra.mapper.UploadParamMapper;
import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.storage.LocalObjectStorage;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E10-T01 / A-ATT-008、C-SCAN-018：上传校验单元测试（真实 Tika 检测 + 真实策略解析）； E10-T02 起文件体经存储端口落桶 （本测试用真实 {@link
 * LocalObjectStorage}，验证租户前缀与内容不进台账）。
 *
 * <p>锁定任务验收：改扩展名的可执行文件（report.pdf 实为 PE）被拒 40001；此外：白名单外扩展名、 宏 Office / 脚本 /
 * 压缩包内容、扩展名与真实类型不符、空文件、超大小（文档 / 录音两类上限）、 文件名缺扩展名 / 超长全部 40001；合法 PDF 通过、 对象带 t/{租户}/ 前缀入桶、台账落
 * PENDING（SHA-256、真实类型）且写 FILE_UPLOAD 审计。全部合成样例，不含真实文件。
 *
 * <p>E10-T03（图片缩略图）：图片上传后同步生成缩略图对象（thumbnails/ 前缀、保持原格式）并记 thumbnail_key 与 pages=1；非图片（录音 /
 * 文档）不生成任何派生对象（录音不转码、不另存副本）；损坏图片在写桶前 40001 拒绝。
 */
@ExtendWith(MockitoExtension.class)
class FileUploadServiceTest {

    private static final long USER = 7L;

    @TempDir Path storageRoot;

    private final FileUploadParamDefaults defaults = mock(FileUploadParamDefaults.class);
    private final UploadParamMapper overrides = mock(UploadParamMapper.class);
    private final SysFileMapper files = mock(SysFileMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private ObjectStorage storage;

    private FilePolicyService policy;
    private FileUploadService service;

    @BeforeEach
    void loginAndConfig() {
        // @TempDir 在每个用例前注入，存储替身与其后的服务在此组装
        storage = new LocalObjectStorage(storageRoot);
        policy = new FilePolicyService(defaults, overrides);
        service =
                new FileUploadService(
                        policy,
                        new FileTypeDetector(),
                        new ImageThumbnailGenerator(128),
                        files,
                        storage,
                        audit);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        // 对象键前缀取自租户上下文（TokenAuthenticationFilter 在请求线程里做的事，单测里手工设）
        TenantContext.useTenant(1L, "demoa");
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
        TenantContext.clear();
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

    /** 合法 PDF：对象带租户前缀入桶（文件体不进台账）、落 PENDING 台账（对象键、SHA-256、真实类型、上传人）+ FILE_UPLOAD 审计。 */
    @Test
    void validPdfStoredAndAudited() throws IOException {
        byte[] body = FileTypeDetectorTest.pdf();
        FileUploadService.FileUploadView view = service.upload("出院小结（示例）.pdf", body);

        assertEquals("123", view.fileId());
        assertEquals("pdf", view.format());
        assertEquals("application/pdf", view.mime());
        assertEquals(body.length, view.size());
        assertEquals(64, view.sha256().length());

        ArgumentCaptor<SysFileRow> row = ArgumentCaptor.forClass(SysFileRow.class);
        verify(files).insert(row.capture());
        assertEquals("PENDING", row.getValue().getStatus());
        assertEquals("pdf", row.getValue().getFormatCode());
        assertEquals(USER, row.getValue().getUploaderId());
        assertEquals(view.sha256(), row.getValue().getSha256());
        // 非图片无派生文件：PDF 不生成缩略图、pages 留空（页数解析随 E10-T04 预览，OQ-61）
        assertNull(row.getValue().getThumbnailKey());
        assertNull(row.getValue().getPages());
        // E10-T02 验收：对象键按租户前缀隔离（t/{租户编码}/files/…），完整键入库
        assertTrue(
                row.getValue().getObjectKey().startsWith("t/demoa/files/"),
                "对象键带租户前缀：" + row.getValue().getObjectKey());
        assertTrue(row.getValue().getObjectKey().endsWith(".pdf"));
        // 文件体只存对象存储（桶替身按同一键落盘，内容与上传一致；台账无 body 列，E10-T02 V26）
        assertArrayEquals(
                body, storage.get(row.getValue().getObjectKey().substring("t/demoa/".length())));
        assertTrue(Files.isRegularFile(storageRoot.resolve(row.getValue().getObjectKey())));

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertEquals("FILE_UPLOAD", event.getValue().eventType());
        assertEquals("sys_file:123", event.getValue().target());
        assertNotNull(event.getValue().detail());
    }

    /** 任务验收（E10-T03）：上传图片后生成缩略图——JPEG 入桶 thumbnails/ 前缀、台账记缩略图键、pages=1、原图不受影响。 */
    @Test
    void imageUploadGeneratesThumbnail() throws IOException {
        byte[] body = ImageThumbnailGeneratorTest.jpeg(300, 200);
        FileUploadService.FileUploadView view = service.upload("超声报告（示例）.jpg", body);

        assertEquals("jpg", view.format());
        assertEquals(Integer.valueOf(1), view.pages(), "图片页数恒为 1");

        ArgumentCaptor<SysFileRow> row = ArgumentCaptor.forClass(SysFileRow.class);
        verify(files).insert(row.capture());
        String thumbnailKey = row.getValue().getThumbnailKey();
        assertTrue(
                thumbnailKey != null && thumbnailKey.startsWith("t/demoa/thumbnails/"),
                "缩略图对象带租户前缀 + thumbnails/ 前缀：" + thumbnailKey);
        assertTrue(thumbnailKey.endsWith(".jpg"), "缩略图保持原扩展名（无转码）");
        assertEquals(Integer.valueOf(1), row.getValue().getPages());

        // 桶里两个对象：原文件字节不变；缩略图可解码且最大边 ≤ 128
        assertArrayEquals(
                body, storage.get(row.getValue().getObjectKey().substring("t/demoa/".length())));
        byte[] thumbBytes = storage.get(thumbnailKey.substring("t/demoa/".length()));
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(thumbBytes));
        assertTrue(
                Math.max(thumb.getWidth(), thumb.getHeight()) <= 128,
                "缩略图最大边 ≤ 128：" + thumb.getWidth() + "×" + thumb.getHeight());
        // 原图与缩略图是两个不同对象
        assertNotEquals(row.getValue().getObjectKey(), thumbnailKey);
    }

    /** PNG 上传同样生成缩略图，且仍是 PNG（格式不变 = 无转码）。 */
    @Test
    void pngUploadKeepsThumbnailFormat() throws IOException {
        service.upload("心电图（示例）.png", ImageThumbnailGeneratorTest.png(300, 200));

        ArgumentCaptor<SysFileRow> row = ArgumentCaptor.forClass(SysFileRow.class);
        verify(files).insert(row.capture());
        assertEquals("png", row.getValue().getFormatCode());
        assertTrue(row.getValue().getThumbnailKey().endsWith(".png"));
        byte[] thumbBytes =
                storage.get(row.getValue().getThumbnailKey().substring("t/demoa/".length()));
        assertEquals((byte) 0x89, thumbBytes[0], "缩略图仍是 PNG 魔数");
    }

    /** 非图片（录音 / 文档）不生成任何派生对象：录音不转码、不另存副本（tech-plan §10「派生文件」）。 */
    @Test
    void nonImageUploadHasNoDerivedObjects() throws IOException {
        service.upload("通话（示例）.mp3", FileTypeDetectorTest.mp3(128));
        service.upload("出院小结（示例）.pdf", FileTypeDetectorTest.pdf());

        ArgumentCaptor<SysFileRow> row = ArgumentCaptor.forClass(SysFileRow.class);
        verify(files, times(2)).insert(row.capture());
        for (SysFileRow captured : row.getAllValues()) {
            assertNull(captured.getThumbnailKey(), captured.getFormatCode() + " 不生成缩略图");
        }
        // 桶里只有两个原文件对象，没有 thumbnails/ 目录（不另存副本）
        try (var paths = Files.walk(storageRoot)) {
            assertEquals(2, paths.filter(Files::isRegularFile).count(), "桶里只有原文件对象");
        }
        assertFalse(Files.exists(storageRoot.resolve("t/demoa/thumbnails")), "无 thumbnails/ 派生目录");
    }

    /** 损坏图片（Tika 认魔数、解码不出像素）：40001 拒绝，且不 PUT 原图、不落台账（解码在写桶之前）。 */
    @Test
    void corruptImageRejectedBeforeStoring() throws IOException {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.upload("照片（示例）.jpg", FileTypeDetectorTest.jpeg()));
        assertEquals(40001, ex.code());
        assertTrue(ex.getMessage().contains("无法解析"), ex.getMessage());
        verify(files, never()).insert(any(SysFileRow.class));
        try (var paths = Files.walk(storageRoot)) {
            assertEquals(0, paths.filter(Files::isRegularFile).count(), "不留任何对象");
        }
    }

    /** 存储端口失败：统一 50000 抛给调用方，且不落台账（先 PUT 后 INSERT，ADR 0006 §4）。 */
    @Test
    void storageFailureSurfacesAs50000AndNoRow() {
        FileUploadService failing =
                new FileUploadService(
                        policy,
                        new FileTypeDetector(),
                        new ImageThumbnailGenerator(128),
                        files,
                        brokenStorage(),
                        audit);
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> failing.upload("出院小结（示例）.pdf", FileTypeDetectorTest.pdf()));
        assertEquals(50000, ex.code());
        verify(files, never()).insert(any(SysFileRow.class));
        verify(audit, never()).record(any(AuditEvent.class));
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

    /** 永远失败的存储端口（模拟 OBS 侧故障 → 50000）。 */
    private static ObjectStorage brokenStorage() {
        return new ObjectStorage() {
            @Override
            public String put(String relativeKey, byte[] body, String contentType) {
                throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
            }

            @Override
            public byte[] get(String relativeKey) {
                throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
            }

            @Override
            public void delete(String relativeKey) {
                throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
            }
        };
    }

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
