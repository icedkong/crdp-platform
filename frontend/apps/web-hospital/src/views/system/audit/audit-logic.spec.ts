/**
 * E08-T04 医院端审计中心页纯逻辑单测：页签 → 列组装（数据访问页签含访问方式与记录数、
 * 登录页签含登录方式与会话号）、行 key、详情标题。
 */
import { describe, expect, it } from 'vitest';

import { columnsForTab, detailTitle, rowKey } from './audit-logic';

describe('columnsForTab', () => {
  it('操作日志页签：时间 / 账号 / 姓名 / 模块 / 操作 / 详情 / 结果 / IP / 操作列', () => {
    const columns = columnsForTab('operation');
    expect(columns.map((c) => c.title)).toEqual([
      '时间',
      '账号',
      '姓名',
      '模块',
      '操作',
      '详情',
      '结果',
      'IP',
      '操作列',
    ]);
  });

  it('数据访问页签：额外包含访问方式与记录数（PLT-AUD-003 扩展字段）', () => {
    const titles = columnsForTab('data-access').map((c) => c.title);
    expect(titles).toContain('访问方式');
    expect(titles).toContain('记录数');
    expect(titles.indexOf('访问方式')).toBeLessThan(titles.indexOf('详情'));
  });

  it('登录页签：额外包含登录方式与会话号', () => {
    const titles = columnsForTab('login').map((c) => c.title);
    expect(titles).toContain('登录方式');
    expect(titles).toContain('会话号');
  });
});

describe('rowKey', () => {
  it('页签 + 行 id 组成唯一 key（不同页签可能撞 id）', () => {
    expect(rowKey('operation', 5)).toBe('operation-5');
    expect(rowKey('login', 5)).toBe('login-5');
  });
});

describe('detailTitle', () => {
  it('按日志类型给标题', () => {
    expect(detailTitle('OPERATION', 12)).toBe('操作日志 #12');
    expect(detailTitle('LOGIN', 13)).toBe('登录日志 #13');
    expect(detailTitle('DATA_ACCESS', 14)).toBe('数据访问日志 #14');
  });
});
