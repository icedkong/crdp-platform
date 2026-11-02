package com.crdp.framework.outbox;

/** 一次分发的结果计数：成功标记 SENT、仍留 PENDING 待重试、转入死信 FAILED 的条数。 */
public record DispatchResult(int dispatched, int pendingRetry, int deadLettered) {

    static final DispatchResult EMPTY = new DispatchResult(0, 0, 0);

    public int total() {
        return dispatched + pendingRetry + deadLettered;
    }
}
