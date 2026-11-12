package com.crdp.followup.implant.app;

import com.crdp.followup.implant.infra.mapper.ImplantMapper;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.framework.datascope.DataScope;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 植入记录与患者可见性的数据面网关（E21-T01，与 PatientContactGateway 同口径）。Mapper 语句上的 {@code @DataScope}
 * 满足启动扫描（E07-T01），运行期 ThreadLocal 范围上下文由本类的注解经 Spring 代理开启 ——服务方法必须经代理调用。
 *
 * <p>全部查询按当前用户数据范围：越范围患者一律 null / 空清单（上层按 40400 处理，不暴露对象存在）。
 */
@Component
public class ImplantGateway {

    private final PatientMapper patientMapper;
    private final ImplantMapper implantMapper;

    public ImplantGateway(PatientMapper patientMapper, ImplantMapper implantMapper) {
        this.patientMapper = patientMapper;
        this.implantMapper = implantMapper;
    }

    /** 范围内在册患者 id（植入记录入口的可见性校验；越范围 / 不存在 null）。 */
    @DataScope(resource = "patient")
    public Long patientIdInScope(long patientId) {
        return patientMapper.findLiveIdById(patientId);
    }

    /** 患者全部植入记录（范围内；越范围空清单）。 */
    @DataScope(resource = "patient")
    public List<ImplantMapper.ImplantRow> implantsOf(long patientId) {
        return implantMapper.findImplants(patientId);
    }

    /** 单条植入记录（范围内；越范围 / 不存在 null，上层 40400）。 */
    @DataScope(resource = "patient")
    public ImplantMapper.ImplantRow implantInScope(long implantId) {
        return implantMapper.findImplant(implantId);
    }

    /** 当前在用植入记录（范围内；无在用 null）。 */
    @DataScope(resource = "patient")
    public ImplantMapper.ImplantRow activeImplantOf(long patientId) {
        return implantMapper.findActiveImplant(patientId);
    }

    /** 日期当时在用的植入记录（E21-T03 / C-PAT-007；范围内无匹配 null）。 */
    @DataScope(resource = "patient")
    public ImplantMapper.ImplantRow implantAt(long patientId, LocalDate date) {
        return implantMapper.findImplantAt(patientId, date);
    }

    /** 患者全部电极（范围内；越范围空清单）。 */
    @DataScope(resource = "patient")
    public List<ImplantMapper.LeadRow> leadsOf(long patientId) {
        return implantMapper.findLeads(patientId);
    }

    /** 某植入记录下的电极（范围内；越范围空清单）。 */
    @DataScope(resource = "patient")
    public List<ImplantMapper.LeadRow> leadsOfImplant(long implantId) {
        return implantMapper.findLeadsOfImplant(implantId);
    }
}
