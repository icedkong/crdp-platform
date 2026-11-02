import type { NoticeMessage, NoticeTarget } from './notify';

import { describe, expect, it } from 'vitest';

import { noticeTargetRoute } from './notify';

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
