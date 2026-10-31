/**
 * 新建租户向导的纯逻辑（E02-T07 / PLT-TEN-002）：编码建议值、编码与口令校验。页面只做展示与编排。
 */

const CODE_ALPHABET = 'abcdefghjkmnpqrstuvwxyz23456789';

/** 租户编码规则：小写字母开头，小写字母、数字、下划线（平台规格"租户编码"）。 */
export const TENANT_CODE_PATTERN = /^[a-z][a-z0-9_]{0,31}$/;

/** 编码建议值：页面可改。 */
export function suggestTenantCode(): string {
  const array = [...crypto.getRandomValues(new Uint32Array(7))];
  const chars = array.map(
    (value) => CODE_ALPHABET[value % CODE_ALPHABET.length],
  );
  return `t${chars.join('')}`;
}

export function validateTenantCode(code: string): string | undefined {
  if (!TENANT_CODE_PATTERN.test(code)) {
    return '编码须以小写字母开头，仅含小写字母、数字、下划线，最长 32 位';
  }
  return undefined;
}

export function validateInitialPassword(password: string): string | undefined {
  if (password.length < 8 || password.length > 64) {
    return '初始口令长度为 8 至 64 位';
  }
  return undefined;
}

/** 访问地址预览（PLT-TEN-015）：租户子域名 {编码}.平台域名；统一登录页可用编码登录。 */
export function subdomainPreview(code: string): string {
  return `${code}.<平台域名>`;
}
