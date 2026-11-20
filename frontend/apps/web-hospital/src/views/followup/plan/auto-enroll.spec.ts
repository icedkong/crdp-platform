/* eslint-disable vue/one-component-per-file, vue/require-prop-types -- 页面交互测试用轻量控件替身。 */
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import PlanDetail from './detail.vue';
import PlanList from './index.vue';
const state = vi.hoisted(() => ({ edit: true }));
const api = vi.hoisted(() => ({
  detail: vi.fn(),
  list: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  push: vi.fn(),
}));
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { planId: '71' } }),
  useRouter: () => ({ push: api.push }),
}));
vi.mock('#/reference/use-breakpoint', () => ({
  useBreakpoint: () => ({ isMobile: ref(false) }),
}));
vi.mock('#/reference/use-permission', () => ({
  usePermission: () => ({ hasPermission: () => state.edit }),
}));
vi.mock('#/api/core/plan', () => ({
  fetchPlanDetail: api.detail,
  listPlans: api.list,
  createPlan: api.create,
  updatePlan: api.update,
  copyPlan: vi.fn(),
  publishPlan: vi.fn(),
  savePlanNodes: vi.fn(),
  fetchPlanEnrollments: vi.fn(),
  fetchPlanLogs: vi.fn(),
  disablePlan: vi.fn(),
  pausePlan: vi.fn(),
  resumePlan: vi.fn(),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.title?.(), slots.default?.()]),
  }),
}));
vi.mock('@crdp/status-tag', () => ({
  StatusTag: defineComponent({ setup: () => () => h('span') }),
}));
vi.mock('ant-design-vue', () => {
  const box = defineComponent({
    setup:
      (_, { slots, attrs }) =>
      () =>
        h('div', attrs, slots.default?.()),
  });
  const button = defineComponent({
    props: ['loading', 'disabled'],
    setup:
      (p, { slots, attrs }) =>
      () =>
        h(
          'button',
          { ...attrs, disabled: p.loading || p.disabled },
          slots.default?.(),
        ),
  });
  const input = defineComponent({
    props: ['value'],
    emits: ['update:value'],
    setup:
      (p, { emit, attrs }) =>
      () =>
        h('input', {
          ...attrs,
          value: p.value,
          onInput: (e: Event) =>
            emit('update:value', (e.target as HTMLInputElement).value),
        }),
  });
  const toggle = defineComponent({
    props: ['checked', 'disabled'],
    emits: ['update:checked'],
    setup:
      (p, { emit, attrs }) =>
      () =>
        h('input', {
          ...attrs,
          type: 'checkbox',
          checked: p.checked,
          disabled: p.disabled,
          onChange: (e: Event) =>
            emit('update:checked', (e.target as HTMLInputElement).checked),
        }),
  });
  const modal = defineComponent({
    props: ['open'],
    emits: ['ok'],
    setup:
      (p, { slots, emit }) =>
      () =>
        p.open
          ? h('section', [
              slots.default?.(),
              h('button', { onClick: () => emit('ok') }, '确认新建'),
            ])
          : null,
  });
  return {
    Alert: box,
    Button: button,
    Card: box,
    DatePicker: box,
    Descriptions: box,
    DescriptionsItem: box,
    Empty: box,
    Form: box,
    FormItem: box,
    Input: input,
    InputNumber: input,
    Modal: modal,
    Pagination: box,
    Popconfirm: box,
    Result: box,
    Select: box,
    SelectOption: box,
    Spin: box,
    Steps: box,
    Switch: toggle,
    Table: box,
    TabPane: box,
    Tabs: box,
    Textarea: input,
    message: { success: vi.fn() },
  };
});
const plan = (autoEnroll = true, status = 'UNPUBLISHED') => ({
  id: '71',
  code: 'PLAN-EXAMPLE',
  name: '随访计划（示例）',
  version: 1,
  status,
  autoEnroll,
  lockVersion: 4,
  cycleType: 'ONGOING',
  cycleYears: null,
  startDateField: 'DISCHARGE_DATE',
  remark: null,
  enrolledCount: 0,
  publishedAt: null,
});
const wrappers: ReturnType<typeof mount>[] = [];
beforeEach(() => {
  state.edit = true;
  api.list.mockResolvedValue([]);
  api.detail.mockResolvedValue({ plan: plan(), nodes: [] });
  api.create.mockResolvedValue(plan());
  api.update.mockResolvedValue(plan());
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
  vi.clearAllMocks();
});
async function open(component: typeof PlanDetail | typeof PlanList) {
  const w = mount(component);
  wrappers.push(w);
  await flushPromises();
  return w;
}
async function click(w: ReturnType<typeof mount>, text: string) {
  const b = w.findAll('button').find((b) => b.text().includes(text));
  expect(b).toBeTruthy();
  if (!b) throw new Error(`未找到按钮：${text}`);
  await b.trigger('click');
  await flushPromises();
}
describe('oqr-09 自动入组页面契约', () => {
  it('新建默认关闭，开启后提交明确布尔值', async () => {
    const w = await open(PlanList);
    await click(w, '新建计划');
    expect(
      (w.get('input[type=checkbox]').element as HTMLInputElement).checked,
    ).toBe(false);
    await w.get('input[maxlength="64"]').setValue('AUTO_EXAMPLE');
    await w.get('input[maxlength="128"]').setValue('自动入组计划（示例）');
    await w.get('input[type=checkbox]').setValue(true);
    await click(w, '确认新建');
    expect(api.create).toHaveBeenCalledWith(
      expect.objectContaining({ autoEnroll: true }),
    );
  });
  it('编辑回显已开启，修改其他信息不会隐式关闭', async () => {
    const w = await open(PlanDetail);
    await click(w, '编辑基本信息');
    expect(
      (w.get('input[type=checkbox]').element as HTMLInputElement).checked,
    ).toBe(true);
    await click(w, '保存基本信息');
    expect(api.update).toHaveBeenCalledWith(
      '71',
      expect.objectContaining({ autoEnroll: true, baseVersion: 4 }),
    );
  });
  it('关闭开关显式发送 false，保存后按服务器值回显', async () => {
    const w = await open(PlanDetail);
    await click(w, '编辑基本信息');
    await w.get('input[type=checkbox]').setValue(false);
    api.detail.mockResolvedValue({ plan: plan(false), nodes: [] });
    await click(w, '保存基本信息');
    expect(api.update).toHaveBeenCalledWith(
      '71',
      expect.objectContaining({ autoEnroll: false }),
    );
    expect(w.find('input[type=checkbox]').exists()).toBe(false);
    expect(w.text()).toContain('关闭');
  });
  it('版本冲突后重新加载服务器开关，不覆盖服务器版本', async () => {
    const w = await open(PlanDetail);
    await click(w, '编辑基本信息');
    await w.get('input[type=checkbox]').setValue(false);
    api.update.mockRejectedValueOnce(new Error('40900'));
    api.detail.mockResolvedValue({ plan: plan(true), nodes: [] });
    await click(w, '保存基本信息');
    await click(w, '编辑基本信息');
    expect(
      (w.get('input[type=checkbox]').element as HTMLInputElement).checked,
    ).toBe(true);
    expect(api.update).toHaveBeenCalledTimes(1);
  });
  it('无编辑权限只读展示，不能新建或调整开关', async () => {
    state.edit = false;
    const detail = await open(PlanDetail);
    const list = await open(PlanList);
    expect(detail.find('input[type=checkbox]').exists()).toBe(false);
    expect(detail.text()).toContain('开启');
    expect(
      detail.findAll('button').some((b) => b.text() === '编辑基本信息'),
    ).toBe(false);
    expect(
      list.findAll('button').some((b) => b.text().includes('新建计划')),
    ).toBe(false);
  });
  it('已发布计划只读，必须复制新版本后调整', async () => {
    api.detail.mockResolvedValue({ plan: plan(true, 'PUBLISHED'), nodes: [] });
    const w = await open(PlanDetail);
    expect(w.findAll('button').some((b) => b.text() === '编辑基本信息')).toBe(
      false,
    );
    expect(w.find('input[type=checkbox]').exists()).toBe(false);
    expect(w.text()).toContain('开启');
  });
});
