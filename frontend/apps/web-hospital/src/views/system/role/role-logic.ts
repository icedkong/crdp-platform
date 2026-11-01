import type {
  DeptNodeView,
  FieldPolicy,
  OrgCampusNode,
  RoleScopeType,
  RoleType,
  TenantRoleFieldPolicyEntry,
  TenantRoleMenuNode,
  TenantRoleView,
} from '@crdp/api';

import { tenantFieldPolicyText } from '@crdp/api';

/**
 * E06-T05 角色管理页的纯逻辑（校验、过滤、树装配），供 index.vue 与单测共用；
 * 校验口径与后端 RoleAdminService 保持一致（40001 的前端预检）。
 * E07-T06 增加数据范围选项装配与字段策略草稿 / 保存载荷（PLT-PERM-007、009）。
 */

/** 角色编码：必填、≤64、字母数字下划线短横线（创建后不可改）。 */
export function validateRoleCode(code: string): null | string {
  const value = code.trim();
  if (value === '') return '角色编码必填';
  if (value.length > 64) return '角色编码过长（最多 64 个字符）';
  if (!/^[A-Za-z0-9_-]+$/.test(value)) {
    return '角色编码只允许字母、数字、下划线与短横线';
  }
  return null;
}

/** 角色名称：必填、≤64。 */
export function validateRoleName(name: string): null | string {
  const value = name.trim();
  if (value === '') return '角色名称必填';
  if (value.length > 64) return '角色名称过长（最多 64 个字符）';
  return null;
}

/** 角色说明：可空、≤200。 */
export function validateRoleDescription(description: string): null | string {
  const value = description.trim();
  if (value.length > 200) return '角色说明过长（最多 200 个字符）';
  return null;
}

export interface RoleFilter {
  keyword: string;
  type: 'ALL' | RoleType;
}

/** 列表过滤：关键字（编码 / 名称 / 说明）与类型。 */
export function filterRoles(
  roles: TenantRoleView[],
  filter: RoleFilter,
): TenantRoleView[] {
  const keyword = filter.keyword.trim().toLowerCase();
  return roles.filter((role) => {
    if (filter.type !== 'ALL' && role.roleType !== filter.type) return false;
    if (keyword === '') return true;
    return (
      role.code.toLowerCase().includes(keyword) ||
      role.name.toLowerCase().includes(keyword) ||
      (role.description ?? '').toLowerCase().includes(keyword)
    );
  });
}

/** 变更原因（停用 / 启用）：可空、≤200。 */
export function validateReason(reason: string): null | string {
  if (reason.trim().length > 200) return '原因过长（最多 200 个字符）';
  return null;
}

/** 复制默认命名：模板复制沿用模板名（租户按本院习惯改），自定义复制加「（副本）」。 */
export function copyNameSuggestion(source: TenantRoleView): string {
  return source.roleType === 'TEMPLATE'
    ? source.name
    : `${source.name}（副本）`;
}

/** 数据范围是否需要在角色上勾选科室清单（仅自定义科室）。 */
export function isScopeTypeRequiresDept(type: RoleScopeType): boolean {
  return type === 'CUSTOM_DEPT';
}

/** 菜单树勾选态（checkStrictly 口径：显式勾选，父子不联动，保存即原样提交）。 */
export function checkedMenuIds(
  menus: TenantRoleMenuNode[],
  menuIds: number[],
): number[] {
  const assignable = new Set<number>();
  const walk = (nodes: TenantRoleMenuNode[]) => {
    for (const node of nodes) {
      if (node.assignable) assignable.add(node.id);
      walk(node.children);
    }
  };
  walk(menus);
  // 当前授权里超出可分配范围的项（平台收回后未同步）展示为禁用勾选，不参与提交
  return [...new Set(menuIds)].filter((id) => assignable.has(id));
}

/** 菜单节点标题（含按钮的权限码后缀与不可分配标记）。 */
export function menuNodeTitle(node: TenantRoleMenuNode): string {
  const perm = node.permCode ? `（${node.permCode}）` : '';
  return node.assignable
    ? `${node.title}${perm}`
    : `${node.title}${perm} · 不可分配`;
}

/**
 * 保存前补齐祖先目录（默认勾选模式下父目录半选不会出现在 checkedKeys 里，
 * 而菜单树装配按 parent 挂载——缺目录的菜单不会渲染）；只补可分配的祖先。
 */
export function withAncestorDirs(
  menus: TenantRoleMenuNode[],
  checkedIds: number[],
): number[] {
  const parentOf = new Map<number, { assignable: boolean; parent: number }>();
  const walk = (nodes: TenantRoleMenuNode[], parent: number) => {
    for (const node of nodes) {
      parentOf.set(node.id, { assignable: node.assignable, parent });
      walk(node.children, node.id);
    }
  };
  walk(menus, 0);
  const result = new Set<number>();
  for (const id of checkedIds) {
    let current: number | undefined = id;
    while (current !== undefined && current !== 0) {
      const info = parentOf.get(current);
      if (!info) break;
      if (info.assignable) result.add(current);
      current = info.parent;
    }
  }
  return [...result];
}

export interface MenuTreeDatum {
  assignable: boolean;
  children: MenuTreeDatum[];
  key: number;
  title: string;
}

/** 后端菜单树 → ant Tree 数据（title 由 {@link menuNodeTitle} 生成）。 */
export function menuTreeData(menus: TenantRoleMenuNode[]): MenuTreeDatum[] {
  return menus.map((node) => ({
    assignable: node.assignable,
    children: menuTreeData(node.children),
    key: node.id,
    title: menuNodeTitle(node),
  }));
}

export interface DeptTreeDatum {
  children: DeptTreeDatum[];
  title: string;
  value: number;
}

/** 组织树 → 自定义科室 TreeSelect 数据（value 为科室 id；院区只作分组节点，取负数避免与科室 id 冲突）。 */
export function deptTreeOptions(orgs: OrgCampusNode[]): DeptTreeDatum[] {
  const deptNode = (dept: DeptNodeView): DeptTreeDatum => ({
    children: dept.children.map((child) => deptNode(child)),
    title: dept.name,
    value: dept.id,
  });
  return orgs.map((campus) => ({
    children: campus.depts.map((dept) => deptNode(dept)),
    title: campus.name,
    value: -campus.id,
  }));
}

/** 模板 / 内置角色不允许在页面上直接改配置（PLT-ROLE-004、005：复制后调整）。 */
export function isCustomRole(role: TenantRoleView): boolean {
  return role.roleType === 'CUSTOM';
}

// ---------------------------------------------------------------- 字段策略（E07-T06 / PLT-PERM-009）

/** 字段策略草稿值：DEFAULT = 跟随平台默认（不落覆盖行），其余为显式覆盖。 */
export type FieldPolicyDraftValue = 'DEFAULT' | FieldPolicy;

export type FieldPolicyDraft = Record<string, FieldPolicyDraftValue>;

/** 目录项定位键（resource.field，与后端覆盖行同口径）。 */
export function fieldPolicyKey(resource: string, field: string): string {
  return `${resource}.${field}`;
}

/** 页签初始草稿：覆盖项取覆盖值，未覆盖项跟随平台默认。 */
export function fieldPolicyDraftOf(
  fields: TenantRoleFieldPolicyEntry[],
): FieldPolicyDraft {
  const draft: FieldPolicyDraft = {};
  for (const entry of fields) {
    draft[fieldPolicyKey(entry.resource, entry.field)] = entry.overridden
      ? entry.policy
      : 'DEFAULT';
  }
  return draft;
}

/** 保存载荷：只提交显式覆盖项（DEFAULT 不落行 = 回落平台默认，空数组即清空全部覆盖）。 */
export function fieldPolicySaveItems(draft: FieldPolicyDraft): Array<{
  field: string;
  policy: FieldPolicy;
  resource: string;
}> {
  return Object.entries(draft)
    .filter(([, value]) => value !== 'DEFAULT')
    .map(([key, policy]) => {
      const [resource, field] = key.split('.');
      return { field, policy: policy as FieldPolicy, resource };
    });
}

/** 行内下拉选项：跟随平台默认（带当前默认值说明）+ 三种策略。 */
export function fieldPolicyRowOptions(
  entry: TenantRoleFieldPolicyEntry,
): Array<{ label: string; value: FieldPolicyDraftValue }> {
  return [
    {
      label: `跟随平台默认（${tenantFieldPolicyText(entry.defaultPolicy)}）`,
      value: 'DEFAULT',
    },
    { label: tenantFieldPolicyText('VISIBLE'), value: 'VISIBLE' },
    { label: tenantFieldPolicyText('MASKED'), value: 'MASKED' },
    { label: tenantFieldPolicyText('HIDDEN'), value: 'HIDDEN' },
  ];
}

/** 是否有未保存的字段策略修改（保存按钮可用性提示）。 */
export function fieldPolicyDirty(
  fields: TenantRoleFieldPolicyEntry[],
  draft: FieldPolicyDraft,
): boolean {
  const initial = fieldPolicyDraftOf(fields);
  const keys = new Set([...Object.keys(initial), ...Object.keys(draft)]);
  for (const key of keys) {
    if (initial[key] !== draft[key]) return true;
  }
  return false;
}
