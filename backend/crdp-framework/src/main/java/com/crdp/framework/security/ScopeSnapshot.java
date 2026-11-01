package com.crdp.framework.security;

import java.util.List;

/**
 * 登录时冻结的数据范围。多个角色取并集，任一角色为全部则不过滤。
 *
 * <p>E07-T03 / PLT-PERM-008：新增研究项目分支 {@code projectIds}（用户作为成员的研究项目，经扩展点 {@code
 * ResearchProjectScopeProvider} 取数，规则由 rct 模块实现）。项目分支只冻结清单；「项目 → 可见患者」的 SQL 改写分支 随 rct 模块落地，{@code
 * RestrictedScopeRewriter} 对带项目清单的快照拒绝执行（fail closed，不静默少过滤）。
 *
 * <p>会话经 Jackson 序列化存 Redis；升级窗口内的旧会话 JSON 没有 projectIds 字段，紧凑构造器把 null 归一成空清单。
 */
public record ScopeSnapshot(
        boolean all, List<Long> wardIds, List<Long> deptIds, boolean self, List<Long> projectIds) {

    public ScopeSnapshot {
        wardIds = wardIds == null ? List.of() : List.copyOf(wardIds);
        deptIds = deptIds == null ? List.of() : List.copyOf(deptIds);
        projectIds = projectIds == null ? List.of() : List.copyOf(projectIds);
    }

    public static ScopeSnapshot allowAll() {
        return new ScopeSnapshot(true, List.of(), List.of(), false, List.of());
    }

    /** 不含研究项目分支的受限快照（E07-T01 / T02 既有口径，调用方兼容）。 */
    public static ScopeSnapshot restricted(List<Long> wardIds, List<Long> deptIds, boolean self) {
        return restricted(wardIds, deptIds, self, List.of());
    }

    public static ScopeSnapshot restricted(
            List<Long> wardIds, List<Long> deptIds, boolean self, List<Long> projectIds) {
        return new ScopeSnapshot(false, wardIds, deptIds, self, projectIds);
    }

    public boolean empty() {
        return !all && wardIds.isEmpty() && deptIds.isEmpty() && !self && projectIds.isEmpty();
    }
}
