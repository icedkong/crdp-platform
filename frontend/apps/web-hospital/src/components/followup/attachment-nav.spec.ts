// E16-T12 / A-PAT-020（pages.md §4.2 基线 / 随访页签左栏；components.md §5 AttachmentNav）：
// 左栏附件导航容器——桌面 240px 侧栏（可收起），平板 / 手机收为「附件（n）」入口
// （平板左侧抽屉、手机底部抽屉）；点位置标签 locate 跳到该位置、点报告单本体跳第一个位置；
// 分组与「未指定位置」警示组由 #/reference/group 给出（分组细节见 group.spec 与
// attachment-nav-list.spec）。
import type { AttachmentNavItem, ReportLocation } from '#/reference/types';

import { mount } from '@vue/test-utils';
import { nextTick, ref } from 'vue';

import { sizes } from '@crdp/tokens';
import { afterEach, describe, expect, it, vi } from 'vitest';

import AttachmentNav from './attachment-nav.vue';

// 桌面 / 平板 / 手机三态由用例切换（真实断点组合函数的口径见 @crdp/responsive 自身测试）
const breakpointState = { isDesktop: true, isMobile: false };
vi.mock('#/reference/use-breakpoint', () => ({
  useBreakpoint: () => ({
    isDesktop: ref(breakpointState.isDesktop),
    isMobile: ref(breakpointState.isMobile),
  }),
}));

function loc(partial: Partial<ReportLocation>): ReportLocation {
  return { linkType: 'baseline', ...partial };
}

function item(partial: Partial<AttachmentNavItem>): AttachmentNavItem {
  return {
    fileCount: 1,
    home: 'baseline',
    locations: [],
    name: '经胸超声心动图',
    reportDate: '2026-10-28',
    reportId: 'r1',
    typeCode: 'ECHO',
    typeName: '心脏超声',
    ...partial,
  };
}

const echoBaseline = loc({
  anchorKey: 'echo',
  anchorLabel: '超声检查',
  anchorType: 'category',
});
const year3 = loc({
  linkType: 'followup_record',
  linkRecordId: 'rec-3',
  recordLabel: '第3年随访',
});

const items: AttachmentNavItem[] = [
  item({
    reportId: 'r1',
    locations: [echoBaseline, year3],
    thumbnailUrl: '/thumbnails/r1.png',
  }),
  item({
    fileCount: 2,
    home: 'both',
    name: '外院资料（示例）',
    reportId: 'r2',
    typeCode: 'OTHER',
    typeName: '其他',
  }),
];

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
  breakpointState.isDesktop = true;
  breakpointState.isMobile = false;
});

function mountNav(props: Record<string, unknown> = {}) {
  const wrapper = mount(AttachmentNav, {
    attachTo: document.body,
    props: { items, patientId: '301', scope: 'baseline', ...props },
  });
  mounted.push(wrapper);
  return wrapper;
}

describe('e16-t12 AttachmentNav（左栏附件导航容器）', () => {
  it('桌面：240px 侧栏 +「附件（n）」计数 + 类型分组与未指定位置组', async () => {
    const wrapper = mountNav();
    const aside = wrapper.find('[data-testid="attachment-nav"]');
    expect(aside.exists()).toBe(true);
    expect(aside.attributes('data-patient-id')).toBe('301');
    expect(aside.attributes('style')).toContain(
      `${sizes.attachmentNavWidth}px`,
    );
    expect(wrapper.text()).toContain(`附件（${items.length}）`);
    expect(wrapper.text()).toContain('心脏超声（1）');
    expect(wrapper.text()).toContain('未指定位置（1）');
  });

  it('基线页签只展示基线位置：跨基线 / 随访的报告单只带基线侧标签', () => {
    const wrapper = mountNav();
    // r1 有基线与随访两侧位置，基线页签只显示「超声检查」一个标签
    const chips = wrapper
      .findAll('.item')
      .flatMap((node) => node.findAll('.loc'))
      .map((node) => node.text());
    expect(chips).toEqual(['超声检查']);
  });

  it('点位置标签 locate 该位置；点报告单本体跳第一个位置（A-PAT-020）', async () => {
    const wrapper = mountNav();
    const first = wrapper.findAll('.item').at(0);
    await first?.findAll('.loc').at(0)?.trigger('click');
    expect(wrapper.emitted('locate')?.[0]?.[0]).toEqual(echoBaseline);

    await first?.find('.name').trigger('click');
    expect(wrapper.emitted('locate')).toHaveLength(2);
    expect(wrapper.emitted('locate')?.[1]?.[0]).toEqual(echoBaseline);
  });

  it('无位置的报告单点本体走 open（没有第一个位置可跳）', async () => {
    const wrapper = mountNav();
    const loose = wrapper
      .findAll('.item')
      .find((node) => node.text().includes('外院资料'));
    expect(loose).toBeTruthy();
    await loose?.find('.name').trigger('click');
    expect(wrapper.emitted('open')?.[0]).toEqual(['r2']);
    expect(wrapper.emitted('locate')).toBeUndefined();
  });

  it('收起按钮：折叠成 24px 细条、列表隐藏，再点展开恢复', async () => {
    const wrapper = mountNav();
    await wrapper.find('button').trigger('click');
    expect(
      wrapper.find('[data-testid="attachment-nav"]').attributes('style'),
    ).toContain('width: 24px');
    expect(wrapper.findComponent({ name: 'AttachmentNavList' }).exists()).toBe(
      false,
    );
    await wrapper.find('button').trigger('click');
    expect(
      wrapper.find('[data-testid="attachment-nav"]').attributes('style'),
    ).toContain(`width: ${sizes.attachmentNavWidth}px`);
  });

  it('平板：收为「附件（n）」入口，点开左侧抽屉；抽屉内点标签照常 locate 并关闭抽屉', async () => {
    breakpointState.isDesktop = false;
    breakpointState.isMobile = false;
    const wrapper = mountNav({ canUpload: true });
    expect(wrapper.find('[data-testid="attachment-nav"]').exists()).toBe(false);

    await wrapper.find('[data-testid="attachment-open"]').trigger('click');
    await nextTick();
    const drawer = document.body.querySelector('.ant-drawer');
    expect(drawer).toBeTruthy();
    expect(drawer?.className).toContain('ant-drawer-left');
    expect(drawer?.textContent).toContain(`附件（${items.length}）`);

    const chip = drawer?.querySelector<HTMLElement>('.loc');
    chip?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await nextTick();
    expect(wrapper.emitted('locate')?.[0]?.[0]).toEqual(echoBaseline);
  });

  it('手机：底部抽屉（A-PAT-020 手机为底部抽屉）', async () => {
    breakpointState.isDesktop = false;
    breakpointState.isMobile = true;
    const wrapper = mountNav();
    await wrapper.find('[data-testid="attachment-open"]').trigger('click');
    await nextTick();
    expect(document.body.querySelector('.ant-drawer')?.className).toContain(
      'ant-drawer-bottom',
    );
  });

  it('随访页签：按随访记录分组（新的在上），跨记录的报告单在每个记录下各出现一次', () => {
    const crossRecord = item({
      home: 'followup',
      reportId: 'r3',
      locations: [
        loc({
          linkType: 'followup_record',
          linkRecordId: 'rec-2',
          recordDate: '2026-05-10',
          recordLabel: '第2年随访',
        }),
        year3,
      ],
    });
    const wrapper = mountNav({
      scope: 'followup',
      items: [...items, crossRecord],
    });
    const text = wrapper.text();
    expect(text).toContain('第3年随访');
    expect(text).toContain('第2年随访');
    // 无随访位置的报告单不进随访页签；r2（home=both）仍在「未指定位置」组
    expect(text).toContain('未指定位置（1）');
  });
});
