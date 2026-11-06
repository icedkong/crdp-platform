package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.BaselineLockMapper;
import com.crdp.followup.patient.infra.mapper.BaselineSectionMapper;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.framework.datascope.DataScope;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 基线分节与锁定状态的数据面网关（E13-T02）。Mapper 语句上的 {@code @DataScope} 满足启动扫描（E07-T01）， 运行期 ThreadLocal
 * 范围上下文由本类的注解经 Spring 代理开启（与 {@link PatientContactGateway} 同口径）——服务方法必须经代理调用。
 *
 * <p>全部查询按当前用户数据范围：越范围患者一律 null / 空清单（上层按 40400 处理，不暴露对象存在）。
 */
@Component
public class BaselineGateway {

    private final PatientMapper patientMapper;
    private final BaselineSectionMapper sectionMapper;
    private final BaselineLockMapper lockMapper;

    public BaselineGateway(
            PatientMapper patientMapper,
            BaselineSectionMapper sectionMapper,
            BaselineLockMapper lockMapper) {
        this.patientMapper = patientMapper;
        this.sectionMapper = sectionMapper;
        this.lockMapper = lockMapper;
    }

    /** 范围内在册患者 id（基线入口的可见性校验；越范围 / 不存在 null）。 */
    @DataScope(resource = "patient")
    public Long patientIdInScope(long patientId) {
        return patientMapper.findLiveIdById(patientId);
    }

    /** 某分类分节（范围内；越范围 / 未建行 null）。 */
    @DataScope(resource = "patient")
    public BaselineSectionMapper.SectionRowRaw section(long patientId, String categoryKey) {
        return sectionMapper.findSection(patientId, categoryKey);
    }

    /** 全部分节（范围内；越范围空清单）。 */
    @DataScope(resource = "patient")
    public List<BaselineSectionMapper.SectionRowRaw> sections(long patientId) {
        return sectionMapper.findSections(patientId);
    }

    /** 锁定状态（范围内；越范围 / 未建行 null）。 */
    @DataScope(resource = "patient")
    public BaselineLockMapper.LockRowRaw lock(long patientId) {
        return lockMapper.findLock(patientId);
    }
}
