package com.crdp.framework.outbox;

/**
 * outbox 事件的进程内订阅方（E09-T01）。通知规则引擎（E09-T04）、站内信 / 短信派发（E09-T02 / T03）、Webhook 投递（E09-T06）、
 * 待办转交提示（E14+）都实现本接口接入分发器。
 *
 * <p>契约：
 *
 * <ul>
 *   <li><b>至少一次</b>：订阅方可能重复收到同一事件（分发成功但标记 SENT 前进程中断、失败重试），必须幂等；
 *   <li><b>租户上下文</b>：{@link #onEvent} 在事件所属租户的上下文内执行，可直接访问该院数据库；订阅方自身不得再切换租户；
 *   <li><b>同步快路径</b>：在分发线程上串行调用，外部调用（短信、Webhook）须在各自适配器内异步 / 有限重试，不阻塞分发；
 *   <li><b>抛错即失败</b>：任一感兴趣订阅方抛出运行时异常，该事件视为本次分发失败，留在 PENDING 由分发器退避重试； 失败时排在后面的订阅方本轮收不到，重试时从头再来。
 * </ul>
 */
public interface OutboxSubscriber {

    /** 是否订阅该事件类型（见 docs/api/system.md §9 与 docs/api/notify.md 的事件清单）。 */
    boolean supports(String eventType);

    /** 处理一条事件。实现必须幂等（至少一次语义），抛运行时异常表示本次投递失败。 */
    void onEvent(OutboxEvent event);
}
