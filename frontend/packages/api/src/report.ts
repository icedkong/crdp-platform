/**
 * E16-T02 / A-ATT-001、A-ATT-002；C-SCAN-001、C-SCAN-010：报告单运行时
 * （docs/api/report.md §2）——添加报告单对话框、位置选择（必填多选树）与患者内清单。
 *
 * 位置粒度三种（不细到字段）：基线某分类、某条随访记录整条、某条随访记录中的某分组；
 * C 的植入记录锚点（implant_record）随 E21-T32 开放。「修改位置」与按模板版本校验随 E16-T13 落地
 * （PUT /reports/{id}/locations，至少保留 1 个位置）。
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

/**
 * E16-T03 批量上传（A-ATT-014、C-SCAN-007，POST /followup/reports/batch-create）：
 * 先统一选择类型与位置（至少 1 个，可逐份调整），ONE_PER_FILE 每个文件一份、
 * MERGE 合并为一份多页资料；一次最多 50 个文件，单份上限沿用上传策略 maxFilesPerReport。
 */
export type ReportBatchMode = 'MERGE' | 'ONE_PER_FILE';

/** 逐份调整（ONE_PER_FILE；未填的项沿用公共值）。 */
export interface ReportBatchItemOverride {
  fileId: string;
  locations?: ReportLocationInput[];
  nameId?: null | string;
  reportDate?: string;
}

/** 批量上传请求（公共类型 / 名称 / 报告时间 / 位置 + 文件清单与逐份调整）。 */
export interface ReportBatchCreateRequest {
  fileIds: string[];
  items?: ReportBatchItemOverride[];
  locations: ReportLocationInput[];
  /** 每个文件一份 / 合并为一份多页资料 */
  mode: ReportBatchMode;
  nameId?: null | string;
  patientId: string;
  reportDate: string;
  typeCode: string;
}

/** 批量上传结果（reports 顺序与文件一致；MERGE 为单元素）。 */
export interface ReportBatchCreatedView {
  fileCount: number;
  mode: ReportBatchMode;
  reportCount: number;
  /** 首份报告单 id（汇总审计objectId 同值）；reports 为全部创建结果 */
  reports: ReportCreatedView[];
}

/** 首附件（患者内清单行，E16-T14：缩略图 / 文件图标数据源；口径同全院检索行）。 */
export interface ReportFirstFile {
  fileId: string;
  format: string;
}

/** 患者内报告单行（报告单页签列表；左栏附件导航消费，E16-T14）。 */
export interface ReportRow {
  conclusion: null | string;
  createdAt: string;
  creatorName: null | string;
  digitizeStatus: null | string;
  fileCount: number;
  findings: null | string;
  firstFile: null | ReportFirstFile;
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

// ---------------------------------------------------------------- E16-T09 删除（A-ATT-007 / C-SCAN-016）

/** 删除结果（软删除：status=DELETED，数据保留、写 RPT_DELETE / RPT_FILE_DELETE 审计）。 */
export interface ReportDeletedView {
  id: string;
  status: 'DELETED';
}

// ---------------------------------------------------------------- E16-T13 修改位置（A-ATT-020 / C-SCAN-020）

/** 移除位置引用：按四元组（linkType / linkRecordId / anchorType / anchorKey）匹配在册行，模板版本不参与。 */
export interface ReportLocationRemoveInput {
  anchorKey?: string;
  anchorType?: ReportAnchorType;
  linkRecordId?: string;
  linkType: ReportLinkType;
}

/** 修改位置请求（PUT /followup/reports/{id}/locations）：add / remove 至少一项非空；结果至少保留 1 个位置（40001）。 */
export interface ReportLocationsChangeRequest {
  add?: ReportLocationInput[];
  remove?: ReportLocationRemoveInput[];
}

/** 修改位置结果（回读位置标签，与患者内清单同形；无位置报告单经 add 补指定——「未指定位置」组的指定位置入口）。 */
export interface ReportLocationsChangedView {
  id: string;
  locationCount: number;
  locations: ReportLocationTag[];
}

// ---------------------------------------------------------------- E16-T05 全院报告单（A-ATT-016 / C-SCAN-014）

/** 位置筛选（A-ATT-016「位置（含『未指定位置』）」）：不传 = 全部。 */
export type ReportLocationScope = 'BASELINE' | 'FOLLOWUP' | 'UNASSIGNED';

/** 全院列表行电子化状态（含转录产生的 DONE；A 租户不启用、为 null，C-SCAN-011）。 */
export type ReportRowDigitizeStatus = 'DONE' | 'NO_ENTRY' | 'PENDING';

/** 全院报告单检索请求（POST /followup/reports/search；字段全部可空 = 不过滤）。 */
export interface ReportSearchRequest {
  creatorId?: string;
  digitizeStatus?: null | ReportRowDigitizeStatus;
  keyword?: string;
  locationScope?: null | ReportLocationScope;
  pageNo?: number;
  pageSize?: number;
  reportDateFrom?: string;
  reportDateTo?: string;
  typeCode?: string;
  uploadedFrom?: string;
  uploadedTo?: string;
  wardIds?: string[];
}

/** 缩略图首附件（图片走预览短时链接，非图片显示文件类型图标）。 */
export interface ReportSearchFirstFile {
  fileId: string;
  format: string;
}

/** 全院报告单行（姓名 / 病案号只含脱敏值；locations 为空 = 未指定位置，前端显示橙色标签）。 */
export interface ReportSearchRow {
  createdAt: string;
  creatorName: null | string;
  digitizeStatus: null | ReportRowDigitizeStatus;
  fileCount: number;
  firstFile: null | ReportSearchFirstFile;
  id: string;
  locations: ReportLocationTag[];
  mrn: string;
  name: null | string;
  patientId: string;
  patientName: string;
  reportDate: string;
  typeCode: string;
  typeName: string;
  visitNo: null | string;
  wardName: null | string;
}

/** 全院检索分页结果（统一分页口径 + 类型 / 上传人 / 病区候选）。 */
export interface ReportSearchPage {
  items: ReportSearchRow[];
  pageNo: number;
  pageSize: number;
  total: number;
  types: Array<{ code: string; name: string }>;
  uploaders: Array<{ id: string; name: string }>;
  wards: Array<{ id: string; name: string }>;
}

/**
 * 位置列折叠（A-ATT-016 / C-SCAN-014「超过 2 个折叠为 +N，悬停显示全部」）：
 * 返回可见标签与折叠计数；空清单由调用方显示「未指定位置」。
 */
export function foldReportLocations(
  tags: ReportLocationTag[],
  max = 2,
): { all: string; extraCount: number; visible: string[] } {
  const texts = tags.map((tag) => reportLocationText(tag));
  const visible = texts.slice(0, max);
  return {
    all: texts.join('、'),
    extraCount: Math.max(texts.length - visible.length, 0),
    visible,
  };
}

/** 全院列表行电子化状态文案（C-SCAN-011；DONE 只由转录产生；A 的 null 行不显示）。 */
export function reportRowDigitizeStatusText(
  status: null | ReportRowDigitizeStatus,
): null | string {
  if (!status) return null;
  const text: Record<ReportRowDigitizeStatus, string> = {
    DONE: '已电子化',
    NO_ENTRY: '无需录入',
    PENDING: '待录入',
  };
  return text[status];
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
