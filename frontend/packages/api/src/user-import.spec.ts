import type { UserImportReport } from './user-import';

import { describe, expect, it } from 'vitest';

import {
  failedUserRows,
  userImportActionText,
  userImportSummary,
} from './user-import';

/** E06-T08 / PLT-ORG-007：用户导入报告的展示逻辑。 */

function report(partial: Partial<UserImportReport>): UserImportReport {
  return { created: 0, failed: 0, rows: [], total: 0, ...partial };
}

describe('userImportSummary', () => {
  it('全部成功时不附加失败提示', () => {
    expect(userImportSummary(report({ total: 3, created: 3, failed: 0 }))).toBe(
      '共 3 行：新增 3、失败 0',
    );
  });

  it('有失败行时提示修正后重新上传', () => {
    expect(userImportSummary(report({ total: 9, created: 2, failed: 7 }))).toBe(
      '共 9 行：新增 2、失败 7（失败行未导入，请修正后重新上传）',
    );
  });
});

describe('failedUserRows', () => {
  it('只保留失败行并保持行序', () => {
    const result = failedUserRows(
      report({
        rows: [
          { action: 'CREATED', message: null, rowNo: 2, username: 'it.ok' },
          {
            action: 'FAILED',
            message:
              '账号已存在：nurse.demo（如需修改请在用户管理中编辑，导入不更新既有用户）',
            rowNo: 3,
            username: 'nurse.demo',
          },
          { action: 'CREATED', message: null, rowNo: 4, username: 'it.ok2' },
          {
            action: 'FAILED',
            message: '手机号格式不正确（11 位，1 开头）：12345',
            rowNo: 5,
            username: 'it.bad',
          },
        ],
        total: 4,
      }),
    );
    expect(result.map((row) => row.rowNo)).toEqual([3, 5]);
    expect(result[0]?.username).toBe('nurse.demo');
  });

  it('全部成功时为空数组', () => {
    expect(
      failedUserRows(
        report({
          rows: [
            { action: 'CREATED', message: null, rowNo: 2, username: 'it.ok' },
          ],
          total: 1,
        }),
      ),
    ).toEqual([]);
  });
});

describe('userImportActionText', () => {
  it('映射为中文动作', () => {
    expect(userImportActionText('CREATED')).toBe('新增');
    expect(userImportActionText('FAILED')).toBe('失败');
  });
});
