import type { WebhookView } from '@crdp/api';

import { isWebhookUrlLikelyValid } from '@crdp/api';

/**
 * E09-T06 / A-NTF-008 · 机构设置「Webhook」逻辑（纯函数，与后端 WebhookAdminService 同口径）。
 * 回调地址仅 HTTPS 公网（内网 / 回环由后端拒绝，前端只做即时提示）；
 * 订阅事件非空且只认目录内编码（A / C 差异由本院勾选体现——C 不勾「患者死亡」）。
 */

export interface WebhookEditModel {
  enabled: boolean;
  events: string[];
  name: string;
  /** 编辑时留空 = 保持现有密钥；新建时留空 = 系统生成。 */
  secret: string;
  url: string;
}

export const WEBHOOK_NAME_MAX = 100;
export const WEBHOOK_URL_MAX = 500;
export const WEBHOOK_SECRET_MAX = 128;

export function emptyWebhookModel(): WebhookEditModel {
  return {
    enabled: true,
    events: ['TASK_OPENED'],
    name: '',
    secret: '',
    url: '',
  };
}

export function toWebhookModel(row: WebhookView): WebhookEditModel {
  return {
    enabled: row.enabled,
    events: [...row.events],
    name: row.name,
    secret: '',
    url: row.url,
  };
}

export interface WebhookValidationErrors {
  [field: string]: string;
}

/** 保存前校验（与后端 40001 口径一致；密钥只在填写时校验长度与空白）。 */
export function validateWebhookModel(
  model: WebhookEditModel,
): WebhookValidationErrors {
  const errors: WebhookValidationErrors = {};
  if (!model.name.trim()) {
    errors.name = '名称不能为空';
  } else if (model.name.trim().length > WEBHOOK_NAME_MAX) {
    errors.name = `不超过 ${WEBHOOK_NAME_MAX} 个字符`;
  }
  if (!model.url.trim()) {
    errors.url = '回调地址不能为空';
  } else if (model.url.trim().length > WEBHOOK_URL_MAX) {
    errors.url = `不超过 ${WEBHOOK_URL_MAX} 个字符`;
  } else if (!isWebhookUrlLikelyValid(model.url.trim())) {
    errors.url = '回调地址必须是 HTTPS 地址，且不能携带用户名密码或内网地址';
  }
  if (model.events.length === 0) {
    errors.events = '至少订阅一个事件';
  }
  if (model.secret.trim() && model.secret.trim().length > WEBHOOK_SECRET_MAX) {
    errors.secret = `不超过 ${WEBHOOK_SECRET_MAX} 个字符`;
  }
  return errors;
}

/** 新建 / 保存请求体（secret 为空 = 不传，后端口径：null 保持 / 生成）。 */
export function toWebhookSaveRequest(model: WebhookEditModel) {
  const secret = model.secret.trim();
  return {
    enabled: model.enabled,
    events: [...model.events],
    name: model.name.trim(),
    ...(secret === '' ? {} : { secret }),
    url: model.url.trim(),
  };
}

/** 最近投递时间展示（空 → 「未投递过」）。 */
export function lastDeliveryText(value: null | string | undefined): string {
  return value ?? '未投递过';
}
