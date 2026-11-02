import type { TenantDictItemView, TenantDictTypeView } from '@crdp/api';

/**
 * E06-T10 / PLT-CFG-002 · 机构设置「租户字典」逻辑（纯函数）。
 * 继承规则：平台项只能停用 / 恢复（文字与排序不可改）；本院自有项可维护；
 * 平台字典类型不可改，本院自有字典可建可改。校验与后端 TenantDictService 同口径。
 * E11-T08 / A-FLD-012：本院自有项可维护别名（历史写法映射与导入匹配）。
 */

/** 本院字典编码：小写字母开头（与后端 DICT_CODE 同口径）。 */
export const DICT_CODE_PATTERN = /^[a-z][a-z0-9_]{1,63}$/;

export const ITEM_CODE_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;

export interface TenantDictEditModel {
  code: string;
  description: string;
  name: string;
  status: 'DISABLED' | 'ENABLED';
}

export interface TenantDictItemEditModel {
  /** 别名 CSV（本院自有项；平台项不开放）。 */
  aliases: string;
  itemCode: string;
  label: string;
  sortNo: number;
  status: 'DISABLED' | 'ENABLED';
}

export function emptyDictModel(): TenantDictEditModel {
  return { code: '', description: '', name: '', status: 'ENABLED' };
}

export function emptyItemModel(): TenantDictItemEditModel {
  return { aliases: '', itemCode: '', label: '', sortNo: 0, status: 'ENABLED' };
}

export function toDictModel(row: TenantDictTypeView): TenantDictEditModel {
  return {
    code: row.code,
    description: row.description ?? '',
    name: row.name,
    status: row.status,
  };
}

export function toItemModel(row: TenantDictItemView): TenantDictItemEditModel {
  return {
    aliases: row.aliases ?? '',
    itemCode: row.itemCode,
    label: row.label,
    sortNo: row.sortNo,
    status: row.status,
  };
}

/** 新建本院字典校验（与平台字典编码冲突也在内，后端 40902 兜底）。 */
export function validateDictModel(
  model: TenantDictEditModel,
  existing: TenantDictTypeView[],
): string[] {
  const errors: string[] = [];
  if (!DICT_CODE_PATTERN.test(model.code.trim())) {
    errors.push('编码必须是小写字母开头的 2–64 位小写字母、数字或下划线');
  }
  if (existing.some((row) => row.code === model.code.trim())) {
    errors.push('字典编码已存在（含平台字典）');
  }
  if (!model.name.trim()) {
    errors.push('名称不能为空');
  }
  if (model.name.trim().length > 128) {
    errors.push('名称不能超过 128 个字符');
  }
  return errors;
}

export function validateDictUpdate(model: TenantDictEditModel): string[] {
  return model.name.trim() ? [] : ['名称不能为空'];
}

/** 字典项校验：本院自有项可改文字 / 排序；平台项后端只认 status（前端不开放文字编辑）。 */
export function validateItemModel(
  model: TenantDictItemEditModel,
  existingItemCodes: string[],
  creating: boolean,
): string[] {
  const errors: string[] = [];
  if (creating && !ITEM_CODE_PATTERN.test(model.itemCode.trim())) {
    errors.push('项编码必须是 1–64 位字母、数字、下划线或中横线');
  }
  if (creating && existingItemCodes.includes(model.itemCode.trim())) {
    errors.push('项编码已存在（含平台项）');
  }
  if (!model.label.trim()) {
    errors.push('显示文字不能为空');
  }
  if (model.label.trim().length > 128) {
    errors.push('显示文字不能超过 128 个字符');
  }
  if ((model.aliases ?? '').trim().length > 255) {
    errors.push('别名不能超过 255 个字符');
  }
  return errors;
}

/** 字典类型行文案：生效项数（含本院自有与停用计数提示）。 */
export function itemCountText(row: TenantDictTypeView): string {
  if (row.source === 'PLATFORM') {
    return row.disabledPlatformCount > 0
      ? `${row.itemCount}（停用平台项 ${row.disabledPlatformCount}）`
      : `${row.itemCount}`;
  }
  return `${row.itemCount}`;
}
