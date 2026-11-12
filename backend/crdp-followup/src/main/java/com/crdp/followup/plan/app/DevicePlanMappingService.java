package com.crdp.followup.plan.app;

import com.crdp.followup.implant.app.ImplantDictPort;
import com.crdp.followup.implant.infra.mapper.ImplantFeatureMapper;
import com.crdp.followup.plan.infra.mapper.DevicePlanMappingMapper;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 器械类型 → 计划映射与按器械类型自动入组（E21-T05 / C-FU-003；表 followup V86，接口 docs/api/implant.md §2、§3）。
 *
 * <p>C-FU-003「保存植入记录后按『起搏器类型 → 计划』映射自动入组，起算日为植入日期；映射可配置，也可手工改选计划」：
 *
 * <ul>
 *   <li><b>映射配置</b>（GET / PUT {@code /plans/device-mapping}，权限 {@code followup:plan:edit}，OQ-13）：
 *       整体替换保存；deviceType 校验业务字典 pacemaker_type（含停用项，与 ImplantService 字段校验同口径，C-FLD-009），planCode
 *       须为已存在的计划族（fu_plan.code）；
 *   <li><b>自动入组</b>（{@link #autoEnrollByDeviceType}，由 ImplantService 在保存植入记录的同一事务内调用）： 按映射解析该族当前
 *       PUBLISHED 版本（新版本发布时同族旧版本已自动停用，OQ-76①）入组，起算日期快照 = 植入日期（enroll 4 参重载）； 入组即建补录 / 当期任务由
 *       PlanEnrollmentService.enroll 承担（E14-T05：历史建档只为今天之后开始的窗口建任务，C-PAT-008 / Q14）；
 *   <li><b>业务性跳过不报错</b>（与 autoEnrollOnRegistration 逐条容错同向）：未映射 / 映射停用、该族无已发布版本、患者已在同族在组、
 *       患者已死亡或终止——保存植入记录不受影响；系统级异常向上传播（同事务回滚，不留「在组无任务」半状态，E14-T05 口径）；
 *   <li><b>他族在组时跳过</b>：患者已有其他计划族在组（升级 / 更换场景）不自动并入新族计划——原入组结束、未完成任务终止等切换规则随 E21-T06（C-FU-007
 *       切换触发事件表），本任务不产生双计划并行任务；
 *   <li><b>手工改选计划</b>：不经映射直接走既有入组入口（批量入组单条，E13-T07），起算快照为空由任务创建侧按 IMPLANT_DATE 解析在用器械植入日期（
 *       FollowupTaskMapper.resolveStartDate 的 IMPLANT_DATE 分支，本任务补齐）。
 * </ul>
 *
 * <p>仅医院 C：接口入口校验功能开关 {@code implant}（关闭 40301，无行视为关闭）；A / B 租户不保存植入记录、不触达本服务。 配置改动写
 * PLAN_MAPPING_SAVE 操作审计（前后快照，审计只追加）。
 */
@Service
public class DevicePlanMappingService {

    private static final Logger log = LoggerFactory.getLogger(DevicePlanMappingService.class);

    /** 功能开关编码（cfg_feature，与 ImplantService 共用，种子 V100 默认关闭）。 */
    static final String FEATURE_IMPLANT = "implant";

    private final DevicePlanMappingMapper mappingMapper;

    private final PlanMapper planMapper;

    private final PlanEnrollmentMapper enrollmentMapper;

    private final PlanEnrollmentService enrollments;

    private final ImplantDictPort dicts;

    private final ImplantFeatureMapper features;

    private final AuditLogger audit;

    public DevicePlanMappingService(
            DevicePlanMappingMapper mappingMapper,
            PlanMapper planMapper,
            PlanEnrollmentMapper enrollmentMapper,
            PlanEnrollmentService enrollments,
            ImplantDictPort dicts,
            ImplantFeatureMapper features,
            AuditLogger audit) {
        this.mappingMapper = mappingMapper;
        this.planMapper = planMapper;
        this.enrollmentMapper = enrollmentMapper;
        this.enrollments = enrollments;
        this.dicts = dicts;
        this.features = features;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 映射行（编码 + 显示名一并下发，页面不硬编码文案；planCode / planName 为 null = 该类型未映射）。 */
    public record MappingItem(
            String deviceType,
            String deviceTypeLabel,
            String planCode,
            String planName,
            boolean enabled) {}

    /** GET / PUT device-mapping 响应。 */
    public record MappingView(List<MappingItem> items) {}

    /** PUT device-mapping 请求行（planCode 为 null / 空白 = 删除该类型映射）。 */
    public record MappingInput(String deviceType, String planCode, boolean enabled) {}

    /** 自动入组结果（业务性跳过原因供日志 / 联调用，不作为错误）。 */
    public enum AutoEnrollOutcome {
        ENROLLED,
        NOT_MAPPED,
        PLAN_NOT_PUBLISHED,
        ALREADY_IN_PLAN,
        OTHER_PLAN_ACTIVE,
        PATIENT_NOT_ENROLLABLE
    }

    // ---------------------------------------------------------------- 映射配置（implant.md §2）

    /** 全部映射（含未映射的字典类型，按字典 sortNo 排序）：配置页「器械→计划映射」数据源； 开关关闭 40301。 */
    public MappingView mapping() {
        requireImplantEnabled();
        List<ImplantDictPort.DictItem> deviceTypes = dicts.items(ImplantDictPort.DICT_DEVICE_TYPE);
        // 库里的映射行先落（字典项被平台整体停用时仍可见，历史配置不消失）
        Map<String, MappingItem> merged = new LinkedHashMap<>();
        for (DevicePlanMappingMapper.MappingRow row : mappingMapper.findAll()) {
            merged.put(
                    row.deviceType(),
                    new MappingItem(
                            row.deviceType(),
                            dictLabel(deviceTypes, row.deviceType()),
                            row.planCode(),
                            familyName(row.planCode()),
                            row.enabled()));
        }
        for (ImplantDictPort.DictItem item : deviceTypes) {
            merged.putIfAbsent(
                    item.itemCode(),
                    new MappingItem(item.itemCode(), item.label(), null, null, false));
        }
        return new MappingView(List.copyOf(merged.values()));
    }

    /**
     * 保存映射（整体替换，V86 注释）：deviceType 字典外 40001、重复 40001；planCode 非空时须为已存在的计划族（40001）； 空白 planCode
     * 视为删除该类型映射。写 PLAN_MAPPING_SAVE 审计（前后快照）。
     */
    @Transactional
    public MappingView save(List<MappingInput> rows) {
        requireImplantEnabled();
        List<MappingInput> normalized = normalizeInputs(rows);
        List<DevicePlanMappingMapper.MappingRow> before = mappingMapper.findAll();
        Long userId = CurrentSession.current().map(principal -> principal.userId()).orElse(null);
        mappingMapper.deleteAll();
        for (MappingInput row : normalized) {
            mappingMapper.insert(row.deviceType(), row.planCode(), row.enabled(), userId);
        }
        MappingView after = mapping();
        audit.append(
                AuditEntry.operation("followup", "PLAN_MAPPING_SAVE")
                        .objectType("PLAN_MAPPING")
                        .objectId("device-mapping")
                        .description(
                                "保存器械→计划映射（C-FU-003）："
                                        + before.size()
                                        + " 条 → "
                                        + after.items().stream()
                                                .filter(item -> item.planCode() != null)
                                                .count()
                                        + " 条（整体替换）")
                        .beforeJson(snapshot(before))
                        .afterJson(snapshot(after))
                        .result(AuditResult.SUCCESS)
                        .build());
        return after;
    }

    // ---------------------------------------------------------------- 自动入组（implant.md §3 /
    // C-FU-003）

    /**
     * 按器械类型自动入组（ImplantService 保存植入记录后同一事务内调用，不独立开门）： 起算日 = 植入日期；映射缺失 / 停用、无已发布版本、同族已在组、 他族在组（切换随
     * E21-T06）、患者不可入组为业务性跳过（返回原因、不抛错）； 系统级异常向上传播（同事务回滚，不留半状态）。
     */
    public AutoEnrollOutcome autoEnrollByDeviceType(
            long patientId, String deviceType, LocalDate implantDate) {
        DevicePlanMappingMapper.MappingRow mapping = mappingMapper.findByDeviceType(deviceType);
        if (mapping == null || !mapping.enabled()) {
            log.debug("按器械类型自动入组跳过：类型 {} 未映射或已停用（C-FU-003）patient={}", deviceType, patientId);
            return AutoEnrollOutcome.NOT_MAPPED;
        }
        PlanMapper.PlanRow plan = planMapper.findPublishedByCode(mapping.planCode());
        if (plan == null) {
            log.debug(
                    "按器械类型自动入组跳过：计划族 {} 无已发布版本（C-FU-003）patient={}", mapping.planCode(), patientId);
            return AutoEnrollOutcome.PLAN_NOT_PUBLISHED;
        }
        List<String> activePlanCodes = enrollmentMapper.findActivePlanCodes(patientId);
        if (activePlanCodes.contains(plan.code())) {
            log.debug("按器械类型自动入组跳过：患者已在该计划族在组 patient={} plan={}", patientId, plan.code());
            return AutoEnrollOutcome.ALREADY_IN_PLAN;
        }
        if (!activePlanCodes.isEmpty()) {
            // 升级 / 更换等切换场景：原入组结束与任务终止随 E21-T06（C-FU-007），此处不双计划并行
            log.info(
                    "按器械类型自动入组跳过：患者在其他计划族 {} 在组，计划切换随 E21-T06（C-FU-007）patient={} 新族 {}",
                    activePlanCodes,
                    patientId,
                    plan.code());
            return AutoEnrollOutcome.OTHER_PLAN_ACTIVE;
        }
        try {
            enrollments.enroll(
                    patientId, plan.id(), PlanEnrollmentService.MODE_NORMAL, implantDate);
        } catch (ApiException skip) {
            // 患者已死亡 / 已终止、并发已在组等业务性不可入组：跳过不中断植入保存（与 autoEnrollOnRegistration 同向）
            log.debug(
                    "按器械类型自动入组跳过：{}（patient={} plan={}）",
                    skip.getMessage(),
                    patientId,
                    plan.code());
            return AutoEnrollOutcome.PATIENT_NOT_ENROLLABLE;
        }
        log.info(
                "按器械类型自动入组完成（C-FU-003）：patient={} {} → 计划 {} v{}，起算日 {}",
                patientId,
                deviceType,
                plan.code(),
                plan.version(),
                implantDate);
        return AutoEnrollOutcome.ENROLLED;
    }

    // ---------------------------------------------------------------- 内部

    private void requireImplantEnabled() {
        if (!Boolean.TRUE.equals(features.findEnabled(FEATURE_IMPLANT))) {
            throw new ApiException(403, 40301, "植入记录功能未开通（implant），映射不可用");
        }
    }

    /** 保存载荷归一化与校验：字典外类型 / 重复类型 / 不存在的计划族 40001；空白 planCode = 删除映射。 */
    private List<MappingInput> normalizeInputs(List<MappingInput> rows) {
        if (rows == null) {
            rows = List.of();
        }
        if (rows.size() > 64) {
            throw new ApiException(400, 40001, "映射条数过多（最多 64 条）");
        }
        Set<String> known =
                new LinkedHashSet<>(
                        dicts.items(ImplantDictPort.DICT_DEVICE_TYPE).stream()
                                .map(ImplantDictPort.DictItem::itemCode)
                                .toList());
        Set<String> seen = new LinkedHashSet<>();
        List<MappingInput> normalized = new ArrayList<>();
        for (MappingInput row : rows) {
            String deviceType = requireCode(row == null ? null : row.deviceType(), "请选择起搏器类型");
            if (!known.contains(deviceType)) {
                throw new ApiException(400, 40001, "起搏器类型不正确：" + deviceType);
            }
            if (!seen.add(deviceType)) {
                throw new ApiException(400, 40001, "起搏器类型重复：" + deviceType);
            }
            String planCode = trimToNull(row.planCode(), "计划编码");
            if (planCode != null && planMapper.findMaxVersionByCode(planCode) == 0) {
                throw new ApiException(400, 40001, "计划族不存在：" + planCode);
            }
            if (planCode != null) {
                normalized.add(new MappingInput(deviceType, planCode, row.enabled()));
            }
            // planCode 为空 = 该类型不映射（不落行）
        }
        return List.copyOf(normalized);
    }

    private static String requireCode(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, 40001, message);
        }
        return value.trim();
    }

    private static String trimToNull(String value, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 64) {
            throw new ApiException(400, 40001, field + "过长（最多 64 个字符）");
        }
        return trimmed;
    }

    private static String dictLabel(List<ImplantDictPort.DictItem> items, String code) {
        return items.stream()
                .filter(item -> item.itemCode().equals(code))
                .map(ImplantDictPort.DictItem::label)
                .findFirst()
                .orElse(code);
    }

    /** 计划族显示名（族内最新版本行；族不存在返回编码本身——防御，保存时已校验）。 */
    private String familyName(String planCode) {
        PlanMapper.PlanRow latest = planMapper.findLatestByCode(planCode);
        return latest != null ? latest.name() : planCode;
    }

    private static String snapshot(List<DevicePlanMappingMapper.MappingRow> rows) {
        if (rows.isEmpty()) {
            return "[]";
        }
        StringBuilder json = new StringBuilder("[");
        for (DevicePlanMappingMapper.MappingRow row : rows) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append("{\"deviceType\":\"")
                    .append(row.deviceType())
                    .append("\",\"planCode\":\"")
                    .append(row.planCode())
                    .append("\",\"enabled\":")
                    .append(row.enabled())
                    .append('}');
        }
        return json.append(']').toString();
    }

    private static String snapshot(MappingView view) {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (MappingItem item : view.items()) {
            if (item.planCode() == null) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"deviceType\":\"")
                    .append(item.deviceType())
                    .append("\",\"planCode\":\"")
                    .append(item.planCode())
                    .append("\",\"enabled\":")
                    .append(item.enabled())
                    .append('}');
        }
        return json.append(']').toString();
    }
}
