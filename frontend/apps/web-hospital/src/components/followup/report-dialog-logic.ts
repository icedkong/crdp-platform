/**
 * E16-T02 / A-ATT-001、A-ATT-002；C-SCAN-001、C-SCAN-010：添加报告单对话框的纯逻辑
 * （components.md §8、pages.md §6.1）。组件保持薄壳，可测口径集中在这里：
 * 公共槽位（日期 / 所见 / 结论 / 备注）的标签从类型字段集推导；校验与提交载荷不掺 UI 状态。
 */
import type {
  ReportCatalogField,
  ReportCatalogName,
  ReportIndicatorInput,
  ReportLocationInput,
} from '@crdp/api';

import { reportIndicatorFlag, reportRefRangeFor } from '@crdp/api';

/** 公共槽位：类型字段集里映射到对话框公共部分的编码（其余字段值走 fields 提交）。 */
const DATE_CODES = new Set([
  'call_time',
  'report_date',
  'sign_date',
  'surgery_date',
]);
const NOTE_CODES = new Set(['call_note', 'note']);

/** 日期槽位标签：按类型的日期字段显示（报告时间 / 手术日期 / 签署日期 / 通话时间）。 */
export function reportDateLabel(fields: ReportCatalogField[]): string {
  const dateField = fields.find((field) => DATE_CODES.has(field.code));
  return dateField?.label ?? '报告时间';
}

/** 所见槽位：类型无 findings 字段时返回 null（对话框不显示该项，pages.md §6.1「按类型显示」）。 */
export function reportFindingsLabel(
  fields: ReportCatalogField[],
): null | string {
  return fields.find((field) => field.code === 'findings')?.label ?? null;
}

/** 结论槽位：如「超声结论」「造影结论」「心电图诊断」。 */
export function reportConclusionLabel(
  fields: ReportCatalogField[],
): null | string {
  return fields.find((field) => field.code === 'conclusion')?.label ?? null;
}

/** 备注槽位标签（缺省「备注」）。 */
export function reportNoteLabel(fields: ReportCatalogField[]): string {
  const note = fields.find((field) => NOTE_CODES.has(field.code));
  return note?.label ?? '备注';
}

/** 指标表行（A-ATT-012：模板按顺序带出，只需填结果；参考范围按患者性别选定）。 */
export interface IndicatorRow {
  flag: '↑' | '↓' | null;
  itemCode: string;
  itemName: string;
  refRange: null | string;
  result: string;
  unit: null | string;
}

/** 名称绑定的模板项目 → 可填行（选名称自动带出，E16-T01 目录 items）。 */
export function buildIndicatorRows(
  name: null | ReportCatalogName,
  gender?: null | string,
): IndicatorRow[] {
  if (!name) return [];
  return name.items.map((item) => {
    const refRange = reportRefRangeFor(item, gender);
    return {
      itemCode: item.itemCode,
      itemName: item.itemName,
      refRange,
      result: '',
      unit: item.unit,
      flag: null,
    };
  });
}

/** 填结果后重算 ↑ / ↓（未填或解析不出范围不标；口径与后端一致）。 */
export function withIndicatorFlag(row: IndicatorRow): IndicatorRow {
  return {
    ...row,
    flag: row.result ? reportIndicatorFlag(row.refRange, row.result) : null,
  };
}

/** 校验结果：键 → 错误文案（只列第一条给 FormItem，逐项独立显示）。 */
export interface ReportFormValues {
  nameId: null | string;
  nameRequired: boolean;
  reportDate: null | string;
  typeCode: null | string;
}

export function validateReportForm(values: ReportFormValues): {
  nameId: null | string;
  reportDate: null | string;
  typeCode: null | string;
} {
  return {
    nameId: values.nameRequired && !values.nameId ? '请选择名称' : null,
    reportDate: values.reportDate ? null : '请填写报告时间',
    typeCode: values.typeCode ? null : '请选择类型',
  };
}

/** 是否存在任一错误（提交前置）。 */
export function hasReportFormError(errors: {
  nameId: null | string;
  reportDate: null | string;
  typeCode: null | string;
}): boolean {
  return Boolean(errors.typeCode || errors.nameId || errors.reportDate);
}

/** 指标行 → 提交载荷（只提交已填结果的项目）。 */
export function indicatorPayload(rows: IndicatorRow[]): ReportIndicatorInput[] {
  return rows
    .filter((row) => row.result.trim() !== '')
    .map((row) => ({
      itemCode: row.itemCode,
      refRange: row.refRange,
      result: row.result.trim(),
    }));
}

/** 位置输入四元组相等（与后端去重键、LocationPicker 选中键同口径）。 */
export function sameReportLocation(
  a: ReportLocationInput,
  b: ReportLocationInput,
): boolean {
  return (
    a.linkType === b.linkType &&
    (a.linkRecordId ?? '') === (b.linkRecordId ?? '') &&
    (a.anchorType ?? '') === (b.anchorType ?? '') &&
    (a.anchorKey ?? '') === (b.anchorKey ?? '')
  );
}

/** 打开对话框时的初始位置：预选进入位置（可再加，A-ATT-001「自动预选该位置」）。 */
export function initialLocations(
  preset?: ReportLocationInput[],
): ReportLocationInput[] {
  return preset ? preset.map((item) => ({ ...item })) : [];
}
