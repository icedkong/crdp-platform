/**
 * E08-T04 / PLT-AUD-005、006、007 审计中心类型与纯逻辑（医院端 + 平台端共用），与后端
 * `com.crdp.server.system.audit.AuditViews` 同构（docs/api/audit.md §2、§3）。
 * 查询条件与校验口径（时间跨度 ≤ 3 个月、pageNo 从 1 起、pageSize 1–100）与
 * `AuditQuerySupport` 一致；导出审批状态机见 `jobExportStatusText`。
 */

/** 核心日志类型（接口 type 路径段的映射由各端页面完成）。 */
export type AuditLogKind =
  | 'DATA_ACCESS'
  | 'LOGIN'
  | 'OPERATION'
  | 'PLATFORM_ACCESS';

/** 医院端页签（audit.md §2）：不含平台访问记录（PLT-AUD-004 仅存平台侧）。 */
export type HospitalAuditTab = 'data-access' | 'login' | 'operation';

/** 平台端页签（audit.md §3）。 */
export type PlatformAuditTab =
  | 'control-plane'
  | 'login'
  | 'operation'
  | 'tenant-view'
  | 'view-rule';

/** 查询条件（PLT-AUD-005：时间、用户、类型、模块、对象、结果、IP、traceId 组合；平台端含租户）。 */
export interface AuditSearchRequest {
  action?: string;
  endTime: string;
  ip?: string;
  module?: string;
  objectId?: string;
  objectType?: string;
  pageNo?: number;
  pageSize?: number;
  result?: string;
  startTime: string;
  tenantId?: number;
  traceId?: string;
  userId?: number;
  userName?: string;
}

/** 列表行（各类型公共展示字段）。 */
export interface AuditRowView {
  accessMethod: null | string;
  action: string;
  createdAt: string;
  description: null | string;
  displayName: null | string;
  errorCode: null | number;
  id: number;
  ip: null | string;
  loginMethod: null | string;
  logType: string;
  module: null | string;
  objectId: null | string;
  objectType: null | string;
  reason: null | string;
  recordCount: null | number;
  result: null | string;
  sessionId: null | string;
  tenantId: null | number;
  traceId: null | string;
  userName: null | string;
  userType: null | string;
}

export interface AuditPage {
  items: AuditRowView[];
  pageNo: number;
  pageSize: number;
  total: number;
}

/** 同一 traceId 的其他日志（详情「关联链路」）。 */
export interface AuditTraceLink {
  action: null | string;
  createdAt: null | string;
  description: null | string;
  id: number;
  logType: string;
}

/** 详情（PLT-AUD-006）：前后值对比（敏感值为摘要）+ 哈希链值。 */
export interface AuditDetailView {
  afterJson: null | string;
  beforeJson: null | string;
  detailText: null | string;
  hash: null | string;
  prevHash: null | string;
  roleCodes: null | string;
  row: AuditRowView;
  traceLinks: AuditTraceLink[];
  userAgent: null | string;
  viewSessionId: null | string;
}

/** 导出作业（我的导出 / 待审批列表共用）。 */
export interface AuditExportJobView {
  applicantDisplayName: null | string;
  applicantId: number;
  applicantName: string;
  approveComment: null | string;
  approverName: null | string;
  bizType: string;
  conditionSummary: null | string;
  createdAt: string;
  expiresAt: null | string;
  exportType: string;
  failReason: null | string;
  fileHash: null | string;
  fileName: null | string;
  id: number;
  reason: null | string;
  rowCount: null | number;
  status: AuditExportStatus;
}

export type AuditExportStatus =
  | 'FAILED'
  | 'GENERATING'
  | 'PENDING_APPROVAL'
  | 'READY'
  | 'REJECTED';

export interface AuditExportJobPage {
  items: AuditExportJobView[];
  pageNo: number;
  pageSize: number;
  total: number;
}

export interface AuditExportRequest {
  condition: AuditSearchRequest;
  reason?: string;
  type: string;
}

export interface AuditExportSubmitted {
  exportId: number;
  status: AuditExportStatus;
}

/** 最近一次哈希链校验结果（平台端 integrity，PLT-AUD-009）。 */
export interface AuditChainCheckView {
  breakCount: number;
  checkedAt: string;
  detail: null | string;
  firstBreakId: null | number;
  firstBreakKind: null | string;
  hashedRows: number;
  status: string;
  tableName: string;
}

/** 医院端页签 → 类型文案（pages.md §10：操作 / 登录 / 数据访问日志）。 */
export const HOSPITAL_AUDIT_TABS: { key: HospitalAuditTab; label: string }[] = [
  { key: 'operation', label: '操作日志' },
  { key: 'login', label: '登录日志' },
  { key: 'data-access', label: '数据访问日志' },
];

/** 平台端页签（pages.md §11：平台操作、平台登录、租户视角、视角规则变更、租户控制面）。 */
export const PLATFORM_AUDIT_TABS: { key: PlatformAuditTab; label: string }[] = [
  { key: 'operation', label: '平台操作日志' },
  { key: 'login', label: '平台登录日志' },
  { key: 'tenant-view', label: '租户视角记录' },
  { key: 'view-rule', label: '视角规则变更' },
  { key: 'control-plane', label: '租户控制面日志' },
];

/** 结果文案（SUCCESS / DENIED / FAIL，写入口径见 audit.md §1）。 */
export function auditResultText(result: null | string): string {
  switch (result) {
    case 'DENIED': {
      return '被拒绝';
    }
    case 'FAIL': {
      return '失败';
    }
    case 'SUCCESS': {
      return '成功';
    }
    default: {
      return '—';
    }
  }
}

/** 结果标签颜色（StatusTag 语义：成功绿 / 被拒橙 / 失败红）。 */
export function auditResultColor(result: null | string): string {
  switch (result) {
    case 'DENIED': {
      return 'warning';
    }
    case 'FAIL': {
      return 'error';
    }
    case 'SUCCESS': {
      return 'success';
    }
    default: {
      return 'default';
    }
  }
}

/** 数据访问方式文案（VIEW_PLAIN / EXPORT / PRINT / DOWNLOAD / BATCH_QUERY）。 */
export function accessMethodText(method: null | string): string {
  switch (method) {
    case 'BATCH_QUERY': {
      return '批量查询';
    }
    case 'DOWNLOAD': {
      return '下载';
    }
    case 'EXPORT': {
      return '导出';
    }
    case 'PRINT': {
      return '打印';
    }
    case 'VIEW_PLAIN': {
      return '查看明文';
    }
    default: {
      return method ?? '—';
    }
  }
}

/** 核心类型文案（详情与关联链路展示用）。 */
export function auditKindText(kind: null | string): string {
  switch (kind) {
    case 'DATA_ACCESS': {
      return '数据访问日志';
    }
    case 'LOGIN': {
      return '登录日志';
    }
    case 'OPERATION': {
      return '操作日志';
    }
    case 'PLATFORM_ACCESS': {
      return '平台访问日志';
    }
    default: {
      return kind ?? '—';
    }
  }
}

/** 导出作业状态文案（PLT-AUD-007 状态机）。 */
export function jobExportStatusText(status: null | string): string {
  switch (status) {
    case 'FAILED': {
      return '生成失败';
    }
    case 'GENERATING': {
      return '生成中';
    }
    case 'PENDING_APPROVAL': {
      return '待审批';
    }
    case 'READY': {
      return '可下载';
    }
    case 'REJECTED': {
      return '已驳回';
    }
    default: {
      return status ?? '—';
    }
  }
}

/** 导出作业状态标签颜色。 */
export function jobExportStatusColor(status: null | string): string {
  switch (status) {
    case 'GENERATING': {
      return 'processing';
    }
    case 'PENDING_APPROVAL': {
      return 'warning';
    }
    case 'READY': {
      return 'success';
    }
    default: {
      return 'error';
    }
  }
}

/** 单次查询时间跨度上限（platform-spec §9：不超过 3 个月，按 92 天与后端一致）。 */
export const AUDIT_MAX_SPAN_DAYS = 92;

/**
 * 校验时间窗（前端预检，后端同一口径 40001）：
 * 起止必填、end ≥ start、跨度 ≤ 3 个月。返回错误文案，null 表示通过。
 */
export function validateAuditTimeRange(
  start: null | string,
  end: null | string,
): null | string {
  if (!start || !end) return '请选择开始与结束时间';
  const startTime = new Date(start).getTime();
  const endTime = new Date(end).getTime();
  if (Number.isNaN(startTime) || Number.isNaN(endTime)) return '时间格式不正确';
  if (endTime < startTime) return '结束时间不能早于开始时间';
  if (startTime + AUDIT_MAX_SPAN_DAYS * 24 * 3600 * 1000 < endTime) {
    return '单次查询时间跨度不能超过 3 个月，请缩小范围分批查询';
  }
  return null;
}

/** 组装查询条件：去掉空串 / undefined 字段（后端按未填处理）。 */
export function buildAuditSearchCondition(
  filter: {
    action?: string;
    endTime?: string;
    ip?: string;
    module?: string;
    objectId?: string;
    objectType?: string;
    result?: string;
    startTime?: string;
    tenantId?: number;
    traceId?: string;
    userName?: string;
  },
  pageNo = 1,
  pageSize = 20,
): AuditSearchRequest {
  const condition: AuditSearchRequest = {
    endTime: (filter.endTime ?? '').trim(),
    pageNo,
    pageSize,
    startTime: (filter.startTime ?? '').trim(),
  };
  const optional: Record<string, unknown> = {
    action: filter.action,
    ip: filter.ip,
    module: filter.module,
    objectId: filter.objectId,
    objectType: filter.objectType,
    result: filter.result,
    tenantId: filter.tenantId,
    traceId: filter.traceId,
    userName: filter.userName,
  };
  for (const [key, value] of Object.entries(optional)) {
    if (value === undefined || value === null || value === '') continue;
    (condition as Record<string, unknown>)[key] =
      typeof value === 'string' ? value.trim() : value;
  }
  return condition;
}

/** 导出下载提示文案（PLT-AUD-007：24 小时限时下载）。 */
export function exportDownloadHint(job: AuditExportJobView): null | string {
  switch (job.status) {
    case 'FAILED': {
      return `生成失败${job.failReason ? `：${job.failReason}` : ''}`;
    }
    case 'GENERATING': {
      return '文件生成中，请稍后刷新';
    }
    case 'PENDING_APPROVAL': {
      return '待审批通过后可下载';
    }
    case 'READY': {
      return job.expiresAt
        ? `请在 ${job.expiresAt.slice(0, 16).replace('T', ' ')} 前下载`
        : '可下载';
    }
    case 'REJECTED': {
      return `已驳回${job.approveComment ? `：${job.approveComment}` : ''}`;
    }
    default: {
      return null;
    }
  }
}
