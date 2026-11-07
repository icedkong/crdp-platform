/**
 * E16-T02 / A-ATT-001、A-ATT-002；C-SCAN-001、C-SCAN-010：添加报告单对话框的纯逻辑
 * （components.md §8、pages.md §6.1）。组件保持薄壳，可测口径集中在这里：
 * 公共槽位（日期 / 所见 / 结论 / 备注）的标签从类型字段集推导；校验与提交载荷不掺 UI 状态。
 */
import type {
  ContactLogRow,
  ReportCatalogField,
  ReportCatalogName,
  ReportIndicatorInput,
  ReportLocationInput,
} from '@crdp/api';

import {
  contactMethodText,
  reportIndicatorFlag,
  reportRefRangeFor,
} from '@crdp/api';

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

/**
 * E16-T13 / E16-T14 修改位置差异（PUT { add, remove }）：next 相对 initial 新增的进
 * add、消失的进 remove（按四元组匹配，模板版本不参与——remove 口径与后端一致）；
 * 无变化时两者皆空（调用方不发起请求）。
 */
export function locationDiff(
  initial: ReportLocationInput[],
  next: ReportLocationInput[],
): { add: ReportLocationInput[]; remove: ReportLocationInput[] } {
  return {
    add: next.filter(
      (item) => !initial.some((old) => sameReportLocation(old, item)),
    ),
    remove: initial.filter(
      (item) => !next.some((now) => sameReportLocation(item, now)),
    ),
  };
}

// ------------------------------------------- E16-T03 多附件（A-ATT-004、C-SCAN-006）

/**
 * 附件数量拦截（A-ATT-004「一份报告单最多 10 个」按租户 maxFilesPerReport）：已选
 * selected 个再进 incoming 个将超限时返回文案（与服务端 40001 同口径），否则 null。
 * 策略未加载（≤ 0）不拦截，由后端兜底。
 */
export function attachmentOverLimit(
  selected: number,
  incoming: number,
  maxFilesPerReport: number,
): null | string {
  if (maxFilesPerReport <= 0) return null;
  return selected + incoming > maxFilesPerReport
    ? `附件数量超过上限（每份报告单最多 ${maxFilesPerReport} 个）`
    : null;
}

/** 附件计数提示（已选 x / 上限 N；策略未加载时只显示已选数）。 */
export function attachmentCountText(
  selected: number,
  maxFilesPerReport: number,
): string {
  return maxFilesPerReport > 0
    ? `已选 ${selected} / ${maxFilesPerReport} 个附件`
    : `已选 ${selected} 个附件`;
}

/** 顺序调整（C-SCAN-006「可调整顺序」）：index 处上移 / 下移，越界原样返回（顺序随 sortNo 落库）。 */
export function moveItem<T>(list: T[], index: number, delta: -1 | 1): T[] {
  const target = index + delta;
  if (
    index < 0 ||
    index >= list.length ||
    target < 0 ||
    target >= list.length
  ) {
    return list;
  }
  const next = [...list];
  const current = next[index] as T;
  next[index] = next[target] as T;
  next[target] = current;
  return next;
}

// ------------------------------------------- E16-T08 通话记录关联（A-ATT-017）

/** 「通话记录」类型编码（后端 rpt_type.code = CALL，V62 预置；仅该类型显示「关联沟通记录」）。 */
export const CALL_TYPE_CODE = 'CALL';

/** 关联沟通记录下拉选项（A-ATT-017「在报告单页签添加通话记录时可选关联某条沟通记录」）。 */
export interface CallContactOption {
  audioCount: number;
  label: string;
  /** 沟通记录 id（提交为 contactLogId） */
  value: string;
}

/**
 * 沟通记录 → 关联下拉选项：只列带录音的记录（录音即报告单附件，同一批文件不重复上传）；
 * 后端分页倒序（最近在前），无录音的记录关联后没有附件、不列出。
 */
export function callContactOptions(logs: ContactLogRow[]): CallContactOption[] {
  return logs
    .filter((log) => log.audios.length > 0)
    .map((log) => ({
      audioCount: log.audios.length,
      label: `${contactTimeText(log.contactTime)} · ${contactMethodText(log.method)}（录音 ${log.audios.length} 个）`,
      value: log.id,
    }));
}

/** ISO 时间 →「YYYY-MM-DD HH:mm」（与患者详情页展示同口径）。 */
function contactTimeText(value: string): string {
  const normalized = value.includes('T') ? value : `${value}T00:00:00`;
  return normalized.slice(0, 16).replaceAll('T', ' ');
}

/**
 * 位置是否必填：关联沟通记录的「通话记录」可空——后端自动取该沟通关联任务的随访记录（整条），
 * 无对应记录进入「未指定位置」（A-ATT-017）；其余类型保持必填（A-ATT-002）。
 */
export function locationRequired(
  typeCode: null | string,
  contactLogId: null | string,
): boolean {
  return !(typeCode === CALL_TYPE_CODE && contactLogId);
}
