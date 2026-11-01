/**
 * E06-T03 / PLT-ORG-003：科室批量导入类型与展示逻辑，与后端
 * `com.crdp.system.org.DeptImportService` 视图同构（docs/api/system.md §1、import-export.md §2）。
 * 同步一次性模型：上传即逐行校验并落库，单行失败不影响其他行。
 */

/** 逐行结果：action = CREATED / UPDATED / FAILED（失败行带原因）。 */
export type DeptImportAction = 'CREATED' | 'FAILED' | 'UPDATED';

export interface DeptImportRowResult {
  action: DeptImportAction;
  code: string;
  message: null | string;
  /** Excel 行号（含表头，从 1 计）。 */
  rowNo: number;
}

/** 导入报告：合计、新增、更新、失败与逐行明细。 */
export interface DeptImportReport {
  created: number;
  failed: number;
  rows: DeptImportRowResult[];
  total: number;
  updated: number;
}

/** 模板列顺序（与后端表头逐一对应，导出给上传组件校验文件扩展名用）。 */
export const DEPT_IMPORT_COLUMNS = [
  '院区编码',
  '科室编码',
  '上级科室编码',
  '科室名称',
  '科室类型',
  'HIS科室编码',
  '负责人账号',
  '排序',
] as const;

const ACTION_TEXT: Record<DeptImportAction, string> = {
  CREATED: '新增',
  FAILED: '失败',
  UPDATED: '更新',
};

/** 行结果 → 中文动作（结果表 Tag 文案）。 */
export function deptImportActionText(action: DeptImportAction): string {
  return ACTION_TEXT[action] ?? action;
}

/** 导入摘要文案：`共 9 行：新增 2、更新 1、失败 6`；有失败时附加提示。 */
export function deptImportSummary(report: DeptImportReport): string {
  const summary = `共 ${report.total} 行：新增 ${report.created}、更新 ${report.updated}、失败 ${report.failed}`;
  return report.failed > 0
    ? `${summary}（失败行未导入，请修正后重新上传）`
    : summary;
}

/** 结果表只展示失败行（全部成功时表格留空，摘要已说明）。 */
export function failedRows(report: DeptImportReport): DeptImportRowResult[] {
  return report.rows.filter((row) => row.action === 'FAILED');
}

/** 上传前校验：只放行 .xlsx（模板即 xlsx；.xls 旧格式不支持，OQ-52）。 */
export function isXlsxFile(file: File): boolean {
  return file.name.toLowerCase().endsWith('.xlsx');
}
