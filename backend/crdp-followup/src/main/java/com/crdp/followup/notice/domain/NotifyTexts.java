package com.crdp.followup.notice.domain;

/**
 * 站内信标题与摘要模板（A-NTF-002 站内信可列出患者，但姓名、病案号按接收人权限脱敏—— 患者身份经 ntf_message.patientName 密文列与跳转落点承载，标题 / 摘要
 * / 台账不写患者明文）。
 */
public final class NotifyTexts {

    private NotifyTexts() {}

    /** 标题：事件名 + 节点（不含患者姓名明文，V21 表注释口径）。 */
    public static String title(NotifyEventType type, NotifyEventPayload payload) {
        String node = nodeLabel(payload);
        return node == null ? type.label() : type.label() + "：" + node;
    }

    /** 摘要：窗口 / 原因等上下文；null 表示无摘要。 */
    public static String summary(NotifyEventType type, NotifyEventPayload payload) {
        String window = windowLabel(payload);
        String reason =
                payload.reason() == null || payload.reason().isBlank()
                        ? null
                        : payload.reason().trim();
        return switch (type) {
            case WINDOW_PREVIEW ->
                    window == null ? "随访窗口即将开启，请提前安排随访。" : "随访窗口 " + window + " 即将开启，请提前安排随访。";
            case TASK_OPENED ->
                    window == null
                            ? "随访任务已到提醒开始日，请及时安排随访。"
                            : "随访任务已到提醒开始日（窗口 " + window + "），请及时安排随访。";
            case WINDOW_OPEN ->
                    window == null ? "随访窗口已开启，请在窗口内完成随访。" : "随访窗口 " + window + " 已开启，请在窗口内完成随访。";
            case DUE_SOON ->
                    window == null
                            ? "随访任务即将逾期，请尽快处理。"
                            : "随访任务将于 " + payload.windowEndDate() + " 截止，请尽快处理。";
            case TASK_OVERDUE ->
                    window == null
                            ? "随访任务已逾期，请尽快处理或补录。"
                            : "随访任务已逾期（窗口截止 " + payload.windowEndDate() + "），请尽快处理或补录。";
            case DAILY_DIGEST -> "您有随访任务待处理，请登录平台查看。";
            case TASK_REASSIGNED ->
                    reason == null
                            ? "一项随访任务已改派给您，请查收并安排随访。"
                            : "一项随访任务已改派给您（" + reason + "），请查收并安排随访。";
            case CALLBACK_DUE -> "约定的回访时间已到，请按沟通记录联系患者。";
            case UNREACHED_FOLLOWUP ->
                    reason == null
                            ? "上次随访未联系到患者，请按跟进任务继续联系。"
                            : "上次随访未联系到患者（" + reason + "），请按跟进任务继续联系。";
            case LOST_CANDIDATE ->
                    window == null
                            ? "随访窗口已结束且仍未完成，请核实是否失访。"
                            : "随访窗口 " + window + " 已结束且仍未完成，请核实是否失访。";
        };
    }

    private static String nodeLabel(NotifyEventPayload payload) {
        return payload.nodeName() == null || payload.nodeName().isBlank()
                ? null
                : payload.nodeName().trim();
    }

    private static String windowLabel(NotifyEventPayload payload) {
        String start = payload.windowStartDate();
        String end = payload.windowEndDate();
        if (start == null && end == null) {
            return null;
        }
        if (start != null && end != null) {
            return start + " 至 " + end;
        }
        return start != null ? start : end;
    }
}
