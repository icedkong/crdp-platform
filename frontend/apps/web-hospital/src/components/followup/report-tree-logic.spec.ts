// E16-T04 / A-ATT-010、C-SCAN-013（pages.md §4.6）：报告单页签左侧双树纯逻辑
// （按类型「类型 → 名称，带数量」/ 按时间「报告时间 → 名称，时间倒序」；同名报告单
// 按报告时间区分 A-ATT-011；两棵树覆盖同一批报告单、数量合计一致）
import type { ReportRow } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  buildReportTree,
  findTreeNode,
  groupTreeKeys,
  reportsForKey,
  treeFilterLabel,
} from './report-tree-logic';

function row(partial: Partial<ReportRow> & { id: string }): ReportRow {
  return {
    conclusion: null,
    createdAt: '2026-11-01T10:00:00+08:00',
    creatorName: '张医生（示例）',
    digitizeStatus: null,
    fileCount: 1,
    findings: null,
    locations: [],
    name: null,
    note: null,
    patientId: '301',
    reportDate: '2026-10-28',
    typeCode: 'echo',
    typeName: '心脏超声',
    version: 1,
    ...partial,
  };
}

// 5 份：心脏超声 2（同名「经胸超声心动图」两次）、检验 2（血常规 / 血脂）、出院小结 1
const reports: ReportRow[] = [
  row({ id: 'r1', name: '经胸超声心动图', reportDate: '2026-10-28' }),
  row({ id: 'r2', name: '经胸超声心动图', reportDate: '2026-03-12' }),
  row({
    id: 'r3',
    name: '血常规',
    reportDate: '2026-09-01',
    typeCode: 'lab',
    typeName: '检验',
  }),
  row({
    id: 'r4',
    name: '血脂',
    reportDate: '2026-10-28',
    typeCode: 'lab',
    typeName: '检验',
  }),
  row({
    id: 'r5',
    name: null,
    reportDate: '2026-05-20',
    typeCode: 'discharge',
    typeName: '出院小结',
  }),
];

/** 分组数量合计（「数量正确」的总口径：两棵树都等于全部份数）。 */
function sumCounts(nodes: ReturnType<typeof buildReportTree>): number {
  let total = 0;
  for (const node of nodes) total += node.count ?? 0;
  return total;
}

describe('e16-t04 报告单双树 · 按类型（类型 → 名称，带数量）', () => {
  it('类型节点带数量，数量 = 该类型报告单份数，合计 = 全部', () => {
    const tree = buildReportTree(reports, 'type');
    expect(tree.map((node) => node.title)).toEqual([
      '心脏超声（2）',
      '检验（2）',
      '出院小结（1）',
    ]);
    expect(sumCounts(tree)).toBe(reports.length);
  });

  it('类型下二级为名称叶子；同名多次按报告时间区分（A-ATT-011）', () => {
    const echo = buildReportTree(reports, 'type')[0];
    expect(echo?.children?.map((leaf) => leaf.title)).toEqual([
      '经胸超声心动图 · 2026-10-28',
      '经胸超声心动图 · 2026-03-12',
    ]);
    const lab = buildReportTree(reports, 'type')[1];
    expect(lab?.children?.map((leaf) => leaf.title)).toEqual([
      '血常规',
      '血脂',
    ]);
  });

  it('名称未填的显示「（未命名）」；组内多份未命名按报告时间区分', () => {
    const discharge = buildReportTree(reports, 'type')[2];
    expect(discharge?.children?.[0]?.title).toBe('（未命名）');
    expect(discharge?.children?.[0]?.reportId).toBe('r5');

    const two = buildReportTree(
      [
        row({
          id: 'n1',
          name: null,
          reportDate: '2026-05-20',
          typeCode: 'discharge',
          typeName: '出院小结',
        }),
        row({
          id: 'n2',
          name: null,
          reportDate: '2026-01-02',
          typeCode: 'discharge',
          typeName: '出院小结',
        }),
      ],
      'type',
    )[0];
    expect(two?.children?.map((leaf) => leaf.title)).toEqual([
      '（未命名） · 2026-05-20',
      '（未命名） · 2026-01-02',
    ]);
  });

  it('叶子 key 唯一（r:{id}），可直接定位单份报告单', () => {
    const leaves = buildReportTree(reports, 'type').flatMap(
      (node) => node.children ?? [],
    );
    const keys = new Set(leaves.map((leaf) => leaf.key));
    expect(keys.size).toBe(reports.length);
    expect(
      reportsForKey(reports, 'type', 'r:r3').map((item) => item.id),
    ).toEqual(['r3']);
  });
});

describe('e16-t04 报告单双树 · 按时间（报告时间 → 名称，时间倒序）', () => {
  it('日期分组按报告时间倒序，节点带数量，合计 = 全部', () => {
    const tree = buildReportTree(reports, 'time');
    expect(tree.map((node) => node.title)).toEqual([
      '2026-10-28（2）',
      '2026-09-01（1）',
      '2026-05-20（1）',
      '2026-03-12（1）',
    ]);
    expect(sumCounts(tree)).toBe(reports.length);
  });

  it('日期下二级为名称叶子；同日同名多份按序号区分', () => {
    const sameDay = buildReportTree(
      [
        row({ id: 'a1', name: '血常规', reportDate: '2026-10-28' }),
        row({ id: 'a2', name: '血常规', reportDate: '2026-10-28' }),
      ],
      'time',
    )[0];
    expect(sameDay?.children?.map((leaf) => leaf.title)).toEqual([
      '血常规 · 第1份',
      '血常规 · 第2份',
    ]);
  });

  it('两棵树切换覆盖同一批报告单，叶子数与总数一致', () => {
    const byType = buildReportTree(reports, 'type');
    const byTime = buildReportTree(reports, 'time');
    const leafKeys = (nodes: ReturnType<typeof buildReportTree>) =>
      nodes.flatMap((node) => node.children?.map((leaf) => leaf.key) ?? []);
    expect(leafKeys(byType)).toHaveLength(reports.length);
    expect(leafKeys(byTime)).toHaveLength(reports.length);
    expect(new Set(leafKeys(byType)).size).toBe(reports.length);
    expect(new Set(leafKeys(byTime)).size).toBe(reports.length);
  });
});

describe('e16-t04 报告单双树 · 选中与过滤', () => {
  it('reportsForKey：类型键 / 日期键 / 单份键 / null=全部', () => {
    expect(reportsForKey(reports, 'type', 'type:lab').map((r) => r.id)).toEqual(
      ['r3', 'r4'],
    );
    expect(
      reportsForKey(reports, 'time', 'date:2026-10-28').map((r) => r.id),
    ).toEqual(['r1', 'r4']);
    expect(reportsForKey(reports, 'type', null)).toHaveLength(5);
    // 未知键兜底为全部（不应出现，防御）
    expect(reportsForKey(reports, 'type', 'nope')).toHaveLength(5);
  });

  it('findTreeNode / groupTreeKeys / treeFilterLabel', () => {
    const tree = buildReportTree(reports, 'type');
    const timeTree = buildReportTree(reports, 'time');
    const nodeOrThrow = (nodes: typeof tree, key: string) => {
      const hit = findTreeNode(nodes, key);
      if (!hit) throw new Error(`树里没有节点 ${key}`);
      return hit;
    };
    expect(findTreeNode(tree, 'type:lab')?.label).toBe('检验');
    expect(findTreeNode(tree, 'r:r5')?.label).toBe('（未命名）');
    expect(findTreeNode(tree, 'nope')).toBeNull();
    expect(groupTreeKeys(tree)).toEqual([
      'type:echo',
      'type:lab',
      'type:discharge',
    ]);
    expect(treeFilterLabel(nodeOrThrow(tree, 'type:echo'))).toBe(
      '类型：心脏超声',
    );
    expect(treeFilterLabel(nodeOrThrow(timeTree, 'date:2026-10-28'))).toBe(
      '时间：2026-10-28',
    );
    expect(treeFilterLabel(nodeOrThrow(tree, 'r:r1'))).toBe(
      '报告单：经胸超声心动图 · 2026-10-28',
    );
  });

  it('空列表两棵树都为空', () => {
    expect(buildReportTree([], 'type')).toEqual([]);
    expect(buildReportTree([], 'time')).toEqual([]);
  });
});
