import type { WebhookView } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  emptyWebhookModel,
  lastDeliveryText,
  toWebhookModel,
  toWebhookSaveRequest,
  validateWebhookModel,
} from './webhooks';

const existing: WebhookView = {
  enabled: false,
  events: ['TASK_OPENED', 'PATIENT_DEAD'],
  id: 7,
  lastDeliveryAt: '2027-04-12T10:30:00+08:00',
  name: '随访集成（示例）',
  updatedAt: '2027-04-11T09:00:00+08:00',
  url: 'https://hooks.example.com/cb',
};

/** E09-T06 / A-NTF-008：机构设置「Webhook」表单逻辑。 */
describe('e09-t06 机构设置 webhook 逻辑', () => {
  it('编辑模型不带回显密钥（secret 只写不读）', () => {
    const model = toWebhookModel(existing);
    expect(model.secret).toBe('');
    expect(model.events).toEqual(['TASK_OPENED', 'PATIENT_DEAD']);
    expect(model.enabled).toBe(false);
  });

  it('校验：名称必填、回调地址仅 HTTPS、至少一个订阅事件', () => {
    const model = emptyWebhookModel();
    model.name = '  ';
    model.url = 'http://insecure.example.com/cb';
    model.events = [];
    expect(validateWebhookModel(model)).toEqual({
      events: '至少订阅一个事件',
      name: '名称不能为空',
      url: '回调地址必须是 HTTPS 地址，且不能携带用户名密码或内网地址',
    });

    model.name = '随访集成（示例）';
    model.url = 'https://hooks.example.com/cb';
    model.events = ['TASK_OVERDUE'];
    expect(validateWebhookModel(model)).toEqual({});
  });

  it('校验：明文 http、携带凭据、超长名称 / 密钥都拒绝', () => {
    const model = emptyWebhookModel();
    model.name = '随访集成（示例）';
    model.url = 'https://user:pass@hooks.example.com/cb';
    expect(validateWebhookModel(model).url).toBeTruthy();

    model.url = 'https://hooks.example.com/cb';
    model.name = '超'.repeat(101);
    expect(validateWebhookModel(model).name).toBeTruthy();

    model.name = '随访集成（示例）';
    model.secret = 'k'.repeat(129);
    expect(validateWebhookModel(model).secret).toBeTruthy();
  });

  it('请求体：secret 留空不传（保持 / 生成由后端口径决定）', () => {
    const model = toWebhookModel(existing);
    const body = toWebhookSaveRequest(model);
    expect(body).not.toHaveProperty('secret');
    expect(body.events).toEqual(['TASK_OPENED', 'PATIENT_DEAD']);

    model.secret = '  new-secret-32-bytes-example-key  ';
    expect(toWebhookSaveRequest(model).secret).toBe(
      'new-secret-32-bytes-example-key',
    );
  });

  it('最近投递时间为空时显示「未投递过」', () => {
    expect(lastDeliveryText(null)).toBe('未投递过');
    expect(lastDeliveryText(undefined)).toBe('未投递过');
    expect(lastDeliveryText('2027-04-12T10:30:00+08:00')).toBe(
      '2027-04-12T10:30:00+08:00',
    );
  });
});
