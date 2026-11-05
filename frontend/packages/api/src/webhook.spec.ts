import { describe, expect, it } from 'vitest';

import {
  isWebhookUrlLikelyValid,
  WEBHOOK_EVENT_OPTIONS,
  webhookDeliveryStatusText,
  webhookEventLabel,
} from './webhook';

/** E09-T06 / A-NTF-008：Webhook 类型与文案（纯函数）。 */
describe('e09-t06 webhook 类型与文案', () => {
  it('可订阅事件目录含四个事件且带中文', () => {
    expect(WEBHOOK_EVENT_OPTIONS.map((option) => option.code)).toEqual([
      'TASK_OPENED',
      'TASK_OVERDUE',
      'RECORD_SUBMITTED',
      'PATIENT_DEAD',
    ]);
    expect(webhookEventLabel('PATIENT_DEAD')).toBe('患者死亡');
    expect(webhookEventLabel('RECORD_SUBMITTED')).toBe('随访提交');
  });

  it('未知事件编码原样展示（目录升级前的旧配置不显示成空白）', () => {
    expect(webhookEventLabel('FUTURE_EVENT')).toBe('FUTURE_EVENT');
  });

  it('投递状态文案齐全', () => {
    expect(webhookDeliveryStatusText('PENDING')).toBe('待投递');
    expect(webhookDeliveryStatusText('SENT')).toBe('已投递');
    expect(webhookDeliveryStatusText('FAILED')).toBe('失败');
  });

  it('回调地址即时校验：仅 https、无凭据、无空白', () => {
    expect(isWebhookUrlLikelyValid('https://hooks.example.com/cb')).toBe(true);
    expect(isWebhookUrlLikelyValid('HTTPS://hooks.example.com/cb')).toBe(true);
    expect(isWebhookUrlLikelyValid('http://hooks.example.com/cb')).toBe(
      false,
      '明文 http',
    );
    expect(
      isWebhookUrlLikelyValid('https://user:pass@hooks.example.com/cb'),
    ).toBe(false, '不允许携带用户名密码');
    expect(isWebhookUrlLikelyValid('https://hooks.example.com/cb x')).toBe(
      false,
      '无空白',
    );
    expect(isWebhookUrlLikelyValid('')).toBe(false);
    expect(
      isWebhookUrlLikelyValid(`https://hooks.example.com/${'a'.repeat(500)}`),
    ).toBe(false, '超长');
  });
});
