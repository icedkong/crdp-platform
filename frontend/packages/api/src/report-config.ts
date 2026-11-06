/**
 * E16-T01 / A-ATT-003、A-ATT-011、A-ATT-012、A-ATT-018；C-SCAN-002、003、004：
 * 报告单设置——资料类型、名称字典与指标模板（docs/api/report.md §2、§6，页面 /system/report-config）。
 *
 * 平台通用、按院配置（A-ATT-018）：同一套接口，医院差异全部在配置行。类型编码创建后不可改；
 * 停用不删除（添加报告单不再可选）；名称同类型唯一、同名报告单可多次添加（A-ATT-011）。
 */

/** 启用 / 停用（类型、名称、模板共用；停用不删除）。 */
export type ReportConfigStatus = 'DISABLED' | 'ENABLED';

/** 电子化状态取值（C-SCAN-011；A 医院不启用、为 null；DONE 只由转录产生，不作配置默认值）。 */
export type ReportDigitizeStatus = 'NO_ENTRY' | 'PENDING';

/** 资料类型行（GET /report-config/types）。 */
export interface ReportTypeView {
  code: string;
  /** 默认电子化状态（C；A 为 null） */
  defaultDigitizeStatus: null | ReportDigitizeStatus;
  /** 字段集（fe_form REPORT 表单编码） */
  formCode: string;
  formName: null | string;
  id: string;
  name: string;
  /** 类型下名称数（含停用） */
  nameCount: number;
  /** 名称是否必填（C-SCAN-003：检查、检验类必填，其他类型选填） */
  nameRequired: boolean;
  remark: null | string;
  sortNo: number;
  status: ReportConfigStatus;
}

/** 名称字典行（GET /report-config/names）。 */
export interface ReportNameView {
  digitizeStatusOverride: null | ReportDigitizeStatus;
  id: string;
  indicatorTplId: null | string;
  indicatorTplName: null | string;
  name: string;
  sortNo: number;
  status: ReportConfigStatus;
  typeCode: string;
  typeName: string;
}

/** 指标模板项目（A-ATT-012：项目名称、代码、参考范围、单位、顺序；参考范围可按性别区分）。 */
export interface ReportIndicatorItemView {
  itemCode: string;
  itemName: string;
  refRange: null | string;
  refRangeFemale: null | string;
  refRangeMale: null | string;
  sortNo: number;
  unit: null | string;
}

/** 指标模板行（GET /report-config/indicator-templates，items 按顺序）。 */
export interface ReportIndicatorTplView {
  /** 绑定该模板的名称数（含停用；> 0 时删除被 40901 拦截） */
  boundNameCount: number;
  id: string;
  items: ReportIndicatorItemView[];
  name: string;
  remark: null | string;
  status: ReportConfigStatus;
}

/** 新建类型请求（POST /report-config/types）。 */
export interface ReportTypeCreateRequest {
  code: string;
  defaultDigitizeStatus?: null | ReportDigitizeStatus;
  formCode: string;
  name: string;
  nameRequired?: boolean;
  remark?: null | string;
  sortNo?: number;
}

/** 编辑类型请求（PUT /report-config/types/{id}；编码不可改）。 */
export interface ReportTypeUpdateRequest {
  defaultDigitizeStatus?: null | ReportDigitizeStatus;
  formCode: string;
  name: string;
  nameRequired?: boolean;
  remark?: null | string;
  sortNo?: number;
  status: ReportConfigStatus;
}

/** 新建名称请求（POST /report-config/names）。 */
export interface ReportNameCreateRequest {
  digitizeStatusOverride?: null | ReportDigitizeStatus;
  indicatorTplId?: null | string;
  name: string;
  sortNo?: number;
  typeCode: string;
}

/** 编辑名称请求（PUT /report-config/names/{id}；所属类型不可改）。 */
export interface ReportNameUpdateRequest {
  digitizeStatusOverride?: null | ReportDigitizeStatus;
  indicatorTplId?: null | string;
  name: string;
  sortNo?: number;
  status: ReportConfigStatus;
}

/** 指标模板项目（新建 / 编辑请求行）。 */
export interface ReportIndicatorItemRequest {
  itemCode: string;
  itemName: string;
  refRange?: null | string;
  refRangeFemale?: null | string;
  refRangeMale?: null | string;
  sortNo?: number;
  unit?: null | string;
}

/** 新建模板请求（items 全量给出）。 */
export interface ReportTplCreateRequest {
  items: ReportIndicatorItemRequest[];
  name: string;
  remark?: null | string;
}

/** 编辑模板请求（items 全量替换）。 */
export interface ReportTplUpdateRequest {
  items: ReportIndicatorItemRequest[];
  name: string;
  remark?: null | string;
  status: ReportConfigStatus;
}

/** 运行时类型（GET /followup/report-types，添加报告单对话框用；E16-T02）。 */
export interface ReportCatalogType {
  code: string;
  defaultDigitizeStatus: null | ReportDigitizeStatus;
  fields: ReportCatalogField[];
  name: string;
  nameRequired: boolean;
}

/** 类型专属字段（fe_form REPORT 表单的启用字段，紧凑视图）。 */
export interface ReportCatalogField {
  code: string;
  key: string;
  label: string;
  required: boolean;
  sortNo: number;
  type: string;
  unit: null | string;
}

/** 运行时名称（GET /followup/report-names?typeCode=；选名称自动带出指标行）。 */
export interface ReportCatalogName {
  id: string;
  indicatorTplId: null | string;
  items: ReportIndicatorItemView[];
  name: string;
  typeCode: string;
}

/** 状态显示文案（列表 Tag 与操作按钮共用）。 */
export const REPORT_CONFIG_STATUS_TEXT: Record<ReportConfigStatus, string> = {
  DISABLED: '停用',
  ENABLED: '启用',
};

/** 电子化状态显示文案（C-SCAN-011；A 医院配置页通常见不到取值）。 */
export const REPORT_DIGITIZE_STATUS_TEXT: Record<ReportDigitizeStatus, string> =
  {
    NO_ENTRY: '无需录入',
    PENDING: '待录入',
  };

export function reportConfigStatusText(status: ReportConfigStatus): string {
  return REPORT_CONFIG_STATUS_TEXT[status] ?? status;
}

export function reportDigitizeStatusText(
  status: null | ReportDigitizeStatus,
): string {
  return status ? (REPORT_DIGITIZE_STATUS_TEXT[status] ?? status) : '不启用';
}

/** 参考范围显示：通用值优先，按性别区分时显示「男 a / 女 b」（A-ATT-012 规格表写法）。 */
export function reportRefRangeText(item: ReportIndicatorItemView): string {
  if (item.refRangeMale || item.refRangeFemale) {
    const male = item.refRangeMale ?? '—';
    const female = item.refRangeFemale ?? '—';
    return `男 ${male} / 女 ${female}`;
  }
  return item.refRange ?? '—';
}

/** 类型 / 模板编码字符集（与后端一致：字母、数字、短横线、下划线，≤32）。 */
export const REPORT_CODE_PATTERN = /^[A-Za-z0-9_-]+$/;

/** 校验新建类型编码：返回错误文案，合法返回 null。 */
export function validateReportTypeCode(code: string): null | string {
  const trimmed = code.trim();
  if (!trimmed) {
    return '请填写类型编码';
  }
  if (trimmed.length > 32) {
    return '类型编码最多 32 个字符';
  }
  return REPORT_CODE_PATTERN.test(trimmed)
    ? null
    : '类型编码只支持字母、数字、短横线与下划线';
}

/** 名称必填显示（C-SCAN-003）。 */
export function reportNameRequiredText(required: boolean): string {
  return required ? '必填' : '选填';
}
