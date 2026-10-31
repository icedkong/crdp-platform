import { useAccess } from '@vben/access';

/**
 * 必须在 setup 里调用一次。
 * 权限码来自 GET /auth/codes。没有权限时调用方隐藏按钮。
 */
export function usePermission() {
  const { hasAccessByCodes } = useAccess();

  function hasPermission(code: string | string[], mode: 'all' | 'any' = 'any') {
    const codes = Array.isArray(code) ? code : [code];
    if (codes.length === 0) return false;
    if (mode === 'all') {
      return codes.every((item) => hasAccessByCodes([item]));
    }
    return hasAccessByCodes(codes);
  }

  return { hasPermission };
}
