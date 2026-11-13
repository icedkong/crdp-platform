package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientDeptHistoryMapper;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.followup.patient.infra.mapper.PatientWardHistoryMapper;
import com.crdp.framework.datascope.DataScope;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 转病区 / 转科室的数据面网关（E13-T08 / A-WARD-005、E21-T21 / C-WARD-006）：运行期范围上下文由本类注解经 Spring 代理开启（与 {@link
 * PatientRegistrationGateway} 同口径）。越范围患者一律 null / 空清单（上层按 40400 处理，不暴露对象存在）。
 */
@Component
public class PatientTransferGateway {

    private final PatientMapper patientMapper;
    private final PatientWardHistoryMapper historyMapper;
    private final PatientDeptHistoryMapper deptHistoryMapper;

    public PatientTransferGateway(
            PatientMapper patientMapper,
            PatientWardHistoryMapper historyMapper,
            PatientDeptHistoryMapper deptHistoryMapper) {
        this.patientMapper = patientMapper;
        this.historyMapper = historyMapper;
        this.deptHistoryMapper = deptHistoryMapper;
    }

    /** 范围内在册患者 id 与当前病区 / 科室（转病区 / 转科室入口校验；越范围 / 不存在 null）。 */
    @DataScope(resource = "patient")
    public PatientMapper.WardRef patientWardInScope(long patientId) {
        return patientMapper.findLiveWardById(patientId);
    }

    /** 某患者的转病区历史（范围内；越范围空清单，上层先行 40400 校验）。 */
    @DataScope(resource = "patient")
    public List<PatientWardHistoryMapper.WardHistoryRow> wardHistoryOf(long patientId) {
        return historyMapper.findHistory(patientId);
    }

    /** 某患者的转科室历史（范围内；越范围空清单，上层先行 40400 校验）。 */
    @DataScope(resource = "patient")
    public List<PatientDeptHistoryMapper.DeptHistoryRow> deptHistoryOf(long patientId) {
        return deptHistoryMapper.findHistory(patientId);
    }
}
