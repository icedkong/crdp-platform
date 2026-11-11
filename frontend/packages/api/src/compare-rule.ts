/**
 * E17-T01 / A-FU-027（Q41）、C-FU-011（随 E21）：录入时对比标红规则的类型与配置页逻辑
 * （接口契约 docs/api/followup.md §4，后端 `com.crdp.followup.compare`）。
 *
 * 规则条目（字段、类型、结构）由预置迁移落库（A 的 Q41 全量随 E17-T05、C 默认随 E21-T20），
 * 配置页只做开关与调阈值；本地预校验与后端 CompareRuleType 同口径（后端为准）。
 * 求值（上一次记录解析、实时标红）随 E17-T02 / E17-T03 接入同一组规则。
 */

/** 规则类型（tech-plan §11 七类；params 形状见 CompareRuleParams）。 */
export type CompareRuleType =
  | 'CHANGE_PCT'
  | 'DIFF'
  | 'FLIP_TO_YES'
  | 'NEW_EVENT'
  | 'ORDINAL_RISE'
  | 'OUT_OF_RANGE'
  | 'VALUE_HIT';

/** 阈值参数（联合形状：按 ruleType 取对应键；后端按键集校验）。 */
export interface CompareRuleParams {
  codes?: string[];
  /** NEW_EVENT：事件日期字段（预置，只读）。 */
  dateField?: string;
  /** DIFF / CHANGE_PCT：方向（CHANGE_PCT 额外允许 ANY = 升高或降低）。 */
  direction?: 'ANY' | 'DROP' | 'RISE';
  /** ORDINAL_RISE：升级级数（1–5）。 */
  grades?: number;
  /** CHANGE_PCT：参考注字段键（C 脉宽，E21-T20 / C-FU-010；预置，只读）。 */
  noteField?: string;
  /** CHANGE_PCT：noteField 本次与上一次均为数值且不同时追加的注（如「脉宽不同，仅供参考」；预置，只读）。 */
  noteText?: string;
  /** OUT_OF_RANGE / VALUE_HIT：比较符。 */
  op?: 'GT' | 'GTE' | 'LT' | 'LTE';
  /** CHANGE_PCT：变化率百分比阈值。 */
  percent?: number;
  /** ORDINAL_RISE / NEW_EVENT / VALUE_HIT：命中选项编码。 */
  reachCodes?: string[];
  /** DIFF：差值阈值。 */
  threshold?: number;
  triggerCodes?: string[];
  /** OUT_OF_RANGE / VALUE_HIT：边界值。 */
  value?: number;
}

/** 一条对比规则（GET /api/hospital/followup/compare-rules 行）。 */
export interface CompareRule {
  baselineField?: null | string;
  baselineFieldLabel?: null | string;
  baselineFormCode?: null | string;
  categoryKey: string;
  enabled: boolean;
  fieldLabel?: null | string;
  fieldUnit?: null | string;
  formCode: string;
  formName?: null | string;
  id: number;
  message: string;
  name: string;
  params: CompareRuleParams;
  ruleCode: string;
  ruleType: CompareRuleType;
  ruleTypeLabel: string;
  targetField: string;
  updatedAt?: null | string;
}

/** PUT /api/hospital/followup/compare-rules/{id} 请求体（全量覆盖开关与阈值）。 */
export interface CompareRuleUpdate {
  enabled: boolean;
  params: CompareRuleParams;
}

/** 规则类型展示名（与后端 CompareRuleType.label 同源）。 */
export const COMPARE_RULE_TYPE_LABELS: Record<CompareRuleType, string> = {
  CHANGE_PCT: '变化率',
  DIFF: '差值下降 / 上升',
  FLIP_TO_YES: '由否变是',
  NEW_EVENT: '新发事件',
  ORDINAL_RISE: '序数升级',
  OUT_OF_RANGE: '超出提示范围',
  VALUE_HIT: '取值命中',
};

/** 方向展示名（条件文案用）。 */
export const COMPARE_DIRECTION_LABELS: Record<string, string> = {
  ANY: '升高或降低',
  DROP: '下降',
  RISE: '上升',
};

/** 比较符展示文案（条件文案用）。 */
export const COMPARE_OP_LABELS: Record<string, string> = {
  GTE: '≥',
  GT: '＞',
  LTE: '≤',
  LT: '＜',
};

/** 该类型是否有可调阈值（无阈值键的类型只可开关）。 */
export function supportsThreshold(ruleType: CompareRuleType): boolean {
  return (
    ruleType === 'DIFF' ||
    ruleType === 'CHANGE_PCT' ||
    ruleType === 'ORDINAL_RISE' ||
    ruleType === 'OUT_OF_RANGE'
  );
}

/** 可调阈值的键名（编辑表单按它绑定 InputNumber；无阈值类型返回 null）。 */
export function thresholdKeyOf(
  ruleType: CompareRuleType,
): 'grades' | 'percent' | 'threshold' | 'value' | null {
  switch (ruleType) {
    case 'CHANGE_PCT': {
      return 'percent';
    }
    case 'DIFF': {
      return 'threshold';
    }
    case 'ORDINAL_RISE': {
      return 'grades';
    }
    case 'OUT_OF_RANGE': {
      return 'value';
    }
    default: {
      return null;
    }
  }
}

/** 阈值输入的上下限与步进（与后端同口径）。 */
export function thresholdBounds(
  key: 'grades' | 'percent' | 'threshold' | 'value',
): { max: number; min: number; precision?: number } {
  if (key === 'grades') {
    return { max: 5, min: 1, precision: 0 };
  }
  if (key === 'value') {
    return { max: 10_000, min: -10_000, precision: 2 };
  }
  return { max: 9999, min: 0.01, precision: 2 };
}

/**
 * 表格「对比对象」列文案：上一次随访（无上一次时回落基线字段）/ 无对比（本次取值即命中）。
 */
export function compareTargetText(rule: CompareRule): string {
  if (rule.ruleType === 'FLIP_TO_YES' || rule.ruleType === 'NEW_EVENT') {
    return rule.baselineFieldLabel
      ? `上一次；无时基线（${rule.baselineFieldLabel}）`
      : '上一次随访';
  }
  if (rule.ruleType === 'OUT_OF_RANGE' || rule.ruleType === 'VALUE_HIT') {
    return '固定阈值';
  }
  return rule.baselineFieldLabel
    ? `上一次随访；无时基线（${rule.baselineFieldLabel}）`
    : '上一次随访';
}

/** 表格「条件」列文案：按类型把阈值拼成人话（message 是完整标红提示，列里只看条件）。 */
export function conditionText(rule: CompareRule): string {
  const unit = rule.fieldUnit ?? '';
  const unitSuffix = unit === '' ? '' : ` ${unit}`;
  switch (rule.ruleType) {
    case 'CHANGE_PCT': {
      const direction =
        COMPARE_DIRECTION_LABELS[rule.params.direction ?? ''] ?? '变化';
      return `${direction} > ${rule.params.percent}%`;
    }
    case 'DIFF': {
      const direction =
        COMPARE_DIRECTION_LABELS[rule.params.direction ?? ''] ?? '变化';
      return `${direction} ≥ ${rule.params.threshold}${unitSuffix}`;
    }
    case 'FLIP_TO_YES': {
      return '由否变是';
    }
    case 'NEW_EVENT': {
      return '本次新发（值命中且晚于上一次随访）';
    }
    case 'ORDINAL_RISE': {
      const reach =
        (rule.params.reachCodes ?? []).length > 0 ? '，或达到预设档位' : '';
      return `程度升高 ≥ ${rule.params.grades} 级${reach}`;
    }
    case 'OUT_OF_RANGE': {
      const op = COMPARE_OP_LABELS[rule.params.op ?? ''] ?? '';
      return `${op} ${rule.params.value}${unitSuffix}`;
    }
    case 'VALUE_HIT': {
      if ((rule.params.codes ?? []).length > 0) {
        return `取值命中预设选项`;
      }
      const op = COMPARE_OP_LABELS[rule.params.op ?? ''] ?? '';
      return `${op} ${rule.params.value}${unitSuffix}`;
    }
    default: {
      return rule.message;
    }
  }
}

/** 编辑表单初值：阈值键（无阈值类型为 null）+ 开关。 */
export function toCompareRuleFormModel(rule: CompareRule): {
  enabled: boolean;
  threshold: null | number;
} {
  const key = thresholdKeyOf(rule.ruleType);
  const raw = key === null ? null : rule.params[key];
  return {
    enabled: rule.enabled,
    threshold: raw === undefined ? null : raw,
  };
}

/** 编辑表单 → PUT 请求体：保留预置键，只替换阈值键与开关。 */
export function toCompareRuleUpdate(
  rule: CompareRule,
  model: { enabled: boolean; threshold: null | number },
): CompareRuleUpdate {
  const key = thresholdKeyOf(rule.ruleType);
  const params: CompareRuleParams = { ...rule.params };
  if (key === null) {
    return { enabled: model.enabled, params };
  }
  if (model.threshold !== null && !Number.isNaN(model.threshold)) {
    params[key] = model.threshold;
  }
  return { enabled: model.enabled, params };
}

/** 表单校验（与后端同口径）：阈值必填、范围与小数位。返回错误文案数组。 */
export function validateCompareRuleModel(
  rule: CompareRule,
  model: { enabled: boolean; threshold: null | number },
): string[] {
  const errors: string[] = [];
  const key = thresholdKeyOf(rule.ruleType);
  if (key === null) {
    return errors;
  }
  if (model.threshold === null || Number.isNaN(model.threshold)) {
    errors.push('阈值不能为空');
    return errors;
  }
  const bounds = thresholdBounds(key);
  if (model.threshold < bounds.min || model.threshold > bounds.max) {
    errors.push(
      key === 'grades'
        ? '升级级数须为 1–5 的整数'
        : `阈值须在 ${bounds.min}–${bounds.max} 之间`,
    );
  }
  const precision = bounds.precision ?? 0;
  const scaled = Number(model.threshold.toFixed(precision));
  if (Math.abs(model.threshold - scaled) > 1e-9) {
    errors.push(`阈值最多 ${precision} 位小数`);
  }
  if (key === 'grades' && !Number.isInteger(model.threshold)) {
    errors.push('升级级数须为整数');
  }
  return errors;
}
