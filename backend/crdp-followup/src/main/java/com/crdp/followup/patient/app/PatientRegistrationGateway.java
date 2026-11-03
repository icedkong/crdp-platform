package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.framework.datascope.DataScope;
import com.crdp.framework.datascope.IgnoreScope;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 患者登记的数据面网关（E13-T01）。Mapper 语句上的 {@code @DataScope} / {@code @IgnoreScope} 满足启动扫描 （E07-T01），运行期
 * ThreadLocal 范围上下文由本类的注解经 Spring 代理开启（DataScopeRuntimeChainTest 口径）—— 服务方法必须经代理调用，所以独立成 bean
 * 而不是自调用。
 *
 * <p>两个口径并存（A-PAT-007、AGENTS.md §5.2「重复提示按当前用户范围计算」）：
 *
 * <ul>
 *   <li>{@link #liveIdByMrnBlind}：全院存在性（{@code @IgnoreScope}）——唯一性是全院约束，范围外同号同样 阻止登记，但只取
 *       id、不输出患者数据；
 *   <li>{@link #duplicateSummaryByMrnBlind} / {@link #sameIdentityMatches}：脱敏摘要查询
 *       （{@code @DataScope}）——越范围患者不可见（返回 null / 空清单，不暴露存在）。
 * </ul>
 */
@Component
public class PatientRegistrationGateway {

    private final PatientMapper mapper;

    public PatientRegistrationGateway(PatientMapper mapper) {
        this.mapper = mapper;
    }

    /** 全院病案号存在性（软删除不占用）。 */
    @IgnoreScope(reason = "病案号本院唯一（A-PAT-007 / C-PAT-002）：全院查重只取存在性，不输出患者数据；" + "范围外同号患者同样阻止登记")
    public Long liveIdByMrnBlind(String mrnBlind) {
        return mapper.findLiveIdByMrnBlind(mrnBlind);
    }

    /** 查重摘要（当前用户数据范围内；越范围 null）。 */
    @DataScope(resource = "patient")
    public PatientMapper.DuplicateRow duplicateSummaryByMrnBlind(String mrnBlind) {
        return mapper.findLiveByMrnBlind(mrnBlind);
    }

    /** 「姓名 + 出生日期相同」可能重复（C-PAT-002，范围内）。 */
    @DataScope(resource = "patient")
    public List<PatientMapper.DuplicateRow> sameIdentityMatches(
            String nameBlind, LocalDate birthDate, String mrnBlind, int limit) {
        return mapper.findLiveByIdentity(nameBlind, birthDate, mrnBlind, limit);
    }

    /** 明文来源（E07-T05 端口）：范围内该患者的敏感密文列。 */
    @DataScope(resource = "patient")
    public PatientMapper.SensitiveRow sensitiveRowOf(long patientId) {
        return mapper.findSensitiveById(patientId);
    }
}
