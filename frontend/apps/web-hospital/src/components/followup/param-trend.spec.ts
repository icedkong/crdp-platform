import type { ImplantRecordView, ParamTrendView } from '@crdp/api';

import { flushPromises, mount } from '@vue/test-utils';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchPatientImplants } from '#/api/core/implant';
import { fetchPatientParamTrend } from '#/api/core/patient';

import ParamTrend from './param-trend.vue';

/**
 * E21-T13 / C-FU-012：患者档案「参数趋势」页签组件（pages.md §4.1：折线图 + 下方
 * 历次参数表；可切换心房 / 心室、阈值 / 感知 / 阻抗 / 比例、电池；标红点高亮；
 * 器械更换处画分隔线）。锁定：默认阈值视图双腔两条折线、异常点错误色、表格按
 * 记录合并；电池视图换参数组；空态与失败态（失败可重试）。
 */

const renderEcharts = vi.fn();

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
