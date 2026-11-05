import type {
  WebhookDeliveryPage,
  WebhookEventOption,
  WebhookRedeliverResult,
  WebhookSaveRequest,
  WebhookSaveResult,
  WebhookTestResult,
  WebhookView,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * E09-T06 / A-NTF-008、C-NTF-008、PLT-CFG-007：机构设置 · Webhook 接口
 * （契约 docs/api/notify.md §6）。查看与维护都用 tenant:webhook:edit。
 * 回调地址仅 HTTPS 公网地址（内网 / 回环由后端拒绝 40001）。
 */

/** 本院 Webhook 清单（不含签名密钥）。 */
export function listWebhooks() {
  return requestClient.get<WebhookView[]>(apiPath('/system/webhooks'));
}

/** 新建 Webhook；secretShownOnly 只在本次响应回显一次。 */
export function createWebhook(data: WebhookSaveRequest) {
  return requestClient.post<WebhookSaveResult>(
    apiPath('/system/webhooks'),
    data,
  );
}

/** 修改 Webhook（secret 为 null / 空白 = 保持不变；换钥回显一次）。 */
export function updateWebhook(id: number, data: WebhookSaveRequest) {
  return requestClient.put<WebhookSaveResult>(
    apiPath(`/system/webhooks/${id}`),
    data,
  );
}

/** 删除 Webhook（队列与投递记录一并清除）。 */
export function deleteWebhook(id: number) {
  return requestClient.delete(apiPath(`/system/webhooks/${id}`));
}

/** 连通性测试：立即推送一条 WEBHOOK_TEST，响应回显签名头值。 */
export function testWebhook(id: number) {
  return requestClient.post<WebhookTestResult>(
    apiPath(`/system/webhooks/${id}/test`),
    undefined,
  );
}

/** 投递记录（按状态过滤）。 */
export function listWebhookDeliveries(
  id: number,
  params: { pageNo?: number; pageSize?: number; status?: string } = {},
) {
  return requestClient.get<WebhookDeliveryPage>(
    apiPath(`/system/webhooks/${id}/deliveries`),
    { params },
  );
}

/** 失败手动重投（FAILED → 重新排队，从第 1 次重计退避）。 */
export function redeliverWebhook(webhookId: number, deliveryId: number) {
  return requestClient.post<WebhookRedeliverResult>(
    apiPath(`/system/webhooks/${webhookId}/deliveries/${deliveryId}/redeliver`),
    undefined,
  );
}

/** 可订阅事件目录（配置页多选项）。 */
export function listWebhookEvents() {
  return requestClient.get<WebhookEventOption[]>(
    apiPath('/system/webhooks/events'),
  );
}
