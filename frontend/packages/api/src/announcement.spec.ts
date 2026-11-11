import type { HospitalAnnouncement } from './announcement';

import { describe, expect, it } from 'vitest';

import {
  announcementStatusText,
  recentAnnouncementCount,
} from './announcement';

/**
 * E05-T11 / PLT-CFG-009：系统公告文案与医院端角标口径（纯逻辑单测）。
 * 角标无已读跟踪：统计近 7 天（windowMs）发布的公告条数，提示「有新公告」。
 */
describe('e05-T11 系统公告文案与角标', () => {
  it('状态文案：PUBLISHED 发布中、REVOKED 已撤下、空值兜底为发布中', () => {
    expect(announcementStatusText('PUBLISHED')).toBe('发布中');
    expect(announcementStatusText('REVOKED')).toBe('已撤下');
    expect(announcementStatusText(null)).toBe('发布中');
    expect(announcementStatusText(undefined)).toBe('发布中');
  });

  const NOW = Date.parse('2027-07-26T10:00:00+08:00');
  const WEEK = 7 * 24 * 60 * 60 * 1000;

  function announcement(id: number, publishedAt: string): HospitalAnnouncement {
    return { content: '正文（示例）', id, publishedAt, title: `公告 ${id}` };
  }

  it('角标只统计窗口内发布的公告', () => {
    const items = [
      announcement(1, '2027-07-26T09:00:00+08:00'), // 1 小时前
      announcement(2, '2027-07-21T09:00:00+08:00'), // 5 天前
      announcement(3, '2027-07-10T09:00:00+08:00'), // 16 天前
    ];
    expect(recentAnnouncementCount(items, NOW, WEEK)).toBe(2);
  });

  it('窗口边界（恰好 7 天）不计入，非法时间不计入，空列表为 0', () => {
    expect(
      recentAnnouncementCount(
        [announcement(1, '2027-07-19T10:00:00+08:00')],
        NOW,
        WEEK,
      ),
    ).toBe(0);
    expect(
      recentAnnouncementCount([announcement(1, 'not-a-date')], NOW, WEEK),
    ).toBe(0);
    expect(recentAnnouncementCount([], NOW, WEEK)).toBe(0);
  });
});
