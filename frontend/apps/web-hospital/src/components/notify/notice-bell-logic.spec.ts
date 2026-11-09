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

  it('时间文案：当天只写时分，跨天写 MM-DD HH:mm，非法时间空串（本地时区构造，CI 任意时区等价）', () => {
    const now = new Date(2026, 10, 2, 20, 0);
    const sameDay = new Date(2026, 10, 2, 8, 30);
    const otherDay = new Date(2026, 9, 31, 9, 5);
    const hm = (t: Date) =>
      `${String(t.getHours()).padStart(2, '0')}:${String(t.getMinutes()).padStart(2, '0')}`;
    expect(noticeTimeText(sameDay.toISOString(), now)).toBe(hm(sameDay));
    expect(noticeTimeText(otherDay.toISOString(), now)).toBe(
      `${String(otherDay.getMonth() + 1).padStart(2, '0')}-${String(
        otherDay.getDate(),
      ).padStart(2, '0')} ${hm(otherDay)}`,
    );
    expect(noticeTimeText('not-a-time', now)).toBe('');
  });
});

// E24-T07 真机兼容：Array.prototype.at（Chromium 92+）在微信 XWeb 等旧内核上
// 不存在，顶栏铃铛的落点路由判定 matched.at(-1) 直接 TypeError——每个页面顶栏
// 都挂铃铛，属真机阻断。删掉该原型方法模拟旧内核回归。
describe('e24-t07 铃铛落点路由判定：Array.prototype.at 缺失时正常', () => {
  it('命中末位路由且非 404 兜底 → 已注册；404 兜底 / 空 matched → 未注册', async () => {
    const { routeMatchIsRegistered } = await import('./notice-bell-logic');
    const arrayAt = Array.prototype.at;
    // oxlint-disable-next-line no-extend-native -- 测试临时摘除原型方法模拟旧内核，finally 恢复
    Object.defineProperty(Array.prototype, 'at', {
      configurable: true,
      value: undefined,
    });
    try {
      expect(
        routeMatchIsRegistered([{ name: 'Layout' }, { name: 'FollowupTask' }]),
      ).toBe(true);
      expect(
        routeMatchIsRegistered([
          { name: 'Layout' },
          { name: 'FallbackNotFound' },
        ]),
      ).toBe(false);
      expect(routeMatchIsRegistered([])).toBe(false);
    } finally {
      // oxlint-disable-next-line no-extend-native -- 测试临时摘除原型方法模拟旧内核，finally 恢复
      Object.defineProperty(Array.prototype, 'at', {
        configurable: true,
        value: arrayAt,
      });
    }
  });
});
