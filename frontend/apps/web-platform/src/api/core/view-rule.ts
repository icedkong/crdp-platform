import type {
  ViewRuleCatalog,
  ViewRuleItem,
  ViewRulePreviewRequest,
  ViewRulePreviewResult,
  ViewRuleSaveRequest,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 平台视角规则（E20-T02 / PLT-VIEW-007、008、009，docs/api/platform.md §6）：
 * 层级规则（全局默认 / 租户 / 模块 / 字段）的新建、维护与停用；
 * 效果预览选平台角色 + 租户，返回各模块（页面）与各字段的最终效果——
 * 预览与执行共用后端同一解析器（ViewRuleResolver），预览值即执行值。
 */
export function listViewRules(params?: {
  layer?: string;
  moduleCode?: string;
  resource?: string;
  status?: string;
  tenantId?: number;
}) {
  const query: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(params ?? {})) {
    if (value !== undefined && value !== '') {
      query[key] = value;
    }
  }
  return requestClient.get<ViewRuleItem[]>(apiPath('/view-rules'), {
    params: query,
  });
}

/** 视角字段目录（FIELD 层引用与预览用）与脱敏规则码清单。 */
export function getViewRuleCatalog() {
  return requestClient.get<ViewRuleCatalog>(apiPath('/view-rules/catalog'));
}

export function createViewRule(data: ViewRuleSaveRequest) {
  return requestClient.post<ViewRuleItem>(apiPath('/view-rules'), data);
}

/** 维护规则（含停用 / 启用；版本每次变更 +1）。 */
export function updateViewRule(id: number, data: ViewRuleSaveRequest) {
  return requestClient.put<ViewRuleItem>(apiPath(`/view-rules/${id}`), data);
}

/** 效果预览（PLT-VIEW-009）：平台角色（多选）+ 租户 → 模块与字段最终效果。 */
export function previewViewRules(data: ViewRulePreviewRequest) {
  return requestClient.post<ViewRulePreviewResult>(
    apiPath('/view-rules/preview'),
    data,
  );
}
