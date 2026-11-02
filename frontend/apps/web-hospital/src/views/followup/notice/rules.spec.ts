import type { NotifyRule } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  channelText,
  editModelOf,
  recipientText,
  supportsAdvanceDays,
  supportsSendTime,
  templateOptions,
  timingText,
  validateRuleModel,
} from './rules';

const overdue: NotifyRule = {
  advanceDays: null,
  channels: ['INBOX', 'SMS'],
  enabled: true,
  eventType: 'TASK_OVERDUE',
  label: '已逾期',
  recipients: ['OWNER', 'WARD_STAFF', 'CC_WARD_LEADER'],
  sendTime: '08:30',
  smsTemplateCode: 'NOTIFY_OVERDUE',
};

const opened: NotifyRule = {
  advanceDays: null,
  channels: ['INBOX'],
  enabled: true,
  eventType: 'TASK_OPENED',
  label: '任务开启',
  recipients: ['OWNER', 'WARD_STAFF'],
  sendTime: '08:30',
  smsTemplateCode: null,
};

const reassigned: NotifyRule = {
  advanceDays: null,
  channels: ['INBOX'],
  enabled: true,
  eventType: 'TASK_REASSIGNED',
  label: '任务改派',
  recipients: ['OWNER', 'WARD_STAFF'],
  sendTime: null,
  smsTemplateCode: null,
};

describe('e09-t04 通知设置逻辑', () => {
  it('表单校验：合法已逾期规则通过（短信渠道配了模板）', () => {
    expect(validateRuleModel(editModelOf(overdue), overdue)).toEqual([]);
  });

  it('表单校验：渠道 / 接收人为空、短信无模板、sendTime 口径、提前天数口径全拦', () => {
    const model = editModelOf(overdue);
    model.channels = [];
    model.recipients = [];
    expect(validateRuleModel(model, overdue)).toContain('至少选择一个通知渠道');
    expect(validateRuleModel(model, overdue)).toContain(
      '至少选择一条接收人规则',
    );

    const noTemplate = editModelOf(overdue);
    noTemplate.smsTemplateCode = '';
    expect(validateRuleModel(noTemplate, overdue)).toContain(
      '渠道含短信时必须选择平台审核过的短信模板',
    );

    const badTime = editModelOf(opened);
    badTime.sendTime = '25:00';
    expect(validateRuleModel(badTime, opened)).toContain(
      '发送时间须为 HH:mm（如 08:30）',
    );

    const noSmsButTemplate = editModelOf(opened);
    noSmsButTemplate.smsTemplateCode = 'NOTIFY_OVERDUE';
    expect(validateRuleModel(noSmsButTemplate, opened)).toContain(
      '渠道不含短信时不能设置短信模板',
    );

    const badAdvance = editModelOf(opened);
    badAdvance.advanceDays = 400;
    expect(validateRuleModel(badAdvance, opened)).toContain(
      '提前天数须为 0–365 的整数',
    );
  });

  it('提前天数只对窗口预告 / 即将逾期开放（改派等即时事件不可配）', () => {
    expect(supportsAdvanceDays(overdue)).toBe(false);
    expect(
      supportsAdvanceDays({
        ...opened,
        advanceDays: 7,
        eventType: 'WINDOW_PREVIEW',
      }),
    ).toBe(true);
    const model = editModelOf(reassigned);
    model.advanceDays = 3;
    expect(validateRuleModel(model, reassigned)).toContain(
      '该事件不支持配置提前天数',
    );
  });

  it('发送时间：定时事件有、改派等即时事件显示「即时」', () => {
    expect(supportsSendTime(opened)).toBe(true);
    expect(supportsSendTime(reassigned)).toBe(false);
    expect(timingText(opened)).toBe('08:30');
    expect(timingText(reassigned)).toBe('即时');
    expect(
      timingText({
        ...opened,
        advanceDays: 7,
        eventType: 'WINDOW_PREVIEW',
        sendTime: '08:30',
      }),
    ).toBe('提前 7 天 · 08:30');
  });

  it('表格文案：渠道、接收人、模板选项', () => {
    expect(channelText(overdue.channels)).toBe('站内信 + 短信');
    expect(channelText(opened.channels)).toBe('站内信');
    expect(recipientText(overdue.recipients)).toBe(
      '任务负责人、无负责人时病区成员、抄送病区负责人',
    );
    expect(
      templateOptions([
        {
          code: 'NOTIFY_OVERDUE',
          content: '您有{1}项已逾期。',
          name: '随访逾期提醒',
        },
      ]),
    ).toEqual([
      { label: '随访逾期提醒（NOTIFY_OVERDUE）', value: 'NOTIFY_OVERDUE' },
    ]);
  });
});
