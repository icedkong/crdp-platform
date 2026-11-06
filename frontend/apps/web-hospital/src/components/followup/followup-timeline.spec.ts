import type {
  FormSchema,
  FormSchemaField,
  PatientTimelineEntry,
} from '@crdp/api';

import { mount } from '@vue/test-utils';
import { nextTick, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchFormSchema } from '#/api/core/form';
import { fetchRecordDetail } from '#/api/core/task';

import FollowupTimeline from './followup-timeline.vue';

// happy-dom 视口窄，会被判为手机；表格视图测试按桌面口径（手机形态用例切换 mock 状态）
const breakpointState = { isMobile: false };
vi.mock('#/reference/use-breakpoint', () => ({
  useBreakpoint: () => ({ isMobile: ref(breakpointState.isMobile) }),
}));

// E13-T11 展开记录：只读详情 + 模板（分组条数据源）
vi.mock('#/api/core/form', () => ({
  fetchFormSchema: vi.fn(),
}));
vi.mock('#/api/core/task', () => ({
  fetchRecordDetail: vi.fn(),
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
  document.body.innerHTML = '';
  breakpointState.isMobile = false;
  vi.clearAllMocks();
});

function mountTimeline(entries: PatientTimelineEntry[]) {
  const wrapper = mount(FollowupTimeline, {
    // attachTo：E13-T11 分组条跳转走 document.getElementById（页面锚点同源）
    attachTo: document.body,
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

describe('uNPLANNED 节点（E15-T06 / C-FU-022 单独成节点）', () => {
  it('显示计划外随访标识、随访日期与「不影响后续节点」备注，可打开表单', async () => {
    const wrapper = mountTimeline([
      {
        canOpen: true,
        createType: 'UNPLANNED',
        date: '2026-06-10',
        doneDate: '2026-06-10',
        kind: 'UNPLANNED',
        label: '计划外随访',
        note: '计划外随访（随访时间点「其他」），不影响后续节点；不作对比对象',
        progressDone: 1,
        progressTotal: 1,
        recordId: '811',
        status: 'COMPLETED',
      },
    ]);
    const node = wrapper.find('[data-testid="timeline-node-UNPLANNED"]');
    expect(node.text()).toContain('计划外随访');
    expect(node.text()).toContain('随访日期 2026-06-10');
    expect(node.text()).toContain('不影响后续节点');
    expect(node.text()).toContain('已完成');
    await node.trigger('click');
    expect(wrapper.emitted('open')).toHaveLength(1);
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

/* ----------------------------------------------------------------------
 * E13-T11 / A-PAT-021、C-PAT-018 · 随访记录展开分组条（components.md §9「展开」）：
 * 已提交记录「展开」→ 卡片内只读内容（FormRenderer readonly）+ 顶部吸顶分组条
 * （分组名、已填 / 应填，未填完橙色）；点击滚动到该分组；平板横向滚动、手机下拉选择。
 */
function f(
  partial: Partial<FormSchemaField> & { key: string; label: string },
): FormSchemaField {
  return {
    code: partial.key,
    required: false,
    sensitive: false,
    storage: 'json',
    type: 'text',
    ...partial,
  };
}

const recordSchema: FormSchema = {
  categories: [
    {
      key: 'overview',
      label: '随访概况',
      order: 10,
      fields: [
        f({ key: 'f501', label: '随访日期', type: 'date', required: true }),
        f({ key: 'f502', label: '随访方式', type: 'text', required: true }),
      ],
    },
    {
      key: 'echo',
      label: '超声复查',
      order: 20,
      fields: [
        f({
          key: 'f520',
          label: 'EF 射血分数',
          type: 'number',
          required: true,
        }),
      ],
    },
  ],
  code: 'fu_a_year',
  name: '年度随访（示例）',
  rules: [],
  usage: 'FOLLOWUP',
  version: 3,
};

const recordDetail = {
  context: null,
  data: { f501: '2026-11-02', f502: '电话' },
  dataMeta: {},
  formCode: 'fu_a_year',
  formVersion: 3,
  id: '701',
  status: 'SUBMITTED' as const,
  version: 2,
};

function mockRecordLoading() {
  vi.mocked(fetchRecordDetail).mockResolvedValue(recordDetail);
  vi.mocked(fetchFormSchema).mockResolvedValue(recordSchema);
}

describe('e13-t11 随访记录展开分组条（components.md §9「展开」+ §6）', () => {
  it('有 recordId 的节点显示「展开」：点击展开只读内容 + 顶部分组条（已填 / 应填）', async () => {
    mockRecordLoading();
    const wrapper = mountTimeline([followupNode()]);
    const expandButton = wrapper.find(
      '[data-testid="record-expand-toggle-701"]',
    );
    expect(expandButton.exists()).toBe(true);
    expect(expandButton.text()).toContain('展开');

    await expandButton.trigger('click');
    expect(wrapper.emitted('update:expandedRecordId')?.at(-1)?.[0]).toBe('701');
    await wrapper.setProps({ expandedRecordId: '701' });
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(fetchRecordDetail).toHaveBeenCalledWith('701');
    expect(fetchFormSchema).toHaveBeenCalledWith('fu_a_year', 3);

    const panel = wrapper.find('[data-testid="record-expand-701"]');
    expect(panel.exists()).toBe(true);
    // 分组条：分组名 + 已填 / 应填（FormRenderer progress 事件实时上抛）
    const bar = panel.find('[data-testid="category-bar"]');
    expect(bar.exists()).toBe(true);
    expect(bar.text()).toContain('随访概况');
    expect(bar.text()).toContain('2/2');
    expect(bar.text()).toContain('超声复查');
    expect(bar.text()).toContain('0/1');
    // 只读内容来自 FormRenderer readonly
    expect(panel.text()).toContain('EF 射血分数');
    // 按钮变为「收起」
    expect(
      wrapper.find('[data-testid="record-expand-toggle-701"]').text(),
    ).toContain('收起');
  });

  it('未填完的分组数字为橙色（colors.warning）', async () => {
    mockRecordLoading();
    const wrapper = mountTimeline([followupNode()]);
    await wrapper
      .find('[data-testid="record-expand-toggle-701"]')
      .trigger('click');
    await wrapper.setProps({ expandedRecordId: '701' });
    await new Promise((resolve) => setTimeout(resolve, 0));

    const counts = wrapper.findAll('[data-testid="record-expand-701"] .count');
    expect(counts).toHaveLength(2);
    expect(counts[0]?.attributes('style') ?? '').not.toContain('#FA8C16');
    expect(counts[1]?.attributes('style')).toContain('#FA8C16');
  });

  it('点分组 chip 滚动到该分组（renderer.scrollTo）', async () => {
    mockRecordLoading();
    const wrapper = mountTimeline([followupNode()]);
    await wrapper
      .find('[data-testid="record-expand-toggle-701"]')
      .trigger('click');
    await wrapper.setProps({ expandedRecordId: '701' });
    await new Promise((resolve) => setTimeout(resolve, 0));

    const chips = wrapper.findAll('[data-testid="record-expand-701"] .chip');
    expect(chips).toHaveLength(2);
    await chips[1]?.trigger('click');

    const echoSection = document.querySelector('#crdp-anchor-echo');
    expect(echoSection).not.toBeNull();
    expect(echoSection?.className).toContain('crdp-anchor-flash');
  });

  it('再点「收起」发出 null；计划中节点（无 recordId）没有展开按钮', async () => {
    const wrapper = mountTimeline([
      followupNode(),
      {
        canOpen: false,
        date: '2027-11-05',
        kind: 'PLANNED',
        planDate: '2027-11-05',
        status: 'PLANNED',
      },
    ]);
    expect(
      wrapper.find('[data-testid="record-expand-toggle-701"]').exists(),
    ).toBe(true);
    expect(
      wrapper.findAll('[data-testid^="record-expand-toggle"]'),
    ).toHaveLength(1);

    // 第一次点「展开」→ '701'；再点（已展开，按钮变「收起」）→ null
    const toggle = wrapper.find('[data-testid="record-expand-toggle-701"]');
    await toggle.trigger('click');
    expect(wrapper.emitted('update:expandedRecordId')?.at(-1)?.[0]).toBe('701');
    await wrapper.setProps({ expandedRecordId: '701' });
    await wrapper
      .find('[data-testid="record-expand-toggle-701"]')
      .trigger('click');
    expect(wrapper.emitted('update:expandedRecordId')?.at(-1)?.[0]).toBe(null);
  });

  it('手机：分组条改「跳到分组」下拉（不渲染横向 chip 条）', async () => {
    breakpointState.isMobile = true;
    mockRecordLoading();
    const wrapper = mountTimeline([followupNode()]);
    await wrapper
      .find('[data-testid="record-expand-toggle-701"]')
      .trigger('click');
    await wrapper.setProps({ expandedRecordId: '701' });
    await new Promise((resolve) => setTimeout(resolve, 0));

    const panel = wrapper.find('[data-testid="record-expand-701"]');
    expect(panel.find('[data-testid="category-jump-trigger"]').exists()).toBe(
      true,
    );
    expect(panel.find('[data-testid="category-bar"]').exists()).toBe(false);
  });

  it('加载失败显示错误与重试，不阻塞时间轴', async () => {
    vi.mocked(fetchRecordDetail).mockRejectedValue(new Error('boom'));
    vi.mocked(fetchFormSchema).mockResolvedValue(recordSchema);
    const wrapper = mountTimeline([followupNode()]);
    await wrapper
      .find('[data-testid="record-expand-toggle-701"]')
      .trigger('click');
    await wrapper.setProps({ expandedRecordId: '701' });
    await new Promise((resolve) => setTimeout(resolve, 0));

    const panel = wrapper.find('[data-testid="record-expand-701"]');
    expect(panel.text()).toContain('记录加载失败');
    const retry = panel.find('[data-testid="record-expand-retry"]');
    expect(retry.exists()).toBe(true);

    mockRecordLoading();
    await retry.trigger('click');
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(
      wrapper
        .find('[data-testid="record-expand-701"]')
        .find('[data-testid="category-bar"]')
        .exists(),
    ).toBe(true);
  });
});
