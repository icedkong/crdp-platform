// A-PAT-015, A-NFR-009（E13-T06 保存的筛选 ↔ 页面筛选状态转换；数据全部虚构）
import type { PatientFilterState } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  applySavedFilter,
  savedFilterPayloadIsEmpty,
  savedFilterPayloadOf,
} from './advanced-logic';

function state(patch: Partial<PatientFilterState> = {}): PatientFilterState {
  return {
    keyword: '',
    status: null,
    wardId: null,
    dischargeRange: null,
    nextVisitRange: null,
    ...patch,
  };
}

describe('savedFilterPayloadOf（保存：剥离检索词与分页）', () => {
  it('检索词不进 payload（可能是患者姓名，不落库）', () => {
    const payload = savedFilterPayloadOf(
      state({ keyword: '张小明（示例）', wardId: 3 }),
      null,
    );
    expect(payload).toEqual({ filters: { wardIds: [3] } });
    expect(JSON.stringify(payload)).not.toContain('张小明');
  });

  it('简单筛选与条件树一并保存', () => {
    const payload = savedFilterPayloadOf(
      state({
        status: 'ACTIVE',
        dischargeRange: ['2026-01-01', '2026-06-30'],
      }),
      {
        logic: 'or',
        items: [{ field: 'gender', op: 'EQ', value: 'F' }],
      },
    );
    expect(payload).toEqual({
      condition: {
        logic: 'or',
        items: [{ field: 'gender', op: 'EQ', value: 'F' }],
      },
      filters: {
        dischargeFrom: '2026-01-01',
        dischargeTo: '2026-06-30',
        status: ['ACTIVE'],
      },
    });
  });

  it('空筛选与无条件 → 空 payload（后端 40001 兜底，前端置灰）', () => {
    expect(savedFilterPayloadOf(state(), null)).toEqual({});
    expect(savedFilterPayloadIsEmpty(savedFilterPayloadOf(state(), null))).toBe(
      true,
    );
  });
});

describe('applySavedFilter（应用：回填筛选状态）', () => {
  it('回填简单筛选与条件树，检索词清空', () => {
    const applied = applySavedFilter({
      condition: {
        logic: 'and',
        items: [{ field: 'ward', op: 'EQ', value: 3 }],
      },
      filters: {
        dischargeFrom: '2026-01-01',
        dischargeTo: '2026-06-30',
        wardIds: [4],
      },
    });
    expect(applied.state).toEqual({
      keyword: '',
      status: null,
      wardId: 4,
      dischargeRange: ['2026-01-01', '2026-06-30'],
      nextVisitRange: null,
    });
    expect(applied.condition).toEqual({
      logic: 'and',
      items: [{ field: 'ward', op: 'EQ', value: 3 }],
    });
  });

  it('缺半边的日期区间不回填（与组装口径对称）', () => {
    const applied = applySavedFilter({
      filters: { dischargeFrom: '2026-01-01' },
    });
    expect(applied.state.dischargeRange).toBeNull();
  });
});
