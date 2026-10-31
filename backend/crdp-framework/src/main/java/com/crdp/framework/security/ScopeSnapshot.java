package com.crdp.framework.security;

import java.util.List;

/** 登录时冻结的数据范围。多个角色取并集，任一角色为全部则不过滤。 */
public record ScopeSnapshot(boolean all, List<Long> wardIds, List<Long> deptIds, boolean self) {

    public static ScopeSnapshot allowAll() {
        return new ScopeSnapshot(true, List.of(), List.of(), false);
    }

    public static ScopeSnapshot restricted(List<Long> wardIds, List<Long> deptIds, boolean self) {
        return new ScopeSnapshot(false, List.copyOf(wardIds), List.copyOf(deptIds), self);
    }

    public boolean empty() {
        return !all && wardIds.isEmpty() && deptIds.isEmpty() && !self;
    }
}
