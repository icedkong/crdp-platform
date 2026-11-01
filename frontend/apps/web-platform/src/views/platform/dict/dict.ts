import type { PlatformDictItemView, PlatformDictView } from '@crdp/api';

/**
 * E06-T10 / PLT-CFG-001 · 平台字典管理页逻辑（纯函数，视图组装见 index.vue）。
 * 校验口径与后端 PlatformDictAdminService 一致：编码创建后不可改；项编码字典内唯一。
 */

/** 字典编码：小写字母开头，小写字母 / 数字 / 下划线（与后端 DICT_CODE 同口径）。 */
export const DICT_CODE_PATTERN = /^[a-z][a-z0-9_]{1,63}$/;

/** 项编码：字母 / 数字 / 下划线 / 中横线。 */
export const ITEM_CODE_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;

export interface DictEditModel {
  code: string;
  description: string;
  name: string;
  status: 'DISABLED' | 'ENABLED';
}

export interface DictItemEditModel {
  itemCode: string;
  label: string;
  sortNo: number;
  status: 'DISABLED' | 'ENABLED';
}

/** 新建字典表单的空模型。 */
export function emptyDictModel(): DictEditModel {
  return { code: '', description: '', name: '', status: 'ENABLED' };
}

/** 新建字典项表单的空模型。 */
export function emptyItemModel(): DictItemEditModel {
  return { itemCode: '', label: '', sortNo: 0, status: 'ENABLED' };
}

/** 字典类型行 → 维护表单模型（编辑不改编码）。 */
export function toDictModel(row: PlatformDictView): DictEditModel {
  return {
    code: row.code,
    description: row.description ?? '',
    name: row.name,
    status: row.status,
  };
}

/** 字典项行 → 维护表单模型（编辑不改项编码）。 */
export function toItemModel(row: PlatformDictItemView): DictItemEditModel {
  return {
    itemCode: row.itemCode,
    label: row.label,
    sortNo: row.sortNo,
    status: row.status,
  };
}

/** 新建字典校验：返回错误消息数组（空数组 = 通过）。 */
export function validateDictModel(
  model: DictEditModel,
  existing: PlatformDictView[],
): string[] {
  const errors: string[] = [];
  if (!DICT_CODE_PATTERN.test(model.code.trim())) {
    errors.push('编码必须是小写字母开头的 2–64 位小写字母、数字或下划线');
  }
  validateDictCommon(model, existing, errors);
  return errors;
}

/** 维护字典校验（编码不可改，不再校验格式与冲突）。 */
export function validateDictUpdate(model: DictEditModel): string[] {
  const errors: string[] = [];
  if (!model.name.trim()) {
    errors.push('名称不能为空');
  }
  return errors;
}

function validateDictCommon(
  model: DictEditModel,
  existing: PlatformDictView[],
  errors: string[],
) {
  if (!model.name.trim()) {
    errors.push('名称不能为空');
  }
  if (model.name.trim().length > 128) {
    errors.push('名称不能超过 128 个字符');
  }
  if (model.description.trim().length > 255) {
    errors.push('说明不能超过 255 个字符');
  }
  const code = model.code.trim();
  if (existing.some((row) => row.code === code)) {
    errors.push('字典编码已存在');
  }
}

/** 字典项校验：existingItemCodes 为当前字典的全部项编码。 */
export function validateItemModel(
  model: DictItemEditModel,
  existingItemCodes: string[],
  creating: boolean,
): string[] {
  const errors: string[] = [];
  if (creating && !ITEM_CODE_PATTERN.test(model.itemCode.trim())) {
    errors.push('项编码必须是 1–64 位字母、数字、下划线或中横线');
  }
  if (creating && existingItemCodes.includes(model.itemCode.trim())) {
    errors.push('项编码已存在');
  }
  if (!model.label.trim()) {
    errors.push('显示文字不能为空');
  }
  if (model.label.trim().length > 128) {
    errors.push('显示文字不能超过 128 个字符');
  }
  return errors;
}

/** 项数列文案：「生效 / 全部」，停用项不进租户生效集。 */
export function itemCountText(row: PlatformDictView): string {
  return `${row.enabledItemCount} / ${row.itemCount}`;
}
