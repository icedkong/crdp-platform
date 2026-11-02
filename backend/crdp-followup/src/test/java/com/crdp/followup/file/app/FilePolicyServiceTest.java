package com.crdp.followup.file.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.followup.file.app.FileUploadParamDefaults.ParamDefault;
import com.crdp.followup.file.infra.mapper.UploadParamMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E10-T01 / PLT-CFG-010：上传策略（平台默认 → 租户覆盖 → 代码兜底）单元测试。
 *
 * <p>锁定：默认口径（A-ATT-004：20 MB / 录音 50 MB / 每份 10 个、白名单为目录全集）、租户覆盖生效 （医院 C 每份 50
 * 个的口径经覆盖实现）、白名单只认目录内扩展名（未知令牌忽略、全空回落全集）、 覆盖值不可解析回落平台默认、平台缺行回落代码缺省。
 */
@ExtendWith(MockitoExtension.class)
class FilePolicyServiceTest {

    private final FileUploadParamDefaults defaults = mock(FileUploadParamDefaults.class);
    private final UploadParamMapper overrides = mock(UploadParamMapper.class);

    private final FilePolicyService service = new FilePolicyService(defaults, overrides);

    @BeforeEach
    void noOverrideNoDefault() {
        lenient().when(overrides.findOverride(anyString())).thenReturn(null);
        lenient()
                .when(defaults.param(anyString()))
                .thenAnswer(invocation -> Optional.<ParamDefault>empty());
    }

    /** 默认（平台与租户都没配）：代码内置缺省 = A-ATT-004 口径，白名单为格式目录全集。 */
    @Test
    void fallsBackToCodeDefaults() {
        FilePolicyService.UploadPolicy policy = service.policy();

        assertEquals(9, policy.formats().size(), "缺省放开目录全集 9 种");
        assertEquals(20, policy.maxDocumentMb());
        assertEquals(50, policy.maxAudioMb());
        assertEquals(10, policy.maxFilesPerReport());
        assertTrue(policy.byExtension("pdf").isPresent());
        assertTrue(policy.byExtension("jpeg").isPresent(), "别名 jpeg 命中 JPG");
        assertTrue(policy.byExtension("amr").isPresent());
        assertFalse(policy.byExtension("exe").isPresent());
        assertFalse(policy.byExtension("docm").isPresent(), "宏 Office 不在目录");
        assertFalse(policy.byExtension("zip").isPresent());
        assertFalse(policy.byExtension("gif").isPresent());
        // 大小按类别区分
        assertEquals(20L * 1024 * 1024, policy.byExtension("pdf").orElseThrow().maxBytes());
        assertEquals(50L * 1024 * 1024, policy.byExtension("mp3").orElseThrow().maxBytes());
    }

    /** 平台默认值（plt_param，V33 种子）：生效值取平台行。 */
    @Test
    void usesPlatformDefaults() {
        when(defaults.param(FilePolicyService.KEY_MAX_SIZE_MB))
                .thenReturn(
                        Optional.of(
                                new ParamDefault(
                                        FilePolicyService.KEY_MAX_SIZE_MB, "30", "NUMBER")));
        when(defaults.param(FilePolicyService.KEY_ALLOWED_TYPES))
                .thenReturn(
                        Optional.of(
                                new ParamDefault(
                                        FilePolicyService.KEY_ALLOWED_TYPES,
                                        "jpg,png,pdf",
                                        "TEXT")));

        FilePolicyService.UploadPolicy policy = service.policy();

        assertEquals(30, policy.maxDocumentMb());
        assertEquals(3, policy.formats().size(), "平台白名单收窄为 3 种");
        assertTrue(policy.byExtension("jpg").isPresent());
        assertFalse(policy.byExtension("docx").isPresent());
    }

    /** 租户覆盖（cfg_param）：数值与白名单都按本院生效（医院 C 每份 50 个的口径）。 */
    @Test
    void tenantOverrideWins() {
        when(overrides.findOverride(FilePolicyService.KEY_MAX_FILES_PER_REPORT)).thenReturn("50");
        when(overrides.findOverride(FilePolicyService.KEY_ALLOWED_TYPES))
                .thenReturn("JPG, PNG , pdf,m4a,mp3");
        when(overrides.findOverride(FilePolicyService.KEY_AUDIO_MAX_SIZE_MB)).thenReturn("100");

        FilePolicyService.UploadPolicy policy = service.policy();

        assertEquals(50, policy.maxFilesPerReport());
        assertEquals(100, policy.maxAudioMb());
        assertEquals(5, policy.formats().size(), "覆盖白名单大小写不敏感、忽略空白（JPG/PNG/PDF/M4A/MP3）");
        assertFalse(policy.byExtension("docx").isPresent());
        assertFalse(policy.byExtension("amr").isPresent());
        // 每份数量校验（数量限制，A-ATT-004 / C-SCAN-006）
        assertTrue(policy.withinFileCount(50));
        assertFalse(policy.withinFileCount(51));
        assertFalse(policy.withinFileCount(0), "0 个文件不构成合法报告单附件数");
    }

    /** 白名单含目录外令牌：忽略之；全部无效则回落全集（不因配置错误锁死上传）。 */
    @Test
    void unknownWhitelistTokensIgnored() {
        when(overrides.findOverride(FilePolicyService.KEY_ALLOWED_TYPES)).thenReturn("pdf,gif,exe");
        assertEquals(1, service.policy().formats().size(), "只有 pdf 是目录内格式");

        when(overrides.findOverride(FilePolicyService.KEY_ALLOWED_TYPES)).thenReturn("gif,,docm");
        assertEquals(9, service.policy().formats().size(), "全空回落目录全集");
    }

    /** 覆盖值 / 平台默认值不可解析：逐级回落，绝不放行超大或锁死。 */
    @Test
    void invalidValuesFallBack() {
        when(overrides.findOverride(FilePolicyService.KEY_MAX_SIZE_MB)).thenReturn("abc");
        when(defaults.param(FilePolicyService.KEY_MAX_SIZE_MB))
                .thenReturn(
                        Optional.of(
                                new ParamDefault(
                                        FilePolicyService.KEY_MAX_SIZE_MB, "40", "NUMBER")));
        assertEquals(40, service.policy().maxDocumentMb(), "覆盖不可解析回落平台默认");

        when(overrides.findOverride(FilePolicyService.KEY_MAX_SIZE_MB)).thenReturn("-5");
        assertEquals(40, service.policy().maxDocumentMb(), "非正数覆盖无效，仍取平台默认 40");

        when(defaults.param(FilePolicyService.KEY_MAX_SIZE_MB))
                .thenReturn(Optional.<ParamDefault>empty());
        when(overrides.findOverride(FilePolicyService.KEY_MAX_SIZE_MB)).thenReturn("0");
        assertEquals(20, service.policy().maxDocumentMb(), "平台也没定义时回落代码缺省 20");
    }
}
