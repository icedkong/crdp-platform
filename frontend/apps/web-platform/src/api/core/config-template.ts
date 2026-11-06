/**
 * 配置模板接口（E12-T04 管理页 / A-FLD-014，docs/api/platform.md §7.1）：
 * 读（列表 / 详情 / 包 / 注册表）platform:tenant:view，导出与应用 platform:tenant:create。
 */
import type {
  ConfigApplyResult,
  ConfigPackageView,
  ConfigRegistryEntry,
  ConfigTemplateDetail,
  ConfigTemplateView,
  PageView,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 模板分页（新→旧）。 */
export function listConfigTemplates(pageNo = 1, pageSize = 20) {
  return requestClient.get<PageView<ConfigTemplateView>>(
    apiPath('/config-templates'),
    { params: { pageNo, pageSize } },
  );
}

/** 模板详情：头信息与每张配置表的元信息（不带行数据）。 */
export function configTemplateDetail(id: number) {
  return requestClient.get<ConfigTemplateDetail>(
    apiPath(`/config-templates/${id}`),
  );
}

/** 从源租户导出配置模板（name 缺省「{租户名} 配置模板」；含敏感信息 40901 拦截）。 */
export function exportConfigTemplate(data: {
  name?: string;
  tenantId: number;
}) {
  return requestClient.post<ConfigTemplateView>(
    apiPath('/config-templates'),
    data,
  );
}

/** 配置表注册表：导出会包含哪些表、按什么方式复制（导出范围预览）。 */
export function configTableRegistry() {
  return requestClient.get<ConfigRegistryEntry[]>(
    apiPath('/config-templates/registry'),
  );
}

/** 读取完整包（gzip 解压后的 JSON 结构；后端写 CONFIG_TEMPLATE_DOWNLOAD 审计）。 */
export function configTemplatePackage(id: number) {
  return requestClient.get<ConfigPackageView>(
    apiPath(`/config-templates/${id}/package`),
  );
}

/** 应用到目标租户（导入 + ID 重映射；目标不能是源租户）。 */
export function applyConfigTemplate(id: number, tenantId: number) {
  return requestClient.post<ConfigApplyResult>(
    apiPath(`/config-templates/${id}/apply`),
    { tenantId },
  );
}
