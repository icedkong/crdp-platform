/**
 * E09-T02 / A-NTF-002、C-NTF-002：站内信收件箱的类型与跳转路由
 * （接口契约 docs/api/notify.md §1，后端 `com.crdp.followup.notice.api`）。
 *
 * 患者姓名由后端按接收人当前字段策略脱敏（MASKED 掩码 / HIDDEN 整字段不出现），
 * 前端只展示、不缓存明文；点击消息按 target 跳到对应任务 / 患者 / 录入页，
 * 落点接口在服务端按数据范围再校验（越范围 40400）。
 */

/** 点击跳转的落点类型（后端 NoticeInboxService.TargetKind）。 */
export type NoticeTargetType = 'PATIENT' | 'RECORD' | 'TASK';

/** 跳转落点；无落点的消息（如每日汇总）target 为 undefined。 */
export interface NoticeTarget {
  id: number;
  type: NoticeTargetType;
}

/** 一条站内信（notify.md §1）；patientName 可能因 HIDDEN 策略整字段不出现。 */
export interface NoticeMessage {
  createdAt: string;
  eventType: string;
  id: number;
  patientId?: null | number;
  patientName?: null | string;
  read: boolean;
  summary?: null | string;
  target?: NoticeTarget | null;
  title: string;
}

/** 收件箱一页（统一分页口径）。 */
export interface NoticeMessagePage {
  items: NoticeMessage[];
  pageNo: number;
  pageSize: number;
  total: number;
}

/** 未读数响应。 */
export interface NoticeUnreadCount {
  unread: number;
}

/** 全部已读响应。 */
export interface NoticeMarkedCount {
  marked: number;
}

/** 路由落点（vue-router 的 path/query 子集，避免在本包依赖 router 类型）。 */
export interface NoticeRouteLocation {
  path: string;
  query?: Record<string, string>;
}

/**
 * 站内信 target → 页面路由（pages.md §7 / §3 / §5）：
 * TASK → 我的任务（带 taskId）；PATIENT → 患者档案（带 patientId）；
 * RECORD → 随访录入 `/followup/record/:recordId`（该隐藏路由随 E15 菜单下发，
 * 未注册时由调用方回落提示）。无 target / 类型未知 / id 非法 → null（不跳转）。
 */
export function noticeTargetRoute(
  target: NoticeTarget | null | undefined,
): NoticeRouteLocation | null {
  if (!target || typeof target.id !== 'number' || !Number.isFinite(target.id)) {
    return null;
  }
  switch (target.type) {
    case 'PATIENT': {
      return {
        path: '/followup/patient',
        query: { patientId: String(target.id) },
      };
    }
    case 'RECORD': {
      return { path: `/followup/record/${target.id}` };
    }
    case 'TASK': {
      return { path: '/followup/task', query: { taskId: String(target.id) } };
    }
    default: {
      return null;
    }
  }
}

// ---------------------------------------------------------------- 通知设置（E09-T04 / A-NTF-001、C-NTF-001）

/** 通知渠道（notify.md §2）：站内信即时投递；短信并入每日汇总（E09-T05 发送）。 */
export type NotifyChannel = 'INBOX' | 'SMS';

/**
 * 接收人规则选项（A-NTF-004 / C-NTF-004）：
 * OWNER 任务负责人（发送前按病区复核）、WARD_STAFF 无负责人时病区成员兜底
 * （A 是医生与随访员、C 含护士——差异在病区关联了谁）、CC_WARD_LEADER 逾期抄送病区负责人。
 */
export type NotifyRecipientOption = 'CC_WARD_LEADER' | 'OWNER' | 'WARD_STAFF';

/** 逐事件规则（GET /api/hospital/notify/rules 行；后端目录全集，缺行回落默认值）。 */
export interface NotifyRule {
  advanceDays?: null | number;
  channels: NotifyChannel[];
  enabled: boolean;
  eventType: string;
  label: string;
  recipients: NotifyRecipientOption[];
  sendTime?: null | string;
  smsTemplateCode?: null | string;
}

/** PUT /api/hospital/notify/rules/{eventType} 请求体（全量覆盖）。 */
export interface NotifyRuleUpdate {
  advanceDays?: null | number;
  channels: NotifyChannel[];
  enabled: boolean;
  recipients: NotifyRecipientOption[];
  sendTime?: null | string;
  smsTemplateCode?: null | string;
}

/** 可选短信模板（只读：平台审核过的 NOTIFY 场景模板，租户只能选不能写）。 */
export interface NotifySmsTemplate {
  code: string;
  content: string;
  name: string;
}

/** 渠道展示名（通知设置页）。 */
export const NOTIFY_CHANNEL_LABELS: Record<NotifyChannel, string> = {
  INBOX: '站内信',
  SMS: '短信',
};

/** 接收人规则展示名（通知设置页）。 */
export const NOTIFY_RECIPIENT_LABELS: Record<NotifyRecipientOption, string> = {
  CC_WARD_LEADER: '抄送病区负责人',
  OWNER: '任务负责人',
  WARD_STAFF: '无负责人时病区成员',
};

// ------------------------------------------ 发送记录与设置、个人偏好（E09-T07 / A-NTF-006、A-NTF-007）

/** 发送台账结果（notify.md §4）：QUEUED 短信待 E09-T05 汇总发送 / SENT 已投递 / FAILED 失败（可重试）/ SKIPPED 按规则或个人偏好跳过。 */
export type NotifySendStatus = 'FAILED' | 'QUEUED' | 'SENT' | 'SKIPPED';

/** 一条发送记录（POST /api/hospital/notify/send-logs/search 行）；title/detail/sentAt 可空。 */
export interface NotifySendLog {
  channel: NotifyChannel;
  createdAt: string;
  eventLabel: string;
  eventType: string;
  id: number;
  recipientId: number;
  recipientName?: null | string;
  sendStatus: NotifySendStatus;
  sentAt?: null | string;
  title?: null | string;
}

/** 发送记录一页（统一分页口径）。 */
export interface NotifySendLogPage {
  items: NotifySendLog[];
  pageNo: number;
  pageSize: number;
  total: number;
}

/** 发送记录查询条件（全部可空；dateFrom/dateTo 为 yyyy-MM-dd 闭区间按日）。 */
export interface NotifySendLogSearch {
  channel?: NotifyChannel | null;
  dateFrom?: null | string;
  dateTo?: null | string;
  eventType?: null | string;
  pageNo?: null | number;
  pageSize?: null | number;
  recipientId?: null | number;
  sendStatus?: NotifySendStatus | null;
}

/** 结果展示名（发送记录页）。 */
export const NOTIFY_SEND_STATUS_LABELS: Record<NotifySendStatus, string> = {
  FAILED: '失败',
  QUEUED: '待发送',
  SENT: '已发送',
  SKIPPED: '已跳过',
};

/** 租户通知发送设置（notify.md §2 settings）：免打扰与每日上限的发送侧执行随 E09-T05 汇总作业。 */
export interface NotifySettings {
  dndEnd: string;
  dndStart: string;
  overdueSmsOffAllowed: boolean;
  smsDailyLimit: number;
}

/** PUT /api/hospital/notify/settings 请求体（全量覆盖）。 */
export interface NotifySettingsUpdate {
  dndEnd: string;
  dndStart: string;
  overdueSmsOffAllowed: boolean;
  smsDailyLimit: number;
}

/** 个人通知偏好可选项（本院规则含短信渠道的事件；closeable=false 禁选并说明）。 */
export interface NotifyPrefOption {
  closeable: boolean;
  eventType: string;
  label: string;
}

/** GET /api/hospital/profile/notify-pref 响应。 */
export interface NotifyPrefView {
  options: NotifyPrefOption[];
  overdueSmsOffAllowed: boolean;
  smsOff: string[];
}

/** PUT /api/hospital/profile/notify-pref 请求体（全量覆盖已关短信的事件集合）。 */
export interface NotifyPrefUpdate {
  smsOff: string[];
}

/**
 * 规则 → 编辑表单初值（通知设置页弹窗）：sendTime 缺省（即时事件）回空串，
 * 避免 TimePicker 收到 null 的告警；advanceDays / smsTemplateCode 允许空。
 */
export interface NotifyRuleFormModel {
  advanceDays: null | number;
  channels: NotifyChannel[];
  enabled: boolean;
  recipients: NotifyRecipientOption[];
  sendTime: string;
  smsTemplateCode: string;
}

export function toNotifyRuleFormModel(rule: NotifyRule): NotifyRuleFormModel {
  return {
    advanceDays: rule.advanceDays ?? null,
    channels: [...rule.channels],
    enabled: rule.enabled,
    recipients: [...rule.recipients],
    sendTime: rule.sendTime ?? '',
    smsTemplateCode: rule.smsTemplateCode ?? '',
  };
}

/**
 * 编辑表单 → PUT 请求体：sendTime / smsTemplateCode 空串归 null（即时事件、
 * 渠道不含短信时后端要求为空）；渠道与接收人保持数组原序。
 */
export function toNotifyRuleUpdate(
  eventType: string,
  model: NotifyRuleFormModel,
): NotifyRuleUpdate & { eventType: string } {
  return {
    advanceDays: model.advanceDays,
    channels: [...model.channels],
    enabled: model.enabled,
    eventType,
    recipients: [...model.recipients],
    sendTime: model.sendTime.trim() === '' ? null : model.sendTime.trim(),
    smsTemplateCode:
      model.smsTemplateCode.trim() === '' ? null : model.smsTemplateCode.trim(),
  };
}
