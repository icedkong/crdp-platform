package com.crdp.followup.compare.infra.mapper;

import java.time.LocalDateTime;

/**
 * fu_compare_rule 一行（E17-T01 / A-FU-027）。params 为 JSON 文本（应用层解析并按 rule_type 校验，见 {@link
 * com.crdp.followup.compare.domain.CompareRuleType}）；关联 fe_form / fe_field 的显示名由查询直接带出。
 */
public record CompareRuleRow(
        Long id,
        String formCode,
        String formName,
        String ruleCode,
        String name,
        String categoryKey,
        String targetField,
        String fieldLabel,
        String fieldUnit,
        String ruleType,
        String params,
        String baselineFormCode,
        String baselineField,
        String baselineFieldLabel,
        String message,
        int sortNo,
        String status,
        Long updatedBy,
        LocalDateTime updatedAt) {}
