package com.crdp.server.system.audit;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * E08-T04 / PLT-AUD-007 异步导出执行器：提交或审批通过后不阻塞请求线程， 由 {@code applicationTaskExecutor} 执行生成任务；租户 /
 * 平台上下文经 {@code TenantContextTaskDecorator} 复制到异步线程（AGENTS 第 5 节）。
 */
@Component
public class AuditExportRunner {

    /** 一次导出生成任务：正常结束应写 READY（行数 / 文件 / 哈希 / 截止时间），异常写 FAILED。 */
    @FunctionalInterface
    public interface GenerationTask {
        void run(long exportId);
    }

    /** 失败安全：任务内部自行捕获异常写 FAILED，这里再兜一层防线程吞栈。 */
    @Async("applicationTaskExecutor")
    public void generate(long exportId, GenerationTask task) {
        try {
            task.run(exportId);
        } catch (RuntimeException ignored) {
            // 任务实现已负责落 FAILED；此处只防中断
        }
    }
}
