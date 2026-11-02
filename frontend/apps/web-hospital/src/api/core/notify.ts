/**
 * E09-T02 / A-NTF-002、C-NTF-002：医院端站内信收件箱接口（docs/api/notify.md §1）。
 * 登录即可（无权限码）；患者姓名由后端按接收人字段策略脱敏后返回。
 */
import type {
  NoticeMarkedCount,
  NoticeMessagePage,
  NoticeUnreadCount,
  NotifyRule,
  NotifyRuleUpdate,
  NotifySmsTemplate,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 本人收件箱一页；unread=true 只看未读。 */
export function fetchNoticeMessages(options: {
  pageNo?: number;
  pageSize?: number;
  unread?: boolean;
}) {
  const { pageNo = 1, pageSize = 20, unread } = options;
  return requestClient.get<NoticeMessagePage>(apiPath('/notify/messages'), {
    params: { pageNo, pageSize, ...(unread === undefined ? {} : { unread }) },
  });
}

/** 未读数（顶栏铃铛角标，60 秒轮询或页面可见时刷新）。 */
export function fetchNoticeUnreadCount() {
  return requestClient.get<NoticeUnreadCount>(
    apiPath('/notify/messages/unread-count'),
  );
}

/** 标一条已读（别人的消息 / 不存在 40400，不暴露对象存在）。 */
export function markNoticeMessageRead(id: number) {
  return requestClient.post(apiPath(`/notify/messages/${id}/read`));
}

/** 全部已读。 */
export function markAllNoticeMessagesRead() {
  return requestClient.post<NoticeMarkedCount>(
    apiPath('/notify/messages/read-all'),
  );
}

// ---------------------------------------------------------------- 通知设置（E09-T04 / A-NTF-001、C-NTF-001）

/**
 * 逐事件规则清单（目录全集，缺行回落默认值）。权限 followup:notice:config。
 */
export function fetchNotifyRules() {
  return requestClient.get<NotifyRule[]>(apiPath('/notify/rules'));
}

/** 修改单事件规则（全量覆盖，写 NOTICE_RULE_UPDATE 审计）。 */
export function updateNotifyRule(eventType: string, update: NotifyRuleUpdate) {
  return requestClient.put<NotifyRule>(
    apiPath(`/notify/rules/${eventType}`),
    update,
  );
}

/** 可选短信模板（只读：平台审核过的 NOTIFY 场景模板）。 */
export function fetchNotifySmsTemplates() {
  return requestClient.get<NotifySmsTemplate[]>(
    apiPath('/notify/sms-templates'),
  );
}
