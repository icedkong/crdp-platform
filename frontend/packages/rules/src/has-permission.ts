/**
 * E06-T01 / PLT-PERM-006：按权限码判断按钮显隐（components.md §1.2）。
 * owned 为当前用户权限码（GET /auth/codes，登录后缓存进 access store）；
 * 前端判断只是体验，后端每个接口仍校验权限码（AGENTS.md 规则 9）。
 */
export function hasPermission(
  owned: Iterable<string>,
  required: string | string[],
  mode: 'all' | 'any' = 'any',
): boolean {
  const requiredList = Array.isArray(required) ? required : [required];
  if (requiredList.length === 0) {
    return false;
  }
  const ownedSet = new Set(owned);
  if (mode === 'all') {
    return requiredList.every((code) => ownedSet.has(code));
  }
  return requiredList.some((code) => ownedSet.has(code));
}
