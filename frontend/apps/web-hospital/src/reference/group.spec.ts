import { describe, expect, it } from 'vitest';

import { attachments } from './fixtures';
import { groupBaseline, groupFollowup, itemsForScope } from './group';

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
