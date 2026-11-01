import { describe, expect, it } from 'vitest';

import {
  permissionVersionChanged,
  shouldRefreshPermissions,
} from './permission-refresh';

describe('e06-T01 shouldRefreshPermissions（PLT-PERM-010 / components.md §1.2）', () => {
  it('40300 缺权限码：触发刷新（后端此时已按新授权重算会话）', () => {
    expect(shouldRefreshPermissions(40_300)).toBe(true);
  });

  it('40301 模块未开通：触发刷新，让入口从菜单里消失（OQ-05）', () => {
    expect(shouldRefreshPermissions(40_301)).toBe(true);
  });

  it('40302 是后端未声明数据范围的缺陷，不是权限变化，不刷新', () => {
    expect(shouldRefreshPermissions(40_302)).toBe(false);
  });

  it('其他错误码不触发刷新', () => {
    expect(shouldRefreshPermissions(0)).toBe(false);
    expect(shouldRefreshPermissions(40_100)).toBe(false);
    expect(shouldRefreshPermissions(40_101)).toBe(false);
    expect(shouldRefreshPermissions(40_305)).toBe(false);
    expect(shouldRefreshPermissions(50_000)).toBe(false);
  });

  it('缺失或非法输入不触发刷新', () => {
    expect(shouldRefreshPermissions(undefined)).toBe(false);
    expect(shouldRefreshPermissions(null)).toBe(false);
    expect(shouldRefreshPermissions(Number.NaN)).toBe(false);
  });
});

describe('e06-T01 permissionVersionChanged（me.permissionVersion 变化）', () => {
  it('版本号递增视为变化', () => {
    expect(permissionVersionChanged(1, 2)).toBe(true);
  });

  it('版本号相同不刷新', () => {
    expect(permissionVersionChanged(2, 2)).toBe(false);
  });

  it('首次看到版本号（无从比较）不触发刷新', () => {
    expect(permissionVersionChanged(null, 1)).toBe(false);
  });

  it('任一侧缺失都不触发刷新', () => {
    expect(permissionVersionChanged(1, null)).toBe(false);
    expect(permissionVersionChanged(null, null)).toBe(false);
  });
});
