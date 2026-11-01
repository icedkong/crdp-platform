package com.crdp.platform.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E05-T04（PLT-MOD-002）：随发版登记的模块清单——只登记「随访」模块（验收标准），平台基础 base 是种子数据、 后续模块（rct 等）首期不登记。迁移目录取
 * db/tenant/followup。
 */
class ModuleRegistryTest {

    private final ModuleRegistry registry = new ModuleRegistry();

    @Test
    void onlyFollowupIsRegisteredFromCode() {
        List<ModuleDefinition> definitions = registry.definitions();

        assertEquals(1, definitions.size(), "验收：只登记随访模块，实际：" + definitions);
        ModuleDefinition followup = definitions.get(0);
        assertEquals("followup", followup.code());
        assertEquals("随访", followup.defaultName());
        assertFalse(followup.baseModule(), "随访不是基础模块（基础模块必选不可关闭）");
        assertEquals(List.of("base"), followup.dependsOn());
        assertEquals("db/tenant/followup", followup.migrationLocation());
        assertFalse(followup.featureVersion().isBlank(), "功能版本必填（随发版注册）");
        assertFalse(followup.changeNote().isBlank(), "变更说明必填（PLT-MOD-003）");
    }

    @Test
    void followupExposesNotifyChannelConfigItem() {
        ModuleDefinition followup = registry.find("followup").orElseThrow();

        assertEquals(1, followup.configItems().size());
        ModuleDefinition.ConfigItem item = followup.configItems().get(0);
        assertEquals("defaultNotifyChannel", item.code());
        assertEquals("随访默认渠道", item.name());
        assertFalse(item.defaultValue().isBlank(), "配置项要有默认值（租户级取值默认取模块定义）");
    }

    @Test
    void findReturnsEmptyForUnknownOrNotRegisteredModules() {
        assertTrue(registry.find("rct").isEmpty(), "rct 是后续模块，首期不登记");
        assertTrue(registry.find("base").isEmpty(), "base 是种子数据，不在代码登记清单里");
        assertTrue(registry.find("nope").isEmpty());
    }
}
