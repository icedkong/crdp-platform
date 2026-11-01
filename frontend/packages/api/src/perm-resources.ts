/**
 * 菜单资源与接口资源（E05-T06 / PLT-PERM-001..004、011）：类型与文案。
 * 后端下发形状见 backend PlatformMenuController / PlatformApiResourceController。
 */

/** 管理树节点（含按钮与停用行；软删行不返回）。 */
export interface MenuResourceNode {
  children: MenuResourceNode[];
  component: null | string;
  hidden: boolean;
  icon: null | string;
  id: number;
  moduleCode: string;
  parentId: number;
  path: null | string;
  permCode: null | string;
  routeName: null | string;
  side: 'HOSPITAL' | 'PLATFORM';
  sortNo: number;
  source: 'CUSTOM' | 'MANIFEST';
  status: 'DISABLED' | 'ENABLED';
  title: string;
  type: 'BUTTON' | 'DIR' | 'MENU';
}

/** 新建菜单项（PLT-PERM-001..003）：moduleCode 必填；按钮必填 permCode；目录 / 菜单必填路由字段。 */
export interface MenuCreateRequest {
  component?: null | string;
  hidden?: boolean;
  icon?: null | string;
  moduleCode: string;
  parentId: string;
  path?: null | string;
  permCode?: null | string;
  routeName?: null | string;
  side: 'HOSPITAL' | 'PLATFORM';
  sortNo?: number;
  title: string;
  type: 'BUTTON' | 'DIR' | 'MENU';
}

/** 维护菜单项：标题、图标、排序、隐藏、路由字段、状态可改；权限码 / 端别 / 模块不可改（OQ-11）。 */
export interface MenuUpdateRequest {
  component?: null | string;
  hidden?: boolean;
  icon?: null | string;
  path?: null | string;
  routeName?: null | string;
  sortNo?: number;
  status: 'DISABLED' | 'ENABLED';
  title: string;
}

/** 菜单清单差异（PLT-PERM-011）：ADDED 新增 / CHANGED 变更（结构字段）/ OFFLINE 下线。 */
export interface MenuSyncDiffItem {
  action: 'ADDED' | 'CHANGED' | 'OFFLINE';
  current: null | Record<string, unknown>;
  incoming: null | Record<string, unknown>;
  key: string;
  moduleCode: string;
  side: 'HOSPITAL' | 'PLATFORM';
}

export interface MenuSyncDiffResult {
  empty: boolean;
  items: MenuSyncDiffItem[];
}

export interface MenuSyncApplyResult {
  added: number;
  changed: number;
  offline: number;
}

/** 接口资源（PLT-PERM-004）：启动扫描登记 + 平台端标记。 */
export interface ApiResourceItem {
  actionType: 'DOWNLOAD' | 'EXPORT' | 'QUERY' | 'WRITE';
  auditLevel: 'DATA_ACCESS' | 'NONE' | 'OPERATION';
  httpMethod: string;
  id: number;
  moduleCode: string;
  pathPattern: string;
  permCode: null | string;
  publicAccess: boolean;
  sensitiveLevel: 'NORMAL' | 'SENSITIVE';
  source: 'MANUAL' | 'SCAN' | 'SEED';
}

/** 标记接口属性（PLT-PERM-004）：公共接口、审计级别、敏感级别、动作类型；可给未加注解的接口补绑权限码。 */
export interface ApiMarkRequest {
  actionType?: ApiResourceItem['actionType'];
  auditLevel?: ApiResourceItem['auditLevel'];
  permCode?: null | string;
  publicAccess?: boolean;
  sensitiveLevel?: ApiResourceItem['sensitiveLevel'];
}

/** 接口资源登记同步结果（与启动同一套逻辑）。 */
export interface ApiSyncOutcome {
  action: 'REGISTERED' | 'REMOVED' | 'UNCHANGED' | 'UPDATED';
  httpMethod: string;
  pathPattern: string;
  permCode: null | string;
}

export interface ApiSyncResult {
  changed: boolean;
  outcomes: ApiSyncOutcome[];
}

/** 菜单类型文案（PLT-PERM-001）。 */
export const MENU_TYPE_TEXT: Record<string, string> = {
  DIR: '目录',
  MENU: '菜单',
  BUTTON: '按钮',
};

export function menuTypeText(type: string): string {
  return MENU_TYPE_TEXT[type] ?? type;
}

/** 菜单来源文案（PLT-PERM-011）：MANIFEST 随模块清单发版；CUSTOM 平台端新建。 */
export const MENU_SOURCE_TEXT: Record<string, string> = {
  MANIFEST: '模块清单',
  CUSTOM: '平台自建',
};

export function menuSourceText(source: string): string {
  return MENU_SOURCE_TEXT[source] ?? source;
}

/** 菜单清单差异动作文案（PLT-PERM-011）。 */
export const MENU_SYNC_ACTION_TEXT: Record<string, string> = {
  ADDED: '新增',
  CHANGED: '变更',
  OFFLINE: '下线',
};

export function menuSyncActionText(action: string): string {
  return MENU_SYNC_ACTION_TEXT[action] ?? action;
}

/** 接口动作类型（PLT-PERM-004 关键字段：查询 / 写入 / 导出，用于租户视角只读与导出控制）。 */
export const API_ACTION_TYPE_TEXT: Record<string, string> = {
  QUERY: '查询',
  WRITE: '写入',
  EXPORT: '导出',
  DOWNLOAD: '下载',
};

export function apiActionTypeText(type: string): string {
  return API_ACTION_TYPE_TEXT[type] ?? type;
}

/** 审计级别：不记录 / 记录操作 / 记录数据访问。 */
export const API_AUDIT_LEVEL_TEXT: Record<string, string> = {
  NONE: '不记录',
  OPERATION: '记录操作',
  DATA_ACCESS: '记录数据访问',
};

export function apiAuditLevelText(level: string): string {
  return API_AUDIT_LEVEL_TEXT[level] ?? level;
}

/** 敏感级别：普通 / 敏感（涉及患者身份信息）。 */
export const API_SENSITIVE_LEVEL_TEXT: Record<string, string> = {
  NORMAL: '普通',
  SENSITIVE: '敏感',
};

export function apiSensitiveLevelText(level: string): string {
  return API_SENSITIVE_LEVEL_TEXT[level] ?? level;
}

/** 接口资源登记来源文案。 */
export const API_SOURCE_TEXT: Record<string, string> = {
  SEED: '种子',
  SCAN: '启动扫描',
  MANUAL: '手工标记',
};

export function apiSourceText(source: string): string {
  return API_SOURCE_TEXT[source] ?? source;
}
