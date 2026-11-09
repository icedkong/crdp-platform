/**
 * E24-T03 / NFR-RESP-002 · 患者列表筛选抽屉（pages.md §3：
 * 平板「筛选收进抽屉」（右侧）、手机「筛选进底部抽屉」；
 * design-system §7：底部抽屉 100% 宽只用于手机筛选等四类场景。数据全部虚构。
 */
import type { PatientFilterState } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { afterEach, describe, expect, it } from 'vitest';

import PatientFilterDrawer from './patient-filter-drawer.vue';

const WARD_OPTIONS = [
  { label: '示例一病区', value: 3 },
  { label: '示例二病区', value: 4 },
];

const STATUS_OPTIONS = [
  { label: '随访中', value: 'ACTIVE' },
  { label: '已结束', value: 'FINISHED' },
];

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
});

function filters(patch: Partial<PatientFilterState> = {}): PatientFilterState {
  return {
    keyword: '',
    status: null,
    wardId: null,
    dischargeRange: null,
    nextVisitRange: null,
    ...patch,
  };
}

async function mountDrawer(props: Record<string, unknown> = {}) {
  const wrapper = mount(PatientFilterDrawer, {
    attachTo: document.body,
    props: {
      filters: filters(),
      open: true,
      placement: 'bottom',
      statusOptions: STATUS_OPTIONS,
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

describe('e24-t03 PatientFilterDrawer：患者列表筛选抽屉', () => {
  it('placement=bottom 渲染为底部抽屉，placement=right 渲染为右侧抽屉', async () => {
    const bottom = await mountDrawer({ placement: 'bottom' });
    expect(document.body.querySelector('.ant-drawer')?.className).toContain(
      'ant-drawer-bottom',
    );
    bottom.unmount();
    document.body.innerHTML = '';

    await mountDrawer({ placement: 'right' });
    expect(document.body.querySelector('.ant-drawer')?.className).toContain(
      'ant-drawer-right',
    );
  });

  it('列出筛选字段（病区 / 随访状态 / 出院日期 / 下次随访），检索词常驻顶栏不在抽屉里', async () => {
    const wrapper = await mountDrawer();
    expect(bodyText()).toContain('病区');
    expect(bodyText()).toContain('随访状态');
    expect(bodyText()).toContain('出院日期');
    expect(bodyText()).toContain('下次随访');
    // 下拉候选经 props 传入（antd 弹层懒渲染，不在断言正文里）
    const selects = wrapper.findAllComponents({ name: 'ASelect' });
    expect(selects.length).toBe(2);
    expect(selects[0]?.props('options')).toEqual(WARD_OPTIONS);
    expect(selects[1]?.props('options')).toEqual(STATUS_OPTIONS);
    expect(
      document.body.querySelector('[data-testid="drawer-keyword"]'),
    ).toBeNull();
    wrapper.unmount();
  });

  it('输入改动上抛 update:filters（新状态对象，不直接改 props）', async () => {
    const initial = filters();
    const wrapper = await mountDrawer({ filters: initial });
    const selects = wrapper.findAllComponents({ name: 'ASelect' });
    expect(selects.length).toBe(2);
    selects[0]?.vm.$emit('update:value', 4);
    selects[1]?.vm.$emit('update:value', 'ACTIVE');
    await new Promise((resolve) => setTimeout(resolve, 30));
    // 每次改动基于当时 props 快照整份上抛（页面 Object.assign 回写后才含前一次改动）
    const updates = wrapper.emitted('update:filters');
    expect(updates?.[0]).toEqual([{ ...initial, wardId: 4 }]);
    expect(updates?.[1]).toEqual([{ ...initial, status: 'ACTIVE' }]);
    // props 原对象未被改动
    expect(initial.wardId).toBeNull();
    expect(initial.status).toBeNull();
    wrapper.unmount();
  });

  it('「查询」上抛 search 并关闭抽屉（检索词校验由页面统一做）', async () => {
    const wrapper = await mountDrawer();
    await clickButton('查询');
    expect(wrapper.emitted('search')).toHaveLength(1);
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false]);
    wrapper.unmount();
  });

  it('「重置」上抛 reset；「高级检索」上抛 open-advanced 并收起筛选抽屉', async () => {
    const wrapper = await mountDrawer();
    await clickButton('重置');
    expect(wrapper.emitted('reset')).toHaveLength(1);

    await wrapper.setProps({ open: true });
    await clickButton('高级检索');
    expect(wrapper.emitted('openAdvanced')).toHaveLength(1);
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false]);
    wrapper.unmount();
  });
});
