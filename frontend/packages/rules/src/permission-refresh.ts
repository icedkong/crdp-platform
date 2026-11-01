/**
 * E06-T01 / PLT-PERM-010：权限变化的两个触发源（components.md §1.2、OQ-40）。
 * ① 接口返回 40300（缺权限码）或 40301（模块未开通，入口应消失，OQ-05）；
 * ② me.permissionVersion 与上次看到的不一致。
 * 注意 40302 是「查询受控数据但未声明数据范围」的后端编程错误，不是权限变化，不刷新。
 */
export const PERMISSION_REFRESH_CODES: ReadonlySet<number> = new Set([
  40_300, 40_301,
]);

/** 接口错误码是否应触发「重新拉取 me + 权限码 + 菜单」。 */
export function shouldRefreshPermissions(code?: null | number): boolean {
  return typeof code === 'number' && PERMISSION_REFRESH_CODES.has(code);
}

/**
 * me.permissionVersion 是否发生变化。
 * 首次看到（seen 为 null）或任一侧缺失都返回 false——登录后的第一次 me 不触发刷新。
 */
export function permissionVersionChanged(
  seen: null | number,
  current: null | number,
): boolean {
  if (typeof seen !== 'number' || typeof current !== 'number') {
    return false;
  }
  return seen !== current;
}
