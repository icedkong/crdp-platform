/**
 * 授权范围模板页的纯逻辑（E05-T07 / PLT-SCOPE-003、004）：树表行、模板勾选模型、
 * 敏感清单编辑模型与校验。页面只做展示与编排。
 */
import type {
  ScopeTemplateItem,
  ScopeTemplateModule,
  ScopeTemplateView,
  SensitivePermItem,
} from '@crdp/api';

/** 树表行（antd Table 树形数据）。 */
export interface TemplateRow extends ScopeTemplateItem {
  children?: TemplateRow[];
  disabled: boolean;
}

/** 平铺项 → 树表行（按 parentId 组装；敏感项不可预置进模板，勾选禁用）。 */
export function toTemplateRows(module: ScopeTemplateModule): TemplateRow[] {
  const rows = new Map<number, TemplateRow>();
  for (const item of module.items) {
    rows.set(item.menuId, { ...item, disabled: item.sensitive });
  }
  const roots: TemplateRow[] = [];
  for (const item of module.items) {
    const row = rows.get(item.menuId);
    if (!row) continue;
    const parent = rows.get(item.parentId);
    if (parent && parent !== row) {
      parent.children = [...(parent.children ?? []), row];
    } else {
      roots.push(row);
    }
  }
  return roots;
}

/** 模块勾选模型：menuId → 是否在模板（输入态）。 */
export type TemplateChecked = Record<string, boolean>;

/** 视图 → 各模块初始勾选（inTemplate）。 */
export function initialChecked(
  view: ScopeTemplateView,
): Record<string, TemplateChecked> {
  const state: Record<string, TemplateChecked> = {};
  for (const module of view.modules) {
    const checked: TemplateChecked = {};
    for (const item of module.items) {
      checked[item.menuId] = item.inTemplate;
    }
    state[module.code] = checked;
  }
  return state;
}

/** 勾选态 → 提交载荷（只送勾选 id，排序稳定）。 */
export function toUpdateRequest(checked: TemplateChecked): {
  menuIds: number[];
} {
  return {
    menuIds: Object.entries(checked)
      .filter(([, value]) => value)
      .map(([key]) => Number(key))
      .toSorted((a, b) => a - b),
  };
}

/** 敏感清单编辑行。 */
export interface SensitivePermRowModel {
  name: string;
  permCode: string;
}

/** 清单 → 编辑行。 */
export function toSensitiveRows(
  items: SensitivePermItem[],
): SensitivePermRowModel[] {
  return items.map((item) => ({ name: item.name, permCode: item.permCode }));
}

/** 权限码格式 {模块}:{资源}:{动作}（与后端同一口径）。 */
const PERM_CODE = /^[a-z][a-z0-9-]*(:[a-z][a-z0-9-]*){2}$/;

/** 敏感清单校验：权限码格式、说明必填 ≤128、清单内不重复。返回行号 → 错误。 */
export function validateSensitiveRows(
  rows: SensitivePermRowModel[],
): Record<number, string> {
  const errors: Record<number, string> = {};
  const seen = new Set<string>();
  rows.forEach((row, index) => {
    const code = row.permCode.trim();
    if (!code) {
      errors[index] = '权限码必填';
    } else if (!PERM_CODE.test(code)) {
      errors[index] = '权限码须符合 {模块}:{资源}:{动作}';
    } else if (seen.has(code)) {
      errors[index] = '权限码重复';
    }
    seen.add(code);
    if (!row.name.trim()) {
      errors[index] = `${errors[index] ? `${errors[index]}；` : ''}说明必填`;
    } else if (row.name.trim().length > 128) {
      errors[index] = `${errors[index] ? `${errors[index]}；` : ''}说明过长`;
    }
  });
  return errors;
}
