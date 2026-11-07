import type { CompareRule, CompareRuleType } from '@crdp/api';

import { thresholdKeyOf } from '@crdp/api';

/**
 * E17-T01 / A-FU-027 · 对比规则页（/system/abnormal-rule）页面逻辑（纯函数）。
 * 规则条目由预置迁移落库（Q41 全量随 E17-T05、C 默认随 E21-T20），本页只做开关与调阈值。
 */

/** 阈值输入控件的标签与提示（无阈值类型为 null，弹窗只显示开关）。 */
export function thresholdControl(
  ruleType: CompareRuleType,
): null | { hint: string; label: string } {
  switch (ruleType) {
    case 'CHANGE_PCT': {
      return {
        hint: '本次与上一次的变化率达到该百分比时标红',
        label: '变化率阈值（%）',
      };
    }
    case 'DIFF': {
      return {
        hint: '本次与上一次的差值达到该值时标红（如 EF 下降 ≥ 10 个百分点）',
        label: '差值阈值',
      };
    }
    case 'ORDINAL_RISE': {
      return {
        hint: '选项序数较上一次升高的级数（如返流加重 ≥ 2 级）',
        label: '升级级数',
      };
    }
    case 'OUT_OF_RANGE': {
      return {
        hint: '超出该边界即标红（如 EF ＜ 40、LVDd ＞ 60）',
        label: '边界值',
      };
    }
    default: {
      return null;
    }
  }
}

/** 保存成功提示（pages.md §10：修改记录操作日志）。 */
export function savedMessage(ruleName: string): string {
  return `已保存「${ruleName}」对比规则，并记录操作日志`;
}

/** 列表按 id 原位替换保存后的规则行。 */
export function applyUpdate(
  rules: CompareRule[],
  saved: CompareRule,
): CompareRule[] {
  return rules.map((rule) => (rule.id === saved.id ? saved : rule));
}

/** 表格数据源键（行 key）。 */
export function rowKeyOf(rule: CompareRule): string {
  return String(rule.id);
}

/** 是否无阈值类型（弹窗不显示阈值控件，提示只可开关）。 */
export function isSwitchOnly(ruleType: CompareRuleType): boolean {
  return thresholdKeyOf(ruleType) === null;
}
