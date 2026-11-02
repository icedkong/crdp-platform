import type { NoticeMessage, NoticeTarget, NotifyRule } from './notify';

import { describe, expect, it } from 'vitest';

import {
  noticeTargetRoute,
  NOTIFY_CHANNEL_LABELS,
  NOTIFY_RECIPIENT_LABELS,
  NOTIFY_SEND_STATUS_LABELS,
  toNotifyRuleFormModel,
  toNotifyRuleUpdate,
} from './notify';

/** E09-T02 / A-NTF-002、C-NTF-002：站内信 target → 跳转路由（验收：点击消息跳到对应任务）。 */
describe('e09-T02 站内信跳转路由', () => {
  it('任务（TASK）落点进我的任务并携带 taskId', () => {
    const target: NoticeTarget = { id: 4242, type: 'TASK' };
    expect(noticeTargetRoute(target)).toEqual({
      path: '/followup/task',
      query: { taskId: '4242' },
    });
  });

  it('pATIENT 落点进患者档案并携带 patientId', () => {
    expect(noticeTargetRoute({ id: 990_001, type: 'PATIENT' })).toEqual({
      path: '/followup/patient',
      query: { patientId: '990001' },
    });
  });

  it('rECORD 落点进随访录入详情（隐藏路由随 E15 菜单下发）', () => {
    expect(noticeTargetRoute({ id: 33, type: 'RECORD' })).toEqual({
      path: '/followup/record/33',
    });
  });

  it('无落点、未知类型或非法 id 不跳转（每日汇总等）', () => {
    const message = {
      createdAt: '2026-11-02T08:30:00+08:00',
      eventType: 'DAILY_DIGEST',
      id: 1,
      read: false,
      title: '今日随访汇总',
    } satisfies NoticeMessage;
    expect(noticeTargetRoute(message.target)).toBeNull();
    expect(
      noticeTargetRoute({ id: 1, type: 'VISIT' } as unknown as NoticeTarget),
    ).toBeNull();
    expect(noticeTargetRoute({ id: Number.NaN, type: 'TASK' })).toBeNull();
  });
});

// ---------------------------------------------------------------- 通知设置（E09-T04）

describe('e09-t04 通知设置规则类型', () => {
  const overdueRule: NotifyRule = {
    advanceDays: null,
    channels: ['INBOX', 'SMS'],
    enabled: true,
    eventType: 'TASK_OVERDUE',
    label: '已逾期',
    recipients: ['OWNER', 'WARD_STAFF', 'CC_WARD_LEADER'],
    sendTime: '08:30',
    smsTemplateCode: 'NOTIFY_OVERDUE',
  };

  it('规则 → 表单初值：即时事件 sendTime 回空串、可空字段归 null', () => {
    const model = toNotifyRuleFormModel({
      ...overdueRule,
      sendTime: null,
      smsTemplateCode: null,
      advanceDays: null,
    });
    expect(model.sendTime).toBe('');
    expect(model.smsTemplateCode).toBe('');
    expect(model.advanceDays).toBeNull();
    expect(model.channels).toEqual(['INBOX', 'SMS']);
  });

  it('表单 → PUT 请求体：空串归 null、数组拷贝不反向污染', () => {
    const model = toNotifyRuleFormModel(overdueRule);
    model.sendTime = '  ';
    const update = toNotifyRuleUpdate('TASK_OVERDUE', model);
    expect(update.sendTime).toBeNull();
    expect(update.smsTemplateCode).toBe('NOTIFY_OVERDUE');
    expect(update.channels).toEqual(['INBOX', 'SMS']);
    expect(update.eventType).toBe('TASK_OVERDUE');
    update.channels.push('SMS');
    expect(model.channels).toEqual(['INBOX', 'SMS']);
  });

  it('渠道与接收人展示名齐备（通知设置页文案）', () => {
    expect(NOTIFY_CHANNEL_LABELS.INBOX).toBe('站内信');
    expect(NOTIFY_CHANNEL_LABELS.SMS).toBe('短信');
    expect(NOTIFY_RECIPIENT_LABELS.OWNER).toBe('任务负责人');
    expect(NOTIFY_RECIPIENT_LABELS.WARD_STAFF).toBe('无负责人时病区成员');
    expect(NOTIFY_RECIPIENT_LABELS.CC_WARD_LEADER).toBe('抄送病区负责人');
  });
});

/** E09-T07 / A-NTF-006、A-NTF-007：发送记录与个人偏好的类型口径。 */
describe('e09-T07 发送记录与个人偏好', () => {
  it('四种台账结果都有展示名（发送记录页文案齐备）', () => {
    expect(NOTIFY_SEND_STATUS_LABELS.QUEUED).toBe('待发送');
    expect(NOTIFY_SEND_STATUS_LABELS.SENT).toBe('已发送');
    expect(NOTIFY_SEND_STATUS_LABELS.FAILED).toBe('失败');
    expect(NOTIFY_SEND_STATUS_LABELS.SKIPPED).toBe('已跳过');
  });
});
