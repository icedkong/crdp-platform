import type { ImplantRecordView, ParamTrendView } from '@crdp/api';

import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchPatientImplants } from '#/api/core/implant';
import { fetchPatientParamTrend } from '#/api/core/patient';

import ParamTrend from './param-trend.vue';

/**
 * E21-T13 / C-FU-012：患者档案「参数趋势」页签组件（pages.md §4.1：折线图 + 下方
 * 历次参数表；可切换心房 / 心室、阈值 / 感知 / 阻抗 / 比例、电池；标红点高亮；
 * 器械更换处画分隔线）。锁定：默认阈值视图双腔两条折线、异常点错误色、表格按
 * 记录合并；电池视图换参数组；空态与失败态（失败可重试）。
 *
 * E24-T10 / pages.md §4.7「手机：图占满宽，表格改卡片」：手机形态图保留、
 * 历次参数表转卡片（随访日期为标题、参数逐行、标红值错误色、缺测 —），
 * 卡片分页与表格每页 20 同口径。
 */

const renderEcharts = vi.fn();

// happy-dom 视口窄，会被判为手机；桌面用例按桌面口径（手机形态用例切换 mock 状态）
const breakpointState = { isMobile: false };
vi.mock('#/reference/use-breakpoint', () => ({
  useBreakpoint: () => ({ isMobile: ref(breakpointState.isMobile) }),
}));

vi.mock('@vben/plugins/echarts', () => ({
  EchartsUI: {
    name: 'EchartsUI',
    props: { height: { default: '360px', type: String } },
    template: '<div data-testid="trend-chart" />',
  },
  useEcharts: () => ({ renderEcharts }),
}));

vi.mock('#/api/core/patient', () => ({
  fetchPatientParamTrend: vi.fn(),
}));
vi.mock('#/api/core/implant', () => ({
  fetchPatientImplants: vi.fn(),
}));

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  breakpointState.isMobile = false;
  vi.clearAllMocks();
});

function trendView(): ParamTrendView {
  return {
    series: [
      {
        label: null,
        param: 'ra_threshold',
        points: [
          { abnormal: false, date: '2026-01-12', recordId: '901', value: 0.6 },
          { abnormal: true, date: '2026-07-15', recordId: '902', value: 1.1 },
        ],
        unit: 'V',
      },
      {
        label: null,
        param: 'rv_threshold',
        points: [
          { abnormal: false, date: '2026-01-12', recordId: '901', value: 0.8 },
          { abnormal: false, date: '2026-07-15', recordId: '902', value: 0.7 },
        ],
        unit: 'V',
      },
      {
        label: null,
        param: 'battery_voltage',
        points: [
          { abnormal: false, date: '2026-01-12', recordId: '901', value: 3.1 },
        ],
        unit: 'V',
      },
    ],
  };
}

function implantRecord(
  id: string,
  implantType: string,
  implantDate: string,
): ImplantRecordView {
  return {
    current: implantType === 'FIRST',
    deviceType: 'PACEMAKER',
    deviceTypeLabel: '起搏器',
    endDate: null,
    endReason: null,
    id,
    implantDate,
    implantNo: 1,
    implantType,
    implantTypeLabel: null,
    leads: [],
    patientId: '301',
    status: 'ACTIVE',
    statusLabel: null,
    version: 1,
  };
}

async function mountPanel(
  view: null | ParamTrendView,
  implants: ImplantRecordView[] = [],
) {
  vi.mocked(fetchPatientParamTrend).mockResolvedValue(view ?? { series: [] });
  vi.mocked(fetchPatientImplants).mockResolvedValue({ items: implants });
  const wrapper = mount(ParamTrend, {
    attachTo: document.body,
    props: { patientId: '301' },
  });
  mounted.push(wrapper);
  await flushPromises();
  return wrapper;
}

describe('paramTrend 参数趋势页签', () => {
  it('默认阈值视图：心房 / 心室两条折线，异常点错误色，更换处画分隔线', async () => {
    const wrapper = await mountPanel(trendView(), [
      implantRecord('11', 'FIRST', '2025-12-01'),
      implantRecord('12', 'REPLACE', '2026-06-01'),
    ]);
    expect(fetchPatientParamTrend).toHaveBeenCalledWith('301');
    expect(fetchPatientImplants).toHaveBeenCalledWith('301');

    // 类别与腔室切换（pages.md §4.1）
    expect(wrapper.text()).toContain('阈值');
    expect(wrapper.text()).toContain('起搏比例');
    expect(wrapper.text()).toContain('电池');
    expect(wrapper.text()).toContain('全部');
    expect(wrapper.text()).toContain('心室');

    const option = renderEcharts.mock.lastCall?.[0];
    const series = option.series as Array<{
      data: Array<null | number | { itemStyle: { color: string } }>;
      name: string;
    }>;
    expect(series.map((item) => item.name)).toEqual([
      '心房起搏阈值',
      '心室起搏阈值',
    ]);
    expect(series[0]?.data[1]).toEqual({
      itemStyle: { color: '#FF4D4F' },
      value: 1.1,
    });
    const first = option.series[0] as { markLine?: { data: unknown[] } };
    expect(first.markLine?.data).toHaveLength(1);

    // 历次参数表：两行（按随访日期升序），列头带单位，异常单元格标红
    const table = wrapper.find('[data-testid="trend-table"]');
    expect(table.exists()).toBe(true);
    const html = table.html();
    expect(html).toContain('2026-01-12');
    expect(html).toContain('心房起搏阈值(V)');
    expect(html).toContain('abnormal');
    expect(table.findAll('tbody tr')).toHaveLength(2);
  });

  it('电池视图：隐藏腔室切换、改选电池电压（腔室参数不出现）', async () => {
    const wrapper = await mountPanel(trendView());
    const kinds = wrapper.find('[data-testid="trend-kind"]');
    await kinds.findAll('label')[4]?.trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="trend-chamber"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="trend-battery-param"]').exists()).toBe(
      true,
    );
    const option = renderEcharts.mock.lastCall?.[0];
    const series = option.series as Array<{ name: string }>;
    expect(series.map((item) => item.name)).toEqual(['电池电压']);
    // 表格同时展示电池电压与 AT/AF 负荷两列
    expect(wrapper.find('[data-testid="trend-table"]').html()).toContain(
      '电池电压(V)',
    );
  });

  it('空数据：显示「暂无程控参数记录」空态', async () => {
    const wrapper = await mountPanel({ series: [] });
    expect(wrapper.text()).toContain('暂无程控参数记录');
    expect(wrapper.find('[data-testid="trend-table"]').exists()).toBe(false);
  });

  it('加载失败：空态带重试，重试成功后渲染', async () => {
    vi.mocked(fetchPatientParamTrend)
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce(trendView());
    vi.mocked(fetchPatientImplants).mockResolvedValue({ items: [] });
    const wrapper = mount(ParamTrend, { props: { patientId: '301' } });
    mounted.push(wrapper);
    await flushPromises();

    expect(wrapper.text()).toContain('参数趋势加载失败');
    await wrapper.find('[data-testid="trend-retry"]').trigger('click');
    await flushPromises();
    expect(fetchPatientParamTrend).toHaveBeenCalledTimes(2);
    expect(wrapper.find('[data-testid="trend-table"]').exists()).toBe(true);
  });
});

describe('paramTrend 手机形态（E24-T10 / §4.7 图占满宽、表格改卡片）', () => {
  it('图保留，历次参数表转卡片：随访日期为标题、参数逐行、标红值错误色、缺测 —', async () => {
    breakpointState.isMobile = true;
    const wrapper = await mountPanel(trendView());

    // 图占满宽（折线图仍在，容器为块级通栏）
    expect(wrapper.find('[data-testid="trend-chart"]').exists()).toBe(true);
    // 表格转卡片：不再渲染表格
    expect(wrapper.find('[data-testid="trend-table"]').exists()).toBe(false);
    const cards = wrapper.findAll('[data-testid="trend-card"]');
    expect(cards).toHaveLength(2);

    // 第一张卡：2026-01-12 两条参数（0.6 / 0.8），无标红
    expect(cards[0]?.text()).toContain('2026-01-12');
    expect(cards[0]?.text()).toContain('心房起搏阈值(V)');
    expect(cards[0]?.text()).toContain('0.6');
    expect(cards[0]?.text()).toContain('心室起搏阈值(V)');
    expect(cards[0]?.text()).toContain('0.8');

    // 第二张卡：07-15 心房阈值标红（C-FU-010 错误色类）、异常值呈现
    expect(cards[1]?.text()).toContain('2026-07-15');
    expect(cards[1]?.text()).toContain('1.1');
    expect(cards[1]?.find('.abnormal')).toBeTruthy();
  });

  it('卡片分页与表格每页 20 同口径：21 次随访两页，翻页后见第 21 条，切类别回第 1 页', async () => {
    breakpointState.isMobile = true;
    const points = Array.from({ length: 21 }, (_, index) => ({
      abnormal: false,
      date: `${2024 + Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}-15`,
      recordId: String(1000 + index),
      value: 0.5,
    }));
    vi.mocked(fetchPatientParamTrend).mockResolvedValue({
      series: [{ label: null, param: 'ra_threshold', points, unit: 'V' }],
    });
    vi.mocked(fetchPatientImplants).mockResolvedValue({ items: [] });
    const wrapper = mount(ParamTrend, {
      attachTo: document.body,
      props: { patientId: '301' },
    });
    mounted.push(wrapper);
    await flushPromises();

    let cards = wrapper.findAll('[data-testid="trend-card"]');
    expect(cards).toHaveLength(20);
    const pagination = wrapper.find('[data-testid="trend-card-pagination"]');
    expect(pagination.exists()).toBe(true);
    expect(wrapper.text()).toContain('共 21 次');

    // 翻到第 2 页：只剩最晚一次随访
    await pagination.find('.ant-pagination-next button').trigger('click');
    await flushPromises();
    cards = wrapper.findAll('[data-testid="trend-card"]');
    expect(cards).toHaveLength(1);

    // 切类别（阈值 → 感知无数据 → 切回阈值）卡片回到第 1 页 20 条
    const kinds = wrapper.find('[data-testid="trend-kind"]');
    await kinds.findAll('label')[1]?.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('暂无程控参数记录');
    await kinds.findAll('label')[0]?.trigger('click');
    await flushPromises();
    expect(wrapper.findAll('[data-testid="trend-card"]')).toHaveLength(20);
  });
});
