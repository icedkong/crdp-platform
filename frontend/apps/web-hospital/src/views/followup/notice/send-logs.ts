import type {
  NotifyChannel,
  NotifySendLogSearch,
  NotifySendStatus,
} from '@crdp/api';

import { NOTIFY_SEND_STATUS_LABELS } from '@crdp/api';

/**
 * E09-T07 / A-NTF-006、C-NTF-006 · 发送记录页签逻辑（纯函数）。
 * 查询条件 → POST /send-logs/search 请求体（空值不出现）；失败可重试。
 */

/** 结果筛选选项（含「全部」由视图以 undefined 处理）。 */
export const SEND_STATUS_OPTIONS: {
  label: string;
  value: NotifySendStatus;
}[] = (['QUEUED', 'SENT', 'FAILED', 'SKIPPED'] as NotifySendStatus[]).map(
  (value) => ({ label: NOTIFY_SEND_STATUS_LABELS[value], value }),
);

/** 渠道筛选选项。 */
export const CHANNEL_FILTER_OPTIONS: { label: string; value: NotifyChannel }[] =
  [
    { label: '站内信', value: 'INBOX' },
    { label: '短信', value: 'SMS' },
  ];

/** 筛选表单模型（全部可空 = 不限）。 */
export interface SendLogFilterModel {
  channel?: NotifyChannel;
  dateRange?: [string, string] | undefined;
  eventType?: string;
  recipientId?: null | number;
  sendStatus?: NotifySendStatus;
}

/**
 * 筛选表单 → 查询请求体：空值不出现（undefined 字段 JSON 序列化丢弃），
 * dateRange 拆 dateFrom/dateTo；recipientId 非正数视为未填。
 */
export function toSendLogSearch(
  model: SendLogFilterModel,
  pageNo: number,
  pageSize = 20,
): NotifySendLogSearch {
  const search: NotifySendLogSearch = { pageNo, pageSize };
  if (model.eventType?.trim()) {
    search.eventType = model.eventType.trim();
  }
  if (model.channel) {
    search.channel = model.channel;
  }
  if (model.sendStatus) {
    search.sendStatus = model.sendStatus;
  }
  if (
    model.recipientId !== null &&
    model.recipientId !== undefined &&
    Number.isInteger(model.recipientId) &&
    model.recipientId > 0
  ) {
    search.recipientId = model.recipientId;
  }
  if (model.dateRange && model.dateRange[0] && model.dateRange[1]) {
    search.dateFrom = model.dateRange[0];
    search.dateTo = model.dateRange[1];
  }
  return search;
}

/** 行是否可重试（只有失败记录；与后端口径一致，按钮显隐用）。 */
export function isRetryable(sendStatus: string): boolean {
  return sendStatus === 'FAILED';
}

/** 结果列 Tag 颜色（design-system §4 状态色：成功 / 处理中 / 警告 / 默认）。 */
export function sendStatusColor(status: NotifySendStatus): string {
  switch (status) {
    case 'FAILED': {
      return 'error';
    }
    case 'QUEUED': {
      return 'processing';
    }
    case 'SENT': {
      return 'success';
    }
    default: {
      return 'default';
    }
  }
}

/** 时间列文案：到分钟（与审计 / 站内信口径一致）。 */
export function sendTimeText(value: null | string | undefined): string {
  if (!value) {
    return '—';
  }
  return value.length >= 16 ? value.slice(0, 16).replace('T', ' ') : value;
}
