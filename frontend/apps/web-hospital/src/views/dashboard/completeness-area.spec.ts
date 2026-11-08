// E19-T05 / A-DASH-010、A-DASH-011、C-DASH-009（pages.md §2 首页数据完整度区）：
// 基线资料完整度与随访资料完整度左右并列——左侧平均值 / 中位数 + 五段分布（可切换
// 按分类明细表）+ 最常缺失字段前 10；右侧平均值 / 已统计记录数 + 按随访年度表格 +
// 最常缺失字段前 10；共用筛选（患者类型 / 时间范围，时间维度各卡自带）；点缺失字段
// 钻取患者列表（missingFieldRoute：路由 query 带钻取条件，患者列表同一计算过滤）；
// 病区筛选（wardId）变更联动重载。测试数据全部虚构。
import type {
  BaselineCompletenessCardView,
  FollowupCompletenessCardView,
} from '@crdp/api';

import { mount } from '@vue/test-utils';

import { afterEach, describe, expect, it, vi } from 'vitest';

import CompletenessArea from './completeness-area.vue';

const fetchBaselineCompletenessCard = vi.fn();
const fetchFollowupCompletenessCard = vi.fn();
const routerPush = vi.fn();

vi.mock('#/api/core/dashboard', () => ({
  fetchBaselineCompletenessCard: (...args: unknown[]) =>
    fetchBaselineCompletenessCard(...args),
  fetchFollowupCompletenessCard: (...args: unknown[]) =>
    fetchFollowupCompletenessCard(...args),
}));
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerPush }),
}));

const bands = (counts: number[]) =>
  counts.map((count, index) => ({
    band: `BAND_${index}`,
    count,
    label: ['100%', '90–99%', '70–89%', '50–69%', '低于 50%'][index],
    pct: count * 10,
  }));

const baselineView: BaselineCompletenessCardView = {
  averagePct: 50,
  categories: [
    {
      averagePct: 50,
      categoryKey: 'basic',
      label: '基本信息',
      patientCount: 3,
    },
    {
      averagePct: 100,
      categoryKey: 'case',
      label: '病例基本信息',
      patientCount: 2,
    },
  ],
  distribution: bands([1, 0, 0, 1, 1]),
  from: null,
  medianPct: 50,
  patientCount: 3,
  patientTypes: [{ code: 'ELECTIVE', label: 'ELECTIVE' }],
  timeField: 'DISCHARGE',
  topMissing: [
    {
      categoryKey: 'basic',
      fieldKey: 'f9t5b',
      label: '字段二（示例）',
      missingCount: 2,
      missingRatePct: 66.7,
    },
    {
      categoryKey: 'basic',
      fieldKey: 'f9t5a',
      label: '字段一（示例）',
      missingCount: 1,
      missingRatePct: 33.3,
    },
  ],
  to: null,
  wardId: null,
};

const followupView: FollowupCompletenessCardView = {
  averagePct: 75,
  distribution: bands([1, 1, 0, 0, 0]),
  from: null,
  medianPct: 75,
  patientCount: 2,
  patientTypes: [{ code: 'ELECTIVE', label: 'ELECTIVE' }],
  recordCount: 2,
  timeField: 'VISIT',
  to: null,
  topMissing: [
    {
      categoryKey: 'fu_contact',
      fieldKey: 'f214',
      label: '随访日期（示例）',
      missingCount: 1,
      missingRatePct: 50,
    },
  ],
  wardId: null,
  year: null,
  yearOptions: [{ label: '第1年', year: 1 }],
  years: [
    {
      averagePct: 75,
      distribution: bands([1, 0, 0, 1, 0]),
      label: '第1年',
      medianPct: 75,
      recordCount: 2,
      year: 1,
    },
  ],
};

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

function mountArea(
  props: Record<string, unknown> = {},
  baseline: BaselineCompletenessCardView = baselineView,
  followup: FollowupCompletenessCardView = followupView,
) {
  fetchBaselineCompletenessCard.mockResolvedValue(baseline);
  fetchFollowupCompletenessCard.mockResolvedValue(followup);
  const wrapper = mount(CompletenessArea, { attachTo: document.body, props });
  mounted.push(wrapper);
  return wrapper;
}

async function flush() {
  await new Promise((resolve) => setTimeout(resolve, 20));
}

describe('e19-t05 首页数据完整度 · 基线资料完整度（A-DASH-010）', () => {
  it('平均值与中位数、五段分布表（人数 / 占比）、最常缺失字段前 10（按缺失人数降序）', async () => {
    const wrapper = mountArea();
    await flush();

    expect(fetchBaselineCompletenessCard).toHaveBeenCalledWith({
      from: undefined,
      patientType: undefined,
      timeField: 'DISCHARGE',
      to: undefined,
      wardId: undefined,
    });
    const summary = wrapper.find('[data-testid="baseline-summary"]');
    expect(summary.text()).toContain('50.0%');
    expect(summary.text()).toContain('3');
    const bandTable = wrapper.find('[data-testid="baseline-band-table"]');
    expect(bandTable.findAll('tbody tr')).toHaveLength(5);
    expect(bandTable.text()).toContain('100%');
    expect(bandTable.text()).toContain('低于 50%');
    expect(bandTable.text()).toContain('10.0%');
    const missing = wrapper.find('[data-testid="baseline-top-missing"]');
    expect(missing.text()).toContain('字段二（示例）');
    expect(missing.text()).toContain('缺 2 人');
    expect(missing.text()).toContain('66.7%');
  });

  it('可切换为按分类的完整度表（分类 / 平均完整度 / 患者数）', async () => {
    const wrapper = mountArea();
    await flush();

    await wrapper
      .find('[data-testid="baseline-view-categories"]')
      .trigger('click');
    const table = wrapper.find('[data-testid="baseline-category-table"]');
    expect(table.exists()).toBe(true);
    expect(table.text()).toContain('基本信息');
    expect(table.text()).toContain('病例基本信息');
    expect(table.text()).toContain('50.0%');
    expect(table.text()).toContain('100.0%');
  });

  it('无患者时显示空状态', async () => {
    const wrapper = mountArea({}, { ...baselineView, patientCount: 0 });
    await flush();

    expect(wrapper.text()).toContain('暂无患者数据');
  });
});

describe('e19-t05 首页数据完整度 · 随访资料完整度（A-DASH-011 / Q40）', () => {
  it('平均值与已统计记录数、按随访年度表格（记录数 / 平均 / 中位数 / 分布）', async () => {
    const wrapper = mountArea();
    await flush();

    const summary = wrapper.find('[data-testid="followup-summary"]');
    expect(summary.text()).toContain('75.0%');
    expect(summary.text()).toContain('2');
    const table = wrapper.find('[data-testid="followup-year-table"]');
    const rows = table.findAll('tbody tr');
    expect(rows).toHaveLength(1);
    const text = rows[0]?.text() ?? '';
    expect(text).toContain('第1年');
    expect(text).toContain('75.0%');
    expect(text).toContain('100% ×1');
    expect(text).toContain('50–69% ×1');
    const missing = wrapper.find('[data-testid="followup-top-missing"]');
    expect(missing.text()).toContain('随访日期（示例）');
    expect(missing.text()).toContain('缺 1 条');
  });

  it('无已完成记录时显示空状态', async () => {
    const wrapper = mountArea({}, baselineView, {
      ...followupView,
      recordCount: 0,
      years: [],
    });
    await flush();

    expect(wrapper.text()).toContain('暂无已完成的随访记录');
  });
});

describe('e19-t05 首页数据完整度 · 钻取与筛选（验收「可钻取到缺失字段」）', () => {
  it('点缺失字段进入缺该字段的患者列表（路由 query 带钻取条件）', async () => {
    const wrapper = mountArea({ wardId: 3 });
    await flush();

    await wrapper
      .find('[data-testid="baseline-missing-f9t5b"]')
      .trigger('click');
    expect(routerPush).toHaveBeenCalledWith({
      path: '/followup/patient',
      query: {
        missingField: 'f9t5b',
        missingFieldLabel: '字段二（示例）',
        missingKind: 'BASELINE',
        timeField: 'DISCHARGE',
        wardId: 3,
      },
    });

    await wrapper
      .find('[data-testid="followup-missing-f214"]')
      .trigger('click');
    expect(routerPush).toHaveBeenLastCalledWith({
      path: '/followup/patient',
      query: {
        missingField: 'f214',
        missingFieldLabel: '随访日期（示例）',
        missingKind: 'FOLLOWUP',
        timeField: 'VISIT',
        wardId: 3,
      },
    });
  });

  it('病区筛选（wardId）变更联动重载（A-DASH-005 全页统计）', async () => {
    const wrapper = mountArea({ wardId: 3 });
    await flush();
    expect(fetchBaselineCompletenessCard).toHaveBeenLastCalledWith(
      expect.objectContaining({ wardId: 3 }),
    );

    await wrapper.setProps({ wardId: 5 });
    await flush();
    expect(fetchBaselineCompletenessCard).toHaveBeenLastCalledWith(
      expect.objectContaining({ wardId: 5 }),
    );
    expect(fetchFollowupCompletenessCard).toHaveBeenLastCalledWith(
      expect.objectContaining({ wardId: 5 }),
    );
  });

  it('卡片旁注明与随访率的口径区分', () => {
    const wrapper = mountArea();

    expect(wrapper.text()).toContain(
      '完整度只统计已填写的资料，随访做没做见每年随访率',
    );
  });
});
