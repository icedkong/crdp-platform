package com.crdp.followup.implant.app;

import com.crdp.followup.implant.domain.ImplantCodes;
import com.crdp.followup.implant.infra.mapper.ImplantFeatureMapper;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.ImplantRow;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.LeadRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 当前器械判定与器械上下文（E21-T03 / C-PAT-007、C-FLD-005、C-FU-009；tasks.csv 验收「表单按当前器械显示字段」）。
 *
 * <p><b>判定口径（C-PAT-007）</b>：状态「在用」的植入记录即当前器械；随访记录按<b>随访日期</b>关联当时在用的器械（补录旧记录同样按日期匹配）——区间 {@code
 * [implant_date, end_date)}：旧器械的结束日期 = 新植入日期（E21-T02 更换流程），区间右开，
 * <b>更换当日归新器械</b>；在用行无结束日期视为仍在线。fu_record 不落关联列，关联随用随算（持久化与对比过滤随 E21-T11 / OQ-130③、OQ-134③）。
 *
 * <p><b>器械上下文（C-FLD-005 / C-FU-009，条件 DSL 的 ctx）</b>：按 C 规格 §2「按器械条件显示」表从器械类型与 在用电极配置派生三个键（形状由共享用例
 * {@code form-condition-cases.json} 锁定，前后端一致）：
 *
 * <ul>
 *   <li>{@code device.type}：起搏器类型编码（LV 电极组仅 CRT-P / CRT-D，ICD 治疗 / 充电时间 / 高压阻抗仅 ICD / CRT-D，用
 *       {@code in}）；
 *   <li>{@code device.hasRaLead}：有在用 RA 电极（心房参数组：阈值 / 脉宽 / P 波 / 阻抗 / 起搏比例）；
 *   <li>{@code device.chambers}：在用电极去重腔室数（AV 间期「双腔及以上」用 {@code gte 2}；左室参数首期不加，Q09）。
 * </ul>
 *
 * <p>废弃留置 / 已拔除电极不计入；沿用电极（同一行挂新记录，E21-T02 KEEP）计入新器械。功能开关 {@code implant} 关闭（A / B
 * 租户）或无匹配器械（取出后、首次植入前）→ 无上下文：ctx 断链 → 条件不成立 → 字段隐藏且不必填 （OQ-65①），植入查询不出发。查询经 {@link
 * ImplantGateway}（@DataScope）出行——本服务必须经 Spring 代理调用。
 */
@Service
public class DeviceContextService {

    private final ImplantGateway gateway;
    private final ImplantFeatureMapper features;
    private final ImplantDictPort dicts;

    public DeviceContextService(
            ImplantGateway gateway, ImplantFeatureMapper features, ImplantDictPort dicts) {
        this.gateway = gateway;
        this.features = features;
        this.dicts = dicts;
    }

    /**
     * 器械上下文视图（{@code GET /records/{id}} 的 {@code device}；页面以 {@code { device: … }}} 传入 FormRenderer
     * 的 {@code context}，表头当前器械显示用 typeLabel / implantDate 随 E21-T11）。
     */
    public record DeviceView(
            String implantId,
            String type,
            String typeLabel,
            LocalDate implantDate,
            boolean hasRaLead,
            int chambers,
            boolean current) {}

    /**
     * 当前器械判定（C-PAT-007）：{@code date} 为空（任务录入的记录随访日期在表单字段、fu_record.visit_date 为 NULL）→
     * 当前器械（在用记录）；否则按日期匹配当时在用的器械。开关关闭 / 无匹配返回 null。
     */
    public DeviceView resolve(long patientId, LocalDate date) {
        if (!Boolean.TRUE.equals(features.findEnabled(ImplantService.FEATURE_IMPLANT))) {
            return null;
        }
        ImplantRow row =
                date == null
                        ? gateway.activeImplantOf(patientId)
                        : gateway.implantAt(patientId, date);
        return row == null ? null : viewOf(row);
    }

    /**
     * 条件上下文（C-FLD-005 / C-FU-009）：{@code {device: {type, hasRaLead, chambers}}}，供
     * FormConditionEngine 的 ctx 与页面 FormRenderer 共用（提交校验与隐藏字段清空同上下文，tech-plan §6
     * 「后端保存时再求值一次」）。无器械返回空 Map（ctx 全部断链）。
     */
    public Map<String, Object> conditionContext(long patientId, LocalDate date) {
        DeviceView view = resolve(patientId, date);
        if (view == null) {
            return Map.of();
        }
        return Map.of(
                "device",
                Map.of(
                        "type", view.type(),
                        "hasRaLead", view.hasRaLead(),
                        "chambers", view.chambers()));
    }

    /** 植入记录行 → 上下文视图：电极面取挂在该记录下且「在用」的电极（沿用行计入新器械，废弃 / 拔除不计）。 */
    private DeviceView viewOf(ImplantRow row) {
        List<LeadRow> activeLeads =
                gateway.leadsOfImplant(row.id()).stream()
                        .filter(lead -> ImplantCodes.LEAD_STATUS_ACTIVE.equals(lead.status()))
                        .toList();
        boolean hasRaLead =
                activeLeads.stream()
                        .anyMatch(lead -> ImplantCodes.CHAMBER_RA.equals(lead.chamber()));
        int chambers = (int) activeLeads.stream().map(LeadRow::chamber).distinct().count();
        return new DeviceView(
                String.valueOf(row.id()),
                row.deviceType(),
                deviceTypeLabel(row.deviceType()),
                row.implantDate(),
                hasRaLead,
                chambers,
                ImplantCodes.STATUS_ACTIVE.equals(row.status()));
    }

    /** 起搏器类型显示名（字典 pacemaker_type，含停用项——历史记录照常显示，C-FLD-009；未知编码为 null）。 */
    private String deviceTypeLabel(String deviceType) {
        return dicts.items(ImplantDictPort.DICT_DEVICE_TYPE).stream()
                .filter(item -> item.itemCode().equals(deviceType))
                .map(ImplantDictPort.DictItem::label)
                .findFirst()
                .orElse(null);
    }
}
