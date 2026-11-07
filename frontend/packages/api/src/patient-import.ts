/**
 * E18-T06 / A-PAT-008、A-PAT-018：患者历史数据导入向导（/followup/patient/import）的类型与
 * 纯逻辑（接口契约 docs/api/import-export.md §1，后端 `ImportJobController`）。
 *
 * 向导三步可见进度（tasks.csv E18-T06 验收）：上传（multipart → jobId）、校验
 * （PRECHECKING → stagedRows 递增 → PRECHECKED / PRECHECK_FAILED）、提交（IMPORTING →
 * 按 imp_batch 批次推进 → DONE / PARTIAL_FAILED）。状态与进度全部来自作业视图轮询，
 * 前端不自行推导业务状态。
 */

/** 作业状态机（import-export.md §1；CANCELLED / ROLLED_BACK 为终态）。 */
export type ImportJobStatus =
  | 'CANCELLED'
  | 'DONE'
  | 'IMPORTING'
  | 'PARTIAL_FAILED'
  | 'PRECHECK_FAILED'
  | 'PRECHECKED'
  | 'PRECHECKING'
  | 'ROLLED_BACK'
  | 'UPLOADED';

/** 版面（上传文件解析后回填）：WIDE 宽表 / LONG 长表（基线 + 随访记录）。 */
export type ImportLayout = 'LONG' | 'WIDE';

/** 重复患者处理策略（A-PAT-018「可按病案号重新导入（更新或跳过）」）。 */
export type ImportDuplicateStrategy = 'SKIP' | 'UPDATE';

/** 提交批次行（按批展示进度与回滚入口，E18-T05）。 */
export interface ImportBatch {
  batchNo: number;
  createdPatients: number;
  failReason?: null | string;
  patientCount: number;
  recordsCreated: number;
  skippedPatients: number;
  status: 'DONE' | 'FAILED' | 'PENDING' | 'ROLLED_BACK' | 'RUNNING';
  updatedPatients: number;
}

/** 提交统计（进度与结果展示）。 */
export interface ImportCommitStats {
  batches: number;
  batchesDone: number;
  createdPatients: number;
  failedBatches: number[];
  recordsCreated: number;
  skippedPatients: number;
  updatedPatients: number;
}

/** 作业视图（GET /api/hospital/followup/imports/{jobId}；轮询单次拿全部状态与进度）。 */
export interface ImportJobView {
  batches: ImportBatch[];
  createdAt?: null | string;
  defaultWardId?: null | number;
  duplicateStrategy?: null | string;
  errorCount: number;
  failReason?: null | string;
  fileName: string;
  fileSize: number;
  jobId: number;
  layout?: null | string;
  layoutProblems: string[];
  operatorName: string;
  stagedRows: number;
  stats: ImportCommitStats;
  status: ImportJobStatus;
  updatedAt?: null | string;
  warnCount: number;
}

/** 校验问题行（分页展示；不含单元格原值——敏感列不回显，明细看批注版报告）。 */
export interface ImportIssueItem {
  code: string;
  columnLetters?: null | string;
  header?: null | string;
  id: number;
  level: 'ERROR' | 'WARN';
  message: string;
  rowNo: number;
  sheetName: string;
}

/** 校验问题分页。 */
export interface ImportIssuePage {
  items: ImportIssueItem[];
  pageNo: number;
  pageSize: number;
  total: number;
}

/** 确认提交请求（POST /imports/{jobId}/confirm）。 */
export interface ImportConfirmRequest {
  defaultWardId?: null | number;
  duplicateStrategy: ImportDuplicateStrategy;
}

/** 向导轮询间隔（毫秒）：预检 / 提交进度都是秒级推进，2 秒足够顺滑且不压接口。 */
export const IMPORT_POLL_INTERVAL_MS = 2000;

/** 向导当前大步（a-steps 三步：上传、校验、提交）。 */
export type ImportWizardStep = 'commit' | 'upload' | 'validate';

/** 作业状态 → 向导大步：上传后（UPLOADED 起预检排队）都算进入校验步；提交后进入提交步。 */
export function importWizardStep(
  status: ImportJobStatus | null,
): ImportWizardStep {
  if (status === null || status === 'UPLOADED') {
    return 'upload';
  }
  if (
    status === 'IMPORTING' ||
    status === 'PARTIAL_FAILED' ||
    status === 'DONE' ||
    status === 'CANCELLED' ||
    status === 'ROLLED_BACK'
  ) {
    return 'commit';
  }
  // PRECHECKING / PRECHECKED / PRECHECK_FAILED：校验与报告
  return 'validate';
}

/** 状态是否还在推进（决定轮询是否继续；终态停轮询，用户按结果行动）。 */
export function shouldPollImport(status: ImportJobStatus | null): boolean {
  return (
    status === 'UPLOADED' || status === 'PRECHECKING' || status === 'IMPORTING'
  );
}

/** 作业状态文案（design-system §15–16：不创同义词）。 */
export function importStatusText(status: ImportJobStatus): string {
  switch (status) {
    case 'CANCELLED': {
      return '已取消';
    }
    case 'DONE': {
      return '已完成';
    }
    case 'IMPORTING': {
      return '导入中';
    }
    case 'PARTIAL_FAILED': {
      return '部分失败';
    }
    case 'PRECHECK_FAILED': {
      return '预检失败';
    }
    case 'PRECHECKED': {
      return '预检完成';
    }
    case 'PRECHECKING': {
      return '预检中';
    }
    case 'ROLLED_BACK': {
      return '已回滚';
    }
    case 'UPLOADED': {
      return '已上传';
    }
  }
}

/** 状态 → a-tag 颜色（design-system §1.2：进行中蓝、完成绿、失败红、已取消 / 已回滚灰）。 */
export function importStatusColor(status: ImportJobStatus): string {
  switch (status) {
    case 'DONE':
    case 'PRECHECKED': {
      return 'success';
    }
    case 'IMPORTING':
    case 'PRECHECKING':
    case 'UPLOADED': {
      return 'processing';
    }
    case 'PARTIAL_FAILED':
    case 'PRECHECK_FAILED': {
      return 'error';
    }
    default: {
      return 'default';
    }
  }
}

/** a-steps 的步骤状态：finish / process / wait / error。 */
export type ImportStepStatus = 'error' | 'finish' | 'process' | 'wait';

/** 大步在 a-steps 上的呈现（失败步标 error；当前步 process；未到步 wait）。 */
export function importStepStatus(
  step: ImportWizardStep,
  current: ImportWizardStep,
  job: ImportJobView | null,
): ImportStepStatus {
  const order: ImportWizardStep[] = ['upload', 'validate', 'commit'];
  const stepIndex = order.indexOf(step);
  const currentIndex = order.indexOf(current);
  if (stepIndex < currentIndex) {
    return 'finish';
  }
  if (stepIndex > currentIndex) {
    return 'wait';
  }
  if (!job) {
    return 'process';
  }
  if (step === 'validate' && job.status === 'PRECHECK_FAILED') {
    return 'error';
  }
  if (step === 'commit' && job.status === 'PARTIAL_FAILED') {
    return 'error';
  }
  return 'process';
}

/** 提交进度百分比（IMPORTING 按完成批次推进；无批次时 0，前端配合 a-progress 的状态色）。 */
export function importCommitPercent(stats: ImportCommitStats | null): number {
  if (!stats || stats.batches <= 0) {
    return 0;
  }
  return Math.round((stats.batchesDone / stats.batches) * 100);
}

/** 提交结果摘要文案（pages.md §10「确认 → 结果下载」前的一句话结果）。 */
export function importResultSummary(stats: ImportCommitStats): string {
  return `新建患者 ${stats.createdPatients} 名，更新 ${stats.updatedPatients} 名，跳过 ${stats.skippedPatients} 名，生成随访记录 ${stats.recordsCreated} 条`;
}

/** 是否可确认提交（A-PAT-018：错误行不可导入——有 ERROR 时后端 40001，前端同样拦）。 */
export function canConfirmImport(job: ImportJobView | null): boolean {
  return job !== null && job.status === 'PRECHECKED' && job.errorCount === 0;
}

/** 确认提交前的表单校验（返回错误文案；null = 可提交）。 */
export function validateImportConfirmForm(
  strategy: ImportDuplicateStrategy | null,
): null | string {
  if (!strategy) {
    return '请选择重复患者的处理方式（更新或跳过）';
  }
  return null;
}

/** 文件大小展示（KB / MB，保留一位小数）。 */
export function importFileSizeText(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }
  if (bytes >= 1024) {
    return `${Math.round(bytes / 1024)} KB`;
  }
  return `${bytes} B`;
}

/** 上传文件前置校验（xlsx；大小上限 200 MB 与后端 multipart 配置一致）。 */
export const IMPORT_UPLOAD_MAX_BYTES = 200 * 1024 * 1024;

/** 上传文件校验（返回错误文案；null = 可上传）。 */
export function validateImportUploadFile(
  file: null | { name: string; size: number },
): null | string {
  if (!file) {
    return '请选择 Excel 文件';
  }
  if (!file.name.toLowerCase().endsWith('.xlsx')) {
    return '只支持 .xlsx 文件（Excel 2007 及以上）';
  }
  if (file.size <= 0) {
    return '文件为空，请检查后重新选择';
  }
  if (file.size > IMPORT_UPLOAD_MAX_BYTES) {
    return '文件超过 200 MB 上限，请拆分后再导入';
  }
  return null;
}
