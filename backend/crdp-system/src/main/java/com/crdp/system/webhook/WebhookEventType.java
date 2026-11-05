package com.crdp.system.webhook;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Webhook 订阅事件目录（E09-T06 / A-NTF-008、C-NTF-008；notify.md §6）。
 *
 * <p>A / C 差异（C 无「患者死亡」）由订阅配置体现——配置面只勾本院关心的事件，不写租户分支（AGENTS §5.7）。 code 与 sys_outbox 事件类型同口径；{@link
 * #objectType()} 是推给接收方的对象类型（负载只含事件类型与对象 ID，不含患者信息）。
 *
 * <p>RECORD_SUBMITTED / PATIENT_DEAD 的事件产生方（记录提交、患者死亡登记）随 E15 / 患者域任务落地， 本目录先行登记——事件一旦经 sys_outbox
 * 发布，订阅中的 Webhook 即收到推送。
 */
public enum WebhookEventType {
    /** 任务开启（提醒开始），对象 = 任务。 */
    TASK_OPENED("任务开启", "TASK", "taskId"),

    /** 已逾期，对象 = 任务。 */
    TASK_OVERDUE("已逾期", "TASK", "taskId"),

    /** 随访提交，对象 = 随访记录（fu_record，随 E15 产生事件）。 */
    RECORD_SUBMITTED("随访提交", "RECORD", "recordId"),

    /** 患者死亡（A-NTF-008，医院 C 不订阅即可），对象 = 患者。 */
    PATIENT_DEAD("患者死亡", "PATIENT", "patientId"),

    /** 连通性测试（POST /webhooks/{id}/test 专用，不可订阅）。 */
    WEBHOOK_TEST("Webhook 测试", "WEBHOOK", null);

    private final String label;
    private final String objectType;
    private final String payloadIdField;

    WebhookEventType(String label, String objectType, String payloadIdField) {
        this.label = label;
        this.objectType = objectType;
        this.payloadIdField = payloadIdField;
    }

    public static Optional<WebhookEventType> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(type -> type.name().equals(code.trim())).findFirst();
    }

    /** 中文名（配置页展示）。 */
    public String label() {
        return label;
    }

    /** 推送负载的对象类型（TASK / RECORD / PATIENT / WEBHOOK）。 */
    public String objectType() {
        return objectType;
    }

    /** 事件负载里承载对象 ID 的字段名（NotifyEventPayload 契约，notify.md §2）。 */
    public String payloadIdField() {
        return payloadIdField;
    }

    /** 可订阅事件（配置页多选项；WEBHOOK_TEST 只是 test 接口的载荷类型）。 */
    public static List<WebhookEventType> subscribable() {
        return List.of(TASK_OPENED, TASK_OVERDUE, RECORD_SUBMITTED, PATIENT_DEAD);
    }
}
