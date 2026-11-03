package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientStatusHistoryMapper;
import com.crdp.framework.datascope.DataScope;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 状态时间线的数据面网关（E13-T03）：运行期范围上下文由本类注解经 Spring 代理开启（与 {@link PatientRegistrationGateway}
 * 同口径），查询按当前用户数据范围改写（越范围空清单）。
 */
@Component
public class PatientStatusGateway {

    private final PatientStatusHistoryMapper mapper;

    public PatientStatusGateway(PatientStatusHistoryMapper mapper) {
        this.mapper = mapper;
    }

    /** 某患者的状态时间线（范围内；越范围空清单，上层先行 40400 校验）。 */
    @DataScope(resource = "patient")
    public List<PatientStatusHistoryMapper.StatusRow> historyOf(long patientId) {
        return mapper.findHistory(patientId);
    }
}
