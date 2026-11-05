import type { PatientTimelineEntry } from '@crdp/api';

import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import FollowupTimeline from './followup-timeline.vue';

// happy-dom 视口窄，会被判为手机；表格视图测试按桌面口径
vi.mock('#/reference/use-breakpoint', () => ({
  useBreakpoint: () => ({ isMobile: false }),
}));

/**
 * E15-T07 / A-FU-023、C-FU-023 · 随访时间轴组件单测（components.md §9）。
 *
 * 锁定：FOLLOWUP 节点显示计划日期 / 窗口 / 完成日期 / 完成进度 x/y；PLANNED 节点
 * 显示「计划中」并置灰不可点（canOpen=false，任务开启日才创建）；BACKFILL 节点带
 * 补录标识；点击 canOpen 节点 emit open（父页新标签页打开表单）。
 */
const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  localStorage.removeItem('crdp:fu-view');
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
});

function mountTimeline(entries: PatientTimelineEntry[]) {
  const wrapper = mount(FollowupTimeline, {
    props: { entries, patientId: '301' },
  });
  mounted.push(wrapper);
  return wrapper;
}

function followupNode(
  patch: Partial<PatientTimelineEntry> = {},
): PatientTimelineEntry {
  return {
    canOpen: true,
    completeKind: 'ON_TIME',
    createType: 'NORMAL',
    date: '2026-11-05',
    doneDate: '2026-11-02',
    kind: 'FOLLOWUP',
    label: '年度随访（示例）',
    planDate: '2026-11-05',
    progressDone: 1,
    progressTotal: 1,
    recordId: '701',
    status: 'COMPLETED',
    windowEnd: '2026-11-12',
    windowStart: '2026-10-29',
    ...patch,
  };
}

describe('fOLLOWUP 节点（A-FU-023 验收：子计划进度）', () => {
  it('显示计划日期 / 窗口 / 完成日期与进度 x/y', () => {
    const wrapper = mountTimeline([followupNode()]);
    const node = wrapper.find('[data-testid="timeline-node-FOLLOWUP"]');
    expect(node.exists()).toBe(true);
    const text = node.text();
    expect(text).toContain('年度随访（示例）');
    expect(text).toContain('计划 2026-11-05');
    expect(text).toContain('窗口 2026-10-29 ~ 2026-11-12');
    expect(text).toContain('完成 2026-11-02');
    expect(text).toContain('进度 1/1');
    expect(text).toContain('已完成');
  });

  it('逾期完成节点显示「已完成（逾期完成）」细分', () => {
    const wrapper = mountTimeline([
      followupNode({ completeKind: 'OVERDUE', doneDate: '2026-11-20' }),
    ]);
    expect(
      wrapper.find('[data-testid="timeline-node-FOLLOWUP"]').text(),
    ).toContain('逾期完成');
  });

  it('点击 canOpen 节点 emit open（父页新标签页打开表单）', async () => {
    const wrapper = mountTimeline([followupNode()]);
    await wrapper
      .find('[data-testid="timeline-node-FOLLOWUP"]')
      .trigger('click');
    expect(wrapper.emitted('open')?.[0]?.[0]).toMatchObject({
      kind: 'FOLLOWUP',
      recordId: '701',
    });
  });
});

describe('pLANNED 节点（A-FU-023 验收：下次计划日期）', () => {
  it('显示「下次随访 / 计划中」并置灰不可点', async () => {
    const wrapper = mountTimeline([
      {
        canOpen: false,
        date: '2027-11-05',
        kind: 'PLANNED',
        note: '计划中，任务开启日才创建',
        planDate: '2027-11-05',
        status: 'PLANNED',
      },
    ]);
    const node = wrapper.find('[data-testid="timeline-node-PLANNED"]');
    expect(node.text()).toContain('下次随访');
    expect(node.text()).toContain('计划中');
    expect(node.text()).toContain('2027-11-05');
    expect(node.classes()).toContain('disabled');
    await node.trigger('click');
    expect(wrapper.emitted('open')).toBeUndefined();
  });
});

describe('bACKFILL 节点（A-FU-014 / C-FU-023 单独标识）', () => {
  it('显示历史补录标识与实际随访日期', () => {
    const wrapper = mountTimeline([
      {
        canOpen: true,
        createType: 'BACKFILL',
        date: '2024-05-10',
        doneDate: '2024-05-10',
        kind: 'BACKFILL',
        label: '历史补录',
        note: '补录，随访日期 2024-05-10，2025-01-15 录入',
        progressDone: 1,
        progressTotal: 1,
        recordId: '801',
        status: 'COMPLETED',
      },
    ]);
    const text = wrapper.find('[data-testid="timeline-node-BACKFILL"]').text();
    expect(text).toContain('历史补录');
    expect(text).toContain('随访日期 2024-05-10');
    expect(text).toContain('补录，随访日期 2024-05-10');
  });
});

describe('既有节点（E13-T05 回归）', () => {
  it('出院 / 状态节点不显示进度、不 emit open', async () => {
    const wrapper = mountTimeline([
      { date: '2026-03-08', kind: 'DISCHARGE', label: '出院' },
      {
        date: '2026-09-01',
        kind: 'STATUS',
        note: '心源性死亡',
        status: 'DEAD',
      },
    ]);
    const discharge = wrapper.find('[data-testid="timeline-node-DISCHARGE"]');
    expect(discharge.text()).toContain('出院');
    expect(discharge.text()).not.toContain('进度');
    await discharge.trigger('click');
    expect(wrapper.emitted('open')).toBeUndefined();
  });

  it('表格视图含计划 / 窗口 / 完成日期 / 进度列（components.md §9 表格形态）', async () => {
    localStorage.setItem('crdp:fu-view', 'table');
    const wrapper = mountTimeline([followupNode()]);
    await nextTick(); // onMounted 读记忆后异步重渲染一次
    const headers = wrapper
      .findAll('th')
      .map((th) => th.text())
      .join(' ');
    expect(headers).toContain('计划日期');
    expect(headers).toContain('窗口');
    expect(headers).toContain('完成日期');
    expect(headers).toContain('进度');
    expect(wrapper.text()).toContain('进度 1/1');
  });
});
