// E24-T04 / NFR-RESP-002 · PatientHeader 手机形态（components.md §4：
// 手机折叠为「姓名 + 状态 + 下次随访」，右侧「详情 ▾ / 收起 ▴」展开其余；
// 操作按钮收进「操作」按钮打开的底部抽屉，抽屉为 design-system §7 允许的
// 手机「操作」菜单场景）。数据全部虚构。
import type { PatientOverview } from '@crdp/api';

import { mount } from '@vue/test-utils';
import { nextTick, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import PatientHeader from './patient-header.vue';

// 桌面 / 手机两态由用例切换（真实断点组合函数的口径见 @crdp/responsive 自身测试）
const breakpointState = { isMobile: false };
vi.mock('#/reference/use-breakpoint', () => ({
  useBreakpoint: () => ({ isMobile: ref(breakpointState.isMobile) }),
}));
// MaskedField 里的权限判断依赖 pinia（accessStore），本测试只关心头部形态，mock 掉
vi.mock('#/reference/use-permission', () => ({
  usePermission: () => ({ hasPermission: () => false }),
}));

function overview(patch: Partial<PatientOverview> = {}): PatientOverview {
  return {
    actions: ['transfer', 'unplanned'],
    admissionDate: '2026-10-20',
    age: 62,
    baselineCompleteness: 0.92,
    deptName: '心内科',
    dischargeDate: '2026-10-28',
    dischargeStatus: 1,
    followupCompleteness: 0.4,
    gender: 'M',
    id: '301',
    latestFollowup: null,
    legacyNo: 'L-0001（示例）',
    name: '张*明（示例）',
    nextVisit: {
      planDate: '2027-11-05',
      windowEnd: '2027-11-12',
      windowStart: '2027-10-29',
    },
    patientNo: 'P0001234（示例）',
    patientType: '重症冠心病',
    responsibleName: '李医生（示例）',
    status: 'ACTIVE',
    surgeryDate: '2026-10-25',
    terminated: false,
    visitNo: 'V0001（示例）',
    wardId: 3,
    wardName: '示例一病区',
    ...patch,
  };
}

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
  breakpointState.isMobile = false;
});

function mountHeader(patch: Partial<PatientOverview> = {}) {
  const wrapper = mount(PatientHeader, {
    attachTo: document.body,
    props: { overview: overview(patch) },
  });
  mounted.push(wrapper);
  return wrapper;
}

function clickBodyButton(text: string) {
  const button = [...document.body.querySelectorAll('button')].find((item) =>
    (item.textContent ?? '').replaceAll(' ', '').includes(text),
  );
  expect(button, `应存在按钮「${text}」`).toBeTruthy();
  button?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
}

describe('e24-t04 PatientHeader：手机折叠头部与操作抽屉', () => {
  it('桌面：操作按钮行内常显（联系方式与变更记录 / 转病区 / 新建计划外随访）', () => {
    const wrapper = mountHeader();
    expect(wrapper.find('[data-testid="open-contacts"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="open-transfer"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="open-unplanned"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="header-actions-open"]').exists()).toBe(
      false,
    );
  });

  it('手机：折叠为姓名 + 状态 + 下次随访，「详情 ▾」展开其余信息', async () => {
    breakpointState.isMobile = true;
    const wrapper = mountHeader();
    const text = wrapper.text();
    expect(text).toContain('张*明（示例）');
    expect(text).toContain('下次随访');
    // 折叠态不显示第二、三行元信息（性别年龄 / 编号 / 完整度）
    expect(text).not.toContain('62 岁');
    expect(text).not.toContain('基线资料完整度');

    await wrapper
      .find('[data-testid="header-details-toggle"]')
      .trigger('click');
    expect(wrapper.text()).toContain('62 岁');
    expect(wrapper.text()).toContain('基线资料完整度');
  });

  it('手机：操作按钮不占头部，收进「操作」底部抽屉', async () => {
    breakpointState.isMobile = true;
    const wrapper = mountHeader();
    expect(wrapper.find('[data-testid="open-contacts"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="open-transfer"]').exists()).toBe(false);

    await wrapper.find('[data-testid="header-actions-open"]').trigger('click');
    await nextTick();
    const drawer = document.body.querySelector('.ant-drawer');
    expect(drawer).toBeTruthy();
    expect(drawer?.className).toContain('ant-drawer-bottom');
    expect(drawer?.textContent).toContain('联系方式与变更记录');
    expect(drawer?.textContent).toContain('转病区');
    expect(drawer?.textContent).toContain('新建计划外随访');
  });

  it('手机：抽屉内点操作上抛事件并关闭抽屉（点完先关抽屉再做动作）', async () => {
    breakpointState.isMobile = true;
    const wrapper = mountHeader();
    await wrapper.find('[data-testid="header-actions-open"]').trigger('click');
    await nextTick();
    const drawer = wrapper.findComponent({ name: 'ADrawer' });
    expect(drawer.props('open')).toBe(true);
    clickBodyButton('转病区');
    await nextTick();
    expect(wrapper.emitted('transfer')).toHaveLength(1);
    expect(wrapper.findComponent({ name: 'ADrawer' }).props('open')).toBe(
      false,
    );
  });

  it('手机：summary.actions 不含的动作不进抽屉（后端按权限计算）', async () => {
    breakpointState.isMobile = true;
    const wrapper = mountHeader({ actions: [] });
    await wrapper.find('[data-testid="header-actions-open"]').trigger('click');
    await nextTick();
    const drawerText = document.body.textContent ?? '';
    expect(drawerText).toContain('联系方式与变更记录');
    expect(drawerText).not.toContain('转病区');
    expect(drawerText).not.toContain('新建计划外随访');
  });
});
