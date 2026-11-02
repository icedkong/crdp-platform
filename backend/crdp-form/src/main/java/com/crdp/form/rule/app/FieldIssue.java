package com.crdp.form.rule.app;

/**
 * 一条校验命中（form.md §2 42200 的 {@code data.fieldErrors[]} 元素与前端提示同构）。
 *
 * <p>{@code rule} 是命中的规则标识：范围用固定码 {@code hardRange} / {@code softRange}，跨字段用 fe_rule 的
 * 稳定编码（rule_id）。
 */
public record FieldIssue(String key, String categoryKey, String rule, String message) {}
