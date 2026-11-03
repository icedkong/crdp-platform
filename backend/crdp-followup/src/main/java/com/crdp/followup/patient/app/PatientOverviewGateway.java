package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientOverviewMapper;
import com.crdp.framework.datascope.DataScope;
import org.springframework.stereotype.Component;

/**
 * 档案概览与时间轴的数据面网关（E13-T05）。Mapper 语句上的 {@code @DataScope} 满足启动扫描（E07-T01）， 运行期 ThreadLocal
 * 范围上下文由本类注解经 Spring 代理开启（与 {@link PatientSearchGateway} 同构）——服务方法必须经代理调用，所以独立成 bean。
 *
 * <p>按 {@code resource = "patient"} 改写：越范围（病区 / 科室 / 本人范围外）患者不可见（返回 null）， 范围为空时同样不可见而不是报错。
 */
@Component
public class PatientOverviewGateway {

    private final PatientOverviewMapper mapper;

    public PatientOverviewGateway(PatientOverviewMapper mapper) {
        this.mapper = mapper;
    }

    /** 单个患者的概览行（当前用户数据范围内；越范围 / 不存在 null，上层按 40400 处理）。 */
    @DataScope(resource = "patient")
    public PatientOverviewMapper.OverviewRow findOverview(long patientId) {
        return mapper.findOverview(patientId);
    }
}
