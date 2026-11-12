/**
 * E16-T11 / A-ATT-019（pages.md §4.6「对照录入」、§5.1 对照录入规则）：
 * 报告单「对照录入」目标解析纯逻辑——一侧显示报告单，另一侧打开该报告单所在
 * 位置的**基线档案**（plt_menu 227 /followup/patient/:id/baseline/edit）或
 * **随访记录表单**（plt_menu 223 /followup/record/:recordId，pages.md §5），
 * 路由统一带 `?compare=<报告单 id>` 由表单页渲染右侧分屏（CompareReportPane）。
 *
 * - 基线位置带 `cat=<锚点分类>` 直达该分类（baseline-edit 已按 query.cat 切节）；
 * - 随访位置缺 linkRecordId（在册但记录不可达）→ null；implant_record 随 E21-T32
 *   开放，暂不可达（AGENTS.md：不实现首期不做范围外的位置类型）；
 * - 同一表单去重：整条记录与其分组（section）指向同一条记录 → 只留一项，
 *   标签保留带分组的一条（「第3年随访 · 超声复查」比「第3年随访」更精确）；
 * - 按目标表单的编辑权限过滤入口（基线 followup:patient:edit、随访
 *   followup:record:edit；§4.10 只读角色不显示操作按钮），页面内仍有各自的
 *   权限 / 只读兜底（前端隐藏不能代替后端校验）。
 */
import type { ReportLocationTag, ReportRow } from '@crdp/api';

import { reportLocationText } from '@crdp/api';

/** 对照录入路由参数名（表单页据此挂右侧分屏）。 */
export const COMPARE_QUERY_KEY = 'compare';

/** 从路由 query 取对照报告单 id（缺失 / 非字符串 / 空串 → null）。 */
export function compareReportIdOf(
  query: Record<string, unknown>,
): null | string {
  const value = query[COMPARE_QUERY_KEY];
  return typeof value === 'string' && value ? value : null;
}

/** 对照录入的编辑权限（目标表单各自的编辑权限码）。 */
export interface CompareEditPermissions {
  /** 基线分节编辑：followup:patient:edit */
  canEditBaseline: boolean;
  /** 随访记录编辑：followup:record:edit */
  canEditRecord: boolean;
}

/** 一个可对照录入的目标表单（多个时先弹选择，A-ATT-019「有多个位置时选择」）。 */
export interface CompareTarget {
  /** 去重键 = 路由（同一表单只给一个选项）。 */
  key: string;
  /** 位置标签：「基线 · 病例基本信息」「第3年随访 · 超声复查」（列表同口径）。 */
  label: string;
  /** 新标签页打开的路由（含 compare 参数）。 */
  route: string;
}

/** 单个位置 → 对照录入路由（不可达返回 null）。 */
export function compareRouteOf(
  patientId: number | string,
  location: ReportLocationTag,
  reportId: number | string,
): null | string {
  const compare = `${COMPARE_QUERY_KEY}=${encodeURIComponent(String(reportId))}`;
  if (location.linkType === 'baseline') {
    const cat = location.anchorKey
      ? `&cat=${encodeURIComponent(location.anchorKey)}`
      : '';
    return `/followup/patient/${patientId}/baseline/edit?${compare}${cat}`;
  }
  if (location.linkType === 'followup_record' && location.linkRecordId) {
    return `/followup/record/${location.linkRecordId}?${compare}`;
  }
  return null;
}

/** 报告单的全部可对照目标（按位置顺序；同表单去重；无权限 / 不可达过滤）。 */
export function compareTargetsOf(
  row: Pick<ReportRow, 'id' | 'locations' | 'patientId'>,
  permissions: CompareEditPermissions,
): CompareTarget[] {
  const targets: CompareTarget[] = [];
  const byRoute = new Map<string, CompareTarget>();
  for (const location of row.locations) {
    if (location.linkType === 'implant_record') continue; // E21-T32 起开放
    if (location.linkType === 'baseline') {
      if (!permissions.canEditBaseline) continue;
    } else if (!permissions.canEditRecord) {
      continue;
    }
    const route = compareRouteOf(row.patientId, location, row.id);
    if (!route) continue;
    const label = reportLocationText(location);
    const existing = byRoute.get(route);
    if (existing) {
      // 同表单去重：标签保留带锚点的一条（整条 + 分组指向同一记录）
      if (!existing.label.includes('·') && label.includes('·')) {
        existing.label = label;
      }
      continue;
    }
    const target: CompareTarget = { key: route, label, route };
    byRoute.set(route, target);
    targets.push(target);
  }
  return targets;
}

// ------------------------------ E21-T17 转录到结构化记录（C-SCAN-012 / C-FU-017）

/** 转录标记参数名（?transcribe=1 与 ?compare= 报告单 id 配对；只有显式转录态的保存才把报告单置「已电子化」）。 */
export const TRANSCRIBE_QUERY_KEY = 'transcribe';

/**
 * 转录态判定（表单页提交口径）：compare 与 transcribe=1 两参数齐备才是转录——
 * 普通对照录入（只带 compare，如对照心脏超声报告录随访）不改电子化状态。
 */
export function transcribeActiveOf(query: Record<string, unknown>): boolean {
  const flag = query[TRANSCRIBE_QUERY_KEY];
  return (
    typeof flag === 'string' &&
    flag === '1' &&
    compareReportIdOf(query) !== null
  );
}

/**
 * 可转录的电子化状态（C-SCAN-011 / C-SCAN-012）：待录入与未设置（类型 / 名称未配置默认值）
 * 可转录；已电子化 = 已转录过、无需录入 = 手工豁免，都不再给转录入口。
 */
export function transcribableStatus(status: null | string): boolean {
  return status === null || status === 'PENDING';
}

/** 单个位置 → 转录路由（只认随访记录位置；基线 / 植入位置不是转录目标，E21-T32 前不可达）。 */
export function transcribeRouteOf(
  patientId: number | string,
  location: ReportLocationTag,
  reportId: number | string,
): null | string {
  if (location.linkType !== 'followup_record' || !location.linkRecordId) {
    return null;
  }
  const compare = `${COMPARE_QUERY_KEY}=${encodeURIComponent(String(reportId))}`;
  return `/followup/record/${location.linkRecordId}?${compare}&${TRANSCRIBE_QUERY_KEY}=1`;
}

/**
 * 报告单的全部转录目标（C-SCAN-012「位置中的随访记录，多个时选择」）：只看随访记录位置
 * （整条与分组指向同一记录时去重，标签保留带分组的一条）；权限与状态门槛由调用方把关
 * （followup:record:edit + transcribableStatus）。
 */
export function transcribeTargetsOf(
  row: Pick<ReportRow, 'id' | 'locations' | 'patientId'>,
): CompareTarget[] {
  const targets: CompareTarget[] = [];
  const byRecord = new Map<string, CompareTarget>();
  for (const location of row.locations) {
    if (!location.linkRecordId) continue;
    const route = transcribeRouteOf(row.patientId, location, row.id);
    if (!route) continue;
    const label = reportLocationText(location);
    const existing = byRecord.get(location.linkRecordId);
    if (existing) {
      // 同一记录去重：标签保留带分组的一条（比「第3次随访」更精确）
      if (!existing.label.includes('·') && label.includes('·')) {
        existing.label = label;
      }
      continue;
    }
    const target: CompareTarget = { key: route, label, route };
    byRecord.set(location.linkRecordId, target);
    targets.push(target);
  }
  return targets;
}
