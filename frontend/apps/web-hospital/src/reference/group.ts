import type { AttachmentGroup, AttachmentNavItem } from './types';

/**
 * 基线：每份附件只出现一次，位置标签列出全部基线侧位置。无位置进橙色组。
 * E21-T32 / C-PAT-017：植入记录在基线资料页签——implant 位置随基线侧列出。
 */
export function groupBaseline(items: AttachmentNavItem[]): AttachmentGroup[] {
  const located = items.filter((item) =>
    item.locations.some((location) => isBaselineSideLocation(location)),
  );
  const unlocated = items.filter((item) => item.locations.length === 0);
  const byType = new Map<string, AttachmentNavItem[]>();
  for (const item of located) {
    const baselineLocations = item.locations.filter((location) =>
      isBaselineSideLocation(location),
    );
    const copy = { ...item, locations: baselineLocations };
    const list = byType.get(item.typeName) ?? [];
    list.push(copy);
    byType.set(item.typeName, list);
  }
  const groups: AttachmentGroup[] = [...byType.entries()].map(
    ([typeName, groupItems]) => ({
      items: groupItems,
      key: typeName,
      title: `${typeName}（${groupItems.length}）`,
    }),
  );
  if (unlocated.length > 0) {
    groups.push({
      items: unlocated,
      key: 'unlocated',
      title: `未指定位置（${unlocated.length}）`,
      warning: true,
    });
  }
  return groups;
}

/** 随访：按记录时间倒序，再按类型。跨记录的附件在每次记录下各出现一次。 */
export function groupFollowup(items: AttachmentNavItem[]): AttachmentGroup[] {
  const unlocated = items.filter(
    (item) =>
      item.locations.length === 0 &&
      (item.home === 'followup' || item.home === 'both'),
  );
  const records = new Map<
    string,
    {
      date: string;
      label: string;
      types: Map<string, AttachmentNavItem[]>;
    }
  >();

  for (const item of items) {
    const byRecord = new Map<string, AttachmentNavItem['locations']>();
    for (const location of item.locations) {
      if (location.linkType !== 'followup_record') continue;
      const recordId = location.linkRecordId ?? 'record';
      const list = byRecord.get(recordId) ?? [];
      list.push(location);
      byRecord.set(recordId, list);
    }
    for (const [recordId, locations] of byRecord) {
      let bucket = records.get(recordId);
      if (!bucket) {
        bucket = {
          date: locations[0]?.recordDate ?? '',
          label: locations[0]?.recordLabel ?? recordId,
          types: new Map(),
        };
        records.set(recordId, bucket);
      }
      const copy = { ...item, locations };
      const typed = bucket.types.get(item.typeName) ?? [];
      typed.push(copy);
      bucket.types.set(item.typeName, typed);
    }
  }

  const groups: AttachmentGroup[] = [...records.entries()]
    .toSorted((left, right) => (left[1].date < right[1].date ? 1 : -1))
    .map(([recordId, bucket]) => ({
      children: [...bucket.types.entries()].map(([typeName, groupItems]) => ({
        items: groupItems,
        key: `${recordId}-${typeName}`,
        title: `${typeName}（${groupItems.length}）`,
      })),
      items: [],
      key: recordId,
      title: bucket.label,
    }));

  if (unlocated.length > 0) {
    groups.push({
      items: unlocated,
      key: 'unlocated',
      title: `未指定位置（${unlocated.length}）`,
      warning: true,
    });
  }
  return groups;
}

export function itemsForScope(
  items: AttachmentNavItem[],
  scope: 'baseline' | 'followup',
) {
  return items.filter((item) => {
    if (item.locations.length === 0) {
      return item.home === 'both' || item.home === scope;
    }
    if (scope === 'baseline') {
      // E21-T32 / C-PAT-017：植入记录位置在基线资料页签展示（implant 位置计入 baseline 侧）
      return item.locations.some((location) =>
        isBaselineSideLocation(location),
      );
    }
    return item.locations.some(
      (location) => location.linkType === 'followup_record',
    );
  });
}

/** 基线侧位置（基线分类 + 植入记录——C 的植入记录在基线资料页签，C-PAT-017）。 */
function isBaselineSideLocation(
  location: AttachmentNavItem['locations'][number],
) {
  return (
    location.linkType === 'baseline' || location.linkType === 'implant_record'
  );
}

export function countAttachments(items: AttachmentNavItem[]) {
  return items.length;
}
