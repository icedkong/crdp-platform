import type { PatientTimelineEntry } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  backfillDateError,
  defaultBackfillDate,
  isBackfillDateDisabled,
  resolvePresetVisitDate,
  timelineOpenNeedsStart,
  timelineOpenRoute,
} from './backfill-logic';

/**
 * E15-T07 / A-FU-014、C-FU-013 · 历史补录纯逻辑单测。
 *
 * 锁定：补录日期必须是过去的日期（今天及以后不可选，后端 40001 兜底）；默认一年前；
 * 时间轴节点打开路由（有 recordId 直达、无 recordId 的任务节点需先 start、计划中不可打开）。
 */
const TODAY = new Date('2026-11-05T10:00:00');

describe('补录日期校验（A-FU-014「填写实际随访日期」）', () => {
  it('过去日期可选、今天及以后不可选', () => {
    expect(isBackfillDateDisabled('2026-11-04', TODAY)).toBe(false);
    expect(isBackfillDateDisabled('2026-11-05', TODAY)).toBe(true);
    expect(isBackfillDateDisabled('2026-11-06', TODAY)).toBe(true);
  });

  it('空值与非过去日期给出错误文案（与后端 40001 口径一致）', () => {
    expect(backfillDateError(null, TODAY)).toBe('请填写实际随访日期');
    expect(backfillDateError('2026-11-05', TODAY)).toBe(
      '补录随访日期必须是过去的日期',
    );
    expect(backfillDateError('2026-11-04', TODAY)).toBe('');
  });

  it('默认日期 = 一年前的同日（年度随访的常见补录场景）', () => {
    expect(defaultBackfillDate(TODAY)).toBe('2025-11-05');
  });
});

describe('时间轴节点打开路由（components.md §9 open 事件）', () => {
  it('已关联记录的节点直达表单页（recordId 路由）', () => {
    const entry = {
      canOpen: true,
      date: '2026-11-05',
      kind: 'FOLLOWUP',
      recordId: '701',
    } as PatientTimelineEntry;
    expect(timelineOpenRoute(entry)).toBe('/followup/record/701');
    expect(timelineOpenNeedsStart(entry)).toBe(false);
  });

  it('未开始的任务节点需要先 start（无 recordId 有 taskId）', () => {
    const entry = {
      canOpen: true,
      date: '2026-11-05',
      kind: 'FOLLOWUP',
      taskId: '9001',
    } as PatientTimelineEntry;
    expect(timelineOpenRoute(entry)).toBeNull();
    expect(timelineOpenNeedsStart(entry)).toBe(true);
  });

  it('计划中节点不可打开（A-FU-001 任务开启日才创建）', () => {
    const entry = {
      canOpen: false,
      date: '2027-11-05',
      kind: 'PLANNED',
    } as PatientTimelineEntry;
    expect(timelineOpenRoute(entry)).toBeNull();
    expect(timelineOpenNeedsStart(entry)).toBe(false);
  });
});

// E21-T17 / C-SCAN-012：转录新建补录记录的预填日期（报告时间可用则预填，否则回落默认）。
describe('e21-T17 resolvePresetVisitDate（转录预填日期）', () => {
  const today = new Date('2026-11-13T09:00:00+08:00');

  it('合法过去的报告时间 → 预填（截取日期部分，容忍带时间的 ISO 串）', () => {
    expect(resolvePresetVisitDate('2026-10-28', today)).toBe('2026-10-28');
    expect(resolvePresetVisitDate('2026-10-28T14:30:00+08:00', today)).toBe(
      '2026-10-28',
    );
  });

  it('空 / 今天及以后 / 解析失败 → 回落默认补录日期（一年前同日）', () => {
    expect(resolvePresetVisitDate(null, today)).toBe('2025-11-13');
    expect(resolvePresetVisitDate('2026-11-13', today)).toBe('2025-11-13');
    expect(resolvePresetVisitDate('not-a-date', today)).toBe('2025-11-13');
  });
});
