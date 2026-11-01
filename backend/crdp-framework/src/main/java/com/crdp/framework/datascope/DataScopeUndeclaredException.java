package com.crdp.framework.datascope;

import java.util.List;

/**
 * 默认拒绝扫描（E07-T01）发现未声明范围的受控表查询：访问患者及其子表的 SELECT 既没有 {@code @DataScope} 也没有
 * {@code @IgnoreScope}。这是后端编程错误（AGENTS.md 规则 2、docs/api/README.md §2.5），不是用户错误，必须在启动阶段暴露，不能等线上查询时才
 * 40302。
 */
public class DataScopeUndeclaredException extends IllegalStateException {

    private final List<String> violations;

    public DataScopeUndeclaredException(List<String> violations) {
        super(message(violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }

    private static String message(List<String> violations) {
        return "以下受控表查询未声明数据范围，启动被拒绝（默认拒绝，E07-T01）：\n  "
                + String.join("\n  ", violations)
                + "\n修复方式：在 Mapper 方法或接口上标注 @DataScope(resource = \"patient\")，"
                + "或调度 / 全院入口标注 @IgnoreScope(reason = \"...\") 并在评审中说明。";
    }
}
