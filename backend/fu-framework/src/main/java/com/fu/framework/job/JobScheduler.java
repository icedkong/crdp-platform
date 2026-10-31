package com.fu.framework.job;

/** 调度入口。ShedLock 锁表已在平台库预留，依赖与作业在引入每日任务时再加。 */
public interface JobScheduler {

    void runForEachTenant(String jobName);
}
