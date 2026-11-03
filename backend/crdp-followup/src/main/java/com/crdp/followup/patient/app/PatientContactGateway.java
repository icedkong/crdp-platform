package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientContactMapper;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.framework.datascope.DataScope;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 联系方式与患者可见性的数据面网关（E13-T03）。Mapper 语句上的 {@code @DataScope} 满足启动扫描（E07-T01）， 运行期 ThreadLocal
 * 范围上下文由本类的注解经 Spring 代理开启（与 {@link PatientRegistrationGateway} 同口径）——服务方法必须经代理调用。
 *
 * <p>全部查询按当前用户数据范围：越范围患者一律 null / 空清单（上层按 40400 处理，不暴露对象存在）。
 */
@Component
public class PatientContactGateway {

    private final PatientMapper patientMapper;
    private final PatientContactMapper contactMapper;

    public PatientContactGateway(PatientMapper patientMapper, PatientContactMapper contactMapper) {
        this.patientMapper = patientMapper;
        this.contactMapper = contactMapper;
    }

    /** 范围内在册患者 id（联系方式 / 状态历史入口的可见性校验；越范围 / 不存在 null）。 */
    @DataScope(resource = "patient")
    public Long patientIdInScope(long patientId) {
        return patientMapper.findLiveIdById(patientId);
    }

    /** 某槽位当前版本（范围内；越范围 null）。 */
    @DataScope(resource = "patient")
    public PatientContactMapper.ContactRow currentVersion(long patientId, String slot) {
        return contactMapper.findCurrentVersion(patientId, slot);
    }

    /** 全部版本（范围内；越范围空清单）。 */
    @DataScope(resource = "patient")
    public List<PatientContactMapper.ContactRow> versionsOf(long patientId) {
        return contactMapper.findVersions(patientId);
    }

    /** 当前电话1密文（明文查看 phone 字段来源，patient.md §5；范围内，越范围 null）。 */
    @DataScope(resource = "patient")
    public String currentPhone1Cipher(long patientId) {
        return contactMapper.findCurrentPhone1Cipher(patientId);
    }
}
