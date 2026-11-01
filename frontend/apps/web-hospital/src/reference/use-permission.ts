import { useAccessStore } from '@vben/stores';

import { hasPermission as matchPermission } from '@crdp/rules';

/**
 * 必须在 setup 里调用一次（components.md §1.2）。
 * 权限码来自 GET /auth/codes（PLT-PERM-006），登录后缓存进 access store；
 * 权限变化（40300 / permissionVersion 变化）时由 auth store 重新拉取，
 * 这里读 store 保持响应式。没有权限时调用方隐藏按钮；匹配逻辑统一在 @crdp/rules。
 */
export function usePermission() {
  const accessStore = useAccessStore();

  function hasPermission(code: string | string[], mode: 'all' | 'any' = 'any') {
    return matchPermission(accessStore.accessCodes, code, mode);
  }

  return { hasPermission };
}
