/**
 * E24-T03 / NFR-RESP-002 · 患者列表响应式纯逻辑（pages.md §3：
 * 平板筛选收进抽屉、手机筛选进底部抽屉；数据全部虚构）。
 */
import type { PatientFilterState } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import { drawerFilterCount } from './responsive-logic';

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

describe('drawerFilterCount（筛选抽屉触发按钮的生效条件数）', () => {
  it('无筛选为 0', () => {
    expect(drawerFilterCount(state())).toBe(0);
  });

  it('病区 / 状态 / 两个日期区间各计 1（检索词不计——顶栏常驻输入框可见）', () => {
    expect(
      drawerFilterCount(
        state({
          keyword: '张小明（示例）',
          status: 'ACTIVE',
          wardId: 3,
          dischargeRange: ['2026-01-01', '2026-06-30'],
          nextVisitRange: ['2026-11-01', '2026-12-31'],
        }),
      ),
    ).toBe(4);
  });

  it('日期区间只选了一半不计（与检索请求体「未选全不发送」同口径）', () => {
    expect(
      drawerFilterCount(
        state({
          // RangePicker 半选状态防御（类型上假装完整元组）
          dischargeRange: ['2026-01-01', null] as unknown as [string, string],
          nextVisitRange: [null, null] as unknown as [string, string],
        }),
      ),
    ).toBe(0);
  });

  it('select 清空会置 undefined，病区两种「无值」都不计', () => {
    expect(drawerFilterCount(state({ wardId: undefined }))).toBe(0);
  });
});
