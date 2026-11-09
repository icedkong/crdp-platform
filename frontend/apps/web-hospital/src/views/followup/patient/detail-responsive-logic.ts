/**
 * E24-T04 / NFR-RESP-002 · 患者详情响应式纯逻辑（pages.md §4.10 手机行：
 * 「随访记录、植入记录、操作日志表格改卡片」与 §4.6「手机：列表卡片」——
 * 本页现有表格为沟通记录与报告单，操作日志页签随后续任务、植入记录随 C 在 M5）。
 * 卡片字段行与分页切片的口径集中在这里，测试见 detail-responsive-logic.spec.ts。
 */
import type { ReportRow } from '@crdp/api';

export interface CardLine {
  label: string;
  value: string;
}

/** 沟通记录卡片行（表格列：时间 / 方式 / 结果 / 约定下次联系 / 备注 / 操作人）。 */
export interface ContactCardRow {
  contactTime: string;
  creatorName: string;
  methodText: string;
  nextContact: string;
  note: string;
  resultText: string;
}

/** ISO 时间 →「YYYY-MM-DD HH:mm」（本地展示口径，日期补零时辰）。 */
export function formatDateTime(value: string): string {
  const normalized = value.includes('T') ? value : `${value}T00:00:00`;
  return normalized.slice(0, 16).replaceAll('T', ' ');
}

/** 沟通记录卡片字段行：时间为卡片标题，方式 / 结果 / 约定下次联系 / 备注 / 操作人逐行。 */
export function contactCardLines(row: ContactCardRow): CardLine[] {
  return [
    { label: '方式', value: row.methodText },
    { label: '结果', value: row.resultText },
    { label: '约定下次联系', value: row.nextContact },
    { label: '备注', value: row.note },
    { label: '操作人', value: row.creatorName },
  ];
}

/**
 * 报告单卡片字段行（pages.md §4.6 列：类型、名称、报告时间、位置、上传人、
 * 上传时间）：名称为卡片标题、位置标签由模板渲染（悬停 / 点按看全部），这里给
 * 类型 / 报告时间 / 上传（人 · 时间）三行。
 */
export function reportCardLines(row: ReportRow): CardLine[] {
  return [
    { label: '类型', value: row.typeName },
    {
      label: '报告时间',
      value: row.reportDate ? row.reportDate.slice(0, 10) : '—',
    },
    {
      label: '上传',
      value: `${row.creatorName ?? '—'} · ${formatDateTime(row.createdAt)}`,
    },
  ];
}

/** 卡片分页切片（表格每页 20 同口径；页码越界时回落最后一页，删除后不空页）。 */
export function pageSlice<T>(items: T[], pageNo: number, pageSize = 20): T[] {
  if (items.length === 0) return [];
  const totalPage = Math.ceil(items.length / pageSize);
  const safePage = Math.min(Math.max(pageNo, 1), totalPage);
  return items.slice((safePage - 1) * pageSize, safePage * pageSize);
}
