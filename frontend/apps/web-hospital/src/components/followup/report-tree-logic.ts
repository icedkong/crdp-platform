/**
 * E16-T04 / A-ATT-010、C-SCAN-013（pages.md §4.6）：报告单页签左侧双树浏览的纯逻辑。
 * 按类型（类型 → 名称，带数量）/ 按时间（报告时间 → 名称，时间倒序）；同名报告单按
 * 报告时间区分（A-ATT-011）；两棵树覆盖同一批报告单，分组数量合计恒等于总份数。
 * 组件（report-tree.vue）保持薄壳，可测口径集中在这里。
 */
import type { ReportRow } from '@crdp/api';

/** 树模式：type 按类型 / time 按时间。 */
export type ReportTreeMode = 'time' | 'type';

/** 节点粒度：分组（类型 / 日期）或单份报告单叶子。 */
export type ReportTreeNodeKind = 'date' | 'report' | 'type';

/** 树节点（antd a-tree 的 treeData 形状 + label / count 供筛选标签使用）。 */
export interface ReportTreeNode {
  children?: ReportTreeNode[];
  /** 分组内报告单份数（叶子无） */
  count?: number;
  /** 纯文案（不含数量），供筛选标签等使用 */
  label: string;
  kind: ReportTreeNodeKind;
  key: string;
  /** 叶子才有：报告单 id */
  reportId?: string;
  /** 显示文案（分组含数量，如「心脏超声（2）」） */
  title: string;
}

const UNNAMED = '（未命名）';
const UNDATED = '（未填报告时间）';

/** 报告时间分组键（未填的报告单归入「未填报告时间」组，排最后）。 */
function dateKey(row: ReportRow): string {
  return row.reportDate || UNDATED;
}

/** 类型树：类型分组（列表首现顺序）→ 名称叶子；同名多次按报告时间区分（A-ATT-011）。 */
function buildTypeTree(reports: ReportRow[]): ReportTreeNode[] {
  const groups = new Map<string, ReportRow[]>();
  for (const row of reports) {
    const bucket = groups.get(row.typeCode);
    if (bucket) bucket.push(row);
    else groups.set(row.typeCode, [row]);
  }
  return [...groups.entries()].map(([typeCode, rows]) => {
    const label = rows[0]?.typeName ?? typeCode;
    return {
      children: typeLeaves(rows),
      count: rows.length,
      key: `type:${typeCode}`,
      kind: 'type' as const,
      label,
      title: `${label}（${rows.length}）`,
    };
  });
}

/** 类型下的名称叶子：同名多次 → 「名称 · 报告时间」（仍同名同日再追加序号）。 */
function typeLeaves(rows: ReportRow[]): ReportTreeNode[] {
  const nameCounts = new Map<string, number>();
  const dateCounts = new Map<string, number>();
  for (const row of rows) {
    const name = row.name ?? UNNAMED;
    nameCounts.set(name, (nameCounts.get(name) ?? 0) + 1);
    dateCounts.set(
      `${name}|${row.reportDate}`,
      (dateCounts.get(`${name}|${row.reportDate}`) ?? 0) + 1,
    );
  }
  const seen = new Map<string, number>();
  return rows.map((row) => {
    const name = row.name ?? UNNAMED;
    let label = name;
    if ((nameCounts.get(name) ?? 0) > 1) {
      label = `${name} · ${row.reportDate || UNDATED}`;
      const same = (dateCounts.get(`${name}|${row.reportDate}`) ?? 0) > 1;
      if (same) {
        const seq = (seen.get(`${name}|${row.reportDate}`) ?? 0) + 1;
        seen.set(`${name}|${row.reportDate}`, seq);
        label = `${label} · 第${seq}份`;
      }
    }
    return {
      key: `r:${row.id}`,
      kind: 'report' as const,
      label,
      reportId: row.id,
      title: label,
    };
  });
}

/** 时间树：报告时间分组（倒序，未填最后）→ 名称叶子；同日同名按序号区分。 */
function buildTimeTree(reports: ReportRow[]): ReportTreeNode[] {
  const groups = new Map<string, ReportRow[]>();
  for (const row of reports) {
    const key = dateKey(row);
    const bucket = groups.get(key);
    if (bucket) bucket.push(row);
    else groups.set(key, [row]);
  }
  return [...groups.entries()]
    .toSorted((a, b) => {
      if (a[0] === UNDATED) return 1;
      if (b[0] === UNDATED) return -1;
      if (a[0] !== b[0]) return a[0] < b[0] ? 1 : -1;
      return 0;
    })
    .map(([date, rows]) => ({
      children: timeLeaves(rows),
      count: rows.length,
      key: `date:${date}`,
      kind: 'date' as const,
      label: date,
      title: `${date}（${rows.length}）`,
    }));
}

/** 日期下的名称叶子：同日同名多份 → 「名称 · 第n份」。 */
function timeLeaves(rows: ReportRow[]): ReportTreeNode[] {
  const nameCounts = new Map<string, number>();
  for (const row of rows) {
    const name = row.name ?? UNNAMED;
    nameCounts.set(name, (nameCounts.get(name) ?? 0) + 1);
  }
  const seen = new Map<string, number>();
  return rows.map((row) => {
    const name = row.name ?? UNNAMED;
    let label = name;
    if ((nameCounts.get(name) ?? 0) > 1) {
      const seq = (seen.get(name) ?? 0) + 1;
      seen.set(name, seq);
      label = `${name} · 第${seq}份`;
    }
    return {
      key: `r:${row.id}`,
      kind: 'report' as const,
      label,
      reportId: row.id,
      title: label,
    };
  });
}

/** 构建双树之一（mode：type / time）。 */
export function buildReportTree(
  reports: ReportRow[],
  mode: ReportTreeMode,
): ReportTreeNode[] {
  return mode === 'type' ? buildTypeTree(reports) : buildTimeTree(reports);
}

/** 深度查找节点（选中键 → 节点标签；找不到返回 null）。 */
export function findTreeNode(
  nodes: ReportTreeNode[],
  key: string,
): null | ReportTreeNode {
  for (const node of nodes) {
    if (node.key === key) return node;
    if (node.children) {
      const hit = findTreeNode(node.children, key);
      if (hit) return hit;
    }
  }
  return null;
}

/** 选中键 → 列表过滤（null = 全部；未知键兜底为全部，防御）。 */
export function reportsForKey(
  reports: ReportRow[],
  mode: ReportTreeMode,
  key: null | string,
): ReportRow[] {
  if (!key) return reports;
  if (key.startsWith('type:')) {
    const code = key.slice('type:'.length);
    return reports.filter((row) => row.typeCode === code);
  }
  if (key.startsWith('date:')) {
    const date = key.slice('date:'.length);
    return reports.filter((row) => dateKey(row) === date);
  }
  if (key.startsWith('r:')) {
    const id = key.slice('r:'.length);
    return reports.filter((row) => row.id === id);
  }
  return reports;
}

/** 选中节点的筛选标签文案（列表工具栏显示）：「类型：心脏超声」「时间：2026-10-28」。 */
export function treeFilterLabel(node: ReportTreeNode): string {
  if (node.kind === 'type') return `类型：${node.label}`;
  if (node.kind === 'date') return `时间：${node.label}`;
  return `报告单：${node.label}`;
}

/** 全部分组键（树的默认展开键；叶子不展开）。 */
export function groupTreeKeys(nodes: ReportTreeNode[]): string[] {
  return nodes.flatMap((node) =>
    node.kind === 'report'
      ? []
      : [node.key, ...(node.children ? groupTreeKeys(node.children) : [])],
  );
}
