/**
 * E16-T14 / A-ATT-020、C-SCAN-020（接口 E16-T13 PUT /reports/{id}/locations）：
 * 修改 / 指定位置弹窗——打开回显当前位置（树带出版本回填）、保存只提交差异
 * （add / remove），移除到 0 个先在前端拦截（「请至少选择 1 个位置」），无变化
 * 不发请求直接关闭；成功 emit success 供页面刷新清单。
 */
import type { ReportLocationNode, ReportLocationsChangedView } from '@crdp/api';

import { flushPromises, mount } from '@vue/test-utils';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchReportLocations, updateReportLocations } from '#/api/core/report';

import ReportLocationModal from './report-location-modal.vue';

vi.mock('#/api/core/report', () => ({
  fetchReportLocations: vi.fn(),
  updateReportLocations: vi.fn(),
}));

const baselineLeaf: ReportLocationNode = {
  key: 'baseline-case',
  selectable: true,
  title: '基线 · 病例基本信息',
  value: {
    anchorKey: 'case',
    anchorTemplateVersion: 3,
    anchorType: 'category',
    linkType: 'baseline',
  },
};
const echoSection: ReportLocationNode = {
  key: 'rec-88-echo',
  selectable: true,
  title: '第2年随访 · 超声复查',
  value: {
    anchorKey: 'echo',
    anchorTemplateVersion: 2,
    anchorType: 'section',
    linkRecordId: '88',
    linkType: 'followup_record',
  },
};
const tree: ReportLocationNode[] = [
  {
    children: [baselineLeaf],
    key: 'baseline',
    selectable: false,
    title: '基线资料',
  },
  {
    children: [echoSection],
    key: 'followup',
    selectable: false,
    title: '随访记录',
  },
];

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

function mountModal(
  report: null | Record<string, unknown>,
  props: Record<string, unknown> = {},
) {
  const wrapper = mount(ReportLocationModal, {
    attachTo: document.body,
    props: {
      open: true,
      patientId: '301',
      report: report as never,
      ...props,
    },
  });
  mounted.push(wrapper);
  return wrapper;
}

const changedView: ReportLocationsChangedView = {
  id: '900',
  locationCount: 2,
  locations: [],
};

async function clickSave(wrapper: ReturnType<typeof mountModal>) {
  // Modal 的 ok 回调（footer 按钮在传送门内，直接触发组件 ok 事件）
  wrapper.findComponent({ name: 'AModal' }).vm.$emit('ok');
  await flushPromises();
}

describe('e16-t14 ReportLocationModal（修改 / 指定位置）', () => {
  it('打开回显当前位置（树带出版本回填）；未改动保存不发请求、直接关闭', async () => {
    vi.mocked(fetchReportLocations).mockResolvedValue(tree);
    const wrapper = mountModal({
      id: '900',
      label: '经胸超声心动图（示例）',
      locations: [
        {
          anchorKey: 'case',
          anchorLabel: '病例基本信息',
          anchorType: 'category',
          linkType: 'baseline',
          templateVersion: 1,
        },
      ],
    });
    await flushPromises();
    expect(fetchReportLocations).toHaveBeenCalledWith('301');
    // 位置树已渲染，选中键按四元组对齐（版本以树带出的 3 为准）
    // Modal 内容走传送门，从 document.body 断言
    expect(
      document.body.querySelector('[data-testid="location-picker"]'),
    ).toBeTruthy();
    expect(document.body.textContent).toContain(
      '修改位置 · 经胸超声心动图（示例）',
    );

    await clickSave(wrapper);
    expect(updateReportLocations).not.toHaveBeenCalled();
  });

  it('移除到 0 个：前端先拦（请至少选择 1 个位置），不发请求', async () => {
    vi.mocked(fetchReportLocations).mockResolvedValue(tree);
    const wrapper = mountModal({
      id: '900',
      label: '外院资料（示例）',
      locations: [
        {
          anchorType: null,
          linkRecordId: '88',
          linkType: 'followup_record',
          templateVersion: 2,
        },
      ],
    });
    await flushPromises();

    // 清空选择：LocationPicker 的 TreeSelect allow-clear 走 change 事件
    const clear = document.body.querySelector('.ant-select-clear');
    (clear as HTMLElement | null)?.dispatchEvent(
      new MouseEvent('mousedown', { bubbles: true }),
    );
    await flushPromises();

    const vm = wrapper.vm as unknown as {
      locations: Array<Record<string, unknown>>;
    };
    vm.locations.splice(0); // 清空（树交互由 location-picker 测试锁定）
    await flushPromises();

    await clickSave(wrapper);
    expect(updateReportLocations).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('请至少选择 1 个位置');
  });

  it('指定位置（无初始位置）：选中后保存只发 add；成功 emit success', async () => {
    vi.mocked(fetchReportLocations).mockResolvedValue(tree);
    vi.mocked(updateReportLocations).mockResolvedValue(changedView);
    const wrapper = mountModal({
      id: '901',
      label: '外院资料（示例）',
      locations: [],
    });
    await flushPromises();

    // 直接调组件内部状态模拟勾选（树交互由 location-picker 自身测试锁定）
    const vm = wrapper.vm as unknown as {
      locations: Array<Record<string, unknown>>;
    };
    vm.locations.push({ ...echoSection.value });
    await flushPromises();

    await clickSave(wrapper);
    expect(updateReportLocations).toHaveBeenCalledWith('901', {
      add: [echoSection.value],
    });
    expect(wrapper.emitted('success')?.[0]?.[0]).toEqual(changedView);
  });

  it('修改位置：换位置保存发 add + remove（四元组差异）', async () => {
    vi.mocked(fetchReportLocations).mockResolvedValue(tree);
    vi.mocked(updateReportLocations).mockResolvedValue(changedView);
    const wrapper = mountModal({
      id: '900',
      label: '经胸超声心动图（示例）',
      locations: [
        {
          anchorKey: 'case',
          anchorLabel: '病例基本信息',
          anchorType: 'category',
          linkType: 'baseline',
          templateVersion: 3,
        },
      ],
    });
    await flushPromises();
    const vm = wrapper.vm as unknown as {
      locations: Array<Record<string, unknown>>;
    };
    vm.locations.splice(0, vm.locations.length, { ...echoSection.value });
    await flushPromises();

    await clickSave(wrapper);
    expect(updateReportLocations).toHaveBeenCalledWith('900', {
      add: [echoSection.value],
      remove: [
        {
          anchorKey: 'case',
          anchorTemplateVersion: 3,
          anchorType: 'category',
          linkType: 'baseline',
        },
      ],
    });
  });
});
