package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.followup.implant.app.ImplantDictPort;
import com.crdp.system.dict.TenantDictService;
import com.crdp.system.dict.TenantDictService.DictItemView;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E21-T04：植入记录业务字典端口适配器——把 TenantDictService 的合并视图（平台项 − 本院停用 + 本院自有项） 映射为含生效标记的字典项（停用项保留，是否进下拉由
 * ImplantService 过滤，C-FLD-009）。
 */
class ImplantDictPortAdapterTest {

    private final TenantDictService dicts = mock(TenantDictService.class);
    private final ImplantDictPort adapter = new ImplantDictPortAdapter(dicts);

    @Test
    void mapsStatusToEnabledFlag() {
        when(dicts.itemsOrEmpty("pacing_mode"))
                .thenReturn(
                        List.of(
                                new DictItemView(
                                        "VVI", "VVI", null, 10, "ENABLED", "PLATFORM", false, null),
                                new DictItemView(
                                        "CRT_D",
                                        "CRT-D",
                                        null,
                                        110,
                                        "DISABLED",
                                        "PLATFORM",
                                        false,
                                        null),
                                new DictItemView(
                                        "OWN_DEMO",
                                        "示例本院项",
                                        null,
                                        120,
                                        "ENABLED",
                                        "TENANT",
                                        true,
                                        null)));

        List<ImplantDictPort.DictItem> items = adapter.items("pacing_mode");

        assertEquals(3, items.size());
        assertTrue(items.get(0).enabled());
        assertEquals(false, items.get(1).enabled(), "本院停用的平台项保留但标记不生效");
        assertTrue(items.get(2).enabled());
        assertEquals("示例本院项", items.get(2).label());
    }

    @Test
    void unknownDictReturnsEmptyList() {
        when(dicts.itemsOrEmpty("nosuch")).thenReturn(List.of());
        assertTrue(adapter.items("nosuch").isEmpty());
    }
}
