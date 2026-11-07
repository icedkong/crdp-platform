import type { AttachmentNavItem } from './types';

import { describe, expect, it } from 'vitest';

import { attachments } from './fixtures';
import { groupBaseline, groupFollowup, itemsForScope } from './group';

function navItem(
  partial: Partial<AttachmentNavItem> &
    Pick<AttachmentNavItem, 'home' | 'locations'>,
): AttachmentNavItem {
  return {
    fileCount: 1,
    name: '经胸超声心动图',
    reportDate: '2026-10-28',
    reportId: 'r1',
    typeCode: 'ECHO',
    typeName: '心脏超声',
    ...partial,
  };
}

describe('itemsForScope（左栏按页签取数，E16-T12）', () => {
  const baselineOnly = navItem({
    home: 'baseline',
    locations: [
      {
        anchorKey: 'echo',
        anchorLabel: '超声检查',
        anchorType: 'category',
        linkType: 'baseline',
      },
    ],
    reportId: 'rb',
  });
  const followupOnly = navItem({
    home: 'followup',
    locations: [
      {
        linkRecordId: 'rec-3',
        linkType: 'followup_record',
        recordLabel: '第3年随访',
      },
    ],
    reportId: 'rf',
  });
  const looseBoth = navItem({
    home: 'both',
    locations: [],
    reportId: 'r-both',
  });
  const looseFollowup = navItem({
    home: 'followup',
    locations: [],
    reportId: 'r-loose-f',
  });
  const items = [baselineOnly, followupOnly, looseBoth, looseFollowup];

  it('基线页签：含基线位置的进，只有随访位置的不进；未指定位置按 home 进', () => {
    expect(
      itemsForScope(items, 'baseline').map((item) => item.reportId),
    ).toEqual(['rb', 'r-both']);
  });

  it('随访页签：含随访记录位置的进，只有基线位置的不进；home=followup 的未指定位置组也进', () => {
    expect(
      itemsForScope(items, 'followup').map((item) => item.reportId),
    ).toEqual(['rf', 'r-both', 'r-loose-f']);
  });
});

describe('attachment grouping', () => {
  it('lists a baseline attachment once and keeps every baseline chip', () => {
    const groups = groupBaseline(itemsForScope(attachments, 'baseline'));
    const echo = groups.find((group) => group.key === '心脏超声');
    expect(echo?.items).toHaveLength(1);
    expect(echo?.items[0]?.locations.map((item) => item.anchorLabel)).toEqual([
      '超声检查',
      '病例基本信息',
    ]);
  });

  it('puts attachments without a location in the warning group', () => {
    const groups = groupBaseline(itemsForScope(attachments, 'baseline'));
    const loose = groups.find((group) => group.key === 'unlocated');
    expect(loose?.warning).toBe(true);
    expect(loose?.title).toContain('未指定位置');
  });

  it('repeats a follow-up attachment under each record with only that record chips', () => {
    const groups = groupFollowup(itemsForScope(attachments, 'followup'));
    const year3 = groups.find((group) => group.key === 'rec-3');
    const year2 = groups.find((group) => group.key === 'rec-2');
    const chips = (key: string | undefined) =>
      groups
        .find((group) => group.key === key)
        ?.children?.flatMap((child) => child.items)
        .flatMap((item) =>
          item.locations.map((location) => location.recordLabel),
        );
    expect(year3?.title).toBe('第3年随访');
    expect(chips('rec-3')).toEqual(['第3年随访']);
    expect(chips('rec-2')).toEqual(['第2年随访']);
    expect(year2).toBeTruthy();
  });
});
