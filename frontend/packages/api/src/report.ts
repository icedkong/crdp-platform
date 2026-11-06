/**
 * E16-T02 / A-ATT-001、A-ATT-002；C-SCAN-001、C-SCAN-010：报告单运行时
 * （docs/api/report.md §2）——添加报告单对话框、位置选择（必填多选树）与患者内清单。
 *
 * 位置粒度三种（不细到字段）：基线某分类、某条随访记录整条、某条随访记录中的某分组；
 * C 的植入记录锚点（implant_record）随 E21-T32 开放。模板版本校验与「修改位置」随 E16-T13。
 * 运行时目录类型（ReportCatalogType / ReportCatalogName）见 report-config.ts（E16-T01）。
 */

/** 位置链接类型（implant_record 随 E21-T32）。 */
export type ReportLinkType = 'baseline' | 'followup_record' | 'implant_record';

/** 锚点粒度：category 基线分类 / section 随访分组 / null 整条记录。 */
export type ReportAnchorType = 'category' | 'section' | null;

/** 位置输入（components.md §8 ReportLocationInput；提交时由位置树带出、原样回传）。 */
export interface ReportLocationInput {
  anchorKey?: string;
  /** 锚点所属模板版本（由 options 带出；E16-T13 起后端按版本校验） */
  anchorTemplateVersion: number;
  anchorType?: ReportAnchorType;
  linkRecordId?: string;
  linkType: ReportLinkType;
}

/** 位置树节点（GET /patients/{id}/report-locations；selectable=false 为分组节点）。 */
export interface ReportLocationNode {
  children?: ReportLocationNode[];
  key: string;
  selectable: boolean;
  title: string;
  /** 叶子 / 可选节点才有 */
  value?: ReportLocationInput;
}

/** 位置标签（患者内清单行；recordLabel / anchorLabel 直接显示）。 */
export interface ReportLocationTag {
  adjusted?: boolean;
  anchorKey?: string;
  anchorLabel?: string;
  anchorType?: ReportAnchorType;
  linkRecordId?: string;
  linkType: ReportLinkType;
  recordDate?: string;
  recordLabel?: string;
  templateVersion: number;
}

/** 指标结果输入（refRange 为前端按患者性别选定后的参考范围显示串）。 */
export interface ReportIndicatorInput {
  itemCode: string;
  refRange?: null | string;
  result: string;
}

/** 创建请求（POST /followup/reports；locations ≥ 1）。 */
export interface ReportCreateRequest {
  conclusion?: null | string;
  /** 类型专属字段值（键 = 该类型 REPORT 表单稳定键） */
  fields?: Record<string, unknown>;
  fileIds?: string[];
  findings?: null | string;
  indicators?: ReportIndicatorInput[];
  /** 位置必填、可多选（A-ATT-002 / C-SCAN-010） */
  locations: ReportLocationInput[];
  nameId?: null | string;
  note?: null | string;
  patientId: string;
  reportDate: string;
  typeCode: string;
}

/** 创建结果。 */
export interface ReportCreatedView {
  fileCount: number;
  id: string;
  locationCount: number;
  name: null | string;
  reportDate: string;
  typeCode: string;
  typeName: string;
}

/** 患者内报告单行（报告单页签列表；左栏附件导航完整形态随 E16-T14）。 */
export interface ReportRow {
  conclusion: null | string;
  createdAt: string;
  creatorName: null | string;
  digitizeStatus: null | string;
  fileCount: number;
  findings: null | string;
  id: string;
  locations: ReportLocationTag[];
  name: null | string;
  note: null | string;
  patientId: string;
  reportDate: string;
  typeCode: string;
  typeName: string;
  version: number;
}

/** 患者内清单响应（scope=all 默认 / baseline / followup）。 */
export interface PatientReportsView {
  items: ReportRow[];
  patientId: string;
  scope: 'all' | 'baseline' | 'followup';
}

/**
 * 位置标签文案（列表 / 详情显示）：「基线 · 病例基本信息」「第3年随访 · 超声复查」；
 * 整条记录无锚点时只显示记录名；adjusted 追加「原位置已调整」（E16-T15 起）。
 */
export function reportLocationText(tag: ReportLocationTag): string {
  const scope =
    tag.linkType === 'baseline' ? '基线' : (tag.recordLabel ?? '随访记录');
  const anchor = tag.anchorLabel ?? '';
  const text = anchor ? `${scope} · ${anchor}` : scope;
  return tag.adjusted ? `${text}（原位置已调整）` : text;
}

/**
 * ↑ / ↓ 标记（A-ATT-012，与服务端同口径的容错解析）：结果与参考范围都可解析为数值时比较，
 * 超上限 '↑'、低于下限 '↓'，否则不标。范围支持「3.5–9.5」「≤ 3.4」「＜125」「≥ 60」。
 */
export function reportIndicatorFlag(
  refRange: null | string,
  result: null | string,
): '↑' | '↓' | null {
  if (!refRange || !result) return null;
  const value = Number.parseFloat(result.trim());
  if (Number.isNaN(value) || !/^-?[0-9]+(\.[0-9]+)?$/.test(result.trim()))
    return null;
  const range = refRange.trim();
  let match = range.match(
    /^([0-9]+(?:\.[0-9]+)?)\s*[-–~～至]\s*([0-9]+(?:\.[0-9]+)?)$/,
  );
  if (match) {
    if (value < Number.parseFloat(match[1] ?? '0')) return '↓';
    return value > Number.parseFloat(match[2] ?? '0') ? '↑' : null;
  }
  match = range.match(/^(?:≤|<=|＜|<)\s*([0-9]+(?:\.[0-9]+)?)$/);
  if (match) return value > Number.parseFloat(match[1] ?? '0') ? '↑' : null;
  match = range.match(/^(?:≥|>=|＞|>)\s*([0-9]+(?:\.[0-9]+)?)$/);
  if (match) return value < Number.parseFloat(match[1] ?? '0') ? '↓' : null;
  return null;
}

/** 参考范围按性别选定（A-ATT-012 参考范围可按性别区分；未知性别用通用列）。 */
export function reportRefRangeFor(
  item: {
    refRange: null | string;
    refRangeFemale: null | string;
    refRangeMale: null | string;
  },
  gender?: 'F' | 'M' | null | string,
): null | string {
  if (gender === 'M' && item.refRangeMale) return item.refRangeMale;
  if (gender === 'F' && item.refRangeFemale) return item.refRangeFemale;
  return item.refRange ?? item.refRangeMale ?? item.refRangeFemale ?? null;
}
