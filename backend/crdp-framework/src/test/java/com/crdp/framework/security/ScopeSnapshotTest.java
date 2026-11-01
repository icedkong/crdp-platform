package com.crdp.framework.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E07-T03 / PLT-PERM-008：快照携带研究项目分支（memberProjectIds 冻结进 projectIds）。 三参 restricted 保持 E07-T01/T02
 * 调用方兼容（项目分支恒空）；四参 restricted 由 RESEARCH_PROJECT 类型触发时使用。 旧会话 JSON（E07-T03 之前序列化，无 projectIds
 * 字段）反序列化后不得因 null 分支炸掉访问器。
 */
class ScopeSnapshotTest {

    @Test
    void threeArgRestrictedKeepsEmptyProjects() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(3L), List.of(11L), true);
        assertEquals(List.of(3L), scope.wardIds());
        assertEquals(List.of(11L), scope.deptIds());
        assertTrue(scope.self());
        assertTrue(scope.projectIds().isEmpty(), "三参构造不带研究项目分支");
    }

    @Test
    void fourArgRestrictedCarriesProjectIds() {
        ScopeSnapshot scope =
                ScopeSnapshot.restricted(List.of(3L), List.of(), false, List.of(7L, 8L));
        assertEquals(List.of(7L, 8L), scope.projectIds());
        assertFalse(scope.empty(), "带项目清单的快照不是空范围（项目分支待 rct 模块改写）");
    }

    @Test
    void projectIdsCountTowardEmpty() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(), List.of(), false, List.of());
        assertTrue(scope.empty(), "项目分支为空且无其他分支 → 空范围（看不到患者）");
    }

    @Test
    void projectIdsAreCopiedDefensively() {
        List<Long> mutable = new java.util.ArrayList<>(List.of(7L));
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(), List.of(), false, mutable);
        mutable.add(9L);
        assertEquals(List.of(7L), scope.projectIds(), "快照冻结登录时刻的清单，外部可变列表不影响");
    }

    /** 会话存 Redis（SessionStore，Jackson 序列化）；升级窗口内旧会话 JSON 没有 projectIds 字段。 */
    @Test
    void oldSessionJsonWithoutProjectIdsDeserializesSafely() throws Exception {
        String oldJson = "{\"all\":false,\"wardIds\":[3],\"deptIds\":[],\"self\":false}";
        ScopeSnapshot scope = new ObjectMapper().readValue(oldJson, ScopeSnapshot.class);
        assertEquals(List.of(3L), scope.wardIds());
        assertEquals(List.of(), scope.projectIds(), "缺失字段按空清单处理，不返回 null");
        assertFalse(scope.empty(), "病区分支仍在");
    }
}
