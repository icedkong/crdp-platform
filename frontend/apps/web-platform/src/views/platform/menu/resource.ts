/**
 * 菜单资源页的纯逻辑（E05-T06 / PLT-PERM-001..003、011）：新建 / 维护表单模型与校验、
 * 树展平、清单差异文案。校验口径与后端 MenuAdminService 一致（40001 的前端第一道提示）。
 */
import type {
  MenuCreateRequest,
  MenuResourceNode,
  MenuSyncDiffItem,
} from '@crdp/api';

import { menuSyncActionText, menuTypeText } from '@crdp/api';

/** 权限码格式 {模块}:{资源}:{动作}（创建后不可改名，OQ-11）。 */
export const PERM_CODE_PATTERN = /^[a-z][a-z0-9-]*(?::[a-z][a-z0-9-]*){2}$/;

/** 路由名大驼峰（Vben 路由 name，如 FollowupPatient）。 */
export const ROUTE_NAME_PATTERN = /^[A-Z][A-Za-z0-9]*$/;

export type MenuSide = 'HOSPITAL' | 'PLATFORM';

/** 端别切换选项（PLT-PERM-001：平台端、医院端两棵树）。 */
export const SIDE_OPTIONS: Array<{ label: string; value: MenuSide }> = [
  { label: '平台端', value: 'PLATFORM' },
  { label: '医院端', value: 'HOSPITAL' },
];

export function sideText(side: string): string {
  if (side === 'HOSPITAL') return '医院端';
  if (side === 'PLATFORM') return '平台端';
  return side;
}

/** 新建表单模型（对话框状态；parentId 为字符串便于空值表示顶级）。 */
export interface MenuEditModel {
  component: string;
  hidden: boolean;
  icon: string;
  moduleCode: string;
  parentOptions: MenuResourceNode[];
  parentId: string;
  path: string;
  permCode: string;
  routeName: string;
  /** 仅维护用：新建固定 ENABLED；停用后不进入授权范围与菜单下发。 */
  side: MenuSide;
  sortNo: number;
  status: 'DISABLED' | 'ENABLED';
  title: string;
  type: 'BUTTON' | 'DIR' | 'MENU';
}

export function newMenuModel(side: MenuSide, modules: string[]): MenuEditModel {
  return {
    component: '',
    hidden: false,
    icon: '',
    moduleCode: modules[0] ?? '',
    parentOptions: [],
    parentId: '0',
    path: '',
    permCode: '',
    routeName: '',
    side,
    sortNo: 0,
    status: 'ENABLED',
    title: '',
    type: 'MENU',
  };
}

/** 编辑已有项的表单模型（类型 / 模块 / 权限码不可改，界面上禁用）。 */
export function toEditModel(node: MenuResourceNode): MenuEditModel {
  return {
    component: node.component ?? '',
    hidden: node.hidden,
    icon: node.icon ?? '',
    moduleCode: node.moduleCode,
    parentOptions: [],
    parentId: String(node.parentId),
    path: node.path ?? '',
    permCode: node.permCode ?? '',
    routeName: node.routeName ?? '',
    side: node.side,
    sortNo: node.sortNo,
    status: node.status,
    title: node.title,
    type: node.type,
  };
}

export function toCreateRequest(model: MenuEditModel): MenuCreateRequest {
  const blankToNull = (value: string) =>
    value.trim() === '' ? null : value.trim();
  return {
    component: model.type === 'MENU' ? blankToNull(model.component) : null,
    hidden: model.hidden,
    icon: blankToNull(model.icon),
    moduleCode: model.moduleCode,
    parentId: model.parentId || '0',
    path: model.type === 'BUTTON' ? null : blankToNull(model.path),
    permCode: model.type === 'BUTTON' ? blankToNull(model.permCode) : null,
    routeName: model.type === 'BUTTON' ? null : blankToNull(model.routeName),
    side: model.side,
    sortNo: model.sortNo,
    title: model.title.trim(),
    type: model.type,
  };
}

/** 新建校验（PLT-PERM-001..003）：返回错误信息数组（空 = 通过）。 */
export function validateCreate(model: MenuEditModel): string[] {
  const errors: string[] = [];
  if (!model.title.trim()) {
    errors.push('标题必填');
  } else if (model.title.trim().length > 64) {
    errors.push('标题过长（最多 64 个字符）');
  }
  if (!model.moduleCode) {
    errors.push('所属模块必填（每个菜单与按钮必须归属一个模块）');
  }
  if (model.type === 'BUTTON') {
    if (!model.permCode.trim()) {
      errors.push('按钮必须绑定权限码');
    } else if (!PERM_CODE_PATTERN.test(model.permCode.trim())) {
      errors.push('权限码格式 {模块}:{资源}:{动作}，如 followup:task:view');
    }
  } else {
    if (!ROUTE_NAME_PATTERN.test(model.routeName.trim())) {
      errors.push('目录 / 菜单必填路由名（大驼峰，如 FollowupPatient）');
    }
    if (!model.path.trim().startsWith('/')) {
      errors.push('目录 / 菜单必填以 / 开头的路径');
    }
    if (model.type === 'MENU' && !model.component.trim()) {
      errors.push('菜单必填前端视图路径（如 followup/patient/index）');
    }
  }
  return errors;
}

/** 展平树（表格数据源与「新增子项」定位）。 */
export function flattenMenuTree(nodes: MenuResourceNode[]): MenuResourceNode[] {
  const flat: MenuResourceNode[] = [];
  const walk = (list: MenuResourceNode[]) => {
    for (const node of list) {
      flat.push(node);
      if (node.children.length > 0) walk(node.children);
    }
  };
  walk(nodes);
  return flat;
}

/** 指定类型可选上级：目录（顶级或目录下）、菜单（目录下）、按钮（菜单下）。 */
export function parentCandidates(
  nodes: MenuResourceNode[],
  type: MenuEditModel['type'],
): MenuResourceNode[] {
  return flattenMenuTree(nodes).filter((node) =>
    // 目录 / 菜单只能挂在目录下（或顶级），按钮只能挂在菜单下
    type === 'BUTTON' ? node.type === 'MENU' : node.type === 'DIR',
  );
}

/** 清单差异行文案（PLT-PERM-011）：「新增 · 医院端 · FollowupPatient：患者档案」。 */
export function diffLine(item: MenuSyncDiffItem): string {
  const title =
    (item.incoming?.title as string | undefined) ??
    (item.current?.title as string | undefined) ??
    '';
  return `${menuSyncActionText(item.action)} · ${sideText(item.side)} · ${item.key}${title ? `：${title}` : ''}`;
}

/** 差异分组计数摘要（确认弹窗顶部）。 */
export function diffSummary(items: MenuSyncDiffItem[]): string {
  const count = (action: string) =>
    items.filter((item) => item.action === action).length;
  const added = count('ADDED');
  const changed = count('CHANGED');
  const offline = count('OFFLINE');
  return `新增 ${added} 项、变更 ${changed} 项、下线 ${offline} 项`;
}

/** 应用结果文案。 */
export function applyResultText(result: {
  added: number;
  changed: number;
  offline: number;
}): string {
  return `已应用：新增 ${result.added} 项、变更 ${result.changed} 项、下线 ${result.offline} 项`;
}

/** 表格类型列文案。 */
export function typeText(type: string): string {
  return menuTypeText(type);
}
