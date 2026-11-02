package com.crdp.form.rule.infra.mapper;

/** fe_rule 行（E11-T04 / A-FLD-006、C-FLD-007）。params 为 JSON 文本，应用层解析。 */
public record RuleRow(
        Long id,
        String formCode,
        String ruleId,
        String categoryKey,
        String targetField,
        String severity,
        String ruleType,
        String params,
        String message,
        int sortNo) {}
