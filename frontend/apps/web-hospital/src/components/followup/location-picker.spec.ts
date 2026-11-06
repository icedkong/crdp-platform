// E16-T02 / A-ATT-002、C-SCAN-010（components.md §8）：位置选择——预选进入位置、
// 至少 1 个（错误文案「请至少选择 1 个位置」）、整条 vs 分组（勾分组不自动勾整条）、
// 模板版本随选项原样回传
import type { ReportLocationNode } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { TreeSelect } from 'ant-design-vue';
import { afterEach, describe, expect, it } from 'vitest';

import LocationPicker from './location-picker.vue';

// 树：基线 2 分类 + 随访记录 1 条（整条 + 2 分组），叶子带模板版本
const options: ReportLocationNode[] = [
  {
    children: [
      {
        key: 'baseline:basic',
        selectable: true,
        title: '基本信息',
        value: {
          anchorKey: 'basic',
          anchorTemplateVersion: 3,
          anchorType: 'category',
          linkType: 'baseline',
        },
      },
      {
        key: 'baseline:case',
        selectable: true,
        title: '病例基本信息',
        value: {
          anchorKey: 'case',
          anchorTemplateVersion: 3,
          anchorType: 'category',
          linkType: 'baseline',
        },
      },
    ],
    key: 'baseline',
    selectable: false,
    title: '基线资料',
  },
  {
    children: [
      {
        children: [
          {
            key: 'rec:88:sec:overview',
            selectable: true,
            title: '随访概况',
            value: {
              anchorKey: 'overview',
              anchorTemplateVersion: 2,
              anchorType: 'section',
              linkRecordId: '88',
              linkType: 'followup_record',
            },
          },
          {
            key: 'rec:88:sec:echo',
            selectable: true,
            title: '超声复查',
            value: {
              anchorKey: 'echo',
              anchorTemplateVersion: 2,
              anchorType: 'section',
              linkRecordId: '88',
              linkType: 'followup_record',
            },
          },
        ],
        key: 'rec:88',
        selectable: true,
        title: '第2年随访 2026-06-10',
        value: {
          anchorTemplateVersion: 2,
          anchorType: null,
          linkRecordId: '88',
          linkType: 'followup_record',
        },
      },
    ],
    key: 'followup',
    selectable: false,
    title: '随访记录',
  },
];

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
});

/** 树里某叶子的位置值（避免非空断言；用例树结构固定）。 */
function leafValue(key: string): ReportLocationInput {
  const queue: ReportLocationNode[] = [...options];
  while (queue.length > 0) {
    const node = queue.shift();
    if (!node) break;
    if (node.key === key && node.value) return node.value;
    if (node.children) queue.push(...node.children);
  }
  throw new Error(`树里没有叶子 ${key}`);
}

/** 第一次 update:value 事件的载荷（勾选叶子回传的位置数组）。 */
function firstEmittedLocation(wrapper: { emitted: (name: string) => unknown }) {
  const events = wrapper.emitted('update:value') as
    | Array<Array<Array<Record<string, unknown>>>>
    | undefined;
  return events?.[0]?.[0];
}

function mountPicker(props: Record<string, unknown> = {}) {
  const wrapper = mount(LocationPicker, {
    attachTo: document.body,
    props: {
      options,
      patientId: '301',
      value: [],
      ...props,
    },
  });
  mounted.push(wrapper);
  return wrapper;
}

describe('e16-t02 LocationPicker（报告单位置选择）', () => {
  it('至少 1 个：showError 且空值时显示「请至少选择 1 个位置」', async () => {
    const wrapper = mountPicker({ showError: true });
    expect(document.body.textContent).toContain('请至少选择 1 个位置');

    await wrapper.setProps({ showError: false });
    expect(document.body.textContent).not.toContain('请至少选择 1 个位置');
  });

  it('有预选时提示「已按进入位置预选，可以再加。」', () => {
    const category = leafValue('baseline:basic');
    mountPicker({ preset: [category], value: [category] });
    expect(document.body.textContent).toContain('已按进入位置预选，可以再加');
  });

  it('勾选叶子回传带模板版本的完整位置（linkType / 记录 / 锚点）', async () => {
    const wrapper = mountPicker();
    const picker = wrapper.findComponent(TreeSelect);
    expect(picker.exists()).toBe(true);

    // 模拟勾选「第2年随访 · 超声复查」叶子：SHOW_ALL 严格模式回传带 value 的对象
    picker.vm.$emit('update:value', [
      { value: 'rec:88:sec:echo', label: '超声复查' },
    ]);
    await wrapper.vm.$nextTick();

    expect(firstEmittedLocation(wrapper)).toEqual([
      {
        anchorKey: 'echo',
        anchorTemplateVersion: 2,
        anchorType: 'section',
        linkRecordId: '88',
        linkType: 'followup_record',
      },
    ]);
  });

  it('同一记录的整条与分组是两个独立选项（勾分组不勾整条）', async () => {
    const wrapper = mountPicker({
      // 预选整条记录（anchorType null）
      value: [leafValue('rec:88')],
    });
    const picker = wrapper.findComponent(TreeSelect);
    // 只勾分组后回传：整条不再在值里（tree-check-strictly 父子不联动）
    picker.vm.$emit('update:value', [
      { value: 'rec:88:sec:overview', label: '随访概况' },
    ]);
    await wrapper.vm.$nextTick();
    expect(firstEmittedLocation(wrapper)).toEqual([
      {
        anchorKey: 'overview',
        anchorTemplateVersion: 2,
        anchorType: 'section',
        linkRecordId: '88',
        linkType: 'followup_record',
      },
    ]);
  });
});
