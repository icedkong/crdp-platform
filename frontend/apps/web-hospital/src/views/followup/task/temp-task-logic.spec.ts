import { describe, expect, it } from 'vitest';

import {
  defaultTempTaskState,
  defaultUnplannedDate,
  isUnplannedDateDisabled,
  tempTaskFormError,
  tempTaskWindowText,
  unplannedDateError,
} from './temp-task-logic';

/**
 * E15-T06 / A-FU-013、A-FU-026、C-FU-022 · 临时添加任务与计划外随访纯逻辑单测。
 *
 * 锁定：临时任务（患者与计划日期必填、窗口宽度 0–365、默认今天 + 7 天）；计划外随访
 * （日期必填且不能晚于今天、当天可录——与补录「必须早于今天」区分）。
 */
const TODAY = new Date('2026-11-05T10:00:00');

describe('临时添加任务（A-FU-013）', () => {
  it('默认状态：计划日期今天、FORM 填表随访、窗口 ±7 天', () => {
    const state = defaultTempTaskState(null, TODAY);
    expect(state.planDate).toBe('2026-11-05');
    expect(state.taskType).toBe('FORM');
    expect(state.windowDays).toBe(7);
    expect(tempTaskFormError(state)).toBe('请选择患者');
  });

  it('患者与计划日期必填、窗口宽度边界 0–365', () => {
    expect(tempTaskFormError(defaultTempTaskState(null, TODAY))).toContain(
      '请选择患者',
    );
    const withPatient = { ...defaultTempTaskState('301', TODAY) };
    expect(tempTaskFormError(withPatient)).toBe('');
    expect(tempTaskFormError({ ...withPatient, planDate: null })).toBe(
      '请填写计划日期',
    );
    expect(tempTaskFormError({ ...withPatient, windowDays: 366 })).toContain(
      '0–365',
    );
    expect(tempTaskFormError({ ...withPatient, windowDays: -1 })).toContain(
      '0–365',
    );
    expect(tempTaskFormError({ ...withPatient, windowDays: 0 })).toBe('');
    expect(
      tempTaskFormError({ ...withPatient, note: 'x'.repeat(256) }),
    ).toContain('255');
    expect(
      tempTaskFormError({ ...withPatient, note: '再住院后加访（示例）' }),
    ).toBe('');
  });

  it('窗口文案（宽度 0 = 当天）', () => {
    expect(tempTaskWindowText(0)).toBe('计划日当天');
    expect(tempTaskWindowText(7)).toBe('计划日期 ± 7 天');
  });
});

describe('计划外随访日期（C-FU-022：当天可录、未来不可选）', () => {
  it('默认今天；今天及过去可选、未来不可选', () => {
    expect(defaultUnplannedDate(TODAY)).toBe('2026-11-05');
    expect(isUnplannedDateDisabled('2026-11-05', TODAY)).toBe(false);
    expect(isUnplannedDateDisabled('2026-11-04', TODAY)).toBe(false);
    expect(isUnplannedDateDisabled('2026-11-06', TODAY)).toBe(true);
  });

  it('空值与未来日期给出错误文案（与后端 40001 口径一致）', () => {
    expect(unplannedDateError(null, TODAY)).toBe('请填写随访日期');
    expect(unplannedDateError('2026-11-06', TODAY)).toContain('不能晚于今天');
    expect(unplannedDateError('2026-11-05', TODAY)).toBe('');
  });
});
