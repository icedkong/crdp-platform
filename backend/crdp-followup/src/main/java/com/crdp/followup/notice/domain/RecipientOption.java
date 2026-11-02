package com.crdp.followup.notice.domain;

/**
 * 接收人规则选项（A-NTF-004 / C-NTF-004；notify.md §2）。
 *
 * <ul>
 *   <li>{@link #OWNER} 任务负责人：TASK_REASSIGNED 为新负责人、CALLBACK_DUE 为记录人（事件负载的 ownerId）；
 *       发送前按病区范围复核，已不负责该病区（且非全院范围）的负责人不再收到；
 *   <li>{@link #WARD_STAFF} 病区成员兜底：无有效负责人时发给该病区全部成员（A 是医生与随访员、 C
 *       含护士——两院都是经「负责病区」关联病区的医护，差异在关联了谁，不在解析逻辑）；
 *   <li>{@link #CC_WARD_LEADER} 抄送病区负责人：暂按病区字典「默认随访负责人」解析（OQ-60）， 维护界面随 E13-T08。
 * </ul>
 *
 * <p>首期不发给患者（A-NTF-004 / C-NTF-004 医院已确认）：任何选项都只解析 sys_user 医护。
 */
public enum RecipientOption {
    OWNER,
    WARD_STAFF,
    CC_WARD_LEADER
}
