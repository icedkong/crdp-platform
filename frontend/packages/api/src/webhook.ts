/**
 * E09-T06 / A-NTF-008、C-NTF-008、PLT-CFG-007：租户 Webhook 的类型与文案
 * （接口契约 docs/api/notify.md §6，后端 `com.crdp.system.webhook` + `HospitalWebhookController`）。
 *
 * 签名密钥只在新建 / 更换时回显一次（secretShownOnce），前端提示立即配置到接收方；
 * 列表与查询永不返回。回调地址仅 HTTPS 且拒绝内网 / 回环地址（后端双重校验，
 * 前端 `isWebhookUrlLikelyValid` 只做即时提示，不是最终裁决）。
 */

/** 一条 Webhook 配置（GET 列表 / 保存响应里的 webhook 对象；不含签名密钥）。 */
export interface WebhookView {
  enabled: boolean;
  events: string[];
  id: number;
  lastDeliveryAt?: null | string;
  name: string;
  updatedAt?: null | string;
  url: string;
}

/** 保存（新建 / 修改）响应：换钥时 secretShownOnce 只出现这一次。 */
export interface WebhookSaveResult {
  secretShownOnce?: null | string;
  webhook: WebhookView;
}

/** 新建 / 修改请求；secret 为 null / 空白 = 保持不变（新建 = 系统生成）。 */
export interface WebhookSaveRequest {
  enabled?: boolean;
  events?: string[];
  name?: string;
  secret?: null | string;
  url?: string;
}

/** 可订阅事件目录（后端 GET /webhooks/events 下发；此为同口径静态目录）。 */
export interface WebhookEventOption {
  code: string;
  label: string;
}

/** 可订阅事件静态目录（A / C 差异由本院勾选体现——C 不勾「患者死亡」即可）。 */
export const WEBHOOK_EVENT_OPTIONS: WebhookEventOption[] = [
  { code: 'TASK_OPENED', label: '任务开启' },
  { code: 'TASK_OVERDUE', label: '已逾期' },
  { code: 'RECORD_SUBMITTED', label: '随访提交' },
  { code: 'PATIENT_DEAD', label: '患者死亡' },
];

/** 事件编码 → 中文（未知编码原样返回，配置页 Tag 展示）。 */
export function webhookEventLabel(code: string): string {
  return (
    WEBHOOK_EVENT_OPTIONS.find((option) => option.code === code)?.label ?? code
  );
}

/** 投递状态（sys_webhook_delivery.status）。 */
export type WebhookDeliveryStatus = 'FAILED' | 'PENDING' | 'SENT';

/** 一条投递记录（PLT-CFG-007 投递记录；payload 可按签名复核）。 */
export interface WebhookDeliveryView {
  attempt: number;
  createdAt: string;
  error?: null | string;
  eventType: string;
  id: number;
  nextAttemptAt?: null | string;
  objectId?: null | string;
  objectType?: null | string;
  payload: string;
  responseStatus?: null | number;
  sentAt?: null | string;
  status: WebhookDeliveryStatus;
  webhookId: number;
}

/** 投递记录分页（统一分页口径）。 */
export interface WebhookDeliveryPage {
  items: WebhookDeliveryView[];
  pageNo: number;
  pageSize: number;
  total: number;
}

/** 连通性测试结果（回显签名头值，可与接收方日志比对）。 */
export interface WebhookTestResult {
  error?: null | string;
  responseStatus?: null | number;
  signature: string;
  signatureAlgorithm: string;
  status: WebhookDeliveryStatus;
  timestamp: number;
}

/** 手动重投结果。 */
export interface WebhookRedeliverResult {
  delivery: WebhookDeliveryView;
  requeued: boolean;
}

/** 投递状态文案（状态列与筛选下拉）。 */
export function webhookDeliveryStatusText(
  status: WebhookDeliveryStatus,
): string {
  switch (status) {
    case 'FAILED': {
      return '失败';
    }
    case 'PENDING': {
      return '待投递';
    }
    case 'SENT': {
      return '已投递';
    }
    default: {
      return status;
    }
  }
}

/** 空白与控制字符（U+0000–U+0020、U+007F）：地址里出现即拒绝（防走私头）。 */
// eslint-disable-next-line no-control-regex -- 刻意检测控制字符（防 CRLF 走私头），与后端 WebhookSsrfGuard 同口径
const WEBHOOK_URL_FORBIDDEN = /[\u0000-\u0020\u007F]/;

/**
 * 回调地址即时校验（表单提示用；后端 WebhookSsrfGuard 是最终裁决）：
 * 仅 https、不含用户名密码、无空白与控制字符、长度 ≤ 500。
 */
export function isWebhookUrlLikelyValid(url: string): boolean {
  if (!url || url.length > 500) {
    return false;
  }
  if (WEBHOOK_URL_FORBIDDEN.test(url)) {
    return false;
  }
  if (url.includes('@')) {
    return false;
  }
  return /^https:\/\/[^\s/$.?#][^\s]*$/i.test(url);
}
