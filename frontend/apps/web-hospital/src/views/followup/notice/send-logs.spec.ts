import { describe, expect, it } from 'vitest';

import {
  isRetryable,
  sendStatusColor,
  sendTimeText,
  toSendLogSearch,
} from './send-logs';

/** E09-T07 / A-NTF-006 · 发送记录页签：筛选 → 查询体、可重试行、展示文案。 */
describe('e09-T07 发送记录查询', () => {
  it('空筛选只带分页（空值字段不出现）', () => {
    expect(toSendLogSearch({}, 1)).toEqual({ pageNo: 1, pageSize: 20 });
  });

  it('全条件透传：dateRange 拆起止日期', () => {
    expect(
      toSendLogSearch(
        {
          channel: 'SMS',
          dateRange: ['2026-10-01', '2026-10-31'],
          eventType: 'TASK_OVERDUE',
          recipientId: 7,
          sendStatus: 'FAILED',
        },
        3,
        50,
      ),
    ).toEqual({
      channel: 'SMS',
      dateFrom: '2026-10-01',
      dateTo: '2026-10-31',
      eventType: 'TASK_OVERDUE',
      pageNo: 3,
      pageSize: 50,
      recipientId: 7,
      sendStatus: 'FAILED',
    });
  });

  it('非法接收人 ID（0、负数、非整数、空串事件）不进查询体', () => {
    expect(toSendLogSearch({ eventType: '  ', recipientId: 0 }, 1)).toEqual({
      pageNo: 1,
      pageSize: 20,
    });
    expect(toSendLogSearch({ recipientId: -1 }, 1).recipientId).toBeUndefined();
    expect(
      toSendLogSearch({ recipientId: 1.5 }, 1).recipientId,
    ).toBeUndefined();
  });

  it('只有失败记录可重试（与后端口径一致）', () => {
    expect(isRetryable('FAILED')).toBe(true);
    expect(isRetryable('QUEUED')).toBe(false);
    expect(isRetryable('SENT')).toBe(false);
    expect(isRetryable('SKIPPED')).toBe(false);
  });

  it('结果 Tag 颜色与时间文案', () => {
    expect(sendStatusColor('SENT')).toBe('success');
    expect(sendStatusColor('QUEUED')).toBe('processing');
    expect(sendStatusColor('FAILED')).toBe('error');
    expect(sendStatusColor('SKIPPED')).toBe('default');
    expect(sendTimeText('2026-11-02T08:30:12')).toBe('2026-11-02 08:30');
    expect(sendTimeText(undefined)).toBe('—');
  });
});
