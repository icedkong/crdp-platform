// E19-T03 / A-DASH-003、A-DASH-004、A-DASH-008、C-DASH-003（pages.md §2 首页任务区）：
// 「看板 | 任务表」切换、看板四列与每列前 20 张卡片 +「查看全部」、看板筛选
// （我的 / 本病区 / 全部 + 负责人）、任务表三页签数量、行内快速操作按权限显隐、
// 病区筛选（wardId）变更联动重载。测试数据全部虚构（张*明（示例）等）。
import type { TaskBoardView, TaskTableView } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { Segmented } from 'ant-design-vue';
import { afterEach, describe, expect, it, vi } from 'vitest';

import TaskArea from './task-area.vue';

const fetchTaskBoardCard = vi.fn();
const fetchTaskTableCard = vi.fn();
const fetchDoctorOptions = vi.fn();
const startTask = vi.fn();
const acknowledgeTask = vi.fn();

vi.mock('#/api/core/dashboard', () => ({
  fetchDashboardWards: vi.fn().mockResolvedValue({ wards: [] }),
  fetchTaskBoardCard: (...args: unknown[]) => fetchTaskBoardCard(...args),
  fetchTaskTableCard: (...args: unknown[]) => fetchTaskTableCard(...args),
}));
vi.mock('#/api/core/patient', () => ({
  fetchDoctorOptions: (...args: unknown[]) => fetchDoctorOptions(...args),
}));
vi.mock('#/api/core/task', () => ({
  acknowledgeTask: (...args: unknown[]) => acknowledgeTask(...args),
  batchAssignTasks: vi.fn(),
  startTask: (...args: unknown[]) => startTask(...args),
}));
vi.mock('#/reference/use-permission', () => ({
  usePermission: () => ({
    hasPermission: (code: string) =>
      code === 'followup:record:edit' || code === 'followup:task:assign',
  }),
}));

const board: TaskBoardView = {
  columns: [
    {
      drill: { query: { status: 'PENDING', tab: 'all' }, target: 'TASK_LIST' },
      status: 'PENDING',
      tasks: [
        {
          assigneeName: '王医生（示例）',
          id: '901',
          lastContactResult: 'NOT_REACHED',
          mrn: 'MRN*',
          nodeName: '第4年随访',
          occurrence: 4,
          patientId: '301',
          patientName: '张*明（示例）',
          planDate: '2026-11-05',
          recordId: null,
          status: 'PENDING',
          taskType: 'FORM',
          wardName: '示例二病区',
          windowEnd: '2026-11-12',
          windowStart: '2026-10-29',
        },
      ],
      title: '待随访',
      total: 25,
    },
    {
      drill: {
        query: { status: 'IN_PROGRESS', tab: 'all' },
        target: 'TASK_LIST',
      },
      status: 'IN_PROGRESS',
      tasks: [],
      title: '进行中',
      total: 0,
    },
    {
      drill: { query: { status: 'OVERDUE', tab: 'all' }, target: 'TASK_LIST' },
      status: 'OVERDUE',
      tasks: [],
      title: '逾期',
      total: 3,
    },
    {
      drill: {
        query: {
          completedFrom: '2026-11-01',
          completedTo: '2026-11-30',
          status: 'COMPLETED',
          tab: 'all',
        },
        target: 'TASK_LIST',
      },
      status: 'COMPLETED',
      tasks: [],
      title: '本月已完成',
      total: 8,
    },
  ],
  doneColumn: '本月已完成',
  doneFrom: '2026-11-01',
  doneTo: '2026-11-30',
  scope: 'MINE',
};

const tableView: TaskTableView = {
  items: [
    {
      assigneeName: '王医生（示例）',
      id: '901',
      mrn: 'MRN*',
      nodeName: '第4年随访',
      occurrence: 4,
      patientId: '301',
      patientName: '张*明（示例）',
      planDate: '2026-11-05',
      recordId: null,
      status: 'PENDING',
      taskType: 'FORM',
      wardName: '示例二病区',
      windowEnd: '2026-11-12',
      windowStart: '2026-10-29',
    },
  ],
  tab: 'TODAY',
  tabs: [
    {
      drill: { query: { tab: 'all' }, target: 'TASK_LIST' },
      tab: 'TODAY',
      title: '今日任务',
      total: 4,
    },
    {
      drill: { query: { status: 'OVERDUE', tab: 'all' }, target: 'TASK_LIST' },
      tab: 'OVERDUE',
      title: '超时任务',
      total: 2,
    },
    {
      drill: { query: { tab: 'mine' }, target: 'TASK_LIST' },
      tab: 'MINE',
      title: '我负责的任务',
      total: 7,
    },
  ],
  today: '2026-11-08',
  total: 4,
};

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

function mountArea(props: Record<string, unknown> = {}) {
  fetchTaskBoardCard.mockResolvedValue(board);
  fetchTaskTableCard.mockResolvedValue(tableView);
  fetchDoctorOptions.mockResolvedValue({
    doctors: [{ displayName: '王医生（示例）', id: '9' }],
  });
  const wrapper = mount(TaskArea, {
    attachTo: document.body,
    props,
  });
  mounted.push(wrapper);
  return wrapper;
}

async function flush() {
  await new Promise((resolve) => setTimeout(resolve, 20));
}

describe('e19-t03 首页任务区 · 看板（A-DASH-003 / A-DASH-004）', () => {
  it('四列标题与总数；卡片显示脱敏姓名 / 病区 / 节点 / 窗口 / 负责人 / 最近联系', async () => {
    const wrapper = mountArea();
    await flush();

    expect(fetchTaskBoardCard).toHaveBeenCalledWith(
      expect.objectContaining({ assigneeId: undefined, scope: 'MINE' }),
    );
    const text = document.body.textContent ?? '';
    expect(text).toContain('待随访');
    expect(text).toContain('进行中');
    expect(text).toContain('逾期');
    expect(text).toContain('本月已完成');
    expect(text).toContain('张*明（示例）');
    expect(text).toContain('示例二病区');
    expect(text).toContain('第4年随访');
    expect(text).toContain('未接通');
    expect(text).toContain('王医生（示例）');
    // 25 > 前 20 张卡片（本测 1 张）→「查看全部」带列总数
    expect(text).toContain('查看全部（25）');
    expect(
      wrapper.find('[data-testid="board-view-all-PENDING"]').exists(),
    ).toBe(true);
  });

  it('看板筛选切换：scope 切到本病区后按新参数重载（A-DASH-004）', async () => {
    const wrapper = mountArea();
    await flush();
    expect(fetchTaskBoardCard).toHaveBeenLastCalledWith(
      expect.objectContaining({ scope: 'MINE' }),
    );

    // 第 2 个 Segmented = 看板筛选（第 1 个是「看板 | 任务表」切换）
    const scope = wrapper.findAllComponents(Segmented)[1];
    (
      scope.vm as unknown as { $emit: (event: string, value: unknown) => void }
    ).$emit('update:value', 'WARD');
    await wrapper.vm.$nextTick();
    await flush();
    expect(fetchTaskBoardCard).toHaveBeenLastCalledWith(
      expect.objectContaining({ scope: 'WARD' }),
    );
  });

  it('卡片快速操作：记录联系 / 录入随访 / 改派（A-DASH-003）', async () => {
    mountArea();
    await flush();

    expect(
      document.body.querySelector('[data-testid="dashboard-board-enter-901"]'),
    ).not.toBeNull();
    expect(
      document.body.querySelector('[data-testid="dashboard-board-assign-901"]'),
    ).not.toBeNull();
    // 记录联系按钮存在（打开 ContactLogModal）
    expect((document.body.textContent ?? '').includes('记录联系')).toBe(true);
  });

  it('病区筛选联动：wardId 变更后按新参数重载看板（A-DASH-005）', async () => {
    const wrapper = mountArea();
    await flush();
    expect(fetchTaskBoardCard).toHaveBeenLastCalledWith(
      expect.objectContaining({ wardId: undefined }),
    );

    await wrapper.setProps({ wardId: 2 });
    await flush();
    expect(fetchTaskBoardCard).toHaveBeenLastCalledWith(
      expect.objectContaining({ wardId: 2 }),
    );
  });
});

describe('e19-t03 首页任务区 · 任务表（A-DASH-008）', () => {
  it('三页签与数量、当前页签行、查看全部', async () => {
    const wrapper = mountArea();
    await flush();

    // 切到任务表（第 1 个 Segmented =「看板 | 任务表」）
    const mode = wrapper.findComponent(Segmented);
    (
      mode.vm as unknown as { $emit: (event: string, value: unknown) => void }
    ).$emit('update:value', 'table');
    await wrapper.vm.$nextTick();
    await flush();

    expect(fetchTaskTableCard).toHaveBeenCalledWith({ tab: 'TODAY' });
    const text = document.body.textContent ?? '';
    expect(text).toContain('今日任务');
    expect(text).toContain('超时任务');
    expect(text).toContain('我负责的任务');
    expect(text).toContain('张*明（示例）');
    expect(
      wrapper.find('[data-testid="dashboard-task-row-901"]').exists(),
    ).toBe(true);
    // 行内可直接进入随访工作台（录入）
    expect(
      wrapper.find('[data-testid="dashboard-row-enter-901"]').exists(),
    ).toBe(true);
    expect(wrapper.find('[data-testid="table-view-all"]').exists()).toBe(true);
  });

  it('站内提醒行显示「完成提醒」而非录入（A-FU-026，今日任务含站内提醒）', async () => {
    const wrapper = mountArea();
    await flush();
    // mountArea 内置默认响应：换成站内提醒行后再切任务表（加载发生在切换时）
    fetchTaskTableCard.mockResolvedValue({
      ...tableView,
      items: [
        {
          ...(tableView.items[0] as (typeof tableView.items)[number]),
          status: 'PENDING',
          taskType: 'REMINDER',
        },
      ],
    });

    const mode = wrapper.findComponent(Segmented);
    (
      mode.vm as unknown as { $emit: (event: string, value: unknown) => void }
    ).$emit('update:value', 'table');
    await wrapper.vm.$nextTick();
    await flush();

    expect(wrapper.find('[data-testid="dashboard-row-ack-901"]').exists()).toBe(
      true,
    );
    expect(
      wrapper.find('[data-testid="dashboard-row-enter-901"]').exists(),
    ).toBe(false);
  });
});
