/**
 * E18-T06 导入向导页私有逻辑用例：作业 id 会话级续接（stash / read / clear）、
 * 批次与问题的展示映射（表格 key、状态文案与颜色、单元格位置拼接）。
 */
import type { ImportBatch, ImportIssueItem } from '@crdp/api';

import { afterEach, describe, expect, it } from 'vitest';

import {
  clearStashedImportJob,
  IMPORT_JOB_KEY,
  importBatchRows,
  importBatchStatusColor,
  importBatchStatusText,
  importIssueLevelColor,
  importIssueRows,
  readStashedImportJob,
  stashImportJob,
} from './import-logic';

const batch = (over: Partial<ImportBatch>): ImportBatch => ({
  batchNo: 1,
  createdPatients: 0,
  failReason: null,
  patientCount: 200,
  recordsCreated: 0,
  skippedPatients: 0,
  status: 'DONE',
  updatedPatients: 0,
  ...over,
});

const issue = (over: Partial<ImportIssueItem>): ImportIssueItem => ({
  code: 'BAD_DATE',
  columnLetters: 'M',
  header: '手术日期',
  id: 1,
  level: 'ERROR',
  message: '不是有效日期（示例）',
  rowNo: 3,
  sheetName: '基线',
  ...over,
});

describe('作业 id 会话级续接（刷新后回到同一作业）', () => {
  afterEach(() => {
    window.sessionStorage.clear();
  });

  it('stash 后可读回，clear 后为空', () => {
    expect(readStashedImportJob()).toBeNull();
    stashImportJob(42);
    expect(readStashedImportJob()).toBe(42);
    expect(window.sessionStorage.getItem(IMPORT_JOB_KEY)).toBe('42');
    clearStashedImportJob();
    expect(readStashedImportJob()).toBeNull();
  });

  it('脏值（非正整数）按无作业处理', () => {
    window.sessionStorage.setItem(IMPORT_JOB_KEY, 'abc');
    expect(readStashedImportJob()).toBeNull();
    window.sessionStorage.setItem(IMPORT_JOB_KEY, '-1');
    expect(readStashedImportJob()).toBeNull();
  });
});

describe('批次与问题的展示映射', () => {
  it('批次行带 key、状态文案与颜色', () => {
    const rows = importBatchRows([
      batch({ batchNo: 2, status: 'RUNNING' }),
      batch({ batchNo: 1, status: 'FAILED', failReason: '批次失败（示例）' }),
    ]);
    expect(rows[0]).toMatchObject({
      key: 2,
      statusText: '执行中',
      statusColor: 'processing',
    });
    expect(rows[1]).toMatchObject({
      key: 1,
      statusText: '失败',
      statusColor: 'error',
    });
    expect(importBatchStatusText('PENDING')).toBe('待执行');
    expect(importBatchStatusText('ROLLED_BACK')).toBe('已回滚');
    expect(importBatchStatusColor('DONE')).toBe('success');
    expect(importBatchStatusColor('PENDING')).toBe('default');
  });

  it('问题行拼单元格位置（工作表 行 列），级别 ERROR 红 / WARN 橙', () => {
    const rows = importIssueRows([
      issue({}),
      issue({
        columnLetters: null,
        id: 2,
        level: 'WARN',
        sheetName: '随访记录',
      }),
    ]);
    expect(rows[0]?.position).toBe('基线 第 3 行 M 列');
    expect(rows[1]?.position).toBe('随访记录 第 3 行');
    expect(rows[0]?.levelColor).toBe('error');
    expect(rows[1]?.levelColor).toBe('warning');
    expect(importIssueLevelColor('ERROR')).toBe('error');
    expect(importIssueLevelColor('WARN')).toBe('warning');
  });
});
