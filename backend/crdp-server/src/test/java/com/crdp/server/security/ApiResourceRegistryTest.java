package com.crdp.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.platform.apires.ApiResourceRow;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 登记册快照（E05-T06 / PLT-PERM-004）：精确路径直接命中，路径变量模式按 Ant 匹配，未登记返回 empty。 */
class ApiResourceRegistryTest {

    private final ApiResourceRegistry registry = new ApiResourceRegistry();

    private ApiResourceRow row(String method, String path, String perm, boolean publicAccess) {
        ApiResourceRow row = new ApiResourceRow();
        row.setHttpMethod(method);
        row.setPathPattern(path);
        row.setPermCode(perm);
        row.setPublicAccess(publicAccess);
        return row;
    }

    @Test
    void exactPathHitsAndMissReturnsEmpty() {
        registry.refresh(
                List.of(row("GET", "/api/platform/menus/tree", "platform:menu:view", false)));

        Optional<ApiResourceRegistry.Entry> hit = registry.find("GET", "/api/platform/menus/tree");
        assertTrue(hit.isPresent());
        assertEquals("platform:menu:view", hit.get().permCode());

        // 方法不同不算登记（验收：未登记默认拒绝按「方法 + 路径」判定）
        assertFalse(registry.find("POST", "/api/platform/menus/tree").isPresent());
        assertFalse(registry.find("GET", "/api/platform/not-registered").isPresent());
    }

    @Test
    void pathVariablePatternMatches() {
        registry.refresh(
                List.of(row("PUT", "/api/platform/menus/{id}", "platform:menu:edit", false)));

        Optional<ApiResourceRegistry.Entry> hit = registry.find("PUT", "/api/platform/menus/12345");
        assertTrue(hit.isPresent());
        assertEquals("platform:menu:edit", hit.get().permCode());
        assertFalse(registry.find("PUT", "/api/platform/menus/1/sub").isPresent());
    }

    @Test
    void refreshReplacesSnapshotAtomically() {
        registry.refresh(List.of(row("GET", "/api/platform/old", null, true)));
        registry.refresh(List.of(row("GET", "/api/platform/new", null, true)));

        assertFalse(registry.find("GET", "/api/platform/old").isPresent());
        assertTrue(registry.find("GET", "/api/platform/new").isPresent());
    }
}
