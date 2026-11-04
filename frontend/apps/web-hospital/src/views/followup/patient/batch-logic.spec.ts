import type { PlanView } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  BATCH_ENROLL_SELECTION_KEY,
  enrollablePlanOptions,
  mergeSelection,
  previewSummaryText,
  stashBatchSelection,
  takeStashedSelection,
  validateBatchEnrollForm,
} from './batch-logic';

function plan(id: string, status: string, version = 1): PlanView {
  return {
    code: `PLAN-${id}`,
    cycleType: 'ONGOING',
    cycleYears: null,
    enrolledCount: 0,
    id,
    lockVersion: 0,
    name: `示例计划（${id}）`,
    publishedAt: null,
    remark: null,
    startDateField: 'DISCHARGE_DATE',
    status,
    version,
  };
}

describe('e13-T07 批量入组页逻辑（A-PAT-016）', () => {
  it('目标计划只列已发布（含派生「随访中」）版本', () => {
    const options = enrollablePlanOptions([
      plan('1', 'PUBLISHED', 2),
      plan('2', 'RUNNING'),
      plan('3', 'UNPUBLISHED'),
      plan('4', 'PAUSED'),
      plan('5', 'DISABLED'),
    ]);
    expect(options).toEqual([
      { label: '示例计划（1）（v2）', value: '1' },
      { label: '示例计划（2）（v1）', value: '2' },
    ]);
  });

  it('提交前校验：未勾选 / 未选计划 / 超过单次上限', () => {
    expect(validateBatchEnrollForm('9', 0)).toContain('勾选');
    expect(validateBatchEnrollForm(null, 3)).toContain('随访计划');
    expect(validateBatchEnrollForm('9', 501)).toContain('500');
    expect(validateBatchEnrollForm('9', 20)).toBeNull();
  });

  it('预检摘要文案（确认前显示将加入人数与将跳过人数）', () => {
    expect(previewSummaryText({ items: [], skipped: 2, succeeded: 18 })).toBe(
      '将加入 18 名、将跳过 2 名',
    );
  });

  it('患者列表勾选经 sessionStorage 传递并取出即清（会话级，最多 500）', () => {
    stashBatchSelection([1, '2', 2, '3']);
    expect(window.sessionStorage.getItem(BATCH_ENROLL_SELECTION_KEY)).toBe(
      '["1","2","3"]',
    );
    expect(takeStashedSelection()).toEqual(['1', '2', '3']);
    expect(
      window.sessionStorage.getItem(BATCH_ENROLL_SELECTION_KEY),
    ).toBeNull();
    expect(takeStashedSelection()).toEqual([]);
  });

  it('换页勾选合并：保留其他页选中、当前页按新勾选替换（跨页保留）', () => {
    const pageRows = [
      { id: '11', name: '甲' },
      { id: '12', name: '乙' },
    ] as never;
    // 已选 11（本页）与 99（其他页），本页新勾选只保留 12
    const merged = mergeSelection(['11', '99'], pageRows, ['12']);
    expect(merged.toSorted()).toEqual(['12', '99']);
  });
});
