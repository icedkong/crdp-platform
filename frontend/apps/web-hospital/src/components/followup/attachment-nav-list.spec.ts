// E16-T12 / A-PAT-020（components.md §5 AttachmentNavList）：左栏导航列表体——
// 按报告单类型分组（组标题带数量）、每份报告单只出现一次且下方列出全部位置标签、
// 缩略图与电子化状态随行；「未指定位置」警示组有上传权限时提供「指定位置」入口；
// 空态文案区分基线 / 随访页签并给上传入口。
import type {
  AttachmentGroup,
  AttachmentNavItem,
  ReportLocation,
} from '#/reference/types';

import { mount } from '@vue/test-utils';

import { colors } from '@crdp/tokens';
import { afterEach, describe, expect, it } from 'vitest';

import AttachmentNavList from './attachment-nav-list.vue';

function loc(partial: Partial<ReportLocation>): ReportLocation {
  return { linkType: 'baseline', ...partial };
}

const echoChip = loc({
  anchorKey: 'echo',
  anchorLabel: '超声检查',
  anchorType: 'category',
});
const caseChip = loc({
  anchorKey: 'case',
  anchorLabel: '病例基本信息',
  anchorType: 'category',
});

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

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
});

function mountList(
  groups: AttachmentGroup[],
  props: Record<string, unknown> = {},
) {
  const wrapper = mount(AttachmentNavList, {
    props: {
      canUpload: false,
      emptyText: '暂无基线附件 · 上传',
      groups,
      ...props,
    },
  });
  mounted.push(wrapper);
  return wrapper;
}

describe('e16-t12 AttachmentNavList（左栏导航列表）', () => {
  it('类型分组带数量；每份一次，列出全部位置标签与缩略图', () => {
    const wrapper = mountList([
      {
        items: [
          item({
            locations: [echoChip, caseChip],
            thumbnailUrl: '/thumbnails/r1.png',
          }),
        ],
        key: '心脏超声',
        title: '心脏超声（1）',
      },
    ]);
    expect(wrapper.text()).toContain('心脏超声（1）');
    const imgs = wrapper.findAll('img');
    expect(imgs).toHaveLength(1);
    expect(imgs[0]?.attributes('src')).toBe('/thumbnails/r1.png');
    const chips = wrapper.findAll('.loc').map((node) => node.text());
    expect(chips).toEqual(['超声检查', '病例基本信息']);
  });

  it('点标签 locate 到该位置；点报告单本体 locate 第一个位置', async () => {
    const wrapper = mountList([
      {
        items: [item({ locations: [echoChip, caseChip] })],
        key: '心脏超声',
        title: '心脏超声（1）',
      },
    ]);
    await wrapper.findAll('.loc').at(1)?.trigger('click');
    expect(wrapper.emitted('locate')?.[0]?.[0]).toEqual(caseChip);

    await wrapper.find('.name').trigger('click');
    expect(wrapper.emitted('locate')?.[1]?.[0]).toEqual(echoChip);
  });

  it('整条记录位置（无锚点）标签显示记录名；无 anchorLabel 时回退「整条记录」', async () => {
    const wholeRecord = loc({
      linkType: 'followup_record',
      linkRecordId: 'rec-3',
      recordLabel: '第3年随访',
    });
    const wrapper = mountList([
      {
        items: [item({ locations: [wholeRecord] })],
        key: '心脏超声',
        title: '心脏超声（1）',
      },
    ]);
    expect(wrapper.find('.loc').text()).toBe('第3年随访');
    await wrapper.find('.loc').trigger('click');
    expect(wrapper.emitted('locate')?.[0]?.[0]).toEqual(wholeRecord);
  });

  it('「未指定位置」警示组：组名橙色样式钩子，有上传权限时提供「指定位置」，无权限不显示', async () => {
    const groups: AttachmentGroup[] = [
      {
        items: [
          item({
            name: '外院资料（示例）',
            reportId: 'r2',
            typeCode: 'OTHER',
            typeName: '其他',
          }),
        ],
        key: 'unlocated',
        title: '未指定位置（1）',
        warning: true,
      },
    ];
    const denied = mountList(groups);
    expect(denied.text()).toContain('未指定位置（1）');
    expect(denied.find('.group-title').attributes('style')).toContain(
      colors.warning,
    );
    // 无上传权限不出现「指定位置」按钮（组名「未指定位置」本身不含按钮）
    expect(
      denied
        .findAll('button')
        .filter((node) => node.text().includes('指定位置')),
    ).toHaveLength(0);

    const allowed = mountList(structuredClone(groups), { canUpload: true });
    const buttons = allowed
      .findAll('button')
      .filter((node) => node.text().replaceAll(' ', '').includes('指定位置'));
    expect(buttons).toHaveLength(1);
    await buttons.at(0)?.trigger('click');
    expect(allowed.emitted('upload')).toHaveLength(1);
    // 无位置的报告单点本体 → open（交给报告单详情 / 修改位置），不误抛 locate
    await allowed.find('.name').trigger('click');
    expect(allowed.emitted('open')?.[0]).toEqual(['r2']);
    expect(allowed.emitted('locate')).toBeUndefined();
  });

  it('随访分组（children）：记录组下再按类型，跨记录报告单在每条记录下各出现一次', () => {
    const year3 = loc({
      linkType: 'followup_record',
      linkRecordId: 'rec-3',
      recordLabel: '第3年随访',
    });
    const year2 = loc({
      linkType: 'followup_record',
      linkRecordId: 'rec-2',
      recordLabel: '第2年随访',
    });
    const wrapper = mountList([
      {
        children: [
          {
            items: [item({ reportId: 'r1', locations: [year3] })],
            key: 'rec-3-心脏超声',
            title: '心脏超声（1）',
          },
        ],
        items: [],
        key: 'rec-3',
        title: '第3年随访',
      },
      {
        children: [
          {
            items: [item({ reportId: 'r1', locations: [year2] })],
            key: 'rec-2-心脏超声',
            title: '心脏超声（1）',
          },
        ],
        items: [],
        key: 'rec-2',
        title: '第2年随访',
      },
    ]);
    expect(wrapper.text()).toContain('第3年随访');
    expect(wrapper.text()).toContain('第2年随访');
    expect(wrapper.findAll('.item')).toHaveLength(2);
    expect(wrapper.findAll('.name')[0]?.text()).toContain('经胸超声心动图');
    const chips = wrapper.findAll('.loc').map((node) => node.text());
    expect(chips).toEqual(['第3年随访', '第2年随访']);
  });

  it('电子化状态随行显示（C-SCAN-011；A 租户无此列不受影响）', () => {
    const wrapper = mountList([
      {
        items: [item({ digitizeStatus: '待录入', locations: [echoChip] })],
        key: '心脏超声',
        title: '心脏超声（1）',
      },
    ]);
    expect(wrapper.text()).toContain('待录入');
  });

  it('空态：提示文案，有上传权限时给「上传」入口', async () => {
    const wrapper = mountList([], { canUpload: true });
    expect(wrapper.text()).toContain('暂无基线附件 · 上传');
    const upload = wrapper
      .findAll('button')
      .filter((node) => node.text().replaceAll(' ', '') === '上传');
    expect(upload).toHaveLength(1);
    await upload.at(0)?.trigger('click');
    expect(wrapper.emitted('upload')).toHaveLength(1);
  });
});
