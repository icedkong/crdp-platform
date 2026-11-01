import type { DeptImportReport } from './dept-import';

import { describe, expect, it } from 'vitest';

import {
  deptImportActionText,
  deptImportSummary,
  failedRows,
  isXlsxFile,
} from './dept-import';

/** E06-T03 / PLT-ORG-003：科室导入报告的展示逻辑。 */

function report(partial: Partial<DeptImportReport>): DeptImportReport {
  return {
    created: 0,
    failed: 0,
    rows: [],
    total: 0,
    updated: 0,
    ...partial,
  };
}

describe('deptImportSummary', () => {
  it('全部成功时不附加失败提示', () => {
    expect(
      deptImportSummary(
        report({ total: 3, created: 2, updated: 1, failed: 0 }),
      ),
    ).toBe('共 3 行：新增 2、更新 1、失败 0');
  });

  it('有失败行时提示修正后重新上传', () => {
    expect(
      deptImportSummary(
        report({ total: 9, created: 2, updated: 1, failed: 6 }),
      ),
    ).toBe('共 9 行：新增 2、更新 1、失败 6（失败行未导入，请修正后重新上传）');
  });
});

describe('failedRows / deptImportActionText', () => {
  it('只挑出失败行；动作转中文', () => {
    const view = report({
      rows: [
        { action: 'CREATED', code: 'D1', message: null, rowNo: 2 },
        { action: 'FAILED', code: 'D2', message: '院区不存在：X', rowNo: 3 },
        { action: 'UPDATED', code: 'D3', message: null, rowNo: 4 },
      ],
    });
    expect(failedRows(view)).toEqual([
      { action: 'FAILED', code: 'D2', message: '院区不存在：X', rowNo: 3 },
    ]);
    expect(deptImportActionText('CREATED')).toBe('新增');
    expect(deptImportActionText('UPDATED')).toBe('更新');
    expect(deptImportActionText('FAILED')).toBe('失败');
  });
});

describe('isXlsxFile', () => {
  it('只放行 .xlsx 扩展名（大小写不敏感）', () => {
    expect(isXlsxFile(new File([], '科室.xlsx'))).toBe(true);
    expect(isXlsxFile(new File([], 'DEPARTMENTS.XLSX'))).toBe(true);
    expect(isXlsxFile(new File([], '科室.xls'))).toBe(false);
    expect(isXlsxFile(new File([], '科室.csv'))).toBe(false);
  });
});
