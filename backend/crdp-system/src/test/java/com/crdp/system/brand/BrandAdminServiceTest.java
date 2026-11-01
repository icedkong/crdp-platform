package com.crdp.system.brand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import com.crdp.system.infra.mapper.BrandMapper;
import com.crdp.system.infra.mapper.BrandRow;
import org.junit.jupiter.api.Test;

/**
 * E06-T11 / PLT-CFG-008：租户个性化保存校验（纯单元；MySQL 行为由 BrandAndProfileIT 覆盖）。 显示名称必填与长度、主题色仅限预设色板（OQ-23）、
 * 素材地址放行口径、审计事件与回落逻辑。
 */
class BrandAdminServiceTest {

    private final BrandMapper mapper = mock(BrandMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final BrandAdminService service = new BrandAdminService(mapper, audit);

    private static BrandAdminService.BrandSave save(
            String displayName, String logoUrl, String themeColor) {
        return new BrandAdminService.BrandSave(displayName, logoUrl, null, themeColor, null);
    }

    @Test
    void displayNameIsRequired() {
        ApiException empty =
                assertThrows(
                        ApiException.class, () -> service.save(save(" ", null, null), "虚构示例医院甲"));
        assertEquals(40001, empty.code());

        ApiException tooLong =
                assertThrows(
                        ApiException.class,
                        () -> service.save(save("长".repeat(129), null, null), "虚构示例医院甲"));
        assertEquals(40001, tooLong.code());
    }

    @Test
    void themeColorMustBePreset() {
        ApiException bad =
                assertThrows(
                        ApiException.class,
                        () -> service.save(save("示例医院", null, "PURPLE"), "虚构示例医院甲"));
        assertEquals(40001, bad.code());
        verify(mapper, never()).update(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void assetUrlOnlyHttpOrSitePath() {
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.save(
                                                save("示例医院", "javascript:alert(1)", null),
                                                "虚构示例医院甲"))
                        .code(),
                "data: / javascript: 一律拒绝，防注入登录页");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> service.save(save("示例医院", "ftp://x/y.png", null), "虚构示例医院甲"))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> service.save(save("示例医院", "a".repeat(513), null), "虚构示例医院甲"))
                        .code(),
                "超长地址 40001");
        // 合法的 https 地址与站内路径通过
        service.save(save("示例医院", "https://assets.example.com/logo.png", null), "虚构示例医院甲");
        service.save(save("示例医院", "/assets/tenant/logo.svg", null), "虚构示例医院甲");
    }

    @Test
    void saveFallsBackToDefaultThemeAndWritesAudit() {
        // save() 落库后经 view() 回读组装（updatedAt 由数据库维护），单测里把保存行回灌给 find()
        BrandRow[] saved = new BrandRow[1];
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            saved[0] = invocation.getArgument(0);
                            return 1;
                        })
                .when(mapper)
                .update(any());
        when(mapper.find()).thenAnswer(invocation -> saved[0]);

        BrandAdminService.BrandView view = service.save(save("示例医院甲（北院）", null, null), "虚构示例医院甲");

        assertEquals("示例医院甲（北院）", view.displayName());
        assertEquals("虚构示例医院甲", view.defaultName());
        assertEquals(BrandThemeColor.BLUE.key(), view.themeColor(), "未选主题色回落默认（主色）");
        assertNotNull(view.themeColorHex());

        // 审计 detail 只落名称与色 key，不落素材内容
        verify(audit)
                .record(
                        org.mockito.ArgumentMatchers.<AuditEvent>argThat(
                                event ->
                                        event != null
                                                && event.eventType().equals("BRAND_UPDATE")
                                                && event.detail() != null
                                                && event.detail().contains("示例医院甲（北院）")));
    }

    @Test
    void viewFallsBackToTenantNameWhenNotConfigured() {
        when(mapper.find()).thenReturn(null);
        BrandAdminService.BrandView view = service.view("虚构示例医院甲");

        assertNull(view.displayName(), "未配置时 displayName 为空，由前端回落 defaultName");
        assertEquals("虚构示例医院甲", view.defaultName());
        assertEquals(BrandThemeColor.BLUE.key(), view.themeColor());
        assertNull(view.updatedAt());

        BrandRow row = new BrandRow();
        row.setDisplayName("示例医院甲（南院）");
        row.setThemeColor("TEAL");
        row.setUpdatedAt(java.time.LocalDateTime.now());
        when(mapper.find()).thenReturn(row);
        BrandAdminService.BrandView configured = service.view("虚构示例医院甲");
        assertEquals("示例医院甲（南院）", configured.displayName());
        assertEquals("#1DC29E", configured.themeColorHex());
        assertTrue(configured.updatedAt().contains("+08:00"), "时间带北京时间时区");
    }

    @Test
    void presetPaletteComesFromTokens() {
        assertEquals("#3A75FF", service.themeColors().get(0).hex());
        assertEquals(4, service.themeColors().size(), "BLUE / TEAL / ORANGE / RED 四色（OQ-23）");
    }
}
