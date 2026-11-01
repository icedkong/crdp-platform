/**
 * 租户详情 · 授权范围页签的纯逻辑（E05-T07 / PLT-SCOPE-001..006、008）：模块分组的
 * 树表行、敏感放开原因校验、影响分析文案、变更记录展示。页面只做展示与编排。
 */
import type {
  TenantScopeAffectedRole,
  TenantScopeItem,
  TenantScopeModule,
  TenantScopePreview,
} from '@crdp/api';

import { TENANT_SCOPE_STATE_TEXT } from '@crdp/api';

/** 树表行（antd Table 树形数据：children 嵌套）。 */
export interface ScopeRow extends TenantScopeItem {
  children?: ScopeRow[];
  typeText: string;
}

const TYPE_TEXT: Record<string, string> = {
  BUTTON: '按钮',
  DIR: '目录',
  MENU: '菜单',
};

/** 平铺项 → 树表行（按 parentId 组装，排序沿用后端 order）。 */
export function toScopeRows(module: TenantScopeModule): ScopeRow[] {
  const rows = new Map<number, ScopeRow>();
  for (const item of module.items) {
    rows.set(item.id, { ...item, typeText: TYPE_TEXT[item.type] ?? item.type });
  }
  const roots: ScopeRow[] = [];
  for (const item of module.items) {
    const row = rows.get(item.id);
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

/** 树表行 → 平铺（含 children）。 */
export function flattenRows(rows: ScopeRow[]): TenantScopeItem[] {
  const items: TenantScopeItem[] = [];
  for (const row of rows) {
    const { children, typeText: _typeText, ...item } = row;
    items.push(item, ...flattenRows(children ?? []));
  }
  return items;
}

/** 模块的平铺项（后端本就平铺返回）。 */
export function flattenItems(items: TenantScopeItem[]): TenantScopeItem[] {
  return items;
}

/** 本次新放开的敏感项：勾选了但当前不可分配且敏感（需要逐一填原因）。 */
export function newSensitiveReleases(
  items: TenantScopeItem[],
  checked: Set<number>,
): TenantScopeItem[] {
  return items.filter(
    (item) =>
      item.sensitive && !item.fixed && checked.has(item.id) && !item.assignable,
  );
}

/** 变更原因校验（PLT-SCOPE-008：保存必须填写）：必填 ≤200 字。 */
export function validateReason(reason: string): string | undefined {
  if (!reason.trim()) {
    return '必须填写变更原因';
  }
  if (reason.trim().length > 200) {
    return '变更原因过长（最多 200 个字符）';
  }
  return undefined;
}

/** 敏感放开原因校验（PLT-SCOPE-004）：必填 ≤200 字。返回「menuId → 错误信息」。 */
export function validateReleaseReason(
  reasons: Record<string, string>,
  releases: TenantScopeItem[],
): Record<string, number | string> {
  const errors: Record<string, number | string> = {};
  for (const item of releases) {
    const value = (reasons[String(item.id)] ?? '').trim();
    if (!value) {
      errors[item.id] = `放开“${item.title}”必须填写原因`;
    } else if (value.length > 200) {
      errors[item.id] = `放开“${item.title}”的原因过长（最多 200 个字符）`;
    }
  }
  return errors;
}

/** 三种状态的展示文案（后端状态 → 中文）。 */
export function stateText(state: string): string {
  return (
    TENANT_SCOPE_STATE_TEXT[state as keyof typeof TENANT_SCOPE_STATE_TEXT] ??
    state
  );
}

/** 影响分析（PLT-SCOPE-005）→ 二次确认文案行。 */
export function previewLines(preview: TenantScopePreview): string[] {
  const lines: string[] = [];
  if (preview.removed.length > 0) {
    lines.push(`将收回：${impactTitles(preview.removed)}`);
    for (const role of preview.affectedRoles) {
      lines.push(roleLine(role));
    }
    lines.push(
      `受影响用户：${preview.affectedUserCount} 个（权限版本递增，下次请求即重算）`,
    );
  } else {
    lines.push('本次不收回任何项，无需同步收回角色授权');
  }
  if (preview.sensitiveReleases.length > 0) {
    lines.push(`将放开敏感权限：${impactTitles(preview.sensitiveReleases)}`);
  }
  const plainAdded = preview.added.filter(
    (item) =>
      !preview.sensitiveReleases.some((rel) => rel.menuId === item.menuId),
  );
  if (plainAdded.length > 0) {
    lines.push(`将放开其余项：${impactTitles(plainAdded)}`);
  }
  return lines;
}

function roleLine(role: TenantScopeAffectedRole): string {
  const codes =
    role.removedPermCodes.length > 0
      ? `（权限码：${role.removedPermCodes.join('、')}）`
      : '';
  return `角色 ${role.roleName}（${role.roleCode}）：移除 ${role.removedMenuCount} 项${codes}，成员 ${role.userCount} 人`;
}

function impactTitles(items: { title: string }[]): string {
  return items.map((item) => item.title).join('、');
}

/** 变更记录时间列：空显示「—」，精确到分钟。 */
export function timeText(value: null | string): string {
  if (!value) return '—';
  return value.slice(0, 16).replace('T', ' ');
}
