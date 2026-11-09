/**
 * E13-T06 / A-PAT-015 + E24-T03 ·「我的筛选」下拉（从患者列表行内筛选栏抽出，
 * 桌面行内与平板 / 手机筛选抽屉共用；数据全部虚构）。
 */
import type { SavedFilter } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { afterEach, describe, expect, it } from 'vitest';

import SavedFilterDropdown from './saved-filter-dropdown.vue';

const ITEMS: SavedFilter[] = [
  {
    id: 'f1',
    name: '上半年出院（示例）',
    payload: { filters: { wardIds: [3] } },
  },
  { id: 'f2', name: '二病区随访中（示例）', payload: {} },
];

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
});

async function mountDropdown(items: SavedFilter[] = ITEMS) {
  const wrapper = mount(SavedFilterDropdown, {
    attachTo: document.body,
    props: { savedFilters: items },
  });
  mounted.push(wrapper);
  // Dropdown 弹层挂 body，点开菜单
  await wrapper.find('[data-testid="patient-saved-filters"]').trigger('click');
  await new Promise((resolve) => setTimeout(resolve, 30));
  return wrapper;
}

describe('saved-filter-dropdown（A-PAT-015 我的筛选）', () => {
  it('空清单显示引导文案，不出可点项', async () => {
    const wrapper = await mountDropdown([]);
    expect(document.body.textContent).toContain('暂无保存的筛选');
    wrapper.unmount();
  });

  it('点筛选名上抛 apply；「更新」上抛 overwrite', async () => {
    const wrapper = await mountDropdown();
    const names = document.body.querySelectorAll('.saved-filter-name');
    expect(names.length).toBe(2);
    names[0]?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(wrapper.emitted('apply')?.[0]).toEqual([ITEMS[0]]);

    const update = [...document.body.querySelectorAll('button')].find((item) =>
      (item.textContent ?? '').includes('更新'),
    );
    update?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(wrapper.emitted('overwrite')?.[0]).toEqual([ITEMS[0]]);
    wrapper.unmount();
  });

  it('行内重命名：改名后上抛 rename（item, 新名）；同名 / 空名不上抛', async () => {
    const wrapper = await mountDropdown();
    const rename = [...document.body.querySelectorAll('button')].find((item) =>
      (item.textContent ?? '').includes('重命名'),
    );
    rename?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise((resolve) => setTimeout(resolve, 30));

    const input = wrapper.findAllComponents({ name: 'AInput' }).at(-1);
    expect(input).toBeTruthy();
    input?.vm.$emit('update:value', '下半年出院（示例）');
    await new Promise((resolve) => setTimeout(resolve, 30));
    const confirm = [...document.body.querySelectorAll('button')].find((item) =>
      (item.textContent ?? '').includes('确定'),
    );
    confirm?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(wrapper.emitted('rename')?.[0]).toEqual([
      ITEMS[0],
      '下半年出院（示例）',
    ]);
    expect(wrapper.emitted('rename')).toHaveLength(1);
    wrapper.unmount();
  });

  it('删除经 popconfirm 确认后上抛 remove', async () => {
    const wrapper = await mountDropdown();
    const del = [...document.body.querySelectorAll('button')].find((item) =>
      (item.textContent ?? '').includes('删除'),
    );
    del?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise((resolve) => setTimeout(resolve, 30));
    // Popconfirm 弹层为 .ant-popover，确认按钮为主色按钮
    const ok = document.body.querySelector('.ant-popover .ant-btn-primary');
    expect(ok).toBeTruthy();
    ok?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(wrapper.emitted('remove')?.[0]).toEqual([ITEMS[0]]);
    wrapper.unmount();
  });
});
