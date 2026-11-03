package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientSearchMapper;
import com.crdp.framework.datascope.DataScope;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 患者列表检索的数据面网关（E13-T04）。Mapper 语句上的 {@code @DataScope} 满足启动扫描（E07-T01）， 运行期 ThreadLocal 范围上下文由本类注解经
 * Spring 代理开启（DataScopeRuntimeChainTest 口径）——服务方法必须经代理调用， 所以独立成 bean（与 {@link
 * PatientRegistrationGateway} 同构）。
 *
 * <p>两个入口都按 {@code resource = "patient"} 改写：越范围（病区 / 科室 / 本人范围外）的患者行直接不可见（验收「按病区隔离」），
 * 范围为空时返回空列表而不是报错。
 */
@Component
public class PatientSearchGateway {

    private final PatientSearchMapper mapper;

    public PatientSearchGateway(PatientSearchMapper mapper) {
        this.mapper = mapper;
    }

    /** 分页检索一页（当前用户数据范围内）。 */
    @DataScope(resource = "patient")
    public List<PatientSearchMapper.PatientRowRaw> search(
            PatientSearchMapper.SearchCriteria criteria, int offset, int limit) {
        return mapper.search(criteria, offset, limit);
    }

    /** 条件总数（与 search 同一条件树、同一范围改写）。 */
    @DataScope(resource = "patient")
    public long count(PatientSearchMapper.SearchCriteria criteria) {
        return mapper.count(criteria);
    }
}
