/**
 * E08-T04 平台端审计中心页纯逻辑单测：页签 → 列（租户视角页签含视角会话号）、验链状态文案。
 */
import { describe, expect, it } from 'vitest';

import {
  chainStatusColor,
  chainStatusText,
  PLATFORM_EXPORT_HINT,
  platformColumnsForTab,
} from './logic';

describe('platformColumnsForTab', () => {
  it('平台操作页签：时间 / 租户ID / 账号 / 姓名 / 模块 / 操作 / 详情 / 结果 / IP / 操作列', () => {
    expect(platformColumnsForTab('operation').map((c) => c.title)).toEqual([
      '时间',
      '租户ID',
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

  it('租户视角与视角规则页签：包含视角会话号（PLT-AUD-004 扩展字段）', () => {
    expect(platformColumnsForTab('tenant-view').map((c) => c.title)).toContain(
      '视角会话号',
    );
    expect(platformColumnsForTab('view-rule').map((c) => c.title)).toContain(
      '视角会话号',
    );
  });
});

describe('chainStatusText', () => {
  it('pASS / BROKEN → 完整 / 断链', () => {
    expect(chainStatusText('PASS')).toBe('完整');
    expect(chainStatusText('BROKEN')).toBe('断链');
    expect(chainStatusText(null)).toBe('—');
  });

  it('标签颜色与状态匹配', () => {
    expect(chainStatusColor('PASS')).toBe('success');
    expect(chainStatusColor('BROKEN')).toBe('error');
  });
});

describe('pLATFORM_EXPORT_HINT', () => {
  it('审批口径提示包含“申请人不能自审”与“24 小时”', () => {
    expect(PLATFORM_EXPORT_HINT).toContain('申请人不能自审');
    expect(PLATFORM_EXPORT_HINT).toContain('24 小时');
  });
});
