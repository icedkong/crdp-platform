/**
 * E04-T02 / PLT-AUTH-001、PLT-AUTH-004：统一登录页可记住上次输入的租户编码。
 * 本机只存编码本身，不存账号、密码或其他任何凭据（docs/design/pages.md §1）。
 */

/** 与后端 HospitalLoginRequest.tenantCode 的校验规则一致。 */
const TENANT_CODE_PATTERN = /^[a-z][a-z0-9]{1,31}$/;

const TENANT_CODE_KEY = 'crdp.tenantCode';

export interface TenantCodeMemoryStorage {
  getItem(key: string): null | string;
  removeItem(key: string): void;
  setItem(key: string, value: string): void;
}

/** 去空白并小写化；不合法（含被篡改的存量值）一律返回 null。 */
export function normalizeTenantCode(raw: null | string): null | string {
  if (!raw) {
    return null;
  }
  const normalized = raw.trim().toLowerCase();
  return TENANT_CODE_PATTERN.test(normalized) ? normalized : null;
}

/** 读出上次记住的租户编码；没有或已不合法则返回 null。 */
export function recalledTenantCode(
  storage: TenantCodeMemoryStorage,
): null | string {
  return normalizeTenantCode(storage.getItem(TENANT_CODE_KEY));
}

/** 勾选“记住”才写入；取消勾选或编码不合法时清除本机记录。 */
export function applyTenantCodeMemory(
  storage: TenantCodeMemoryStorage,
  code: null | string,
  remember: boolean,
): void {
  const normalized = normalizeTenantCode(code);
  if (remember && normalized) {
    storage.setItem(TENANT_CODE_KEY, normalized);
  } else {
    storage.removeItem(TENANT_CODE_KEY);
  }
}
