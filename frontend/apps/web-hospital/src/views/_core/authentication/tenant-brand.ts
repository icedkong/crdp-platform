/**
 * E06-T11 / PLT-CFG-008、PLT-AUTH-004：登录页租户品牌的取数节奏（纯逻辑，便于单测）。
 * 租户编码合法才发起识别；防抖由调用方实现，这里只判断「值得取」。
 */
import { TENANT_CODE_PATTERN } from '../authentication/sms-login-form';

export { TENANT_CODE_PATTERN };

/** 租户识别请求防抖（毫秒）。 */
export const BRAND_FETCH_DEBOUNCE_MS = 400;

/** 编码合法才取品牌（空 / 非法不发请求，公开口按 IP 限流，别浪费额度）。 */
export function shouldFetchBrand(code: null | string | undefined): boolean {
  return TENANT_CODE_PATTERN.test((code ?? '').trim());
}

/**
 * 登录页头部文案：未识别出租户时用平台标题（identified=false 不提示「租户不存在」，防探测）。
 */
export function loginBrandTitle(
  identified: boolean,
  tenantName: null | string | undefined,
): string {
  if (!identified || !tenantName) {
    return '临床科研数据一体化平台';
  }
  return tenantName;
}
