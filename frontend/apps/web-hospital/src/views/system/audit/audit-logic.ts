/**
 * E08-T04 医院端审计中心页的纯逻辑（页签 → 列、筛选默认值），供 index.vue 与单测共用；
 * 校验与状态文案在 `@crdp/api/audit`（与后端口径一致），这里只做视图组装。
 */
import type { HospitalAuditTab } from '@crdp/api';

/** 列表列（pages.md §10：时间 / 操作人 / 操作 / 详情；分页 20 条/页）。 */
export interface AuditColumnDef {
  customRender?: string;
  dataIndex: string;
  title: string;
  width?: number;
}

/** 各页签的表格列（data-access 页签多访问方式与记录数）。 */
export function columnsForTab(tab: HospitalAuditTab): AuditColumnDef[] {
  const base: AuditColumnDef[] = [
    { dataIndex: 'createdAt', title: '时间', width: 170 },
    {
      dataIndex: 'userName',
      title: '账号',
      width: 120,
    },
    { dataIndex: 'displayName', title: '姓名', width: 110 },
    { dataIndex: 'module', title: '模块', width: 90 },
    { dataIndex: 'action', title: '操作', width: 150 },
    { dataIndex: 'description', title: '详情' },
  ];
  if (tab === 'data-access') {
    base.splice(5, 0, {
      customRender: 'accessMethod',
      dataIndex: 'accessMethod',
      title: '访问方式',
      width: 100,
    });
    base.splice(6, 0, { dataIndex: 'recordCount', title: '记录数', width: 80 });
  }
  if (tab === 'login') {
    base.splice(5, 0, {
      dataIndex: 'loginMethod',
      title: '登录方式',
      width: 90,
    });
    base.splice(6, 0, { dataIndex: 'sessionId', title: '会话号', width: 140 });
  }
  base.push(
    {
      customRender: 'result',
      dataIndex: 'result',
      title: '结果',
      width: 90,
    },
    { dataIndex: 'ip', title: 'IP', width: 120 },
    {
      customRender: 'action-link',
      dataIndex: 'id',
      title: '操作列',
      width: 80,
    },
  );
  return base;
}

/** 结果筛选选项（SUCCESS / DENIED / FAIL）。 */
export const RESULT_OPTIONS = [
  { label: '成功', value: 'SUCCESS' },
  { label: '被拒绝', value: 'DENIED' },
  { label: '失败', value: 'FAIL' },
];

/** 表格行 key：页签 + 行 id（不同页签可能同 id）。 */
export function rowKey(tab: HospitalAuditTab, id: number): string {
  return `${tab}-${id}`;
}

/** 详情抽屉的标题。 */
export function detailTitle(logType: null | string, id: number): string {
  const labels: Record<string, string> = {
    DATA_ACCESS: '数据访问日志',
    LOGIN: '登录日志',
    OPERATION: '操作日志',
    PLATFORM_ACCESS: '平台访问日志',
  };
  const label = (logType && labels[logType]) || '操作日志';
  return `${label} #${id}`;
}
