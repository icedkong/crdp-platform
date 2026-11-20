package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientCorrectionMapper;
import com.crdp.framework.datascope.DataScope;
import org.springframework.stereotype.Component;

/** 范围声明经独立代理激活；纠错锁定患者后再锁定基线锁行，与并发锁定操作串行。 */
@Component
public class PatientCorrectionGateway {
    private final PatientCorrectionMapper mapper;

    public PatientCorrectionGateway(PatientCorrectionMapper mapper) {
        this.mapper = mapper;
    }

    @DataScope(resource = "patient")
    public PatientCorrectionMapper.Row find(long id, boolean locking) {
        return mapper.find(id, locking);
    }

    @DataScope(resource = "patient")
    public boolean locked(long id, boolean locking) {
        return Boolean.TRUE.equals(mapper.lockState(id, locking));
    }

    @com.crdp.framework.datascope.IgnoreScope(reason = "登记纠错已校验患者范围；仅计数该患者在组起算字段引用，避免清空有效计划基准")
    public long dateUsers(long id, String field) {
        return mapper.countDateUsers(id, field);
    }
}
