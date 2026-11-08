// E19-T04 / A-DASH-006、A-DASH-012、C-DASH-004（pages.md §2 首页统计区）：每年随访率与
// 节点随访率两张卡片——表格行与主列（随访率 + 小字窗口内完成 / 率）、柱线图、
// 队列 / 计划筛选、node_follow_rate 开关显隐（components.md §1.3 useFeature）、
// 病区筛选（wardId）变更联动重载。测试数据全部虚构。
import type { NodeRateCardView, YearlyRateCardView } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { Select } from 'ant-design-vue';
import { afterEach, describe, expect, it, vi } from 'vitest';

import RateArea from './rate-area.vue';

const fetchYearlyRateCard = vi.fn();
const fetchNodeRateCard = vi.fn();
let featureSwitches: Record<string, boolean> = {};

vi.mock('#/api/core/dashboard', () => ({
  fetchNodeRateCard: (...args: unknown[]) => fetchNodeRateCard(...args),
  fetchYearlyRateCard: (...args: unknown[]) => fetchYearlyRateCard(...args),
}));
vi.mock('#/reference/use-feature', () => ({
  useFeature: () => ({
    isEnabled: (code: string) => featureSwitches[code] === true,
    switches: { value: featureSwitches },
  }),
}));

const yearlyView: YearlyRateCardView = {
  asOf: '2026-11-08',
  cohortYear: null,
  cohortYears: [2025, 2024],
  rows: [
    {
      deathCount: 1,
      dueCount: 4,
      followRatePct: 75,
      followedCount: 3,
      inWindowCount: 2,
      inWindowRatePct: 50,
      label: '第1年',
      lostRatePct: 0,
      nodeName: null,
      occurrence: 1,
      year: 1,
    },
    {
      deathCount: 0,
      dueCount: 3,
      followRatePct: 66.7,
      followedCount: 2,
      inWindowCount: 2,
      inWindowRatePct: 66.7,
      label: '第2年',
      lostRatePct: 33.3,
      nodeName: null,
      occurrence: 2,
      year: 2,
    },
  ],
};

const nodeView: NodeRateCardView = {
  asOf: '2026-11-08',
  cohortYear: null,
  cohortYears: [2025],
  planId: 11,
  planName: '冠心病术后年度随访（示例）',
  plans: [
    { id: 11, name: '冠心病术后年度随访（示例）' },
    { id: 12, name: '起搏器术后随访（示例）' },
  ],
  rows: [
    {
      deathCount: 0,
      dueCount: 4,
      followRatePct: 75,
      followedCount: 3,
      inWindowCount: 2,
      inWindowRatePct: 50,
      label: '第1年',
      lostRatePct: 0,
      nodeName: '电话随访（示例）',
      occurrence: 1,
      year: 1,
    },
    {
      deathCount: 0,
      dueCount: 10,
      followRatePct: 90,
      followedCount: 9,
      inWindowCount: 8,
      inWindowRatePct: 80,
      label: '术后第 1 天（示例）',
      lostRatePct: 10,
      nodeName: '术后第 1 天（示例）',
      occurrence: 1,
      year: null,
    },
  ],
};

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
  vi.clearAllMocks();
  featureSwitches = {};
});

function mountArea(
  props: Record<string, unknown> = {},
  yearly: YearlyRateCardView = yearlyView,
) {
  fetchYearlyRateCard.mockResolvedValue(yearly);
  fetchNodeRateCard.mockResolvedValue(nodeView);
  const wrapper = mount(RateArea, { attachTo: document.body, props });
  mounted.push(wrapper);
  return wrapper;
}

async function flush() {
  await new Promise((resolve) => setTimeout(resolve, 20));
}

describe('e19-t04 首页统计区 · 每年随访率（A-DASH-006）', () => {
  it('表格一行一个随访年度：应随访 / 已随访 / 随访率主列（小字窗口内完成 / 率）/ 失访率 / 死亡人数', async () => {
    const wrapper = mountArea();
    await flush();

    expect(fetchYearlyRateCard).toHaveBeenCalledWith({
      cohortYear: undefined,
      wardId: undefined,
    });
    const table = wrapper.find('[data-testid="yearly-rate-table"]');
    const cells = table.findAll('tbody tr');
    expect(cells).toHaveLength(2);
    const firstRow = cells[0]?.text() ?? '';
    expect(firstRow ?? '').toContain('第1年');
    expect(firstRow ?? '').toContain('75.0%');
    expect(firstRow ?? '').toContain('其中窗口内完成 2 / 50.0%');
    expect(table.text()).toContain('死亡人数', '列头含死亡人数');
    expect(table.text()).toContain('失访率');
    expect(wrapper.text()).toContain('数据截至 2026-11-08（每日刷新）');
  });

  it('无数据时显示空状态；队列筛选随行下发', async () => {
    const wrapper = mountArea({}, { ...yearlyView, rows: [] });
    await flush();

    expect(wrapper.find('[data-testid="yearly-rate-table"]').exists()).toBe(
      false,
    );
    expect(wrapper.text()).toContain('暂无随访数据');
    // 队列候选来自响应（出院年份队列；下拉未展开时选项不在文本里，查 props）
    const cohortSelect = wrapper
      .findAllComponents(Select)
      .find((item) => item.attributes('data-testid') === 'yearly-cohort');
    expect(cohortSelect?.props('options')).toEqual([
      { label: '2025 年', value: 2025 },
      { label: '2024 年', value: 2024 },
    ]);
  });

  it('病区筛选（wardId）变更联动重载（A-DASH-005 全页统计）', async () => {
    const wrapper = mountArea({ wardId: 3 });
    await flush();
    expect(fetchYearlyRateCard).toHaveBeenLastCalledWith({
      cohortYear: undefined,
      wardId: 3,
    });

    await wrapper.setProps({ wardId: 5 });
    await flush();
    expect(fetchYearlyRateCard).toHaveBeenLastCalledWith({
      cohortYear: undefined,
      wardId: 5,
    });
  });
});

describe('e19-t04 首页统计区 · 节点随访率（A-DASH-012 / C-DASH-004）', () => {
  it('开关开启显示：节点行标签（年度循环 = 第 N 年、固定节点 = 节点名）与计划候选缺省预置计划', async () => {
    featureSwitches = { node_follow_rate: true };
    const wrapper = mountArea();
    await flush();

    expect(fetchNodeRateCard).toHaveBeenCalledWith({
      cohortYear: undefined,
      planId: undefined,
      wardId: undefined,
    });
    const rows = wrapper
      .find('[data-testid="node-rate-table"]')
      .findAll('tbody tr');
    expect(rows).toHaveLength(2);
    expect(rows[0]?.text() ?? '').toContain('第1年');
    expect(rows[1]?.text() ?? '').toContain('术后第 1 天（示例）');
    // 缺省选中预置计划（响应回显的 planId）
    expect(wrapper.find('[data-testid="node-rate-plan"]').text()).toContain(
      '冠心病术后年度随访（示例）',
    );
  });

  it('开关关闭（医院 A 默认）隐藏节点随访率卡片——不发起请求（后端仍 40301 兜底）', async () => {
    const wrapper = mountArea();
    await flush();

    expect(fetchNodeRateCard).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="node-rate-table"]').exists()).toBe(
      false,
    );
  });
});
