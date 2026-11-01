import { describe, expect, it } from 'vitest';

import { hasPermission } from './has-permission';

describe('e06-T01 hasPermission（components.md §1.2）', () => {
  it('any 模式：命中任一权限码即通过', () => {
    const owned = ['followup:patient:view', 'followup:patient:plain'];
    expect(hasPermission(owned, 'followup:patient:plain')).toBe(true);
    expect(
      hasPermission(owned, [
        'followup:patient:plain',
        'followup:patient:export',
      ]),
    ).toBe(true);
  });

  it('any 模式：一个都没有则隐藏按钮', () => {
    const owned = ['followup:patient:view'];
    expect(hasPermission(owned, 'followup:patient:plain')).toBe(false);
    expect(
      hasPermission(owned, [
        'followup:patient:plain',
        'followup:patient:export',
      ]),
    ).toBe(false);
  });

  it('all 模式：要求全部命中（多角色合并后的码表）', () => {
    const owned = ['followup:patient:view', 'followup:patient:export'];
    expect(
      hasPermission(
        owned,
        ['followup:patient:view', 'followup:patient:export'],
        'all',
      ),
    ).toBe(true);
    expect(
      hasPermission(
        owned,
        ['followup:patient:view', 'followup:patient:plain'],
        'all',
      ),
    ).toBe(false);
  });

  it('空要求或空码表一律不通过（默认拒绝）', () => {
    expect(hasPermission(['followup:patient:view'], [])).toBe(false);
    expect(hasPermission([], 'followup:patient:view')).toBe(false);
    expect(hasPermission([], [])).toBe(false);
  });

  it('单个权限码字符串与数组和单元素等价', () => {
    expect(hasPermission(['tenant:user:view'], ['tenant:user:view'])).toBe(
      true,
    );
  });
});
