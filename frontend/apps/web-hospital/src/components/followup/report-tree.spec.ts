// E16-T04 / A-ATT-010、C-SCAN-013（pages.md §4.6）：报告单页签左侧双树组件——
// 「按类型 | 按时间」切换、分组数量（心脏超声（2）/ 2026-10-28（2））、点节点选中、
// 再点取消、切换树清空选中、空状态「暂无报告单」
import type { ReportRow } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { Segmented, Tree } from 'ant-design-vue';
import { afterEach, describe, expect, it } from 'vitest';

import ReportTree from './report-tree.vue';

function row(partial: Partial<ReportRow> & { id: string }): ReportRow {
  return {
    conclusion: null,
    createdAt: '2026-11-01T10:00:00+08:00',
    creatorName: '张医生（示例）',
    digitizeStatus: null,
    fileCount: 1,
    findings: null,
    locations: [],
    name: null,
    note: null,
    patientId: '301',
    reportDate: '2026-10-28',
    typeCode: 'echo',
    typeName: '心脏超声',
    version: 1,
    ...partial,
  };
}

const reports: ReportRow[] = [
  row({ id: 'r1', name: '经胸超声心动图', reportDate: '2026-10-28' }),
  row({ id: 'r2', name: '经胸超声心动图', reportDate: '2026-03-12' }),
  row({
    id: 'r3',
    name: '血常规',
    reportDate: '2026-10-28',
    typeCode: 'lab',
    typeName: '检验',
  }),
];

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
});

function mountTree(props: Record<string, unknown> = {}) {
  const wrapper = mount(ReportTree, {
    attachTo: document.body,
    props: { reports, ...props },
  });
  mounted.push(wrapper);
  return wrapper;
}

describe('e16-t04 ReportTree（双树浏览）', () => {
  it('默认按类型：切换器有「按类型 / 按时间」，类型节点带数量', () => {
    const wrapper = mountTree();
    const segmented = wrapper.findComponent(Segmented);
    expect(segmented.exists()).toBe(true);
    expect(document.body.textContent).toContain('按类型');
    expect(document.body.textContent).toContain('按时间');
    expect(document.body.textContent).toContain('心脏超声（2）');
    expect(document.body.textContent).toContain('检验（1）');
  });

  it('切换到按时间：树按报告时间倒序重建、日期节点带数量，并清空选中', async () => {
    const wrapper = mountTree({ selectedKey: 'type:echo' });
    const segmented = wrapper.findComponent(Segmented);
    segmented.vm.$emit('update:value', 'time');
    await wrapper.vm.$nextTick();

    expect(wrapper.emitted('update:mode')?.[0]).toEqual(['time']);
    // 父组件回写 mode 后：树按时间重建，且清空上一棵的选中
    await wrapper.setProps({ mode: 'time', selectedKey: null });
    expect(wrapper.emitted('update:selectedKey')?.at(-1)).toEqual([null]);
    expect(document.body.textContent).toContain('2026-10-28（2）');
    expect(document.body.textContent).toContain('2026-03-12（1）');
  });

  it('点分组节点选中（update:selectedKey），再点同一节点取消选中', async () => {
    const wrapper = mountTree();
    const tree = wrapper.findComponent(Tree);
    tree.vm.$emit('select', ['type:echo'], { selected: true });
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('update:selectedKey')?.[0]).toEqual(['type:echo']);

    tree.vm.$emit('select', [], { selected: false });
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('update:selectedKey')?.[1]).toEqual([null]);
  });

  it('分组节点默认展开（可见名称叶子）', () => {
    mountTree();
    expect(document.body.textContent).toContain('经胸超声心动图 · 2026-10-28');
    expect(document.body.textContent).toContain('血常规');
  });

  it('空状态显示「暂无报告单」', () => {
    mountTree({ reports: [] });
    expect(document.body.textContent).toContain('暂无报告单');
  });

  it('加载中显示「加载中…」而不是空状态', () => {
    mountTree({ loading: true, reports: [] });
    expect(document.body.textContent).toContain('加载中');
    expect(document.body.textContent).not.toContain('暂无报告单');
  });
});
