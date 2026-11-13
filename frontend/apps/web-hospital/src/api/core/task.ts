/**
 * E15-T02 / A-FU-004、A-FU-005、C-FU-020 / E15-T03 A-FU-009：我的任务与随访工作台接口
 * （docs/api/followup.md §2、§3，后端 FollowupTaskController / RecordEntryController /
 * RecordDraftController；页面 pages.md §5、§7）。
 *
 * 权限：列表与队列 followup:task:view（菜单 22「我的任务」）；开始录入 / 暂存 /
 * 提交 followup:record:edit（按钮 222）；改派 followup:task:assign（按钮 224 同码，
 * OQ-18）；认领 followup:task:view（公共池自助认领）。数据范围由后端出行（越范围任务
 * 不可见，直接访问 40400）；行内姓名 / 病案号只含脱敏值。
 */
import type {
  BackfillCreateRequest,
  BackfillCreateView,
  RecordDetailView,
  RecordDuplicateCheckView,
  RecordEditSessionView,
  RecordSubmitResult,
  RecordTimepointSuggestionView,
  RecordUpdateResult,
  TaskAcknowledgeView,
  TaskAssignRequest,
  TaskAssignResult,
  TaskClaimView,
  TaskLostConfirmView,
  TaskPage,
  TaskQueueView,
  TaskSearchRequest,
  TaskStartView,
  TempTaskCreateRequest,
  TempTaskView,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 任务列表检索（页签 scope 缺省 MINE 我的待办；unassigned 只看待认领；返回页签计数与病区候选）。 */
export function searchTasks(data: TaskSearchRequest) {
  return requestClient.post<TaskPage>(apiPath('/followup/tasks/search'), data);
}

/**
 * 改派（A-FU-009，单个 = 传一条 taskIds）：新负责人须负责患者病区（或全院）；
 * 只有未完成任务可改派；改派成功后新负责人收 TASK_REASSIGNED 站内信。
 */
export function batchAssignTasks(data: TaskAssignRequest) {
  return requestClient.post<TaskAssignResult>(
    apiPath('/followup/tasks/batch-assign'),
    data,
  );
}

/** 从公共池认领（A-FU-009）：待认领且未完成的任务认领给当前登录用户。 */
export function claimTask(taskId: string) {
  return requestClient.post<TaskClaimView>(
    apiPath(`/followup/tasks/${taskId}/claim`),
    {},
  );
}

/** 开始录入（幂等）：无记录则创建 DRAFT 草稿并返回 recordId，任务进入进行中。 */
export function startTask(taskId: string) {
  return requestClient.post<TaskStartView>(
    apiPath(`/followup/tasks/${taskId}/start`),
    {},
  );
}

/**
 * 表单页「上一条 / 下一条」队列：按当前列表筛选的顺序返回 cursor 任务的
 * 前一条 / 后一条任务 id（尽头 null）。filter 参数与检索接口同构。
 */
export function fetchTaskQueue(cursor: string, filter: TaskSearchRequest) {
  return requestClient.get<TaskQueueView>(apiPath('/followup/tasks/queue'), {
    params: {
      completedFrom: filter.completedFrom,
      completedTo: filter.completedTo,
      cursor,
      doctorId: filter.doctorId,
      dueFrom: filter.dueFrom,
      dueTo: filter.dueTo,
      keyword: filter.keyword,
      nodeName: filter.nodeName,
      scope: filter.scope,
      status: filter.status,
      unassigned: filter.unassigned,
      wardIds: filter.wardIds,
      windowFrom: filter.windowFrom,
      windowTo: filter.windowTo,
    },
  });
}

/** 记录详情（表单页装配：data / meta / version / status + 任务上下文）。 */
export function fetchRecordDetail(recordId: string) {
  return requestClient.get<RecordDetailView>(
    apiPath(`/followup/records/${recordId}`),
  );
}

/** 暂存 / 自动保存草稿（E25-T01 已实现；changes 只带变更字段，成功后任务变进行中 OQ-77②；overwrite = 冲突流程「保留我的并覆盖」，后端写覆盖审计）。 */
export function saveRecordDraft(
  recordId: string,
  body: {
    baseVersion: number;
    changes: Record<string, unknown>;
    overwrite?: boolean;
  },
) {
  return requestClient.put<{ savedAt: string; version: number }>(
    apiPath(`/followup/records/${recordId}/draft`),
    body,
  );
}

/**
 * 提交（完整校验 42200；成功后任务完成、写字段历史与审计）。E21-T17 / C-SCAN-012：
 * 转录态表单页（?compare= 报告单 id &transcribe=1）带 transcribeReportId——同一事务把
 * 报告单自动加入该记录位置并置「已电子化」，结果附 transcribedReportId。
 */
export function submitRecord(
  recordId: string,
  body: {
    baseVersion: number;
    data: Record<string, unknown>;
    dataMeta?: Record<string, unknown>;
    transcribeReportId?: string;
  },
) {
  return requestClient.post<RecordSubmitResult>(
    apiPath(`/followup/records/${recordId}/submit`),
    body,
  );
}

/**
 * 重复记录提示（E21-T11 / C-FU-024）：同一患者同一有效随访日期（visit_date ?? 表单
 * 随访日期 ?? 任务计划日期）已有在册记录（含草稿，软删不算）时 duplicate = true——
 * 前端提示「该患者在 {日期} 已有一条随访记录，确定继续？」确认后照常保存。
 */
export function checkRecordDuplicate(
  patientId: number | string,
  visitDate: string,
  excludeRecordId?: null | string,
) {
  return requestClient.get<RecordDuplicateCheckView>(
    apiPath(`/followup/patients/${patientId}/records/check-duplicate`),
    { params: { excludeRecordId: excludeRecordId ?? undefined, visitDate } },
  );
}

/**
 * 开始修改已提交记录（E21-T11 / C-FU-026 / Q17）：reason 来自 ReasonModal「开始修改」；
 * 开关开启的租户（医院 C）必须 followup:record:edit-submitted（否则 40300），关闭的租户
 * followup:record:edit 即可；返回当前 version（保存修改的 baseVersion）。
 */
export function startRecordEditSession(
  recordId: string,
  body: { reason: string },
) {
  return requestClient.post<RecordEditSessionView>(
    apiPath(`/followup/records/${recordId}/edit-session`),
    body,
  );
}

/**
 * 保存修改已提交记录（E21-T11 / C-FU-026「修改留字段级历史」）：reason 必填
 * （OQ-14 暂定 A / C 都必填）；SUBMITTED 原地更新 + version + 1（保存后生成 v2）。
 */
export function updateSubmittedRecord(
  recordId: string,
  body: {
    baseVersion: number;
    data: Record<string, unknown>;
    dataMeta?: Record<string, unknown>;
    reason: string;
    /** E21-T17 / C-SCAN-012：转录态保存修改同样完成转录（位置中已有已提交记录的对照录入）。 */
    transcribeReportId?: string;
  },
) {
  return requestClient.put<RecordUpdateResult>(
    apiPath(`/followup/records/${recordId}`),
    body,
  );
}

/** 删除随访记录（E21-T11 / C-FU-026）：软删除、reason 必填、记审计（followup:record:delete）。 */
export function deleteRecord(recordId: string, body: { reason: string }) {
  return requestClient.delete<null>(apiPath(`/followup/records/${recordId}`), {
    data: body,
  });
}

/** 历史补录 / 计划外随访 / 远程监测记录（E15-T07 补录；E15-T06 起计划外 UNPLANNED；E21-T12 起远程监测 REMOTE）：新建记录返回 recordId 进表单页。 */
export function createBackfillRecord(
  patientId: number | string,
  data: BackfillCreateRequest,
): Promise<BackfillCreateView> {
  return requestClient.post(
    apiPath(`/followup/patients/${patientId}/records`),
    data,
  );
}

/**
 * 补录时建议随访时间点（E21-T14 / C-FU-014）：按随访日期距植入天数建议选项（空档区间 /
 * 无器械 / A-B 表单无字段时无建议）、自动关联当时器械、器械接近 ERI 优先建议「ERI期随访」；
 * earliestImplantDate 为补录日期下界。失败不拦截（补录弹窗只做提示）。
 */
export function fetchTimepointSuggestion(
  patientId: number | string,
  visitDate: string,
) {
  return requestClient.get<RecordTimepointSuggestionView>(
    apiPath(`/followup/patients/${patientId}/records/suggest-timepoint`),
    { params: { visitDate } },
  );
}

/**
 * 临时添加任务（E15-T06 / A-FU-013「不占用年度任务」）：挂靠患者在组入组与计划节点，
 * 任务类型 FORM 填表随访 / REMINDER 站内提醒（A-FU-026 无需填表）；负责人缺省当前用户。
 */
export function createTempTask(data: TempTaskCreateRequest) {
  return requestClient.post<TempTaskView>(apiPath('/followup/tasks'), data);
}

/**
 * 完成提醒（E15-T06 / A-FU-026 验收「站内提醒类任务无需填表」）：站内提醒类任务确认即完成，
 * 不创建记录；填表任务 40901（须提交随访记录）。
 */
export function acknowledgeTask(taskId: string) {
  return requestClient.post<TaskAcknowledgeView>(
    apiPath(`/followup/tasks/${taskId}/acknowledge`),
    {},
  );
}

/**
 * 确认失访（S5 / A-FU-012「医生确认后标为失访」，E15-T05 已有后端）：失访候选 → 失访，
 * 同窗口未完成跟进任务关闭；连续 2 个窗口失访返回 patientLostPrompt = true——
 * 前端提示医生到患者详情把患者状态改为失访（不自动改）。
 */
export function confirmLostTask(taskId: string) {
  return requestClient.post<TaskLostConfirmView>(
    apiPath(`/followup/tasks/${taskId}/confirm-lost`),
    {},
  );
}
