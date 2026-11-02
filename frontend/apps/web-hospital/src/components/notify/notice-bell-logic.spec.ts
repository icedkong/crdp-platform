import type { NoticeMessage } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  noticeJumpLocation,
  noticeTimeText,
  shouldMarkRead,
} from './notice-bell-logic';

const TASK_MESSAGE = {
  createdAt: '2026-11-02T08:30:00+08:00',
  eventType: 'TASK_OPENED',
  id: 11,
  patientName: '张*）',
  read: false,
  summary: '第 4 年随访窗口已开启（示例）',
  target: { id: 4242, type: 'TASK' },
  title: '随访任务已开启',
} satisfies NoticeMessage;

const alwaysExists = () => true;

/** E09-T02：铃铛纯逻辑——跳转落点、角标、已读判定与时间文案。 */
describe('e09-T02 站内信铃铛逻辑', () => {
  it('点击 TASK 消息跳到对应任务（验收标准）', () => {
    expect(noticeJumpLocation(TASK_MESSAGE, alwaysExists)).toEqual({
      path: '/followup/task',
      query: { taskId: '4242' },
    });
  });

  it('落点路由未注册（如 RECORD 随 E15 下发）时不跳转', () => {
    const recordMessage = {
      ...TASK_MESSAGE,
      target: { id: 33, type: 'RECORD' },
    } satisfies NoticeMessage;
    expect(noticeJumpLocation(recordMessage, () => false)).toBeNull();
  });

  it('无落点的消息（每日汇总）不跳转', () => {
    const digest = {
      createdAt: '2026-11-02T08:30:00+08:00',
      eventType: 'DAILY_DIGEST',
      id: 12,
      read: false,
      title: '今日随访汇总',
    } satisfies NoticeMessage;
    expect(noticeJumpLocation(digest, alwaysExists)).toBeNull();
  });

  it('只有未读消息点击时才标已读', () => {
    expect(shouldMarkRead(TASK_MESSAGE)).toBe(true);
    expect(shouldMarkRead({ ...TASK_MESSAGE, read: true })).toBe(false);
  });

  it('时间文案：当天只写时分，跨天写 MM-DD HH:mm，非法时间空串', () => {
    const now = new Date('2026-11-02T20:00:00+08:00');
    expect(noticeTimeText('2026-11-02T08:30:00+08:00', now)).toBe('08:30');
    expect(noticeTimeText('2026-10-31T09:05:00+08:00', now)).toBe(
      '10-31 09:05',
    );
    expect(noticeTimeText('not-a-time', now)).toBe('');
  });
});
