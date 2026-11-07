/**
 * E16-T09 / A-ATT-007、C-SCAN-016：报告单删除的纯逻辑（软删除、原因必填、记审计）。
 * 组件保持薄壳，可测口径集中在这里：删除弹窗（ReasonModal）的标题标识与后果告知文案。
 * 失败提示走全局拦截器（后端 message 已是中文友好文案：请填写删除原因 / 报告单不存在
 * 或不在您的数据范围内 / 报告单已被删除，无需重复操作），弹窗保持打开可改原因重试。
 */
import type { ReportRow } from '@crdp/api';

/** 删除弹窗标题里的报告单标识：「心脏超声 · 经胸超声心动图」（无名称只显示类型）。 */
export function reportDeleteLabel(row: ReportRow): string {
  return row.name ? `${row.typeName} · ${row.name}` : row.typeName;
}

/**
 * 删除弹窗说明（后果告知，A-ATT-007 软删除口径）：删除后患者端不再显示、附件一并
 * 移除（预览与下载随即不可用），数据保留并可审计。关联沟通记录的「通话记录」另加
 * A-ATT-017 提示：录音与沟通记录页签是同一文件，删除后两处同时消失（pages.md §4.8）。
 */
export function reportDeleteNote(row: ReportRow): string {
  const files =
    row.fileCount > 0 ? `、附件 ${row.fileCount} 个一并移除` : '（无附件）';
  const callNote = row.contactLogId
    ? '；录音与沟通记录页签为同一文件，删除后两处同时消失'
    : '';
  return `删除后「${reportDeleteLabel(row)}」不再显示${files}${callNote}；数据保留可审计，须填写原因。`;
}
