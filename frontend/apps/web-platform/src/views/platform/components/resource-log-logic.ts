/**
 * 资源变更记录抽屉的纯逻辑（E05-T10 / PLT-PERM-012）：筛选模型 ↔ 查询参数、
 * 操作人 / 时间 / 标签颜色展示口径。文案映射复用 @crdp/api 的 resourceChange*Text。
 */
import type {
  ResourceChangeAction,
  ResourceChangeChannel,
  ResourceChangeLogQuery,
  ResourceChangeType,
} from '@crdp/api';

/** 筛选模型：空串 = 不筛选（全部）。 */
export interface ResourceLogFilterModel {
  action: '' | ResourceChangeAction;
  channel: '' | ResourceChangeChannel;
  keyword: string;
  resourceType: '' | ResourceChangeType;
}

export function emptyFilters(
  resourceType: '' | ResourceChangeType = '',
): ResourceLogFilterModel {
  return { action: '', channel: '', keyword: '', resourceType };
}

/** 筛选模型 → 查询参数（空筛选取空；keyword 去首尾空白）。 */
export function toQuery(
  filters: ResourceLogFilterModel,
  pageNo: number,
  pageSize = 20,
): ResourceChangeLogQuery {
  const keyword = filters.keyword.trim();
  return {
    action: filters.action || undefined,
    channel: filters.channel || undefined,
    keyword: keyword === '' ? undefined : keyword,
    pageNo,
    pageSize,
    resourceType: filters.resourceType || undefined,
  };
}

/** 操作人展示：系统触发（启动扫描 / 清单同步无会话）显示「系统」。 */
export function operatorText(operatorName: null | string): string {
  return operatorName ?? '系统';
}

/** 时间展示：ISO-8601 → 「yyyy-MM-dd HH:mm:ss」。 */
export function timeText(createdAt: string): string {
  return createdAt.slice(0, 19).replace('T', ' ');
}

/** 动作标签颜色：新建绿、变更蓝、删除红、下线橙、登记青。 */
export function actionColor(action: string): string {
  switch (action) {
    case 'CREATE': {
      return 'success';
    }
    case 'DELETE': {
      return 'red';
    }
    case 'OFFLINE': {
      return 'warning';
    }
    case 'UPDATE': {
      return 'blue';
    }
    default: {
      return 'cyan';
    }
  }
}

/** 资源类型标签颜色。 */
export function typeColor(resourceType: string): string {
  switch (resourceType) {
    case 'API': {
      return 'purple';
    }
    case 'MENU': {
      return 'blue';
    }
    default: {
      return 'default';
    }
  }
}

export const RESOURCE_TYPE_OPTIONS = [
  { label: '菜单', value: 'MENU' },
  { label: '接口', value: 'API' },
  { label: '字段', value: 'FIELD' },
];

export const ACTION_OPTIONS = [
  { label: '新建', value: 'CREATE' },
  { label: '变更', value: 'UPDATE' },
  { label: '删除', value: 'DELETE' },
  { label: '下线', value: 'OFFLINE' },
  { label: '登记', value: 'REGISTER' },
];

export const CHANNEL_OPTIONS = [
  { label: '页面操作', value: 'PAGE' },
  { label: '同步 / 扫描', value: 'SYNC' },
];
