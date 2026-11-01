/**
 * E08-T04 平台端审计中心页的纯逻辑（页签 → 列、验链状态文案），供 index.vue 与单测共用；
 * 校验与导出状态文案在 `@crdp/api/audit`（与后端口径一致）。
 */
import type { PlatformAuditTab } from '@crdp/api';

export interface AuditColumnDef {
  customRender?: string;
  dataIndex: string;
  title: string;
  width?: number;
}

/** 各页签的表格列：平台端多租户列；租户视角记录带视角会话号与页面。 */
export function platformColumnsForTab(tab: PlatformAuditTab): AuditColumnDef[] {
  const base: AuditColumnDef[] = [
    { dataIndex: 'createdAt', title: '时间', width: 170 },
    {
      customRender: 'tenant',
      dataIndex: 'tenantId',
      title: '租户ID',
      width: 90,
    },
    { dataIndex: 'userName', title: '账号', width: 130 },
    { dataIndex: 'displayName', title: '姓名', width: 120 },
    { dataIndex: 'module', title: '模块', width: 100 },
    { dataIndex: 'action', title: '操作', width: 150 },
    { dataIndex: 'description', title: '详情' },
  ];
  if (tab === 'tenant-view' || tab === 'view-rule') {
    base.splice(6, 0, {
      dataIndex: 'viewSessionId',
      title: '视角会话号',
      width: 150,
    });
  }
  base.push(
    {
      customRender: 'result',
      dataIndex: 'result',
      title: '结果',
      width: 90,
    },
    { dataIndex: 'ip', title: 'IP', width: 130 },
    {
      customRender: 'action-link',
      dataIndex: 'id',
      title: '操作列',
      width: 80,
    },
  );
  return base;
}

/** 验链结果文案（PLT-AUD-009：PASS / BROKEN）。 */
export function chainStatusText(status: null | string): string {
  const labels: Record<string, string> = { BROKEN: '断链', PASS: '完整' };
  return (status && labels[status]) || '—';
}

/** 验链结果标签颜色。 */
export function chainStatusColor(status: null | string): string {
  return status === 'PASS' ? 'success' : 'error';
}

/** 审批提示：平台账号导出一律待审批，申请人不能自审（PLT-AUD-007 已确认）。 */
export const PLATFORM_EXPORT_HINT =
  '平台账号导出需另一名平台管理员审批（申请人不能自审）；文件加水印，24 小时内有效，导出全程记审计。';
