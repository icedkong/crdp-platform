/**
 * E06-T11 / PLT-CFG-008、PLT-ORG-011：租户个性化与个人中心类型。
 * 与后端 `BrandAdminService` / `LoginBrandService` / `ProfileService` 视图同构（docs/api/system.md §4.1、§7，auth.md §1）。
 */

/** 预设主题色（仅系统预设，OQ-23：主色固定 #3A75FF，租户只换 Logo、名称与预设辅助色）。 */
export interface ThemeColorOption {
  hex: string;
  key: 'BLUE' | 'ORANGE' | 'RED' | 'TEAL';
  label: string;
}

/** 预设色板。取自 docs/design/tokens.ts 四色，与后端 BrandThemeColor 枚举同源（OQ-49 ②）。 */
export const THEME_COLOR_OPTIONS: ThemeColorOption[] = [
  { hex: '#3A75FF', key: 'BLUE', label: '平台蓝（默认）' },
  { hex: '#1DC29E', key: 'TEAL', label: '青绿' },
  { hex: '#FA8C16', key: 'ORANGE', label: '暖橙' },
  { hex: '#FF4D4F', key: 'RED', label: '朱红' },
];

/** 预设色 key → 色值；未知 key 回落默认主色。 */
export function themeColorHexOf(key: null | string | undefined): string {
  return (
    THEME_COLOR_OPTIONS.find((color) => color.key === key)?.hex ?? '#3A75FF'
  );
}

/** 机构设置维护视角视图（GET /system/brand）。displayName 为空表示未配置（回落 defaultName）。 */
export interface BrandView {
  copyright: null | string;
  /** 平台租户名称（回落值，展示与提交前预填都用它）。 */
  defaultName: string;
  displayName: null | string;
  loginBgUrl: null | string;
  logoUrl: null | string;
  themeColor: string;
  themeColorHex: string;
  updatedAt: null | string;
}

/** 保存请求（全量覆盖；素材地址只放行 http(s) 或站内路径）。 */
export interface BrandSaveRequest {
  copyright: null | string;
  displayName: string;
  loginBgUrl: null | string;
  logoUrl: null | string;
  themeColor: null | string;
}

/** 登录页租户识别视图（GET /auth/tenant?code=，免登录）。 */
export interface TenantBrandView {
  copyright: null | string;
  /** 是否识别出租户；false 时各字段为通用默认（防探测），前端不据此提示「租户不存在」。 */
  identified: boolean;
  loginBgUrl: null | string;
  loginMethods: string[];
  logoUrl: null | string;
  tenantName: string;
  themeColor: string;
  themeColorHex: string;
}

/** 个人中心基本信息视图（GET /profile）。手机号只有脱敏值。 */
export interface ProfileView {
  createdAt: null | string;
  displayName: string;
  phoneMasked: null | string;
  roles: string[];
  tenantName: null | string;
  username: string;
}

/** 本人登录记录行（GET /profile/login-logs，最近 20 条）。 */
export interface LoginLogView {
  createdAt: string;
  message: string;
  success: boolean;
}

/** 登录记录结果文案（design-system §15–16 口径：成功 / 失败，不展示原因代码）。 */
export function loginLogResultText(row: LoginLogView): string {
  return row.success ? '成功' : '失败';
}

/** 素材地址放行口径（与后端 BrandAdminService.ASSET_URL 一致）：http(s) 或站内路径。 */
export const ASSET_URL_PATTERN = /^(https?:\/\/[^\s]+|\/[^\s]*)$/;

/** 机构设置表单本地校验：地址为空或合法时通过。 */
export function isValidAssetUrl(url: null | string | undefined): boolean {
  if (!url || url.trim() === '') {
    return true;
  }
  return ASSET_URL_PATTERN.test(url.trim());
}
