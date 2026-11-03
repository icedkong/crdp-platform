import type { PatientOverview, PatientTimelineEntry } from './patient-overview';

/**
 * E13-T05 / A-PAT-004、A-PAT-013、C-PAT-010 纯逻辑单测（验收「概览显示基线、计划、最近随访」）。
 *
 * 锁定口径：概览派生列（完整度 / 计划 / 最近随访）写入方未落地时显示占位而不是
 * 猜测；已终止患者下次随访显示「—（已终止）」；时间轴 STATUS 节点文案按状态码映射。
 */
import { describe, expect, it } from 'vitest';

import {
  completenessText,
  contactSlotText,
  contactSourceText,
  genderText,
  latestFollowupText,
  nextVisitText,
  statusSourceText,
  timelineEntryLabel,
} from './patient-overview';

function overview(patch: Partial<PatientOverview> = {}): PatientOverview {
  return {
    actions: [],
    id: '101',
    name: '张*）',
    patientNo: 'IT*******01',
    status: 'ACTIVE',
    terminated: false,
    ...patch,
  };
}

describe('nextVisitText（计划块，E14 写入前为空）', () => {
  it('无计划显示未安排（E14 入组调度写入前）', () => {
    expect(nextVisitText(overview())).toBe('未安排');
  });

  it('有计划显示计划日期与窗口', () => {
    expect(
      nextVisitText(
        overview({
          nextVisit: {
            planDate: '2026-11-05',
            windowEnd: '2026-11-12',
            windowStart: '2026-10-29',
          },
        }),
      ),
    ).toBe('计划 2026-11-05（窗口 2026-10-29 ~ 2026-11-12）');
  });

  it('已终止显示 —（已终止）（pages.md §4.10）', () => {
    expect(
      nextVisitText(
        overview({
          status: 'DEAD',
          terminated: true,
          nextVisit: { planDate: '2026-11-05' },
        }),
      ),
    ).toBe('—（已终止）');
  });
});

describe('latestFollowupText（最近随访块，E15 写入前为空）', () => {
  it('无已提交记录显示暂无随访记录', () => {
    expect(latestFollowupText(overview())).toBe('暂无随访记录');
  });

  it('有记录显示日期与生存状态', () => {
    expect(
      latestFollowupText(
        overview({
          latestFollowup: { date: '2026-06-10', survivalStatus: 'ALIVE' },
        }),
      ),
    ).toBe('2026-06-10 · 生存状态 存活');
  });

  it('未知生存状态显示原码兜底', () => {
    expect(
      latestFollowupText(
        overview({
          latestFollowup: { date: '2026-06-10', survivalStatus: 'X' },
        }),
      ),
    ).toBe('2026-06-10 · 生存状态 X');
  });
});

describe('completenessText（基线块，E13-T02 / E15 写入前为空）', () => {
  it('null 显示 —，不显示 0%', () => {
    expect(completenessText(null)).toBe('—');
    expect(completenessText(undefined)).toBe('—');
  });

  it('有值显示百分比', () => {
    expect(completenessText(92)).toBe('92%');
    expect(completenessText(78.5)).toBe('78.5%');
  });
});

describe('timelineEntryLabel（A-PAT-013 状态文案）', () => {
  it('状态节点按状态码映射（死亡 / 已暂停）', () => {
    expect(
      timelineEntryLabel({
        date: '2026-09-01',
        kind: 'STATUS',
        status: 'DEAD',
      }),
    ).toBe('死亡');
    expect(
      timelineEntryLabel({
        date: '2026-07-01',
        kind: 'STATUS',
        status: 'PAUSED',
      }),
    ).toBe('已暂停');
  });

  it('出院节点用后端数据名', () => {
    const entry: PatientTimelineEntry = {
      date: '2026-03-08',
      kind: 'DISCHARGE',
      label: '出院',
    };
    expect(timelineEntryLabel(entry)).toBe('出院');
  });

  it('未知状态码原样返回', () => {
    expect(
      timelineEntryLabel({
        date: '2026-09-01',
        kind: 'STATUS',
        status: 'NEW_ONE',
      }),
    ).toBe('NEW_ONE');
  });
});

describe('genderText', () => {
  it('性别 M / F 映射，未知显示 —', () => {
    expect(genderText('M')).toBe('男');
    expect(genderText('F')).toBe('女');
    expect(genderText(null)).toBe('—');
  });
});

describe('联系方式与变更记录文案（A-PAT-004「从概览进入」）', () => {
  it('槽位与来源映射，未知原样返回', () => {
    expect(contactSlotText('PHONE_1')).toBe('电话1');
    expect(contactSlotText('PHONE_2')).toBe('电话2');
    expect(contactSourceText('REGISTRATION')).toBe('登记时');
    expect(contactSourceText('MANUAL')).toBe('页面维护');
    expect(contactSourceText('X')).toBe('X');
  });

  it('状态来源映射（登记 / 人工流转）', () => {
    expect(statusSourceText('REGISTRATION')).toBe('登记');
    expect(statusSourceText('MANUAL')).toBe('人工流转');
  });
});
