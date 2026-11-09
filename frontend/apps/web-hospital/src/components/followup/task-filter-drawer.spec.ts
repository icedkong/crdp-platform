/**
 * E24-T05 / NFR-RESP-002 · 我的任务筛选抽屉（pages.md §7 手机：状态分段常驻
 * 顶栏，其余筛选进底部抽屉；design-system §7：底部抽屉 100% 宽只用于手机筛选
 * 等场景；design-system §5「手机：筛选进底部抽屉」）。数据全部虚构。
 */
import { mount } from '@vue/test-utils';

import { afterEach, describe, expect, it } from 'vitest';

import TaskFilterDrawer from './task-filter-drawer.vue';

const WARD_OPTIONS = [
  { label: '示例一病区', value: 3 },
  { label: '示例二病区', value: 4 },
];

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
});

function filters(patch: Record<string, unknown> = {}) {
  return {
    assignee: 'ALL' as const,
    dueRange: null,
    wardIds: [] as number[],
    ...patch,
  };
}

async function mountDrawer(props: Record<string, unknown> = {}) {
  const wrapper = mount(TaskFilterDrawer, {
    attachTo: document.body,
    props: {
      filters: filters(),
      open: true,
      placement: 'bottom',
      wardOptions: WARD_OPTIONS,
      ...props,
    },
  });
  mounted.push(wrapper);
  await new Promise((resolve) => setTimeout(resolve, 30));
  return wrapper;
}

function bodyText(): string {
  return document.body.textContent ?? '';
}

function clickButton(text: string) {
  const button = [...document.body.querySelectorAll('button')].find((item) =>
    (item.textContent ?? '').replaceAll(' ', '').includes(text),
  );
  expect(button, `应存在按钮「${text}」`).toBeTruthy();
  button?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  return new Promise((resolve) => setTimeout(resolve, 30));
}

describe('e24-t05 TaskFilterDrawer：我的任务筛选抽屉（手机）', () => {
  it('placement=bottom 渲染为底部抽屉（状态筛选不进抽屉——顶部分段常驻）', async () => {
    await mountDrawer();
    const drawer = document.body.querySelector('.ant-drawer');
    expect(drawer?.className).toContain('ant-drawer-bottom');
    // E24-T09 走查 W-1：抽屉根带 crdp-form（rootClassName），手机 36px/16px 规则可达
    expect(drawer?.className).toContain('crdp-form');
    expect(bodyText()).not.toContain('状态');
    expect(
      document.body.querySelector('[data-testid="drawer-status"]'),
    ).toBeNull();
  });

  it('列出抽屉字段（病区 / 计划日期 / 负责人），候选经 props 传入', async () => {
    const wrapper = await mountDrawer();
    expect(bodyText()).toContain('病区');
    expect(bodyText()).toContain('计划日期');
    expect(bodyText()).toContain('负责人');
    const selects = wrapper.findAllComponents({ name: 'ASelect' });
    expect(selects.length).toBe(2);
    expect(selects[0]?.props('options')).toEqual(WARD_OPTIONS);
    expect(selects[1]?.props('options')).toEqual([
      { label: '全部', value: 'ALL' },
      { label: '待认领', value: 'UNASSIGNED' },
    ]);
    wrapper.unmount();
  });

  it('输入改动上抛 update:filters（新状态对象，不直接改 props）', async () => {
    const initial = filters();
    const wrapper = await mountDrawer({ filters: initial });
    const selects = wrapper.findAllComponents({ name: 'ASelect' });
    expect(selects.length).toBe(2);
    selects[0]?.vm.$emit('update:value', [4]);
    selects[1]?.vm.$emit('update:value', 'UNASSIGNED');
    await new Promise((resolve) => setTimeout(resolve, 30));
    const updates = wrapper.emitted('update:filters');
    expect(updates?.[0]).toEqual([{ ...initial, wardIds: [4] }]);
    expect(updates?.[1]).toEqual([{ ...initial, assignee: 'UNASSIGNED' }]);
    // props 原对象未被改动
    expect(initial.wardIds).toEqual([]);
    expect(initial.assignee).toBe('ALL');
    wrapper.unmount();
  });

  it('「查询」上抛 search 并关闭抽屉；「重置」上抛 reset', async () => {
    const wrapper = await mountDrawer();
    await clickButton('查询');
    expect(wrapper.emitted('search')).toHaveLength(1);
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false]);

    await wrapper.setProps({ open: true });
    await clickButton('重置');
    expect(wrapper.emitted('reset')).toHaveLength(1);
    wrapper.unmount();
  });
});
