/**
 * 平台角色页的纯逻辑（E06-T04 / PLT-ROLE-002、003）：授权树组装、表单模型与提交请求、
 * 校验与文案（文案统一在 @crdp/api，这里只做形状转换）。
 */
import type {
  PlatformGrantMenuNode,
  PlatformRoleSaveRequest,
  PlatformRoleView,
} from '@crdp/api';

import {
  platformRoleStatusText,
  platformRoleViewSummary,
  platformTenantScopeText,
  platformVisibleScopeText,
  validatePlatformRole,
} from '@crdp/api';

export {
  platformRoleStatusText,
  platformRoleViewSummary,
  platformTenantScopeText,
  platformVisibleScopeText,
};

export type { PlatformGrantMenuNode, PlatformRoleView };

/** 授权树节点（antd Tree treeData 形状）。 */
export interface GrantTreeNode {
  children: GrantTreeNode[];
  key: number;
  title: string;
}

/** 平铺授权菜单 → 树（parentId 组树；孤儿节点挂在根下，避免数据异常时整棵丢失）。 */
export function buildGrantTree(
  nodes: PlatformGrantMenuNode[],
): GrantTreeNode[] {
  const byParent = new Map<number, PlatformGrantMenuNode[]>();
  for (const node of nodes) {
    const list = byParent.get(node.parentId) ?? [];
    list.push(node);
    byParent.set(node.parentId, list);
  }
  const toTreeNode = (node: PlatformGrantMenuNode): GrantTreeNode => ({
    children: (byParent.get(node.id) ?? []).map((item) => toTreeNode(item)),
    key: node.id,
    title: node.type === 'BUTTON' ? `${node.title}（按钮）` : node.title,
  });
  const roots = byParent.get(0) ?? [];
  const rootLevel = roots.map((item) => toTreeNode(item));
  // parentId 不为 0 但父节点不在清单里的孤儿：也挂到根，保证授权不丢
  const knownIds = new Set(nodes.map((node) => node.id));
  const orphans = nodes.filter(
    (node) => node.parentId !== 0 && !knownIds.has(node.parentId),
  );
  const orphanKeys = new Set(orphans.map((node) => node.id));
  for (const orphan of orphans) {
    if (!rootLevel.some((item) => item.key === orphan.id)) {
      rootLevel.push(toTreeNode(orphan));
      orphanKeys.add(orphan.id);
    }
  }
  return rootLevel;
}

/**
 * 编辑表单模型（对话框状态）。viewSpecifiedTenantIds：租户范围为「指定租户」时的清单（E20-T01）；
 * viewConfigResources：代为配置资源范围（E20-T06 / PLT-VIEW-013，空 = 全部配置类资源，已确认）。
 */
export interface RoleEditModel {
  description: string;
  menuIds: number[];
  name: string;
  status: 'DISABLED' | 'ENABLED';
  viewCanConfigure: boolean;
  viewCanEditBusiness: boolean;
  viewCanEnter: boolean;
  viewCanResetPassword: boolean;
  viewConfigResources: string[];
  viewSpecifiedTenantIds: number[];
  viewTenantScope: 'ALL' | 'RESPONSIBLE' | 'SPECIFIED';
  viewVisibleScope: 'CONFIG_AND_BUSINESS' | 'CONFIG_ONLY';
}

export function toRoleEditModel(
  role: null | PlatformRoleView,
  menuIds: number[],
  specifiedTenantIds: number[] = [],
): RoleEditModel {
  if (!role) {
    return {
      description: '',
      menuIds: [],
      name: '',
      status: 'ENABLED',
      viewCanConfigure: false,
      viewCanEditBusiness: false,
      viewCanEnter: false,
      viewCanResetPassword: false,
      viewConfigResources: [],
      viewSpecifiedTenantIds: [],
      viewTenantScope: 'RESPONSIBLE',
      viewVisibleScope: 'CONFIG_ONLY',
    };
  }
  return {
    description: role.description ?? '',
    menuIds,
    name: role.name,
    status: role.status,
    viewCanConfigure: role.view.canConfigure,
    viewCanEditBusiness: role.view.canEditBusiness,
    viewCanEnter: role.view.canEnter,
    viewCanResetPassword: role.view.canResetPassword,
    viewConfigResources: role.view.configResources ?? [],
    viewSpecifiedTenantIds: specifiedTenantIds,
    viewTenantScope: role.view.tenantScope,
    viewVisibleScope: role.view.visibleScope,
  };
}

/** 表单 → 提交请求（新建与维护同一形状；SPECIFIED 须带非空清单，校验层拦）。 */
export function toRoleSaveRequest(
  model: RoleEditModel,
): PlatformRoleSaveRequest {
  return {
    description: model.description,
    menuIds: model.menuIds,
    name: model.name,
    status: model.status,
    viewCanConfigure: model.viewCanConfigure,
    viewCanEditBusiness: model.viewCanEditBusiness,
    viewCanEnter: model.viewCanEnter,
    viewCanResetPassword: model.viewCanResetPassword,
    // 空清单提交 null = 全部配置类资源（PLT-VIEW-013 已确认）
    viewConfigResources:
      model.viewCanConfigure && model.viewConfigResources.length > 0
        ? model.viewConfigResources
        : null,
    viewSpecifiedTenantIds:
      model.viewTenantScope === 'SPECIFIED' ? model.viewSpecifiedTenantIds : [],
    viewTenantScope: model.viewTenantScope,
    viewVisibleScope: model.viewVisibleScope,
  };
}

/** 保存校验（前端先行，与后端同口径；返回错误信息，空数组 = 通过）。 */
export function validateRoleModel(model: RoleEditModel): string[] {
  return validatePlatformRole(model);
}

/** 列表里视角权限列的一行摘要（不可进入时给灰色提示）。 */
export function roleViewSummaryText(role: PlatformRoleView): string {
  return platformRoleViewSummary(role.view);
}

/** 创建时间 / 更新时间列：未同步显示「—」，精确到分钟。 */
export function roleTimeText(value: null | string): string {
  if (!value) return '—';
  return value.slice(0, 16).replace('T', ' ');
}
