package com.crdp.followup.importer.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * E18-T06 导入异步派发器（OQ-101⑤「multipart 上传 → 对象存储 → 建作业 → 异步派发」）： 上传与确认提交都不阻塞请求线程，向导页轮询作业状态看进度
 * （tasks.csv E18-T06 验收「上传、校验、提交三步可见进度」）。 租户上下文经 {@code TenantContextTaskDecorator}
 * 复制到异步线程（AGENTS.md 第 5 节；与 E08-T04 AuditExportRunner 同款）。
 *
 * <p>失败安全：预检任务内部把任何异常落作业状态 PRECHECK_FAILED（{@link ImportStagingService#runPrecheck}）， 提交任务按批隔离失败
 * （E18-T05），这里只兜防线程吞栈。
 */
@Component
public class ImportDispatchRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportDispatchRunner.class);

    /** 一次异步导入任务（预检或提交执行）。 */
    @FunctionalInterface
    public interface ImportTask {

        void run(long jobId);
    }

    @Async("applicationTaskExecutor")
    public void dispatch(long jobId, ImportTask task) {
        try {
            task.run(jobId);
        } catch (RuntimeException failure) {
            // 预检 / 提交实现已负责把失败落作业状态；此处只防中断丢栈。
            log.warn("导入异步任务异常退出 jobId={}", jobId, failure);
        }
    }
}
