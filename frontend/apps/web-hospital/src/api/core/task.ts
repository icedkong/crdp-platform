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
  RecordSubmitResult,
  TaskAcknowledgeView,
  TaskAssignRequest,
  TaskAssignResult,
  TaskClaimView,
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

/** 提交（完整校验 42200；成功后任务完成、写字段历史与审计）。 */
export function submitRecord(
  recordId: string,
  body: {
    baseVersion: number;
    data: Record<string, unknown>;
    dataMeta?: Record<string, unknown>;
  },
) {
  return requestClient.post<RecordSubmitResult>(
    apiPath(`/followup/records/${recordId}/submit`),
    body,
  );
}

/** 历史补录 / 计划外随访（E15-T07 补录；E15-T06 起计划外 UNPLANNED 当天可录）：新建记录返回 recordId 进表单页。 */
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
