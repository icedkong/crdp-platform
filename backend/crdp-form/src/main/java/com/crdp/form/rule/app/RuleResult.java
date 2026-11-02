package com.crdp.form.rule.app;

import java.util.List;

/**
 * 规则执行结果（E11-T04 / A-FLD-003、A-FLD-006、C-FLD-007）。
 *
 * <p>{@code errors} 是硬校验命中（超硬范围、硬跨字段规则违反）——提交 / 保存修改拦截（42200）； {@code warnings}
 * 是软校验命中（软范围、软规则）——黄色提示并要求确认，不阻止保存（A-FLD-003「超出合理范围提示但可 保存」、C-FLD-007「超出范围黄色提示并要求确认，不阻止保存」）。
 */
public record RuleResult(List<FieldIssue> errors, List<FieldIssue> warnings) {

    public boolean ok() {
        return errors.isEmpty();
    }

    static RuleResult empty() {
        return new RuleResult(List.of(), List.of());
    }
}
