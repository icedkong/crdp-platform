import type {
  NotifyChannel,
  NotifyRecipientOption,
  NotifyRule,
  NotifyRuleFormModel,
  NotifySmsTemplate,
} from '@crdp/api';

import {
  NOTIFY_CHANNEL_LABELS,
  NOTIFY_RECIPIENT_LABELS,
  toNotifyRuleFormModel,
} from '@crdp/api';

/**
 * E09-T04 / A-NTF-001、C-NTF-001 · 通知设置（/system/notice）页面逻辑（纯函数）。
 * 本地预校验与后端 NotifyRuleService 同口径（后端为准）；A / C 的事件差异
 * 由本院在这里开关（C 首期无窗口预告 → 停用即可），不写租户分支。
 */

/** sendTime 的 HH:mm 口径（与后端一致；即时事件允许为空）。 */
const SEND_TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;

/** 可选渠道清单（顺序即展示序）。 */
export const CHANNEL_OPTIONS: { label: string; value: NotifyChannel }[] = (
  ['INBOX', 'SMS'] as NotifyChannel[]
).map((value) => ({ label: NOTIFY_CHANNEL_LABELS[value], value }));

/** 可选接收人规则清单。 */
export const RECIPIENT_OPTIONS: {
  label: string;
  value: NotifyRecipientOption;
}[] = (
  ['OWNER', 'WARD_STAFF', 'CC_WARD_LEADER'] as NotifyRecipientOption[]
).map((value) => ({ label: NOTIFY_RECIPIENT_LABELS[value], value }));

/** 事件是否可配提前天数（窗口预告 / 即将逾期；其余事件无此概念，控件禁用）。 */
export function supportsAdvanceDays(rule: NotifyRule): boolean {
  return rule.eventType === 'WINDOW_PREVIEW' || rule.eventType === 'DUE_SOON';
}

/** 事件是否有发送时间（每日定时事件有；改派、约定回访到时为即时）。 */
export function supportsSendTime(rule: NotifyRule): boolean {
  return rule.sendTime !== null || rule.eventType !== 'TASK_REASSIGNED';
}

/** 表单校验：渠道 / 接收人非空、sendTime 口径、短信渠道必配平台模板。 */
export function validateRuleModel(
  model: NotifyRuleFormModel,
  rule: NotifyRule,
): string[] {
  const errors: string[] = [];
  if (model.channels.length === 0) {
    errors.push('至少选择一个通知渠道');
  }
  if (model.recipients.length === 0) {
    errors.push('至少选择一条接收人规则');
  }
  if (
    model.sendTime.trim() !== '' &&
    !SEND_TIME_RE.test(model.sendTime.trim())
  ) {
    errors.push('发送时间须为 HH:mm（如 08:30）');
  }
  if (model.channels.includes('SMS') && model.smsTemplateCode.trim() === '') {
    errors.push('渠道含短信时必须选择平台审核过的短信模板');
  }
  if (!model.channels.includes('SMS') && model.smsTemplateCode.trim() !== '') {
    errors.push('渠道不含短信时不能设置短信模板');
  }
  if (
    model.advanceDays !== null &&
    (!Number.isInteger(model.advanceDays) ||
      model.advanceDays < 0 ||
      model.advanceDays > 365)
  ) {
    errors.push('提前天数须为 0–365 的整数');
  }
  if (
    model.advanceDays !== null &&
    !supportsAdvanceDays(rule) &&
    rule.advanceDays === null
  ) {
    errors.push('该事件不支持配置提前天数');
  }
  return errors;
}

/** 打开编辑弹窗的表单初值（含当前事件规则快照，校验用）。 */
export function editModelOf(rule: NotifyRule): NotifyRuleFormModel {
  return toNotifyRuleFormModel(rule);
}

/** 短信模板下拉选项（含「不发送短信」占位由视图处理）。 */
export function templateOptions(
  templates: NotifySmsTemplate[],
): { label: string; value: string }[] {
  return templates.map((template) => ({
    label: `${template.name}（${template.code}）`,
    value: template.code,
  }));
}

/** 表格「渠道」列文案：站内信 + 短信。 */
export function channelText(channels: NotifyChannel[]): string {
  return channels.map((channel) => NOTIFY_CHANNEL_LABELS[channel]).join(' + ');
}

/** 表格「接收人」列文案。 */
export function recipientText(recipients: NotifyRecipientOption[]): string {
  return recipients.map((option) => NOTIFY_RECIPIENT_LABELS[option]).join('、');
}

/** 表格「触发时机」列文案：提前天数 + 发送时间（即时事件显示「即时」）。 */
export function timingText(rule: NotifyRule): string {
  const parts: string[] = [];
  if (rule.advanceDays !== null) {
    parts.push(`提前 ${rule.advanceDays} 天`);
  }
  parts.push(rule.sendTime ?? '即时');
  return parts.join(' · ');
}
