package com.crdp.followup.notice.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 通知事件的负载契约（outbox 事件 payload；发布方 = 随访任务域 E14+、汇总作业 E09-T05）。
 *
 * <p>信封沿用组织事件的口径（system.md §9）：{@code tenantCode / operatorId / occurredAt}，时间 ISO-8601 北京时间。
 * 专有字段按事件可选；未知字段忽略（发布方新增字段不炸旧引擎）。
 *
 * <p>患者姓名只进站内信密文列（ntf_message.patient_name_cipher），短信与其余台账列永不落患者信息 （A-NTF-003 / A-NFR-007；NOTIFY
 * 场景参数白名单是通道侧第二道闸）。
 *
 * @param taskId 任务 ID（站内信跳转落点 TASK）
 * @param ownerId 责任人：任务负责人；TASK_REASSIGNED = 新负责人；CALLBACK_DUE = 记录人
 * @param wardId 患者所属病区（负责人病区复核与病区成员兜底的锚点）
 * @param patientId 患者 ID（跳转与关联）
 * @param patientName 患者姓名（只落站内信密文列）
 * @param nodeName 计划节点名（文案用，如「第 4 年随访」）
 * @param windowStartDate 窗口开始日（yyyy-MM-dd，文案用）
 * @param windowEndDate 窗口结束日（yyyy-MM-dd，文案用）
 * @param reason 事件说明（改派原因、跟进说明等文案用）
 * @param recipientIds 显式接收人清单（DAILY_DIGEST 由汇总作业按待办算好下发；出现时优先于规则解析）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotifyEventPayload(
        Long taskId,
        Long ownerId,
        Long wardId,
        Long patientId,
        String patientName,
        String nodeName,
        String windowStartDate,
        String windowEndDate,
        String reason,
        List<Long> recipientIds) {}
