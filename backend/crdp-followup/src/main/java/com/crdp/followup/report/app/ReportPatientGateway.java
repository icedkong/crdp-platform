package com.crdp.followup.report.app;

import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.framework.datascope.DataScope;
import org.springframework.stereotype.Component;

/**
 * 报告单子域的患者可见性网关（E16-T02，与 {@code PatientContactGateway} 同口径）。Mapper 语句上的 {@code @DataScope}
 * 满足启动扫描（E07-T01），运行期范围上下文由本类注解经 Spring 代理开启——服务方法必须经代理调用。
 */
@Component
public class ReportPatientGateway {

    private final PatientMapper patientMapper;

    public ReportPatientGateway(PatientMapper patientMapper) {
        this.patientMapper = patientMapper;
    }

    /** 范围内在册患者 id（报告单入口的可见性校验；越范围 / 不存在 null → 上层 40400）。 */
    @DataScope(resource = "patient")
    public Long patientIdInScope(long patientId) {
        return patientMapper.findLiveIdById(patientId);
    }
}
