package com.crdp.framework.datascope;

import com.crdp.framework.security.ScopeSnapshot;

/**
 * 默认拒绝。M0 的 SQL 改写是占位：全部范围原样通过，空范围追加 {@code 1 = 0}， 有范围时只打标记。按患者 EXISTS 关联病区 / 科室 / 本人的改写在 E07 用
 * JSqlParser 完成。
 */
public final class DataScopeGuard {

    private DataScopeGuard() {}

    public static String enforce(String sql, ScopeSnapshot snapshot, boolean ignored) {
        if (!ControlledTables.referencedBy(sql)) {
            return sql;
        }
        if (ignored) {
            return sql;
        }
        if (snapshot == null) {
            throw new DataScopeDeniedException();
        }
        if (snapshot.all()) {
            return sql;
        }
        if (snapshot.empty()) {
            return sql + " AND (1 = 0)";
        }
        return sql + " /* crdp-datascope:restricted */";
    }
}
