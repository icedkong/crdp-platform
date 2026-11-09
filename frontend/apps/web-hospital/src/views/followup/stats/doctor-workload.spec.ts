// E19-T07 / A-DASH-009、C-DASH-010（pages.md §9 统计页 · 医护负责患者数）：按患者责任
// 医生一行（负责患者数 / 在随访 / 本月应随访 / 逾期 / 每年随访率）；点击数字进入对应
// 患者 / 任务列表（钻取条件由后端随行下发，前端只透传路由 query）；病区筛选 wardId
// 变更联动重载；随访率主列 + 小字「已随访 n / 应随访 m」与 asOf / asOfMonth 说明。
// 测试数据全部虚构。
import type { DoctorWorkloadView } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { afterEach, describe, expect, it, vi } from 'vitest';

import DoctorWorkload from './doctor-workload.vue';

const fetchDoctorWorkload = vi.fn();
const routerPush = vi.fn();

vi.mock('#/api/core/dashboard', () => ({
  fetchDoctorWorkload: (...args: unknown[]) => fetchDoctorWorkload(...args),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerPush }),
}));

const view: DoctorWorkloadView = {
  asOfMonth: '2026-11',
  asOf: '2026-11-08',
  rows: [
    {
      activeDrill: {
        query: { doctorId: '11', status: 'ACTIVE' },
        target: 'PATIENT_LIST',
      },
      activePatients: 3,
      doctorId: '11',
      doctorName: '张医生（示例）',
      followRatePct: 75,
      monthDue: 2,
      monthDueDrill: {
        query: {
          doctorId: '11',
          dueFrom: '2026-11-01',
          dueTo: '2026-11-30',
          tab: 'all',
        },
        target: 'TASK_LIST',
      },
      overdue: 1,
      overdueDrill: {
        query: { doctorId: '11', status: 'OVERDUE', tab: 'all' },
        target: 'TASK_LIST',
      },
      rateDueCount: 4,
      rateFollowedCount: 3,
      totalDrill: {
        query: { doctorId: '11', doctorName: '张医生（示例）' },
        target: 'PATIENT_LIST',
      },
      totalPatients: 4,
    },
    {
      activeDrill: {
        query: { doctorId: '12', status: 'ACTIVE' },
        target: 'PATIENT_LIST',
      },
      activePatients: 1,
      doctorId: '12',
      doctorName: '李医生（示例）',
      followRatePct: 0,
      monthDue: 0,
      monthDueDrill: {
        query: {
          doctorId: '12',
          dueFrom: '2026-11-01',
          dueTo: '2026-11-30',
          tab: 'all',
        },
        target: 'TASK_LIST',
      },
      overdue: 0,
      overdueDrill: {
        query: { doctorId: '12', status: 'OVERDUE', tab: 'all' },
        target: 'TASK_LIST',
      },
      rateDueCount: 1,
      rateFollowedCount: 0,
      totalDrill: {
        query: { doctorId: '12', doctorName: '李医生（示例）' },
        target: 'PATIENT_LIST',
      },
      totalPatients: 1,
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
  data: DoctorWorkloadView = view,
) {
  fetchDoctorWorkload.mockResolvedValue(data);
  const wrapper = mount(DoctorWorkload, {
    attachTo: document.body,
    props,
  });
  mounted.push(wrapper);
  return wrapper;
}

async function flush() {
  await new Promise((resolve) => setTimeout(resolve, 20));
}

describe('e19-T07 医护负责患者数（A-DASH-009 / C-DASH-010）', () => {
  it('表格一位医生一行：计数列可点钻取、随访率主列 + 小字已随访 / 应随访、asOf 与本月口径', async () => {
    const wrapper = mountArea();
    await flush();

    expect(fetchDoctorWorkload).toHaveBeenCalledWith({ wardId: undefined });
    const table = wrapper.find('[data-testid="doctor-workload-table"]');
    expect(table.findAll('tbody tr')).toHaveLength(2);
    const firstRow = table.findAll('tbody tr')[0]?.text() ?? '';
    expect(firstRow).toContain('张医生（示例）');
    expect(firstRow).toContain('4');
    expect(firstRow).toContain('75.0%');
    expect(firstRow).toContain('已随访 3 / 应随访 4');
    expect(wrapper.text()).toContain('随访率数据截至 2026-11-08（每日刷新）');
    expect(wrapper.text()).toContain('本月口径 2026-11');
  });

  it('点数字按后端下发的钻取目标跳转（患者列表 doctorId〔/ status〕、任务列表 tab=all）', async () => {
    const wrapper = mountArea();
    await flush();

    await wrapper.find('[data-testid="workload-total-11"]').trigger('click');
    expect(routerPush).toHaveBeenLastCalledWith({
      path: '/followup/patient',
      query: { doctorId: '11', doctorName: '张医生（示例）' },
    });
    await wrapper.find('[data-testid="workload-active-11"]').trigger('click');
    expect(routerPush).toHaveBeenLastCalledWith({
      path: '/followup/patient',
      query: { doctorId: '11', status: 'ACTIVE' },
    });
    await wrapper
      .find('[data-testid="workload-month-due-11"]')
      .trigger('click');
    expect(routerPush).toHaveBeenLastCalledWith({
      path: '/followup/task',
      query: {
        doctorId: '11',
        dueFrom: '2026-11-01',
        dueTo: '2026-11-30',
        tab: 'all',
      },
    });
    await wrapper.find('[data-testid="workload-overdue-11"]').trigger('click');
    expect(routerPush).toHaveBeenLastCalledWith({
      path: '/followup/task',
      query: { doctorId: '11', status: 'OVERDUE', tab: 'all' },
    });
    expect(routerPush).toHaveBeenCalledTimes(4);
  });

  it('病区筛选（wardId）变更联动重载（A-DASH-005 全页统计）', async () => {
    const wrapper = mountArea({ wardId: 3 });
    await flush();
    expect(fetchDoctorWorkload).toHaveBeenLastCalledWith({ wardId: 3 });

    await wrapper.setProps({ wardId: 5 });
    await flush();
    expect(fetchDoctorWorkload).toHaveBeenLastCalledWith({ wardId: 5 });
  });

  it('无数据时空状态；加载失败提示错误不白屏', async () => {
    const empty = mountArea({}, { ...view, rows: [] });
    await flush();
    expect(empty.find('[data-testid="doctor-workload-empty"]').exists()).toBe(
      true,
    );

    fetchDoctorWorkload.mockRejectedValueOnce(new Error('boom'));
    const wrapper = mountArea();
    await flush();
    expect(wrapper.find('[data-testid="doctor-workload-table"]').exists()).toBe(
      false,
    );
  });
});
