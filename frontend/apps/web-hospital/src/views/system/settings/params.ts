import type { TenantParamLogView, TenantParamView } from '@crdp/api';

import { paramRangeText, validateOverrideValue } from '@crdp/api';

/**
 * E06-T10 / PLT-CFG-004、011 · 机构设置「租户参数」逻辑（纯函数）。
 * 覆盖值本地预校验与后端 ParamValueValidator 同口径（后端为准）；
 * 生效值与锁定失效（stale）的展示提示按 platform-spec §10。
 */

export interface OverrideFormModel {
  reason: string;
  value: string;
}

export function emptyOverrideModel(): OverrideFormModel {
  return { reason: '', value: '' };
}

/** 打开覆盖对话框时，把当前生效值预填为初值（从平台默认值或本院覆盖值改起）。 */
export function toOverrideModel(row: TenantParamView): OverrideFormModel {
  return { reason: '', value: row.effectiveValue ?? '' };
}

/** 覆盖校验：空值、类型 / 范围（锁定参数前端直接不给入口，双保险仍校验）。 */
export function validateOverrideModel(
  model: OverrideFormModel,
  row: TenantParamView,
): string[] {
  const errors: string[] = [];
  if (!row.tenantOverridable) {
    errors.push('该参数为平台锁定，不允许租户覆盖');
  }
  if (model.reason.trim().length > 200) {
    errors.push('变更说明不能超过 200 个字符');
  }
  const valueError = validateOverrideValue(
    row.valueType,
    model.value,
    row.valueRange,
  );
  if (valueError) {
    errors.push(valueError);
  }
  return errors;
}

/** 值列文案：生效值 = 覆盖值时带「本院覆盖」标记。 */
export function effectiveValueText(row: TenantParamView): string {
  return row.overridden && row.tenantValue === row.effectiveValue
    ? `${row.effectiveValue}（本院覆盖）`
    : (row.effectiveValue ?? '—');
}

/** 覆盖状态提示：锁定失效（stale）> 已覆盖 > 平台默认。 */
export function overrideStateText(row: TenantParamView): string {
  if (row.stale) {
    return '覆盖已失效（参数被平台锁定，恢复默认值生效）';
  }
  return row.overridden ? '已覆盖' : '平台默认';
}

/** 变更记录行文案：覆盖 20 → 30（原因）。 */
export function paramLogLine(row: TenantParamLogView): string {
  const from = row.oldValue ?? '（默认）';
  const to = row.newValue ?? '（恢复默认）';
  const reason = row.reason ? `，原因：${row.reason}` : '';
  return `${row.paramKey} ${from} → ${to}${reason}`;
}

/** 范围提示（表单 extra）；无范围（布尔 / JSON 或未配置）返回空串。 */
export function rangeHint(row: TenantParamView): string {
  const range = paramRangeText(row.valueType, row.valueRange);
  return range === '—' || range === '不限' ? '' : `允许范围：${range}`;
}
