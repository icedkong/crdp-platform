/**
 * E06-T08 / PLT-ORG-007：用户批量导入类型与展示逻辑，与后端
 * `com.crdp.server.system.user.UserImportService` 视图同构（docs/api/system.md §2、import-export.md §2）。
 * 同步一次性模型（与科室导入同口径，OQ-52）：上传即逐行校验并新建，单行失败不影响其他行；
 * 只新建不更新既有用户，导入报告不含初始密码（首次短信登录后设置）。
 */

/** 逐行结果：action = CREATED / FAILED（失败行带原因）。 */
export type UserImportAction = 'CREATED' | 'FAILED';

export interface UserImportRowResult {
  action: UserImportAction;
  message: null | string;
  /** Excel 行号（含表头，从 1 计）。 */
  rowNo: number;
  username: string;
}

/** 导入报告：合计、新增、失败与逐行明细（导入不更新既有用户，无 UPDATED）。 */
export interface UserImportReport {
  created: number;
  failed: number;
  rows: UserImportRowResult[];
  total: number;
}

/** 模板列顺序（与后端表头逐一对应；清单列用分号分隔多个编码）。 */
export const USER_IMPORT_COLUMNS = [
  '账号',
  '姓名',
  '工号',
  '手机号',
  '主科室编码',
  '角色编码',
  '负责病区编码',
  '负责科室编码',
] as const;

const ACTION_TEXT: Record<UserImportAction, string> = {
  CREATED: '新增',
  FAILED: '失败',
};

/** 行结果 → 中文动作（结果表 Tag 文案）。 */
export function userImportActionText(action: UserImportAction): string {
  return ACTION_TEXT[action] ?? action;
}

/** 导入摘要文案：`共 9 行：新增 2、失败 7`；有失败时附加提示。 */
export function userImportSummary(report: UserImportReport): string {
  const summary = `共 ${report.total} 行：新增 ${report.created}、失败 ${report.failed}`;
  return report.failed > 0
    ? `${summary}（失败行未导入，请修正后重新上传）`
    : summary;
}

/** 结果表只展示失败行（全部成功时表格留空，摘要已说明）。 */
export function failedUserRows(
  report: UserImportReport,
): UserImportRowResult[] {
  return report.rows.filter((row) => row.action === 'FAILED');
}
