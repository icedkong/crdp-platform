/**
 * E18-T06 / A-PAT-008、A-PAT-018 · 导入向导页（/followup/patient/import，pages.md §10
 * 「下载模板 → 上传 → 预检报告 → 选重复处理 → 确认 → 结果下载；同租户同时只允许一个作业」）
 * 的页面级纯逻辑。三步可见进度（tasks.csv E18-T06 验收）的状态推导在
 * `@crdp/api` 的 patient-import（importWizardStep / shouldPollImport / importCommitPercent），
 * 这里只放本页私有部分：作业 id 的会话级续接（刷新 / 误关标签后回到同一作业）与批次 /
 * 问题的展示映射。
 */
import type { ImportBatch, ImportIssueItem } from '@crdp/api';

/** 当前作业 id 的会话级键（id 为内部编号非敏感；同租户同时只有一个作业）。 */
export const IMPORT_JOB_KEY = 'crdp:patient-import:jobId';

/** 暂存当前作业 id（上传成功 / 恢复轮询时写）。 */
export function stashImportJob(jobId: number): void {
  window.sessionStorage.setItem(IMPORT_JOB_KEY, String(jobId));
}

/** 读取当前作业 id（不清除——刷新页面继续跟进度；null = 无进行中作业）。 */
export function readStashedImportJob(): null | number {
  const raw = window.sessionStorage.getItem(IMPORT_JOB_KEY);
  if (!raw) return null;
  const id = Number(raw);
  return Number.isInteger(id) && id > 0 ? id : null;
}

/** 清掉当前作业 id（取消 / 回滚重来 / 换一台电脑重开向导）。 */
export function clearStashedImportJob(): void {
  window.sessionStorage.removeItem(IMPORT_JOB_KEY);
}

/** 批次状态文案（E18-T05 imp_batch 状态机）。 */
export function importBatchStatusText(status: ImportBatch['status']): string {
  switch (status) {
    case 'DONE': {
      return '已完成';
    }
    case 'FAILED': {
      return '失败';
    }
    case 'PENDING': {
      return '待执行';
    }
    case 'ROLLED_BACK': {
      return '已回滚';
    }
    case 'RUNNING': {
      return '执行中';
    }
  }
}

/** 批次状态 → a-tag 颜色（design-system §1.2）。 */
export function importBatchStatusColor(status: ImportBatch['status']): string {
  switch (status) {
    case 'DONE': {
      return 'success';
    }
    case 'FAILED': {
      return 'error';
    }
    case 'PENDING': {
      return 'default';
    }
    case 'ROLLED_BACK': {
      return 'default';
    }
    case 'RUNNING': {
      return 'processing';
    }
  }
}

/** 批次行 → 表格数据源（补 key 与展示字段）。 */
export function importBatchRows(batches: ImportBatch[]) {
  return batches.map((batch) => ({
    key: batch.batchNo,
    statusColor: importBatchStatusColor(batch.status),
    statusText: importBatchStatusText(batch.status),
    ...batch,
  }));
}

/** 问题级别 → a-tag 颜色（ERROR 红 / WARN 橙，design-system §1.2）。 */
export function importIssueLevelColor(level: ImportIssueItem['level']): string {
  return level === 'ERROR' ? 'error' : 'warning';
}

/** 问题行 → 表格数据源（补 key；单元格位置拼「工作表 行X 列Y」）。 */
export function importIssueRows(items: ImportIssueItem[]) {
  return items.map((item) => ({
    key: item.id,
    levelColor: importIssueLevelColor(item.level),
    position: `${item.sheetName} 第 ${item.rowNo} 行${item.columnLetters ? ` ${item.columnLetters} 列` : ''}`,
    ...item,
  }));
}
