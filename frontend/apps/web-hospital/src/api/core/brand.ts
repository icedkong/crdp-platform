import type {
  BrandSaveRequest,
  BrandView,
  TenantBrandView,
  ThemeColorOption,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * E06-T11 / PLT-CFG-008：机构设置 · 个性化接口。
 * 契约见 docs/api/system.md §4.1；查看与维护都用 tenant:brand:edit（菜单按它下发）。
 */

/** 本院个性化配置（displayName 为空表示未配置，回落 defaultName）。 */
export function getBrand() {
  return requestClient.get<BrandView>(apiPath('/system/brand'));
}

/** 保存个性化（全量覆盖，即时生效并写审计）。 */
export function saveBrand(data: BrandSaveRequest) {
  return requestClient.put<BrandView>(apiPath('/system/brand'), data);
}

/** 预设色板（渲染色卡用；与 @crdp/api THEME_COLOR_OPTIONS 同源）。 */
export function getThemeColors() {
  return requestClient.get<ThemeColorOption[]>(
    apiPath('/system/brand/theme-colors'),
  );
}

/**
 * 登录页租户识别（免登录公开口，按 IP 每小时限流）。
 * 未识别出租户时返回通用默认值（identified=false），前端不提示「租户不存在」。
 */
export function getTenantBrand(code: string) {
  return requestClient.get<TenantBrandView>(apiPath('/auth/tenant'), {
    params: { code },
  });
}
