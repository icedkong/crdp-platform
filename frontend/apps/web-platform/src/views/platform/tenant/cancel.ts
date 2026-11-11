/**
 * 注销与数据处理页纯逻辑（E05-T08 / PLT-TEN-018..021）：阶段文案与步骤、各动作的可用性
 * （前端显隐只作引导，最终校验在后端）、表单校验、保留清单编辑 ⇄ 提交载荷。页面只做展示与编排。
 */
import type {
  TenantCancelFlowView,
  TenantCancelStage,
  TenantExportView,
  TenantRetentionSaveItem,
} from '@crdp/api';

/** 流程阶段中文文案。 */
export const CANCEL_STAGE_TEXT: Record<TenantCancelStage, string> = {
  ACKED: '已签收（待封存与销毁）',
  COOLING: '注销中 · 冷静期',
  DESTROYED: '已销毁 · 已注销',
  EXPORTED: '迁出包已生成（待签收）',
  PENDING_APPROVAL: '已申请（待审批）',
  REJECTED: '申请被驳回',
  REVOKED: '已撤销（回到停用）',
};

/** 迁出包限时下载文案（规格已确认：7 天 / 5 次）。 */
export const EXPORT_LINK_HINT =
  '下载链接 7 天内有效、最多下载 5 次；过期或用完可重新生成';

/** 保留项状态文案。 */
export const RETENTION_STATUS_TEXT: Record<string, string> = {
  DESTROYED: '已销毁',
  REGISTERED: '已登记（待封存）',
  SEALED: '已封存',
};

/** 步骤条（与规格注销流程 8 步对应；展示层合并证明入销毁步）。 */
export const CANCEL_STEPS = [
  { key: 'APPLY', title: '发起申请' },
  { key: 'APPROVE', title: '审批' },
  { key: 'COOLING', title: '冷静期' },
  { key: 'EXPORT', title: '迁出包' },
  { key: 'ACK', title: '医院签收' },
  { key: 'SEAL', title: '封存保留数据' },
  { key: 'DESTROY', title: '销毁与证明' },
] as const;

/** 步骤条的当前下标（无流程 = 0；终态停在最后一步）。 */
export function stepIndex(stage: null | TenantCancelStage): number {
  switch (stage) {
    case 'ACKED': {
      return 5;
    }
    case 'COOLING': {
      return 2;
    }
    case 'DESTROYED': {
      return 6;
    }
    case 'EXPORTED': {
      return 4;
    }
    case null:
    case 'PENDING_APPROVAL': {
      return 0;
    }
    case 'REJECTED':
    case 'REVOKED': {
      return 1;
    }
    default: {
      return 0;
    }
  }
}

/** 冷静期剩余天数（向上取整；已满返回 0；未登记返回 null）。 */
export function coolingRemainingDays(
  coolingUntil: null | string,
  now = new Date(),
): null | number {
  if (!coolingUntil) {
    return null;
  }
  const until = new Date(coolingUntil);
  const diff = until.getTime() - now.getTime();
  return diff <= 0 ? 0 : Math.ceil(diff / 86_400_000);
}

/** 冷静期是否已满（可生成迁出包）。未登记视为未满（审批时才写入）。 */
export function coolingElapsed(
  flow: TenantCancelFlowView,
  now = new Date(),
): boolean {
  const remaining = coolingRemainingDays(flow.coolingUntil, now);
  return remaining === 0;
}

/** 发起申请：无流程或被驳回 / 撤销后可（重新）发起；租户必须是停用状态。 */
export function canApply(flow: TenantCancelFlowView): boolean {
  const restartable =
    flow.stage === null ||
    flow.stage === 'REJECTED' ||
    flow.stage === 'REVOKED';
  return restartable && flow.tenantStatus === 'DISABLED';
}

/** 审批（须另一名平台管理员，前端只引导，后端校验发起人不能自审）。 */
export function canApprove(flow: TenantCancelFlowView): boolean {
  return flow.stage === 'PENDING_APPROVAL';
}

/** 冷静期内撤销；已满则只能继续。 */
export function canRevoke(
  flow: TenantCancelFlowView,
  now = new Date(),
): boolean {
  return flow.stage === 'COOLING' && !coolingElapsed(flow, now);
}

/** 生成迁出包：冷静期满后首次生成，或已生成后重新生成（过期 / 次数用完）。 */
export function canGenerateExport(
  flow: TenantCancelFlowView,
  now = new Date(),
): boolean {
  if (flow.stage === 'EXPORTED') {
    return true;
  }
  return flow.stage === 'COOLING' && coolingElapsed(flow, now);
}

/** 签收（平台账号代录医院确认）。 */
export function canAck(flow: TenantCancelFlowView): boolean {
  return flow.stage === 'EXPORTED';
}

/** 封存：迁出包生成之后、销毁之前，且有未封存项。 */
export function canSeal(
  flow: TenantCancelFlowView,
  registeredCount: number,
): boolean {
  const inWindow = flow.stage === 'EXPORTED' || flow.stage === 'ACKED';
  return inWindow && registeredCount > 0;
}

/** 执行销毁：须已签收（未封存项由后端 40901 阻断，前端提示先封存）。 */
export function canDestroy(flow: TenantCancelFlowView): boolean {
  return flow.stage === 'ACKED';
}

/** 保留期满销毁：已封存且期满日已到。 */
export function retentionDue(
  item: { retainUntil: string; status: string },
  today: string,
): boolean {
  return item.status === 'SEALED' && item.retainUntil <= today;
}

// ---------------------------------------------------------------- 申请与保留清单表单

/** 保留清单编辑行（输入态）。 */
export interface RetentionFormItem {
  legalBasis: string;
  retainUntil: string;
  scope: string;
  tablesCsv: string;
}

export const EMPTY_RETENTION_ITEM: RetentionFormItem = {
  legalBasis: '',
  retainUntil: '',
  scope: '',
  tablesCsv: '',
};

export interface ApplyFormModel {
  confirmationFileName: string;
  confirmationFileId: null | number;
  reason: string;
}

export const EMPTY_APPLY_FORM: ApplyFormModel = {
  confirmationFileId: null,
  confirmationFileName: '',
  reason: '',
};

/** 申请表单校验（前端只挡明显错误，最终以后端 40001 为准）。 */
export function validateApplyForm(
  form: ApplyFormModel,
): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!form.reason.trim()) {
    errors.reason = '必须填写注销原因';
  } else if (form.reason.trim().length > 500) {
    errors.reason = '注销原因最多 500 字';
  }
  if (form.confirmationFileId === null) {
    errors.confirmation = '必须上传医院书面确认（PDF / JPG / PNG）';
  }
  return errors;
}

/** 保留项校验：范围、依据、期满日（今天或未来）。 */
export function validateRetentionItems(
  items: RetentionFormItem[],
  today: string,
): string[] {
  return items.map((item) => {
    if (!item.scope.trim() || !item.legalBasis.trim()) {
      return '每条保留项须填写范围与法律或合同依据';
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(item.retainUntil)) {
      return '保留期满日格式应为 YYYY-MM-DD';
    }
    if (item.retainUntil < today) {
      return '保留期满日不能早于今天';
    }
    if (
      item.tablesCsv.trim() &&
      !/^[A-Za-z0-9_,]+$/.test(item.tablesCsv.trim())
    ) {
      return '封存表名只允许字母数字下划线（逗号分隔）';
    }
    return '';
  });
}

/** 编辑行 → 保存载荷（tablesCsv 空串省略）。 */
export function toRetentionSaveItems(
  items: RetentionFormItem[],
): TenantRetentionSaveItem[] {
  return items.map((item) => ({
    legalBasis: item.legalBasis.trim(),
    retainUntil: item.retainUntil,
    scope: item.scope.trim(),
    ...(item.tablesCsv.trim() ? { tablesCsv: item.tablesCsv.trim() } : {}),
  }));
}

// ---------------------------------------------------------------- 展示

/** 字节数文案（迁出包与附件）。 */
export function sizeText(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

/** 迁出包行 → 描述列表行。 */
export function exportSummaryRows(item: TenantExportView): string[][] {
  return [
    ['包号', item.packageNo],
    [
      '内容',
      `${item.tableCount} 张表 · ${item.totalRows} 行 · 原始文件 ${item.fileCount} 个`,
    ],
    [
      '大小 / 校验',
      `${sizeText(item.sizeBytes)} · SHA-256 ${item.sha256.slice(0, 12)}…`,
    ],
    ['生成时间', item.generatedAt],
    [
      '下载',
      `${item.downloadCount}/${item.maxDownloads} 次 · ${item.expiresAt} 前有效`,
    ],
    [
      '签收',
      item.ackedAt ? `${item.ackedAt}（${item.ackNote ?? ''}）` : '未签收',
    ],
  ];
}
