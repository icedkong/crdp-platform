/**
 * E14-T02 / A-FU-002、A-FU-016、A-FU-018、A-FU-019：随访计划接口
 * （docs/api/followup.md §1，后端 PlanController / PlanService，E14-T01 已实现；
 * 页面 pages.md §8）。查看 followup:plan:view（C 无此码，用 followup:plan:edit，OQ-13）；
 * 新建 / 编辑 / 复制 followup:plan:edit；发布 / 暂停 / 恢复 / 停用
 * followup:plan:publish 或 followup:plan:edit（任一命中即放行）。
 * E14-T08：计划患者列表（A-FU-022）与变更记录（A-FU-021）；批量入组随 E13-T07。
 */
import type {
  BatchResult,
  PlanCreateRequest,
  PlanDetailView,
  PlanEnrollmentPage,
  PlanLogsView,
  PlanSaveNodesRequest,
  PlanUpdateRequest,
  PlanView,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 计划列表（每族最新版本在前；status 含派生「随访中 / RUNNING」与在组人数）。 */
export function listPlans() {
  return requestClient.get<PlanView[]>(apiPath('/followup/plans'));
}

/** 计划详情：基本信息 + 节点两层（子计划 / 节点共形，用不到的一层为 null / 默认值）。 */
export function fetchPlanDetail(planId: string) {
  return requestClient.get<PlanDetailView>(
    apiPath(`/followup/plans/${planId}`),
  );
}

/** 新建计划（未发布 v1；code 本院族唯一 40902，创建后不可改）。 */
export function createPlan(data: PlanCreateRequest) {
  return requestClient.post<PlanView>(apiPath('/followup/plans'), data);
}

/** 编辑基本信息（仅未发布，否则 40901；baseVersion 乐观锁冲突 40900）。 */
export function updatePlan(planId: string, data: PlanUpdateRequest) {
  return requestClient.put<PlanView>(
    apiPath(`/followup/plans/${planId}`),
    data,
  );
}

/** 整体保存节点（{ baseVersion, nodes[] } 两层树整体替换，仅未发布；结构失败 40001）。 */
export function savePlanNodes(planId: string, data: PlanSaveNodesRequest) {
  return requestClient.put<PlanDetailView>(
    apiPath(`/followup/plans/${planId}/nodes`),
    data,
  );
}

/** 发布计划（前置至少一个子计划与节点；同族旧已发布版本自动停用）。 */
export function publishPlan(planId: string) {
  return requestClient.post<PlanView>(
    apiPath(`/followup/plans/${planId}/publish`),
  );
}

/** 复制为新版本（从已发布 / 暂停 / 停用版本复制出未发布草稿；同族已有草稿 40901）。 */
export function copyPlan(planId: string) {
  return requestClient.post<PlanView>(
    apiPath(`/followup/plans/${planId}/copy`),
  );
}

/** 暂停（PUBLISHED → PAUSED，不开启新任务、不发通知）。 */
export function pausePlan(planId: string) {
  return requestClient.post<PlanView>(
    apiPath(`/followup/plans/${planId}/pause`),
  );
}

/** 恢复（PAUSED → PUBLISHED）。 */
export function resumePlan(planId: string) {
  return requestClient.post<PlanView>(
    apiPath(`/followup/plans/${planId}/resume`),
  );
}

/** 停用（终态：不再允许入组，在组患者沿用原版本继续）。 */
export function disablePlan(planId: string) {
  return requestClient.post<PlanView>(
    apiPath(`/followup/plans/${planId}/disable`),
  );
}

/**
 * 计划患者列表（E14-T08 / A-FU-022）：加入时间、随访完成次数、状态（已启动 /
 * 已暂停 / 已移除）；姓名与病案号只含脱敏值，按当前用户数据范围出行。
 */
export function fetchPlanEnrollments(
  planId: string,
  pageNo = 1,
  pageSize = 20,
) {
  return requestClient.get<PlanEnrollmentPage>(
    apiPath(`/followup/plans/${planId}/enrollments`),
    { params: { pageNo, pageSize } },
  );
}

/** 计划变更记录（E14-T08 / A-FU-021）：倒序最近 100 条，操作人、时间与前后内容。 */
export function fetchPlanLogs(planId: string) {
  return requestClient.get<PlanLogsView>(
    apiPath(`/followup/plans/${planId}/logs`),
  );
}

// ---------------------------------------------------------------- 批量入组（E13-T07 / A-PAT-016）

/**
 * 批量入组预检（A-PAT-016「确认前显示将加入人数与将跳过人数」）：逐条 ENROLL / SKIP +
 * 原因码（已在组 / 已死亡 / 已移除 / 已终止 / 越范围 / 医生不负责该病区），不写任何数据。
 */
export function batchEnrollPreview(
  planId: string,
  payload: { doctorId?: number | string; patientIds: Array<number | string> },
) {
  return requestClient.post<BatchResult>(
    apiPath(`/followup/plans/${planId}/enrollments/batch-preview`),
    payload,
  );
}

/**
 * 批量入组（A-PAT-016）：逐条入组并返回成功 / 跳过明细（批量操作结果逐条可查）；
 * doctorId 可选——入组同时指定责任医生（A-PAT-017，只能选该病区的医生）。
 */
export function batchEnroll(
  planId: string,
  payload: { doctorId?: number | string; patientIds: Array<number | string> },
) {
  return requestClient.post<BatchResult>(
    apiPath(`/followup/plans/${planId}/enrollments/batch`),
    payload,
  );
}
