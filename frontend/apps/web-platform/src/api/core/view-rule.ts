import type {
  ViewRuleCatalog,
  ViewRuleItem,
  ViewRulePreviewRequest,
  ViewRulePreviewResult,
  ViewRuleSaveOutcome,
  ViewRuleSaveRequest,
  ViewRuleVersionItem,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 平台视角规则（E20-T02 / PLT-VIEW-007、008、009；E20-T05 / PLT-VIEW-014、015，
 * docs/api/platform.md §6）：层级规则（全局默认 / 租户 / 模块 / 字段）的新建、维护与停用；
 * 高敏变更（改明文、取消设盲 / 只读、允许导出及等效放宽）进入待复核，由另一名平台管理员
 * 复核通过后生效；每次变更生成版本（变更人、前后值、复核人），可回滚。
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

/** 新建规则：普通变更即时生效；新建即放宽（可编辑 / 允许导出）为高敏，待复核。 */
export function createViewRule(data: ViewRuleSaveRequest) {
  return requestClient.post<ViewRuleSaveOutcome>(apiPath('/view-rules'), data);
}

/** 维护规则（含停用 / 启用）：普通变更即时生效；高敏变更规则保持原状、进入待复核。 */
export function updateViewRule(id: number, data: ViewRuleSaveRequest) {
  return requestClient.put<ViewRuleSaveOutcome>(
    apiPath(`/view-rules/${id}`),
    data,
  );
}

/** 待复核清单（全部 PENDING_REVIEW 版本行，含规则编号与名称）。 */
export function listPendingViewRuleReviews() {
  return requestClient.get<ViewRuleVersionItem[]>(
    apiPath('/view-rules/reviews/pending'),
  );
}

/** 同意高敏变更（PLT-VIEW-014：发起人以外的另一名平台管理员）。 */
export function approveViewRuleReview(versionId: number, comment?: string) {
  return requestClient.post<ViewRuleItem>(
    apiPath(`/view-rules/reviews/${versionId}/approve`),
    { comment: comment ?? null },
  );
}

/** 驳回高敏变更（规则保持原状；新建即待复核的规则行置停用）。 */
export function rejectViewRuleReview(versionId: number, comment?: string) {
  return requestClient.post<ViewRuleItem>(
    apiPath(`/view-rules/reviews/${versionId}/reject`),
    { comment: comment ?? null },
  );
}

/** 规则版本链（PLT-VIEW-015：变更人、前后值与复核人，版本号倒序）。 */
export function listViewRuleVersions(ruleId: number) {
  return requestClient.get<ViewRuleVersionItem[]>(
    apiPath(`/view-rules/${ruleId}/versions`),
  );
}

/** 回滚到已生效过的历史版本（回滚本身若属放宽，同样进入待复核）。 */
export function rollbackViewRule(ruleId: number, toVersion: number) {
  return requestClient.post<ViewRuleSaveOutcome>(
    apiPath(`/view-rules/${ruleId}/rollback`),
    { toVersion },
  );
}

/** 效果预览（PLT-VIEW-009）：平台角色（多选）+ 租户 → 模块与字段最终效果。 */
export function previewViewRules(data: ViewRulePreviewRequest) {
  return requestClient.post<ViewRulePreviewResult>(
    apiPath('/view-rules/preview'),
    data,
  );
}
