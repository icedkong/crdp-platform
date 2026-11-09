import { useAccessStore } from '@vben/stores';

import { tenantViewWritePermission } from '@crdp/api';
import { hasPermission as matchPermission } from '@crdp/rules';

import { useTenantViewStore } from '#/store';

/**
 * 必须在 setup 里调用一次（components.md §1.2）。
 * 权限码来自 GET /auth/codes（PLT-PERM-006），登录后缓存进 access store；
 * 权限变化（40300 / permissionVersion 变化）时由 auth store 重新拉取，
 * 这里读 store 保持响应式。没有权限时调用方隐藏按钮；匹配逻辑统一在 @crdp/rules。
 *
 * 租户视角模式（E20-T06 / PLT-VIEW-013、018）：视角令牌不带医院权限码——写按钮按会话的代为操作能力
 * 合成显隐（配置类 = 可代为配置 ∧ 资源在范围；业务数据 = 可编辑业务数据 ∧ 可编辑规则命中），
 * 读动作不合成（视角菜单驱动，E20-T04 口径）。前端显隐只是体验，后端执行点逐请求校验并记审计。
 */
export function usePermission() {
  const accessStore = useAccessStore();
  const tenantViewStore = useTenantViewStore();

  function hasPermission(code: string | string[], mode: 'all' | 'any' = 'any') {
    if (tenantViewStore.active) {
      const codes = Array.isArray(code) ? code : [code];
      if (mode === 'all') {
        return codes.every((item) =>
          tenantViewWritePermission(
            tenantViewStore.session?.capabilities,
            item,
          ),
        );
      }
      return codes.some((item) =>
        tenantViewWritePermission(tenantViewStore.session?.capabilities, item),
      );
    }
    return matchPermission(accessStore.accessCodes, code, mode);
  }

  return { hasPermission };
}
