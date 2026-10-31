package com.fu.framework.job;

import org.springframework.stereotype.Component;

@Component
public class UnsupportedJobScheduler implements JobScheduler {

    @Override
    public void runForEachTenant(String jobName) {
        throw new UnsupportedOperationException("按租户扇出的调度将在 E02 之后实现");
    }
}
