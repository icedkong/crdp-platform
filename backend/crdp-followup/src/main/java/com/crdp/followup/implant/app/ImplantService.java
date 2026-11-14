package com.crdp.followup.implant.app;

import com.crdp.followup.implant.domain.ImplantCodes;
import com.crdp.followup.implant.infra.mapper.ImplantFeatureMapper;
import com.crdp.followup.implant.infra.mapper.ImplantMapper;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.ImplantInsert;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.ImplantRow;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.ImplantUpdate;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.LeadInsert;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.LeadRow;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.LeadUpdate;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 植入记录（一对多）与电极记录用例（E21-T01 / C-PAT-004、C-PAT-005；tasks.csv 验收「一个患者多次植入、多根电极」；接口 docs/api/implant.md
 * §1）。
 *
 * <p>C 规格 §2 / §4：器械信息从患者登记拆出，患者 → 植入记录（1:N）→ 电极（可多根、可跨器械沿用）。本任务（T01）覆盖新建与编辑；更换 / 升级 / 电极修正 /
 * 取出的专用流程与计划切换（C-PAT-006、C-FU-007）随 E21-T02 及后续任务。 E21-T05 / C-FU-003：保存后<b>在用</b>的植入记录（新建、登记同建、 更换
 * / 升级 / 其他专用流程；电极修正与历史补录除外）按器械类型映射自动入组（{@code DevicePlanMappingService}），起算日 = 植入日期。
 *
 * <p>E21-T04（C-FLD-002、C-FLD-003）：起搏器类型、品牌、<b>统一起搏模式</b>（患者登记表与程控记录表两套
 * 选项合并为一个字典，植入记录与程控记录共用，Q08）、植入指征、电极植入部位（按腔室）改读业务字典 （平台库 V70 预置，租户经 cfg_dict 停用平台项 /
 * 新增本院项，PLT-CFG-002）：options 只下发生效项 （停用项不进下拉），保存校验接受全部已知项（停用项的历史值可原样保存，与 FormDataValidator 同向），
 * 显示名随字典解析（含停用项，历史记录照常显示，C-FLD-009）。库列不动（仍存稳定字符串编码）。
 *
 * <ul>
 *   <li>一位患者同一时间只有一条「在用」植入记录（C 规格 §4 业务规则）：新建晚于 / 等于当前在用植入日期的记录时，旧记录自动结束（已更换，结束日期 =
 *       新植入日期）；补录早于当前在用的历史器械时新记录直接落「已更换」；并发双开在用由 uk_pt_implant_active 生成列唯一约束兜底 → 40900；
 *   <li>电极挂在植入记录下（RA / RV / LV 腔室 + 按腔室过滤的植入部位，Q15），无导线起搏器（LEADLESS）不录电极（C-PAT-005）；
 *   <li>电极植入日期缺省同植入记录日期（附录 A 电极 #5）；电极沿用（保留原日期跨器械挂接）随 E21-T02；
 *   <li>植入记录状态（在用 / 已更换 / 已取出）系统维护，不接受请求体直改（附录 A 植入 #12「枚举·系统」）；
 *   <li>功能开关 {@code implant}（cfg_feature）关闭 → 40301（无行视为关闭，与 useFeature「未配置＝默认关闭」同向）；写入
 *       IMPLANT_CREATE / IMPLANT_UPDATE 操作审计；字段级历史（C-PAT-011）随 C 字段字典录入 （E23-T20）C_IMPLANT
 *       表单模板落地（docs/open-questions.md）。
 * </ul>
 *
 * <p>越范围 / 不存在的患者与植入记录一律 40400（AGENTS.md §5.2，不暴露对象存在）。
 */
@Service
public class ImplantService {

    private static final Logger log = LoggerFactory.getLogger(ImplantService.class);

    /** 功能开关编码（cfg_feature，种子 V100 默认关闭；components.md §1.3）。 */
    public static final String FEATURE_IMPLANT = "implant";

    /** 植入记录字段字典表单编码（fe_form，V90 随包；字段级留痕键 = 其稳定键 f1xx / f12x）。 */
    public static final String IMPLANT_FORM_CODE = "C_IMPLANT";

    /** 植入记录行 → 字段级留痕值（键 = C_IMPLANT 稳定键，V90 附录植入 #2–#11 + 配套两列； 日期转 ISO 文本，值编码同 fe_record.data）。 */
    private static java.util.Map<String, Object> implantHistoryValues(
            String implantType,
            java.time.LocalDate implantDate,
            String deviceType,
            String brand,
            String model,
            String serialNo,
            String pacingMode,
            String hospital,
            String doctor,
            String indication,
            String indicationNote,
            String note) {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("f102", implantType);
        values.put("f103", implantDate == null ? null : implantDate.toString());
        values.put("f104", deviceType);
        values.put("f105", brand);
        values.put("f106", model);
        values.put("f107", serialNo);
        values.put("f108", pacingMode);
        values.put("f109", hospital);
        values.put("f110", doctor);
        values.put("f111", indication);
        values.put("f114", indicationNote);
        values.put("f115", note);
        return values;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ImplantGateway gateway;
    private final ImplantMapper mapper;
    private final ImplantFeatureMapper features;
    private final ImplantDictPort dicts;
    private final AuditLogger audit;
    private final com.crdp.followup.plan.app.DevicePlanMappingService planMappings;

    private final com.crdp.followup.plan.app.PlanSwitchService planSwitch;

    private final com.crdp.followup.task.app.StartDateRecalculationService startDateRecalc;

    private final com.crdp.form.history.app.FieldHistoryService fieldHistory;

    private final com.crdp.form.record.app.FormService forms;

    public ImplantService(
            ImplantGateway gateway,
            ImplantMapper mapper,
            ImplantFeatureMapper features,
            ImplantDictPort dicts,
            AuditLogger audit,
            com.crdp.followup.plan.app.DevicePlanMappingService planMappings,
            com.crdp.followup.plan.app.PlanSwitchService planSwitch,
            com.crdp.followup.task.app.StartDateRecalculationService startDateRecalc,
            com.crdp.form.history.app.FieldHistoryService fieldHistory,
            com.crdp.form.record.app.FormService forms) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.features = features;
        this.dicts = dicts;
        this.audit = audit;
        this.planMappings = planMappings;
        this.planSwitch = planSwitch;
        this.startDateRecalc = startDateRecalc;
        this.fieldHistory = fieldHistory;
        this.forms = forms;
    }

    // ---------------------------------------------------------------- DTO

    /** 电极载荷：腔室必填；植入日期缺省同植入记录；状态缺省在用。 */
    public record LeadPayload(
            String id,
            String chamber,
            String position,
            String model,
            LocalDate implantDate,
            String status) {}

    /** 植入记录载荷（新建与编辑共用形态；状态与结束信息由系统维护，不在载荷中）。 */
    public record ImplantPayload(
            String implantType,
            LocalDate implantDate,
            String deviceType,
            String brand,
            String model,
            String serialNo,
            String pacingMode,
            String hospital,
            String doctor,
            String indication,
            String indicationNote,
            String note,
            List<LeadPayload> leads) {}

    /**
     * 编辑请求：baseVersion 乐观锁（AGENTS §5.10）；leads 为 null 表示本次不动电极； reason = 修改原因（必填，C-PAT-011 /
     * UAT-SELF-01：字段级历史行带原因，与修改已提交随访记录同口径）。
     */
    public record UpdateRequest(int baseVersion, ImplantPayload implant, String reason) {}

    /**
     * 电极处置（E21-T02 / C-PAT-006 器械更换流程第 2–3 步）：KEEP 沿用（同一电极行挂到新植入记录下， 保留原植入日期）／ ABANDON 废弃留置 ／
     * EXTRACT 已拔除。
     */
    public static final String ACTION_KEEP = "KEEP";

    public static final String ACTION_ABANDON = "ABANDON";
    public static final String ACTION_EXTRACT = "EXTRACT";

    /** 更换 / 升级请求（E21-T02）：type 即新记录植入类型（REPLACE / UPGRADE / LEAD_REVISION / OTHER）。 */
    public record ReplaceRequest(
            int baseVersion,
            String type,
            String note,
            ImplantPayload implant,
            List<LeadAction> leadActions,
            List<LeadPayload> newLeads) {}

    /** 旧电极处置行：leadId + action（KEEP / ABANDON / EXTRACT）。 */
    public record LeadAction(String leadId, String action) {}

    /** 取出请求（E21-T02）：取出后无在用器械，其在用电极须逐根显式处置（不产生沿用电极）。 */
    public record ExplantRequest(
            int baseVersion, LocalDate date, String reason, List<LeadAction> leadActions) {}

    /** 电极视图（编码 + 显示名一并下发，页面不硬编码文案）。 */
    public record LeadView(
            String id,
            String chamber,
            String chamberLabel,
            String position,
            String positionLabel,
            String model,
            LocalDate implantDate,
            String status,
            String statusLabel,
            int version) {}

    /** 植入记录视图：implantNo 按植入日期升序编号（附录 A 植入 #1）；current = 是否当前器械（在用）。 */
    public record ImplantView(
            String id,
            String patientId,
            int implantNo,
            String implantType,
            String implantTypeLabel,
            LocalDate implantDate,
            String deviceType,
            String deviceTypeLabel,
            String brand,
            String brandLabel,
            String model,
            String serialNo,
            String pacingMode,
            String pacingModeLabel,
            String hospital,
            String doctor,
            String indication,
            String indicationLabel,
            String indicationNote,
            String note,
            String status,
            String statusLabel,
            LocalDate endDate,
            String endReason,
            boolean current,
            int version,
            List<LeadView> leads) {}

    /** GET implants 响应：当前器械置顶，其余按植入日期新在前（pages.md §4.4）。 */
    public record ImplantListView(List<ImplantView> items) {}

    /** 选项（编码 + 显示名）。 */
    public record OptionItem(String code, String label) {}

    /** 电极部位按腔室分组（Q15 按腔室过滤）。 */
    public record LeadPositionGroup(String chamber, List<OptionItem> positions) {}

    /** options 响应：植入类型 / 起搏器类型 / 品牌 / 起搏模式 / 植入指征 / 电极部位（分腔室）/ 电极状态。 */
    public record OptionsView(
            List<OptionItem> implantTypes,
            List<OptionItem> deviceTypes,
            List<OptionItem> brands,
            List<OptionItem> pacingModes,
            List<OptionItem> indications,
            List<LeadPositionGroup> leadPositions,
            List<OptionItem> leadStatuses) {}

    // ---------------------------------------------------------------- 查询

    /** 全部植入记录与电极（当前器械置顶）；开关关闭 40301；越范围 / 不存在 40400。 */
    public ImplantListView list(long patientId) {
        requireFeatureEnabled();
        requirePatientInScope(patientId);
        return listUnchecked(patientId);
    }

    /**
     * 选项清单（新建 / 编辑表单与登记页 C 模式共用）。起搏器类型 / 品牌 / 起搏模式（统一字典 C-FLD-003）/ 植入指征 / 电极部位（按腔室）现读业务字典（平台库 V70
     * 预置 + 本院覆盖，E21-T04）：只下发生效项（本院停用的平台项不进下拉）， 顺序按字典 sortNo；植入类型与状态为系统枚举。
     */
    public OptionsView options() {
        requireFeatureEnabled();
        DictTables t = loadDicts();
        return new OptionsView(
                toItems(ImplantCodes.IMPLANT_TYPES),
                t.deviceTypes().enabledItems(),
                t.brands().enabledItems(),
                t.pacingModes().enabledItems(),
                t.indications().enabledItems(),
                List.of(
                        new LeadPositionGroup(
                                ImplantCodes.CHAMBER_RA,
                                t.leadPositions(ImplantCodes.CHAMBER_RA).enabledItems()),
                        new LeadPositionGroup(
                                ImplantCodes.CHAMBER_RV,
                                t.leadPositions(ImplantCodes.CHAMBER_RV).enabledItems()),
                        new LeadPositionGroup(
                                ImplantCodes.CHAMBER_LV,
                                t.leadPositions(ImplantCodes.CHAMBER_LV).enabledItems())),
                toItems(ImplantCodes.LEAD_STATUSES));
    }

    // ---------------------------------------------------------------- 新建（C-PAT-004、C-PAT-008）

    /** 新建植入记录（首次植入 / 历史建档允许过去日期）。当前在用器械与新旧日期的关系见类注释； 成功写 IMPLANT_CREATE 审计。 */
    @Transactional
    public ImplantView create(long patientId, ImplantPayload payload) {
        requireFeatureEnabled();
        requirePatientInScope(patientId);
        long userId = CurrentSession.requireHospital().userId();
        ImplantRow created = doCreate(patientId, payload, userId, false);
        // 新建后回读整表（序号与置顶口径与列表一致）
        return listUnchecked(patientId).items().stream()
                .filter(item -> item.id().equals(String.valueOf(created.id())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("植入记录保存后不可见：" + created.id()));
    }

    /**
     * 登记即建首条植入记录（C-PAT-001「须同时建立第一条植入记录」，OQ-71①：implant 开关切必填集）。 由 PatientRegistrationService
     * 在同一事务内调用——功能开关与患者可见性已由登记侧校验，这里只落库与审计。
     */
    @Transactional
    public void createForRegistration(long patientId, ImplantPayload payload, long userId) {
        doCreate(patientId, payload, userId, true);
    }

    private ImplantRow doCreate(
            long patientId, ImplantPayload payload, long userId, boolean fromRegistration) {
        DictTables dicts = loadDicts();
        Normalized normalized = normalize(payload, dicts);
        ImplantRow active = gateway.activeImplantOf(patientId);

        ImplantInsert row = new ImplantInsert();
        row.setPatientId(patientId);
        row.setImplantType(normalized.implantType());
        row.setImplantDate(normalized.implantDate());
        row.setDeviceType(normalized.deviceType());
        row.setBrand(normalized.brand());
        row.setModel(normalized.model());
        row.setSerialNo(normalized.serialNo());
        row.setPacingMode(normalized.pacingMode());
        row.setHospital(normalized.hospital());
        row.setDoctor(normalized.doctor());
        row.setIndication(normalized.indication());
        row.setIndicationNote(normalized.indicationNote());
        row.setNote(normalized.note());
        row.setCreatedBy(userId);

        String supersededNote = null;
        if (active == null) {
            row.setStatus(ImplantCodes.STATUS_ACTIVE);
        } else if (!normalized.implantDate().isBefore(active.implantDate())) {
            // 新器械接替在用：旧记录自动结束，结束日期 = 新植入日期（C 规格 §4 业务规则）
            mapper.supersedeActive(
                    active.id(),
                    normalized.implantDate(),
                    "更换 / 升级：新植入记录（" + dicts.deviceTypes().label(normalized.deviceType()) + "）接替在用",
                    userId);
            row.setStatus(ImplantCodes.STATUS_ACTIVE);
            supersededNote = "；原在用器械（" + active.id() + "，" + active.implantDate() + "）自动结束为已更换";
        } else {
            // 补录早于当前在用的历史器械：新记录直接为已更换，结束日期 = 当前在用的植入日期
            row.setStatus(ImplantCodes.STATUS_REPLACED);
            row.setEndDate(active.implantDate());
            row.setEndReason("被后植入器械（" + active.id() + "）更换");
        }

        try {
            mapper.insertImplant(row);
        } catch (DuplicateKeyException race) {
            // 并发新建导致两条在用：唯一约束兜底，按冲突提示刷新重试
            log.warn("植入记录并发冲突：患者 {}", patientId);
            throw new ApiException(409, 40900, "该患者刚被他人保存植入记录，请刷新后重试");
        }
        insertLeads(patientId, row.getId(), normalized.leads(), userId);

        audit.append(
                AuditEntry.operation("followup", "IMPLANT_CREATE")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(patientId))
                        .description(
                                (fromRegistration ? "登记新建首条植入记录：" : "新建植入记录：")
                                        + dicts.deviceTypes().label(normalized.deviceType())
                                        + " · "
                                        + normalized.implantDate()
                                        + " · 电极 "
                                        + normalized.leads().size()
                                        + " 根"
                                        + (supersededNote == null ? "" : supersededNote))
                        .afterJson(
                                writeJson(
                                        Map.of(
                                                "implantId", row.getId(),
                                                "implantType", normalized.implantType(),
                                                "deviceType", normalized.deviceType(),
                                                "implantDate", normalized.implantDate().toString(),
                                                "leadCount", normalized.leads().size())))
                        .result(AuditResult.SUCCESS)
                        .build());
        // E21-T05 / C-FU-003：保存在用（新植 / 接替在用）植入记录后按器械类型映射自动入组，起算日 = 植入日期；
        // 补录早于在用的历史器械（REPLACED）不触发。E21-T06 / C-FU-007：升级 / 更换类型且已有在组入组时先走计划切换
        // （结束原入组 + 终止未完成任务 + 按新类型重新入组，SW-2 / SW-3 / SW-4），否则回落自动入组（SW-1 首次植入等）。
        // 业务性跳过在映射服务内部消化，系统级异常同事务回滚
        if (ImplantCodes.STATUS_ACTIVE.equals(row.getStatus())) {
            if (!planSwitch
                    .onActiveImplantAdded(
                            patientId,
                            row.getId(),
                            normalized.implantType(),
                            normalized.deviceType(),
                            normalized.implantDate())
                    .switched()) {
                planMappings.autoEnrollByDeviceType(
                        patientId, normalized.deviceType(), normalized.implantDate());
            }
        }
        return toRowView(row);
    }

    // ---------------------------------------------------------------- 编辑（C-PAT-011 的操作审计；字段级历史随
    // E21-T04）

    /**
     * 编辑植入记录与电极（pages.md §4.4「操作：…编辑」）。带 baseVersion 乐观锁（冲突 40900）； 状态与结束信息不接受直改；leads 为 null
     * 表示本次不动电极。修改原因必填（40001，C-PAT-011 / UAT-SELF-01：字段级历史行带原因， 与修改已提交随访记录 OQ-14「原因必填」同口径）。 成功写
     * IMPLANT_UPDATE 审计（含前后摘要与原因）。
     */
    @Transactional
    public ImplantView update(long implantId, UpdateRequest request) {
        requireFeatureEnabled();
        ImplantRow existing = gateway.implantInScope(implantId);
        if (existing == null) {
            throw new ApiException(404, 40400, "植入记录不存在或不在您的数据范围内");
        }
        String reason = trimToNull(request.reason(), 255, "修改原因");
        if (reason == null) {
            throw new ApiException(400, 40001, "修改植入记录必须填写修改原因（C-PAT-011）");
        }
        if (request.baseVersion() != existing.version()) {
            throw new ApiException(409, 40900, "该植入记录已被他人修改，请刷新后重试");
        }
        DictTables dicts = loadDicts();
        Normalized normalized = normalize(request.implant(), dicts);
        long userId = CurrentSession.requireHospital().userId();

        List<LeadRow> currentLeads = gateway.leadsOfImplant(implantId);
        boolean leadsProvided = request.implant() != null && request.implant().leads() != null;
        // 器械类型改为无导线时不可遗留电极（本次提交的电极集或既有电极集都为空才放行）
        List<LeadPayload> effectiveLeads =
                leadsProvided ? normalized.leads() : leadPayloadsOf(currentLeads);
        rejectLeadlessWithLeads(normalized.deviceType(), effectiveLeads);

        ImplantUpdate row = new ImplantUpdate();
        row.setId(implantId);
        row.setBaseVersion(request.baseVersion());
        row.setImplantType(normalized.implantType());
        row.setImplantDate(normalized.implantDate());
        row.setDeviceType(normalized.deviceType());
        row.setBrand(normalized.brand());
        row.setModel(normalized.model());
        row.setSerialNo(normalized.serialNo());
        row.setPacingMode(normalized.pacingMode());
        row.setHospital(normalized.hospital());
        row.setDoctor(normalized.doctor());
        row.setIndication(normalized.indication());
        row.setIndicationNote(normalized.indicationNote());
        row.setNote(normalized.note());
        row.setUpdatedBy(userId);
        if (mapper.updateImplant(row) == 0) {
            throw new ApiException(409, 40900, "该植入记录已被他人修改，请刷新后重试");
        }
        if (leadsProvided) {
            replaceLeads(existing.patientId(), implantId, normalized.leads(), currentLeads, userId);
        }
        // E23-T20 / OQ-129②：编辑植入记录的字段级留痕（C-PAT-011）——键 = C_IMPLANT 稳定键（V90 字典随包），
        // 与操作审计（前后摘要）同一事务逐字段追加；日期存 ISO 文本（值编码同 fe_record.data）。
        fieldHistory.logChanges(
                com.crdp.form.history.app.FieldHistoryService.PT_IMPLANT,
                implantId,
                IMPLANT_FORM_CODE,
                forms.publishedVersion(IMPLANT_FORM_CODE),
                implantHistoryValues(
                        existing.implantType(),
                        existing.implantDate(),
                        existing.deviceType(),
                        existing.brand(),
                        existing.model(),
                        existing.serialNo(),
                        existing.pacingMode(),
                        existing.hospital(),
                        existing.doctor(),
                        existing.indication(),
                        existing.indicationNote(),
                        existing.note()),
                implantHistoryValues(
                        normalized.implantType(),
                        normalized.implantDate(),
                        normalized.deviceType(),
                        normalized.brand(),
                        normalized.model(),
                        normalized.serialNo(),
                        normalized.pacingMode(),
                        normalized.hospital(),
                        normalized.doctor(),
                        normalized.indication(),
                        normalized.indicationNote(),
                        normalized.note()),
                com.crdp.form.history.domain.ChangeSource.USER,
                "修改植入记录：" + reason);

        audit.append(
                AuditEntry.operation("followup", "IMPLANT_UPDATE")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(existing.patientId()))
                        .description(
                                "编辑植入记录："
                                        + implantId
                                        + "（"
                                        + dicts.deviceTypes().label(existing.deviceType())
                                        + " → "
                                        + dicts.deviceTypes().label(normalized.deviceType())
                                        + (leadsProvided
                                                ? "，电极 "
                                                        + currentLeads.size()
                                                        + " → "
                                                        + normalized.leads().size()
                                                        + " 根"
                                                : "")
                                        + "）：")
                        .reason(reason)
                        .beforeJson(
                                writeJson(
                                        Map.of(
                                                "implantId", existing.id(),
                                                "deviceType", nullSafe(existing.deviceType()),
                                                "implantDate", existing.implantDate().toString())))
                        .afterJson(
                                writeJson(
                                        Map.of(
                                                "implantId", implantId,
                                                "deviceType", nullSafe(normalized.deviceType()),
                                                "implantDate",
                                                        normalized.implantDate().toString())))
                        .result(AuditResult.SUCCESS)
                        .build());

        // E21-T06 / C-FU-007（SW-6 器械类型更正）与 C 规格 §4「植入日期修改后，未开始的任务按新日期重算（沿用 A-FU-011）」：
        // 只针对在用记录——更正起搏器类型走计划切换（按新类型重新入组、起算仍按本记录植入日期、未开始任务随重新入组重算、已完成记录不变）；
        // 仅改植入日期接入 E14-T07 起算重算入口（类型与日期同时更正时，重新入组已用新日期快照，无需重算）。
        boolean activeRecord = ImplantCodes.STATUS_ACTIVE.equals(existing.status());
        boolean typeCorrected = !equalsNullable(existing.deviceType(), normalized.deviceType());
        boolean dateChanged = !existing.implantDate().equals(normalized.implantDate());
        if (activeRecord && typeCorrected) {
            planSwitch.onDeviceTypeCorrected(
                    existing.patientId(),
                    implantId,
                    existing.deviceType(),
                    normalized.deviceType(),
                    normalized.implantDate());
        } else if (activeRecord && dateChanged) {
            startDateRecalc.recalculateOnBaseDateChange(existing.patientId(), userId);
        }

        return listUnchecked(existing.patientId()).items().stream()
                .filter(item -> item.id().equals(String.valueOf(implantId)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("植入记录保存后不可见：" + implantId));
    }

    /** 开关当前是否开启（登记页 C 模式判定用；无行视为关闭）。 */
    public boolean implantEnabled() {
        return Boolean.TRUE.equals(features.findEnabled(FEATURE_IMPLANT));
    }

    // ---------------------------------------------------------------- 更换 / 升级 / 电极修正（E21-T02 /
    // C-PAT-006）

    /**
     * 更换 / 升级 / 电极修正专用流程（C 规格 §4「器械更换流程」，tasks.csv 验收「每种变更保留历史」）。
     *
     * <p>每次变更新增一条植入记录（type = REPLACE / UPGRADE / LEAD_REVISION / OTHER，FIRST 走登记 / 新建），
     * 旧（目标）记录自动结束为「已更换」、结束日期 = 新植入日期——电极修正同样落一条 LEAD_REVISION 记录（验收用例集 SW-5「新增『电极修正』记录」、
     * C-ACC-06），但脉冲发生器不变：类型 / 品牌 / 型号 / 序列号须与原记录一致；「其他」须填说明（note，V83）。电极三选一逐根显式处置（在用电极缺项 40001）：
     *
     * <ul>
     *   <li>KEEP 沿用：电极行不动（保留原植入日期，验收「沿用的电极趋势连续」），补挂到新植入记录下；
     *   <li>ABANDON 废弃留置 / EXTRACT 已拔除：电极行更新状态、保留在旧记录下（历史不删）；
     *   <li>newLeads 新植：新行落库，植入日期缺省 = 手术日期（电极修正的新电极从修正日期起比较参数）。
     * </ul>
     *
     * <p>baseVersion 乐观锁与「目标必须在用」为 40900；无导线新器械不可携带电极（40001）。按器械类型映射自动入组已随 E21-T05 接入 （电极修正不切计划）；升级
     * / 更换的计划切换（结束原入组、终止未完成任务、按新类型重新入组起算 = 手术日期）已随 E21-T06 接入（C-FU-007 切换表 SW-2 / SW-3 / SW-4 /
     * SW-5）。成功写 IMPLANT_REPLACE 审计。
     */
    @Transactional
    public ImplantView replace(long implantId, ReplaceRequest request) {
        requireFeatureEnabled();
        ImplantRow target = requireActiveImplantInScope(implantId, "更换 / 升级 / 电极修正");
        if (request == null || request.baseVersion() != target.version()) {
            throw new ApiException(409, 40900, "该植入记录已被他人修改，请刷新后重试");
        }
        String type = normalizeReplaceType(request.type());
        DictTables dicts = loadDicts();
        // type 与 note 由流程单独承载，注入载荷后复用统一的字段校验（电极不走 implant.leads）
        ImplantPayload source = request.implant();
        ImplantPayload merged =
                new ImplantPayload(
                        type,
                        source == null ? null : source.implantDate(),
                        source == null ? null : source.deviceType(),
                        source == null ? null : source.brand(),
                        source == null ? null : source.model(),
                        source == null ? null : source.serialNo(),
                        source == null ? null : source.pacingMode(),
                        source == null ? null : source.hospital(),
                        source == null ? null : source.doctor(),
                        source == null ? null : source.indication(),
                        source == null ? null : source.indicationNote(),
                        request.note(),
                        null);
        Normalized normalized = normalize(merged, dicts);
        if (normalized.implantDate().isBefore(target.implantDate())) {
            throw new ApiException(400, 40001, "新植入日期不能早于原植入日期（" + target.implantDate() + "）");
        }
        if (ImplantCodes.TYPE_LEAD_REVISION.equals(type)) {
            requireSameGenerator(target, normalized);
        }

        long userId = CurrentSession.requireHospital().userId();
        List<LeadRow> targetLeads = gateway.leadsOfImplant(implantId);
        Map<Long, LeadAction> actions =
                normalizeLeadActions(request.leadActions(), targetLeads, true);
        List<LeadPayload> newLeads =
                normalizeLeads(
                        request.newLeads(),
                        normalized.deviceType(),
                        normalized.implantDate(),
                        dicts);
        if (ImplantCodes.DEVICE_LEADLESS.equals(normalized.deviceType())) {
            rejectLeadlessWithLeads(
                    normalized.deviceType(), keptOrNewLeads(actions, targetLeads, newLeads));
        }

        // 旧器械自动结束（0 行 = 并发被他人接替，40900）
        String typeLabel = labelOf(ImplantCodes.IMPLANT_TYPES, type);
        String endReason =
                typeLabel
                        + "：新植入记录（"
                        + dicts.deviceTypes().label(normalized.deviceType())
                        + "）接替在用";
        if (mapper.supersedeActive(implantId, normalized.implantDate(), endReason, userId) == 0) {
            throw new ApiException(409, 40900, "该植入记录刚被他人处理，请刷新后重试");
        }

        ImplantInsert row = new ImplantInsert();
        row.setPatientId(target.patientId());
        row.setImplantType(type);
        row.setImplantDate(normalized.implantDate());
        row.setDeviceType(normalized.deviceType());
        row.setBrand(normalized.brand());
        row.setModel(normalized.model());
        row.setSerialNo(normalized.serialNo());
        row.setPacingMode(normalized.pacingMode());
        row.setHospital(normalized.hospital());
        row.setDoctor(normalized.doctor());
        row.setIndication(normalized.indication());
        row.setIndicationNote(normalized.indicationNote());
        row.setNote(normalized.note());
        row.setStatus(ImplantCodes.STATUS_ACTIVE);
        row.setCreatedBy(userId);
        try {
            mapper.insertImplant(row);
        } catch (DuplicateKeyException race) {
            log.warn("更换流程并发冲突：植入记录 {}", implantId);
            throw new ApiException(409, 40900, "该患者刚被他人保存植入记录，请刷新后重试");
        }
        applyLeadActions(target.patientId(), implantId, row.getId(), actions, targetLeads, userId);
        insertLeads(target.patientId(), row.getId(), newLeads, userId);

        audit.append(
                AuditEntry.operation("followup", "IMPLANT_REPLACE")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(target.patientId()))
                        .description(
                                typeLabel
                                        + "：植入记录 "
                                        + implantId
                                        + "（"
                                        + dicts.deviceTypes().label(target.deviceType())
                                        + " · "
                                        + target.implantDate()
                                        + "）→ 新记录（"
                                        + dicts.deviceTypes().label(normalized.deviceType())
                                        + " · "
                                        + normalized.implantDate()
                                        + "）；电极 沿用 "
                                        + countAction(actions, ACTION_KEEP)
                                        + " / 废弃留置 "
                                        + countAction(actions, ACTION_ABANDON)
                                        + " / 已拔除 "
                                        + countAction(actions, ACTION_EXTRACT)
                                        + " / 新植 "
                                        + newLeads.size())
                        .beforeJson(
                                writeJson(
                                        Map.of(
                                                "implantId", implantId,
                                                "deviceType", nullSafe(target.deviceType()),
                                                "implantDate", target.implantDate().toString())))
                        .afterJson(
                                writeJson(
                                        Map.of(
                                                "implantId", row.getId(),
                                                "type", type,
                                                "deviceType", nullSafe(normalized.deviceType()),
                                                "implantDate",
                                                        normalized.implantDate().toString())))
                        .result(AuditResult.SUCCESS)
                        .build());
        // E21-T05 / C-FU-003：更换 / 升级 / 其他产生新在用器械，按新器械类型映射自动入组（电极修正不切计划，
        // C-FU-007 切换表 SW-5）。E21-T06 / C-FU-007：升级 / 更换且已有在组入组时先走计划切换——结束原入组
        // + 终止未完成任务 + 按新类型重新入组（SW-2 / SW-3 / SW-4），否则回落自动入组
        if (!ImplantCodes.TYPE_LEAD_REVISION.equals(type)) {
            if (!planSwitch
                    .onActiveImplantAdded(
                            target.patientId(),
                            row.getId(),
                            type,
                            normalized.deviceType(),
                            normalized.implantDate())
                    .switched()) {
                planMappings.autoEnrollByDeviceType(
                        target.patientId(), normalized.deviceType(), normalized.implantDate());
            }
        }

        return listUnchecked(target.patientId()).items().stream()
                .filter(item -> item.id().equals(String.valueOf(row.getId())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("植入记录保存后不可见：" + row.getId()));
    }

    // ---------------------------------------------------------------- 取出（E21-T02 / C-FU-007 状态面）

    /**
     * 取出（POST /implants/{id}/explant）：在用记录置「已取出」，结束日期 = 取出日期，理由缺省「器械取出」； 在用电极须逐根显式标废弃留置 /
     * 已拔除（不产生沿用电极，行保留在旧记录下）。取出后患者无在用器械，再植入走登记 / 新建。 计划侧（结束全部在组入组、终止未完成任务、不再创建任务）已随 E21-T06
     * 接入（C-FU-007 切换表 SW-9）。成功写 IMPLANT_EXPLANT 审计。
     */
    @Transactional
    public ImplantView explant(long implantId, ExplantRequest request) {
        requireFeatureEnabled();
        ImplantRow target = requireActiveImplantInScope(implantId, "取出");
        if (request == null || request.baseVersion() != target.version()) {
            throw new ApiException(409, 40900, "该植入记录已被他人修改，请刷新后重试");
        }
        LocalDate date = requireDate(request.date(), "请填写取出日期");
        if (date.isBefore(target.implantDate())) {
            throw new ApiException(400, 40001, "取出日期不能早于植入日期（" + target.implantDate() + "）");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new ApiException(400, 40001, "取出日期不能晚于今天");
        }
        long userId = CurrentSession.requireHospital().userId();
        List<LeadRow> targetLeads = gateway.leadsOfImplant(implantId);
        Map<Long, LeadAction> actions =
                normalizeLeadActions(request.leadActions(), targetLeads, false);

        String reason = trimToNull(request.reason(), 255, "取出原因");
        String endReason = reason == null ? "器械取出" : reason;
        if (mapper.explantActive(implantId, date, endReason, userId) == 0) {
            throw new ApiException(409, 40900, "该植入记录刚被他人处理，请刷新后重试");
        }
        applyLeadActions(target.patientId(), implantId, -1L, actions, targetLeads, userId);

        audit.append(
                AuditEntry.operation("followup", "IMPLANT_EXPLANT")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(target.patientId()))
                        .description(
                                "取出植入记录："
                                        + implantId
                                        + "（"
                                        + loadDicts().deviceTypes().label(target.deviceType())
                                        + " · "
                                        + target.implantDate()
                                        + " → 取出 "
                                        + date
                                        + "）；电极 已拔除 "
                                        + countAction(actions, ACTION_EXTRACT)
                                        + " / 废弃留置 "
                                        + countAction(actions, ACTION_ABANDON))
                        .beforeJson(
                                writeJson(
                                        Map.of(
                                                "implantId", implantId,
                                                "status", target.status(),
                                                "implantDate", target.implantDate().toString())))
                        .afterJson(
                                writeJson(
                                        Map.of(
                                                "implantId",
                                                implantId,
                                                "status",
                                                ImplantCodes.STATUS_EXPLANTED,
                                                "endDate",
                                                date.toString())))
                        .result(AuditResult.SUCCESS)
                        .build());

        // E21-T06 / C-FU-007（SW-9）：取出且未再植入——同一事务结束全部在组入组并终止未完成任务，之后不再创建任务
        planSwitch.onImplantExplanted(target.patientId(), implantId, date);

        return listUnchecked(target.patientId()).items().stream()
                .filter(item -> item.id().equals(String.valueOf(implantId)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("植入记录保存后不可见：" + implantId));
    }

    // ---------------------------------------------------------------- 内部：更换流程共用

    /** 范围内且在用的目标记录（越范围 / 不存在 40400；不在用 40900——更换 / 取出只针对当前器械）。 */
    private ImplantRow requireActiveImplantInScope(long implantId, String operation) {
        ImplantRow target = gateway.implantInScope(implantId);
        if (target == null) {
            throw new ApiException(404, 40400, "植入记录不存在或不在您的数据范围内");
        }
        if (!ImplantCodes.STATUS_ACTIVE.equals(target.status())) {
            throw new ApiException(
                    409, 40900, "该植入记录不在用，无法" + operation + "（只有当前器械可以" + operation + "）");
        }
        return target;
    }

    /** 更换流程的植入类型（FIRST 走登记 / 新建；字典外取值拒绝）。 */
    private String normalizeReplaceType(String value) {
        String type = requireCode(value, "请选择植入类型");
        if (ImplantCodes.TYPE_FIRST.equals(type)) {
            throw new ApiException(400, 40001, "首次植入请在患者登记或「新建植入记录」中录入");
        }
        if (!ImplantCodes.IMPLANT_TYPES.containsKey(type)) {
            throw new ApiException(400, 40001, "植入类型不正确：" + value);
        }
        return type;
    }

    /** 电极修正不改变脉冲发生器：类型 / 品牌 / 型号 / 序列号须与原记录一致（起搏模式为程控设置，可变）。 */
    private void requireSameGenerator(ImplantRow target, Normalized normalized) {
        List<String> diffs = new ArrayList<>();
        if (!equalsNullable(target.deviceType(), normalized.deviceType())) diffs.add("起搏器类型");
        if (!equalsNullable(target.brand(), normalized.brand())) diffs.add("品牌");
        if (!equalsNullable(target.model(), normalized.model())) diffs.add("型号");
        if (!equalsNullable(target.serialNo(), normalized.serialNo())) diffs.add("序列号");
        if (!diffs.isEmpty()) {
            throw new ApiException(
                    400, 40001, "电极修正不改变脉冲发生器，" + String.join("、", diffs) + "须与原植入记录一致");
        }
    }

    /**
     * 电极处置行归一化：leadId 必须挂在目标植入记录下（40001）；KEEP 只允许在用电极、且只属于更换流程（取出 时 40001）；目标下每根<b>在用</b>电极必须显式处置
     * 40001（缺项拒绝，避免静默沿用 / 遗弃）。已非在用的电极允许改标废弃留置 / 已拔除（术后补记）。
     */
    private Map<Long, LeadAction> normalizeLeadActions(
            List<LeadAction> actions, List<LeadRow> targetLeads, boolean keepAllowed) {
        Map<Long, LeadRow> targetById = new LinkedHashMap<>();
        for (LeadRow lead : targetLeads) {
            targetById.put(lead.id(), lead);
        }
        Map<Long, LeadAction> byLeadId = new LinkedHashMap<>();
        if (actions != null) {
            for (LeadAction action : actions) {
                Long leadId = parseId(action == null ? null : action.leadId());
                LeadRow lead = targetById.get(leadId);
                if (lead == null) {
                    throw new ApiException(400, 40001, "电极不属于该植入记录：" + action.leadId());
                }
                String code = action.action() == null ? "" : action.action().trim();
                if (!ACTION_KEEP.equals(code)
                        && !ACTION_ABANDON.equals(code)
                        && !ACTION_EXTRACT.equals(code)) {
                    throw new ApiException(400, 40001, "电极处置只支持沿用 / 废弃留置 / 已拔除：" + action.action());
                }
                if (ACTION_KEEP.equals(code)) {
                    if (!keepAllowed) {
                        throw new ApiException(400, 40001, "取出不产生沿用电极，请改选废弃留置或已拔除");
                    }
                    if (!ImplantCodes.LEAD_STATUS_ACTIVE.equals(lead.status())) {
                        throw new ApiException(400, 40001, "非在用电极不可沿用：" + action.leadId());
                    }
                }
                if (byLeadId.put(leadId, new LeadAction(String.valueOf(leadId), code)) != null) {
                    throw new ApiException(400, 40001, "电极处置重复：" + action.leadId());
                }
            }
        }
        for (LeadRow lead : targetLeads) {
            if (ImplantCodes.LEAD_STATUS_ACTIVE.equals(lead.status())
                    && !byLeadId.containsKey(lead.id())) {
                throw new ApiException(400, 40001, "请为每根在用电极选择处置（沿用 / 废弃留置 / 已拔除）：" + lead.id());
            }
        }
        return byLeadId;
    }

    /** 落电极处置：KEEP 补挂新植入记录（行不动，保留原植入日期）；ABANDON / EXTRACT 更新状态、留旧记录下。 */
    private void applyLeadActions(
            long patientId,
            long oldImplantId,
            long newImplantId,
            Map<Long, LeadAction> actions,
            List<LeadRow> targetLeads,
            long userId) {
        for (LeadRow lead : targetLeads) {
            LeadAction action = actions.get(lead.id());
            if (action == null) {
                continue;
            }
            if (ACTION_KEEP.equals(action.action())) {
                mapper.linkLead(patientId, newImplantId, lead.id());
                continue;
            }
            LeadUpdate update = new LeadUpdate();
            update.setId(lead.id());
            update.setPatientId(patientId);
            update.setBaseVersion(lead.version());
            update.setChamber(lead.chamber());
            update.setPosition(lead.position());
            update.setModel(lead.model());
            update.setImplantDate(lead.implantDate());
            update.setStatus(
                    ACTION_ABANDON.equals(action.action())
                            ? ImplantCodes.LEAD_STATUS_ABANDONED
                            : ImplantCodes.LEAD_STATUS_EXTRACTED);
            update.setUpdatedBy(userId);
            if (mapper.updateLead(update) == 0) {
                throw new ApiException(409, 40900, "该电极刚被他人修改，请刷新后重试");
            }
        }
    }

    /** 无导线校验的电极面：沿用电极 + 新植电极（KEEP 的行 + newLeads 载荷）。 */
    private static List<LeadPayload> keptOrNewLeads(
            Map<Long, LeadAction> actions, List<LeadRow> targetLeads, List<LeadPayload> newLeads) {
        List<LeadPayload> kept = new ArrayList<>();
        for (LeadRow lead : targetLeads) {
            LeadAction action = actions.get(lead.id());
            if (action != null && ACTION_KEEP.equals(action.action())) {
                kept.add(
                        new LeadPayload(
                                String.valueOf(lead.id()),
                                lead.chamber(),
                                lead.position(),
                                lead.model(),
                                lead.implantDate(),
                                lead.status()));
            }
        }
        List<LeadPayload> all = new ArrayList<>(kept);
        all.addAll(newLeads);
        return all;
    }

    private static int countAction(Map<Long, LeadAction> actions, String action) {
        return (int) actions.values().stream().filter(item -> item.action().equals(action)).count();
    }

    private static boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    // ---------------------------------------------------------------- 内部：电极

    private void insertLeads(long patientId, long implantId, List<LeadPayload> leads, long userId) {
        for (LeadPayload lead : leads) {
            LeadInsert insert = new LeadInsert();
            insert.setPatientId(patientId);
            insert.setChamber(lead.chamber());
            insert.setPosition(lead.position());
            insert.setModel(lead.model());
            insert.setImplantDate(lead.implantDate());
            insert.setStatus(lead.status());
            insert.setCreatedBy(userId);
            mapper.insertLead(insert);
            mapper.linkLead(patientId, implantId, insert.getId());
        }
    }

    /**
     * 编辑时替换电极集：无 id 的行为新植（插入 + 关联）；带 id 的行更新；原有关联中不在新集的 解除关联，电极行不再被任何植入记录引用时删除（录入错误的修正， 临床沿用 /
     * 废弃语义随 E21-T02 电极修正）。
     */
    private void replaceLeads(
            long patientId,
            long implantId,
            List<LeadPayload> leads,
            List<LeadRow> currentLeads,
            long userId) {
        Map<Long, LeadRow> currentById = new LinkedHashMap<>();
        for (LeadRow current : currentLeads) {
            currentById.put(current.id(), current);
        }
        // 新集里带 id 的行：更新（乐观锁基线 = 编辑开始时读到的版本，同事务内为最新值）
        List<LeadPayload> toInsert = new ArrayList<>();
        for (LeadPayload lead : leads) {
            Long leadId = parseId(lead.id());
            if (leadId == null) {
                toInsert.add(lead);
                continue;
            }
            LeadRow current = currentById.remove(leadId);
            if (current == null || current.patientId() != patientId) {
                throw new ApiException(400, 40001, "电极不属于该患者的植入记录：" + lead.id());
            }
            LeadUpdate update = new LeadUpdate();
            update.setId(leadId);
            update.setPatientId(patientId);
            update.setBaseVersion(current.version());
            update.setChamber(lead.chamber());
            update.setPosition(lead.position());
            update.setModel(lead.model());
            update.setImplantDate(lead.implantDate());
            update.setStatus(lead.status());
            update.setUpdatedBy(userId);
            if (mapper.updateLead(update) == 0) {
                throw new ApiException(409, 40900, "该电极刚被他人修改，请刷新后重试");
            }
        }
        insertLeads(patientId, implantId, toInsert, userId);
        // 移除的行：解除关联；电极行无其余引用时删除
        for (LeadRow removed : currentById.values()) {
            mapper.unlinkLead(implantId, removed.id());
            if (mapper.linkCountOfLead(removed.id()) == 0) {
                mapper.deleteUnlinkedLead(removed.id(), patientId);
            }
        }
    }

    private Map<Long, List<LeadRow>> leadsGroupedByImplant(List<ImplantRow> rows) {
        Map<Long, List<LeadRow>> grouped = new HashMap<>();
        if (rows.isEmpty()) {
            return grouped;
        }
        long patientId = rows.get(0).patientId();
        Map<Long, LeadRow> leadById = new HashMap<>();
        for (LeadRow lead : gateway.leadsOf(patientId)) {
            leadById.put(lead.id(), lead);
        }
        for (ImplantMapper.ImplantLinkRow link : mapper.findLinksOfPatient(patientId)) {
            LeadRow lead = leadById.get(link.leadId());
            if (lead != null) {
                grouped.computeIfAbsent(link.implantId(), key -> new ArrayList<>()).add(lead);
            }
        }
        return grouped;
    }

    // ---------------------------------------------------------------- 内部：校验与视图

    private void requireFeatureEnabled() {
        if (!implantEnabled()) {
            throw new ApiException(403, 40301, "植入记录功能未开通（implant），请联系平台配置");
        }
    }

    private void requirePatientInScope(long patientId) {
        if (gateway.patientIdInScope(patientId) == null) {
            throw new ApiException(404, 40400, "患者不存在或不在您的数据范围内");
        }
    }

    private ImplantListView listUnchecked(long patientId) {
        List<ImplantRow> rows = gateway.implantsOf(patientId);
        if (rows.isEmpty()) {
            return new ImplantListView(List.of());
        }
        Map<Long, List<LeadRow>> leadsByImplant = leadsGroupedByImplant(rows);
        DictTables dicts = loadDicts();

        // 植入序号：按植入日期升序 1..N（附录 A 植入 #1「第 1 次、第 2 次……」）
        List<ImplantRow> byDateAsc = new ArrayList<>(rows);
        byDateAsc.sort(Comparator.comparing(ImplantRow::implantDate).thenComparing(ImplantRow::id));
        Map<Long, Integer> implantNoById = new HashMap<>();
        for (int i = 0; i < byDateAsc.size(); i++) {
            implantNoById.put(byDateAsc.get(i).id(), i + 1);
        }

        // 当前器械置顶（在用在前），其余按日期新在前（gateway 已按日期倒序返回）
        List<ImplantView> items = new ArrayList<>();
        for (ImplantRow row : rows.stream().sorted(ImplantService::currentFirst).toList()) {
            items.add(
                    toView(
                            row,
                            implantNoById.get(row.id()),
                            leadsByImplant.getOrDefault(row.id(), List.of()),
                            dicts));
        }
        return new ImplantListView(List.copyOf(items));
    }

    private static int currentFirst(ImplantRow left, ImplantRow right) {
        boolean leftActive = ImplantCodes.STATUS_ACTIVE.equals(left.status());
        boolean rightActive = ImplantCodes.STATUS_ACTIVE.equals(right.status());
        if (leftActive != rightActive) {
            return leftActive ? -1 : 1;
        }
        return 0;
    }

    private ImplantView toView(
            ImplantRow row, Integer implantNo, List<LeadRow> leads, DictTables dicts) {
        List<LeadView> leadViews =
                leads.stream()
                        .map(
                                lead ->
                                        new LeadView(
                                                String.valueOf(lead.id()),
                                                lead.chamber(),
                                                chamberLabel(lead.chamber()),
                                                lead.position(),
                                                dicts.leadPositions(lead.chamber())
                                                        .label(lead.position()),
                                                lead.model(),
                                                lead.implantDate(),
                                                lead.status(),
                                                labelOf(ImplantCodes.LEAD_STATUSES, lead.status()),
                                                lead.version()))
                        .toList();
        return new ImplantView(
                String.valueOf(row.id()),
                String.valueOf(row.patientId()),
                implantNo == null ? 0 : implantNo,
                row.implantType(),
                labelOf(ImplantCodes.IMPLANT_TYPES, row.implantType()),
                row.implantDate(),
                row.deviceType(),
                dicts.deviceTypes().label(row.deviceType()),
                row.brand(),
                dicts.brands().label(row.brand()),
                row.model(),
                row.serialNo(),
                row.pacingMode(),
                dicts.pacingModes().label(row.pacingMode()),
                row.hospital(),
                row.doctor(),
                row.indication(),
                dicts.indications().label(row.indication()),
                row.indicationNote(),
                row.note(),
                row.status(),
                labelOf(ImplantCodes.IMPLANT_STATUSES, row.status()),
                row.endDate(),
                row.endReason(),
                ImplantCodes.STATUS_ACTIVE.equals(row.status()),
                row.version(),
                leadViews);
    }

    /** 归一化后的植入记录字段（新建、编辑与更换流程共用校验）。 */
    private record Normalized(
            String implantType,
            LocalDate implantDate,
            String deviceType,
            String brand,
            String model,
            String serialNo,
            String pacingMode,
            String hospital,
            String doctor,
            String indication,
            String indicationNote,
            String note,
            List<LeadPayload> leads) {}

    private Normalized normalize(ImplantPayload payload, DictTables dicts) {
        if (payload == null) {
            throw new ApiException(400, 40001, "请填写植入记录信息");
        }
        String implantType = normalizeImplantType(payload.implantType());
        return normalizeDevicePart(payload, implantType, dicts);
    }

    /** 归一化除植入类型与电极外的字段（更换流程的 type 单独承载、电极经 leadActions / newLeads 传递， 复用同一套字段校验）。 */
    private Normalized normalizeDevicePart(
            ImplantPayload payload, String implantType, DictTables dicts) {
        LocalDate implantDate = requireDate(payload.implantDate(), "请填写植入日期");
        if (implantDate.isAfter(LocalDate.now())) {
            throw new ApiException(400, 40001, "植入日期不能晚于今天");
        }
        String deviceType = normalizeDeviceType(payload.deviceType(), dicts.deviceTypes());
        String brand = normalizeOptionalCode(dicts.brands(), payload.brand(), "品牌");
        String pacingMode =
                normalizeOptionalCode(dicts.pacingModes(), payload.pacingMode(), "起搏模式");
        String indication =
                normalizeOptionalCode(dicts.indications(), payload.indication(), "植入指征");
        String indicationNote = trimToNull(payload.indicationNote(), 255, "植入指征补充说明");
        if (ImplantCodes.INDICATION_OTHER.equals(indication)
                && (indicationNote == null || indicationNote.isBlank())) {
            throw new ApiException(400, 40001, "植入指征选「其他」时须填写补充说明");
        }
        String note = trimToNull(payload.note(), 255, "植入说明");
        if (ImplantCodes.TYPE_OTHER.equals(implantType) && (note == null || note.isBlank())) {
            throw new ApiException(400, 40001, "植入类型选「其他」时须填写说明（C-PAT-006）");
        }
        List<LeadPayload> leads = normalizeLeads(payload.leads(), deviceType, implantDate, dicts);
        return new Normalized(
                implantType,
                implantDate,
                deviceType,
                brand,
                trimToNull(payload.model(), 128, "型号"),
                trimToNull(payload.serialNo(), 64, "设备序列号"),
                pacingMode,
                trimToNull(payload.hospital(), 128, "植入医院"),
                trimToNull(payload.doctor(), 64, "植入医生"),
                indication,
                indicationNote,
                note,
                leads);
    }

    private List<LeadPayload> normalizeLeads(
            List<LeadPayload> leads, String deviceType, LocalDate implantDate, DictTables dicts) {
        if (leads == null || leads.isEmpty()) {
            return List.of();
        }
        if (ImplantCodes.DEVICE_LEADLESS.equals(deviceType)) {
            throw new ApiException(400, 40001, "无导线起搏器不录电极（C-PAT-005）");
        }
        List<LeadPayload> normalized = new ArrayList<>();
        for (LeadPayload lead : leads) {
            String chamber = normalizeChamber(lead.chamber());
            String position = normalizePosition(chamber, lead.position(), dicts);
            String status = normalizeLeadStatus(lead.status());
            normalized.add(
                    new LeadPayload(
                            trimToNull(lead.id(), 20, "电极 id"),
                            chamber,
                            position,
                            trimToNull(lead.model(), 128, "电极型号"),
                            lead.implantDate() == null ? implantDate : lead.implantDate(),
                            status));
        }
        return List.copyOf(normalized);
    }

    private void rejectLeadlessWithLeads(String deviceType, List<LeadPayload> leads) {
        if (ImplantCodes.DEVICE_LEADLESS.equals(deviceType) && !leads.isEmpty()) {
            throw new ApiException(400, 40001, "无导线起搏器不录电极（C-PAT-005）");
        }
    }

    /** 既有电极行 → 载荷形态（无导线校验用）。 */
    private static List<LeadPayload> leadPayloadsOf(List<LeadRow> leads) {
        return leads.stream()
                .map(
                        lead ->
                                new LeadPayload(
                                        String.valueOf(lead.id()),
                                        lead.chamber(),
                                        lead.position(),
                                        lead.model(),
                                        lead.implantDate(),
                                        lead.status()))
                .toList();
    }

    private String normalizeImplantType(String value) {
        String normalized = requireCode(value, "请选择植入类型");
        if (!ImplantCodes.IMPLANT_TYPES.containsKey(normalized)) {
            throw new ApiException(400, 40001, "植入类型不正确：" + value);
        }
        return normalized;
    }

    private String normalizeDeviceType(String value, DictTable deviceTypes) {
        String normalized = requireCode(value, "请选择起搏器类型");
        if (!deviceTypes.contains(normalized)) {
            throw new ApiException(400, 40001, "起搏器类型不正确：" + value);
        }
        return normalized;
    }

    private String normalizeChamber(String value) {
        String normalized = requireCode(value, "请选择电极腔室");
        if (!ImplantCodes.LEAD_POSITION_DICTS.containsKey(normalized)) {
            throw new ApiException(400, 40001, "电极腔室只支持 RA / RV / LV");
        }
        return normalized;
    }

    private String normalizePosition(String chamber, String value, DictTables dicts) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (!dicts.leadPositions(chamber).contains(normalized)) {
            throw new ApiException(400, 40001, chamber + " 电极植入部位不正确：" + value);
        }
        return normalized;
    }

    private String normalizeLeadStatus(String value) {
        if (value == null || value.isBlank()) {
            return ImplantCodes.LEAD_STATUS_ACTIVE;
        }
        String normalized = value.trim();
        if (!ImplantCodes.LEAD_STATUSES.containsKey(normalized)) {
            throw new ApiException(400, 40001, "电极状态只支持在用 / 废弃留置 / 已拔除");
        }
        return normalized;
    }

    private String normalizeOptionalCode(DictTable table, String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (!table.contains(normalized)) {
            throw new ApiException(400, 40001, field + "取值不正确：" + value);
        }
        return normalized;
    }

    private static String requireCode(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, 40001, message);
        }
        return value.trim();
    }

    private static LocalDate requireDate(LocalDate value, String message) {
        if (value == null) {
            throw new ApiException(400, 40001, message);
        }
        return value;
    }

    private static String trimToNull(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new ApiException(400, 40001, field + "过长（最多 " + maxLength + " 个字符）");
        }
        return trimmed;
    }

    private static Long parseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException failure) {
            throw new ApiException(400, 40001, "电极 id 不正确：" + value);
        }
    }

    private static String labelOf(Map<String, String> table, String code) {
        return code == null ? null : table.get(code);
    }

    private static String chamberLabel(String chamber) {
        return switch (chamber == null ? "" : chamber) {
            case ImplantCodes.CHAMBER_RA -> "右心房（RA）";
            case ImplantCodes.CHAMBER_RV -> "右心室（RV）";
            case ImplantCodes.CHAMBER_LV -> "左心室（LV）";
            default -> chamber;
        };
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static List<OptionItem> toItems(Map<String, String> table) {
        return ImplantCodes.sorted(table).stream()
                .map(entry -> new OptionItem(entry.getKey(), entry.getValue()))
                .toList();
    }

    // ---------------------------------------------------------------- 业务字典（E21-T04 /
    // C-FLD-002、C-FLD-003）

    /** 一次用例调用所需的字典快照（平台预置 + 本院覆盖的合并结果；平台缓存 60 秒，量级小）。 */
    private record DictTables(
            DictTable deviceTypes,
            DictTable brands,
            DictTable pacingModes,
            DictTable indications,
            Map<String, DictTable> leadPositionsByChamber) {

        DictTable leadPositions(String chamber) {
            // 未知腔室（理论不可达，normalizeChamber 已拦）返回空表，显示名退化为编码本身
            return leadPositionsByChamber.getOrDefault(chamber, DictTable.EMPTY);
        }
    }

    /** 单个字典：含停用项（历史值照常显示与保存，C-FLD-009），生效项才进 options 下拉。 */
    private static final class DictTable {

        private static final DictTable EMPTY = new DictTable(List.of());

        private final List<ImplantDictPort.DictItem> items;

        private DictTable(List<ImplantDictPort.DictItem> items) {
            this.items = items;
        }

        static DictTable of(List<ImplantDictPort.DictItem> items) {
            return new DictTable(List.copyOf(items));
        }

        /** 编码是否为字典已知项（含本院停用的平台项——停用项的历史值可原样保存，C-FLD-009）。 */
        boolean contains(String itemCode) {
            return items.stream().anyMatch(item -> item.itemCode().equals(itemCode));
        }

        /** 显示名（含停用项；未知编码返回 null，与 E21-T01 静态表同口径）。 */
        String label(String itemCode) {
            return itemCode == null
                    ? null
                    : items.stream()
                            .filter(item -> item.itemCode().equals(itemCode))
                            .map(ImplantDictPort.DictItem::label)
                            .findFirst()
                            .orElse(null);
        }

        /** 生效项 → 选项清单（停用项不进下拉；顺序按字典 sortNo、itemCode）。 */
        List<OptionItem> enabledItems() {
            return items.stream()
                    .filter(ImplantDictPort.DictItem::enabled)
                    .map(item -> new OptionItem(item.itemCode(), item.label()))
                    .toList();
        }
    }

    /** 读全部植入相关字典（顺序由端口保证：sortNo、itemCode 升序）。 */
    private DictTables loadDicts() {
        Map<String, DictTable> leadPositions = new HashMap<>();
        for (Map.Entry<String, String> chamberDict : ImplantCodes.LEAD_POSITION_DICTS.entrySet()) {
            leadPositions.put(
                    chamberDict.getKey(), DictTable.of(dicts.items(chamberDict.getValue())));
        }
        return new DictTables(
                DictTable.of(dicts.items(ImplantDictPort.DICT_DEVICE_TYPE)),
                DictTable.of(dicts.items(ImplantDictPort.DICT_BRAND)),
                DictTable.of(dicts.items(ImplantDictPort.DICT_PACING_MODE)),
                DictTable.of(dicts.items(ImplantDictPort.DICT_INDICATION)),
                Map.copyOf(leadPositions));
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            return null;
        }
    }

    /** 插入后即时视图行（doCreate 返回；列表口径以 listUnchecked 回读为准）。 */
    private ImplantRow toRowView(ImplantInsert row) {
        return new ImplantRow(
                row.getId(),
                row.getPatientId(),
                row.getImplantType(),
                row.getImplantDate(),
                row.getDeviceType(),
                row.getBrand(),
                row.getModel(),
                row.getSerialNo(),
                row.getPacingMode(),
                row.getHospital(),
                row.getDoctor(),
                row.getIndication(),
                row.getIndicationNote(),
                row.getNote(),
                row.getStatus(),
                row.getEndDate(),
                row.getEndReason(),
                0,
                row.getCreatedBy(),
                null);
    }
}
