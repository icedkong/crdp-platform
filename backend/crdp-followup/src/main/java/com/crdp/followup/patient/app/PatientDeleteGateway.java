package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.framework.datascope.DataScope;
import org.springframework.stereotype.Component;

/**
 * 删除患者的数据面网关（E13-T09 / A-PAT-012）：运行期范围上下文由本类注解经 Spring 代理开启（与 {@link PatientTransferGateway}
 * 同口径）。越范围患者一律 null（上层按 40400 处理，不暴露对象存在）。
 */
@Component
public class PatientDeleteGateway {

    private final PatientMapper patientMapper;

    public PatientDeleteGateway(PatientMapper patientMapper) {
        this.patientMapper = patientMapper;
    }

    /** 范围内在册患者 id（删除入口校验；越范围 / 不存在 / 已删除 null）。 */
    @DataScope(resource = "patient")
    public Long patientIdInScope(long patientId) {
        return patientMapper.findLiveIdById(patientId);
    }
}
