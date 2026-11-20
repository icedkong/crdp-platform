// OQR-05B：范围 / 权限变化后清空旧明细，分页和异步响应不得串到其他统计查询。
import type { StatsDrillPage, StatsDrillQuery } from '@crdp/api';

import { flushPromises, mount } from '@vue/test-utils';

import { useUserStore } from '@vben/stores';

import { Alert, Table } from 'ant-design-vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import StatsDrillDrawer from './stats-drill-drawer.vue';

const api = vi.hoisted(() => ({ fetch: vi.fn() }));
vi.mock('#/api/core/stats-drill', () => ({ fetchStatsDrill: api.fetch }));
vi.mock('#/reference/use-permission', () => ({
  usePermission: () => ({ hasPermission: () => true }),
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@vben/stores', async () => {
  const { reactive } = await import('vue');
  const store = reactive({ userInfo: { permissionVersion: 1 } });
  return { useUserStore: () => store };
});
function account() {
  return useUserStore() as unknown as {
    userInfo: { permissionVersion: number };
  };
}
const first: StatsDrillQuery = {
  kind: 'MONTH',
  from: '2028-02-01',
  to: '2028-02-29',
};
function view(name = '张*（示例）'): StatsDrillPage {
  return {
    items: [{ key: '1', patientId: '1', name, contexts: [] }],
    total: 21,
    pageNo: 1,
    pageSize: 20,
    unit: '患者',
    timeBasis: 'LIVE',
    changed: false,
  };
}
const mounted: Array<{ unmount: () => void }> = [];
function mountDrawer(query: StatsDrillQuery = first) {
  const wrapper = mount(StatsDrillDrawer, {
    props: { query },
    attachTo: document.body,
  });
  mounted.push(wrapper);
  return wrapper;
}
beforeEach(() => {
  api.fetch.mockReset();
  account().userInfo = { permissionVersion: 1 };
});
afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
});

describe('oqr-05b 统计明细生命周期', () => {
  it('分页继续使用相同口径，每页 20 条，关闭清除明细', async () => {
    api.fetch.mockResolvedValue(view());
    const wrapper = mountDrawer();
    await flushPromises();
    expect(api.fetch).toHaveBeenLastCalledWith({
      ...first,
      pageNo: 1,
      pageSize: 20,
    });
    wrapper.findComponent(Table).vm.$emit('change', { current: 2 });
    await flushPromises();
    expect(api.fetch).toHaveBeenLastCalledWith({
      ...first,
      pageNo: 2,
      pageSize: 20,
    });
    await wrapper.setProps({ query: null });
    expect(wrapper.findComponent(Table).exists()).toBe(false);
  });
  it('权限版本变化重新取数，被拒后不保留旧患者', async () => {
    api.fetch
      .mockResolvedValueOnce(view())
      .mockRejectedValueOnce(new Error('没有患者查看权限'));
    const wrapper = mountDrawer();
    await flushPromises();
    expect(wrapper.findComponent(Table).props('dataSource')).toHaveLength(1);
    account().userInfo = { permissionVersion: 2 };
    await flushPromises();
    expect(wrapper.findComponent(Table).exists()).toBe(false);
    expect(wrapper.findComponent(Alert).props('message')).toBe(
      '没有患者查看权限',
    );
  });
  it('切换统计条件后旧响应不能覆盖新明细', async () => {
    let completeOld: ((value: StatsDrillPage) => void) | undefined;
    api.fetch
      .mockImplementationOnce(
        () =>
          new Promise<StatsDrillPage>((resolve) => {
            completeOld = resolve;
          }),
      )
      .mockResolvedValueOnce(view('李*（示例）'));
    const wrapper = mountDrawer();
    await flushPromises();
    await wrapper.setProps({ query: { kind: 'YEAR', year: 1 } });
    await flushPromises();
    completeOld?.(view('张*（示例）'));
    await flushPromises();
    expect(wrapper.findComponent(Table).props('dataSource')).toEqual(
      view('李*（示例）').items,
    );
  });
});
