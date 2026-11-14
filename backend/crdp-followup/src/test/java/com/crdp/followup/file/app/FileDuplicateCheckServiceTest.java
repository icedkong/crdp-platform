package com.crdp.followup.file.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.TenantViewFieldGuard;
import com.crdp.framework.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E21-T18 / C-SCAN-017：重复文件提示单元测试（端口替身 + 真实脱敏算法）。
 *
 * <p>锁定任务验收「同一文件再次上传给出提示」的服务层口径：命中时 duplicate = true 且患者只输出脱敏值（姓名 / 病案号经
 * SensitiveValueMasker，AGENTS §5.3 列表不提供明文）；未命中 duplicate = false 且清单为空； sha256 缺失 / 非 64 位十六进制
 * 40001，大小写归一为小写（上传侧产出小写）。 数据范围（可见范围内提示、范围外不泄露）由 Mapper 语句上的 @DataScope 出行，集成测试
 * FileDuplicateCheckIT 锁定。 全部虚构样例，不含真实数据。
 */
@ExtendWith(MockitoExtension.class)
class FileDuplicateCheckServiceTest {

    private static final String HASH =
            "3a7f1c0b9d2e4f56a8b1c3d4e5f60718293a4b5c6d7e8f9012345678abcdef01";

    @Mock private FileDuplicateCheckPort lookup;
    @Mock private CryptoService crypto;

    private FileDuplicateCheckService service;

    @BeforeEach
    void assemble() {
        service = new FileDuplicateCheckService(lookup, crypto, TenantViewFieldGuard.passthrough());
    }

    /** 命中：duplicate = true，患者脱敏（姓名首尾 + 病案号前 2 后 2），visitNo / 病区原样。 */
    @Test
    void hitReturnsMaskedPatients() {
        when(crypto.decrypt("cipher-a")).thenReturn("张三（示例）");
        when(crypto.decrypt("cipher-mrn")).thenReturn("MRN20260001（示例）");
        when(lookup.findPatientsByFileSha256(HASH, FileDuplicateCheckService.PATIENT_LIMIT))
                .thenReturn(
                        List.of(
                                new FileDuplicateCheckPort.DuplicateFilePatientRow(
                                        9L,
                                        "cipher-a",
                                        "cipher-mrn",
                                        "C-2026-0001",
                                        "心血管内科病区（示例）")));

        FileDuplicateCheckService.DuplicateCheckView view = service.check(HASH);

        assertTrue(view.duplicate(), "同一文件再次上传应提示");
        assertEquals(1, view.patients().size());
        FileDuplicateCheckService.DuplicatePatientView patient = view.patients().get(0);
        assertEquals("9", patient.patientId());
        assertEquals("张*）", patient.nameMasked(), "姓名保留首尾（三字及以上张*明式单星）");
        // 15 字符：前 2（MR）+ 11 个 * + 后 2（例）——前 2 后 2 规则（SensitiveValueMasker）
        assertEquals("MR" + "*".repeat(11) + "例）", patient.mrnMasked(), "病案号按前 2 后 2 脱敏");
        assertEquals("C-2026-0001", patient.visitNo());
        assertEquals("心血管内科病区（示例）", patient.wardName());
    }

    /** 未命中（含本次刚上传、尚未挂任何报告单的 PENDING 文件——经 rpt_report_file 才有患者归属）：duplicate = false、清单为空。 */
    @Test
    void missReturnsEmpty() {
        when(lookup.findPatientsByFileSha256(HASH, FileDuplicateCheckService.PATIENT_LIMIT))
                .thenReturn(List.of());

        FileDuplicateCheckService.DuplicateCheckView view = service.check(HASH);

        assertFalse(view.duplicate());
        assertTrue(view.patients().isEmpty());
    }

    /** 同一患者多份报告单挂同哈希文件：只出一行（提示按患者，不按报告单重复列）。 */
    @Test
    void samePatientListedOnce() {
        when(crypto.decrypt("cipher-a")).thenReturn("李四（示例）");
        when(lookup.findPatientsByFileSha256(HASH, FileDuplicateCheckService.PATIENT_LIMIT))
                .thenReturn(
                        List.of(
                                new FileDuplicateCheckPort.DuplicateFilePatientRow(
                                        5L, "cipher-a", null, null, null)));

        FileDuplicateCheckService.DuplicateCheckView view = service.check(HASH);

        assertEquals(1, view.patients().size());
        assertEquals("李*）", view.patients().get(0).nameMasked());
        assertEquals("", view.patients().get(0).mrnMasked(), "无病案号密文时输出空串（与全院列表同口径）");
    }

    /** 解密失败的密文按未知处理输出空串口径，不抛错（与全院列表同口径）。 */
    @Test
    void undecryptableCipherYieldsEmptyMask() {
        when(crypto.decrypt("cipher-bad")).thenThrow(new IllegalStateException("坏了（示例）"));
        when(lookup.findPatientsByFileSha256(HASH, FileDuplicateCheckService.PATIENT_LIMIT))
                .thenReturn(
                        List.of(
                                new FileDuplicateCheckPort.DuplicateFilePatientRow(
                                        6L, "cipher-bad", "cipher-bad", null, null)));

        FileDuplicateCheckService.DuplicateCheckView view = service.check(HASH);

        assertEquals("", view.patients().get(0).nameMasked());
    }

    /** sha256 校验：缺失 / 空白 / 非 64 位 / 非十六进制全部 40001，不触发查询。 */
    @Test
    void invalidHashRejected() {
        assertEquals(40001, codeOf(() -> service.check(null)));
        assertEquals(40001, codeOf(() -> service.check("  ")));
        assertEquals(40001, codeOf(() -> service.check("abc")), "过短");
        assertEquals(40001, codeOf(() -> service.check(HASH + "00")), "过长");
        assertEquals(40001, codeOf(() -> service.check("z".repeat(64))), "非十六进制字符");
        verify(lookup, org.mockito.Mockito.never())
                .findPatientsByFileSha256(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt());
    }

    /** 大写十六进制归一为小写再查（上传侧产出小写，口子对调用方宽容）。 */
    @Test
    void uppercaseHashNormalizedToLowercase() {
        when(lookup.findPatientsByFileSha256(HASH, FileDuplicateCheckService.PATIENT_LIMIT))
                .thenReturn(List.of());

        service.check(HASH.toUpperCase());

        verify(lookup).findPatientsByFileSha256(HASH, FileDuplicateCheckService.PATIENT_LIMIT);
    }

    private static int codeOf(Runnable call) {
        return assertThrows(ApiException.class, call::run).code();
    }
}
