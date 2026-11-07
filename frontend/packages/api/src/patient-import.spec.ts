/**
 * E18-T06 / A-PAT-008、A-PAT-018 纯逻辑用例（tasks.csv 验收「上传、校验、提交三步
 * 可见进度」的前端面）：向导大步映射（上传 / 校验 / 提交）、轮询停止条件（终态停轮询）、
 * 提交进度百分比（按完成批次）、状态文案与颜色、确认门控（有 ERROR 不可确认）、
 * 上传文件前置校验。
 */
import type {
  ImportCommitStats,
  ImportJobStatus,
  ImportJobView,
} from './patient-import';

import { describe, expect, it } from 'vitest';

import {
  canConfirmImport,
  importCommitPercent,
  importFileSizeText,
  importResultSummary,
  importStatusColor,
  importStatusText,
  importStepStatus,
  importWizardStep,
  shouldPollImport,
  validateImportConfirmForm,
  validateImportUploadFile,
} from './patient-import';

function stats(batches: number, batchesDone: number): ImportCommitStats {
  return {
    batches,
    batchesDone,
    createdPatients: 0,
    failedBatches: [],
    recordsCreated: 0,
    skippedPatients: 0,
    updatedPatients: 0,
  };
}

function job(status: ImportJobStatus, errorCount = 0): ImportJobView {
  return {
    batches: [],
    createdAt: null,
    defaultWardId: null,
    duplicateStrategy: null,
    errorCount,
    failReason: null,
    fileName: '患者导入（示例）.xlsx',
    fileSize: 1024,
    jobId: 1,
    layout: null,
    layoutProblems: [],
    operatorName: 'doctor.demo',
    stagedRows: 0,
    stats: stats(0, 0),
    status,
    updatedAt: null,
    warnCount: 0,
  };
}

describe('importWizardStep（三步可见进度：上传 → 校验 → 提交）', () => {
  it('无作业 / 已上传停在上传步', () => {
    expect(importWizardStep(null)).toBe('upload');
    expect(importWizardStep('UPLOADED')).toBe('upload');
  });

  it('预检中 / 预检完成 / 预检失败在校验步', () => {
    expect(importWizardStep('PRECHECKING')).toBe('validate');
    expect(importWizardStep('PRECHECKED')).toBe('validate');
    expect(importWizardStep('PRECHECK_FAILED')).toBe('validate');
  });

  it('导入中起的全部状态在提交步（含终态与回滚）', () => {
    expect(importWizardStep('IMPORTING')).toBe('commit');
    expect(importWizardStep('PARTIAL_FAILED')).toBe('commit');
    expect(importWizardStep('DONE')).toBe('commit');
    expect(importWizardStep('CANCELLED')).toBe('commit');
    expect(importWizardStep('ROLLED_BACK')).toBe('commit');
  });
});

describe('shouldPollImport（进行中轮询、终态停轮询）', () => {
  it('uPLOADED / PRECHECKING / IMPORTING 继续轮询', () => {
    expect(shouldPollImport('UPLOADED')).toBe(true);
    expect(shouldPollImport('PRECHECKING')).toBe(true);
    expect(shouldPollImport('IMPORTING')).toBe(true);
  });

  it('终态与预检完成停轮询（用户按结果行动）', () => {
    for (const status of [
      'PRECHECKED',
      'PRECHECK_FAILED',
      'DONE',
      'PARTIAL_FAILED',
      'CANCELLED',
      'ROLLED_BACK',
    ] as const) {
      expect(shouldPollImport(status)).toBe(false);
    }
    expect(shouldPollImport(null)).toBe(false);
  });
});

describe('importStepStatus（a-steps 步骤状态）', () => {
  it('已完成步 finish、未到步 wait、当前步 process', () => {
    expect(importStepStatus('upload', 'validate', job('PRECHECKING'))).toBe(
      'finish',
    );
    expect(importStepStatus('commit', 'validate', job('PRECHECKING'))).toBe(
      'wait',
    );
    expect(importStepStatus('validate', 'validate', job('PRECHECKING'))).toBe(
      'process',
    );
  });

  it('预检失败标在校验步、部分失败标在提交步（error）', () => {
    expect(
      importStepStatus('validate', 'validate', job('PRECHECK_FAILED')),
    ).toBe('error');
    expect(importStepStatus('commit', 'commit', job('PARTIAL_FAILED'))).toBe(
      'error',
    );
  });

  it('无作业时当前步按 process 呈现', () => {
    expect(importStepStatus('upload', 'upload', null)).toBe('process');
  });
});

describe('importCommitPercent（提交按批次推进）', () => {
  it('无批次 0；一半完成 50；全部完成 100', () => {
    expect(importCommitPercent(null)).toBe(0);
    expect(importCommitPercent(stats(0, 0))).toBe(0);
    expect(importCommitPercent(stats(4, 2))).toBe(50);
    expect(importCommitPercent(stats(4, 4))).toBe(100);
  });
});

describe('状态文案与颜色（design-system §1.2 口径）', () => {
  it('进行中蓝 / 完成绿 / 失败红 / 取消回滚灰', () => {
    expect(importStatusColor('PRECHECKING')).toBe('processing');
    expect(importStatusColor('DONE')).toBe('success');
    expect(importStatusColor('PRECHECK_FAILED')).toBe('error');
    expect(importStatusColor('PARTIAL_FAILED')).toBe('error');
    expect(importStatusColor('CANCELLED')).toBe('default');
    expect(importStatusText('IMPORTING')).toBe('导入中');
    expect(importStatusText('PRECHECKED')).toBe('预检完成');
  });
});

describe('确认门控与结果摘要', () => {
  it('预检完成且无 ERROR 才可确认（A-PAT-018 错误行不可导入）', () => {
    expect(canConfirmImport(job('PRECHECKED'))).toBe(true);
    expect(canConfirmImport(job('PRECHECKED', 2))).toBe(false);
    expect(canConfirmImport(job('IMPORTING'))).toBe(false);
    expect(canConfirmImport(null)).toBe(false);
  });

  it('未选重复策略不能提交；结果摘要含四类计数', () => {
    expect(validateImportConfirmForm(null)).toContain('重复患者');
    expect(validateImportConfirmForm('UPDATE')).toBeNull();
    expect(
      importResultSummary({
        ...stats(1, 1),
        createdPatients: 10,
        recordsCreated: 21,
        skippedPatients: 2,
        updatedPatients: 3,
      }),
    ).toBe('新建患者 10 名，更新 3 名，跳过 2 名，生成随访记录 21 条');
  });
});

describe('上传文件前置校验', () => {
  it('非 xlsx / 空文件 / 超限拒绝', () => {
    expect(validateImportUploadFile(null)).toContain('选择');
    expect(
      validateImportUploadFile({ name: '患者导入.xls', size: 10 }),
    ).toContain('xlsx');
    expect(
      validateImportUploadFile({ name: '患者导入.xlsx', size: 0 }),
    ).toContain('空');
    expect(
      validateImportUploadFile({
        name: '患者导入.xlsx',
        size: 201 * 1024 * 1024,
      }),
    ).toContain('200 MB');
    expect(
      validateImportUploadFile({ name: '患者导入.xlsx', size: 1024 }),
    ).toBeNull();
  });

  it('文件大小展示', () => {
    expect(importFileSizeText(512)).toBe('512 B');
    expect(importFileSizeText(2048)).toBe('2 KB');
    expect(importFileSizeText(5 * 1024 * 1024)).toBe('5.0 MB');
  });
});
