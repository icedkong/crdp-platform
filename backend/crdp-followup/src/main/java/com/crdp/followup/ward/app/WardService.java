package com.crdp.followup.ward.app;

import com.crdp.followup.ward.infra.mapper.WardInsert;
import com.crdp.followup.ward.infra.mapper.WardMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 病区 / 中心字典用例（E13-T08 / A-WARD-001、A-WARD-008、C-WARD-001；接口 docs/api/system.md §1，页面
 * /system/ward，pages.md §10）。字典是独立字典，不挂科室下（医院已确认）；编码本院唯一、创建后不可改；
 * 停用不删除——病区下仍有在册患者时不能停用，须先转出（A-WARD-008）。
 *
 * <p>「在随访患者」的停用拦截口径：在册（deleted = 0）患者数 > 0 即拦截。患者登记即 ACTIVE（OQ-71②）、 终止 /
 * 死亡患者的病区归属仍是管理事实（统计与历史回看要用），逐状态枚举会漏拦，故按在册口径从严。
 *
 * <p>字典为 ward 表（不在 ControlledTables，字典非患者数据）；唯一的患者面访问是停用前计数（管理口径 {@code @IgnoreScope}，见 {@link
 * WardMapper#countLivePatients}）。增改停全部写审计（AGENTS.md §5.4 配置 / 状态变更）。
 */
@Service
public class WardService {

    private static final Logger log = LoggerFactory.getLogger(WardService.class);

    /** 类型枚举（A-WARD-001 关键字段：病区 / 中心；C-WARD-001 同一字典）。 */
    public static final Set<String> WARD_TYPES = Set.of("WARD", "CENTER");

    /** 别名单项上限（多值 CSV 总长 ≤ 255，与 cfg_dict_item.aliases 同口径）。 */
    static final int ALIAS_ITEM_MAX = 64;

    static final int ALIAS_TOTAL_MAX = 255;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WardMapper mapper;
    private final AuditLogger audit;

    public WardService(WardMapper mapper, AuditLogger audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 病区行（列表 / 回显；aliases 由 CSV 拆为清单，patientCount = 在册患者数）。 */
    public record WardView(
            String id,
            String code,
            String name,
            String wardType,
            List<String> aliases,
            String defaultOwnerId,
            String defaultOwnerName,
            Integer sortNo,
            String status,
            long patientCount,
            long memberCount) {}

    public record WardsView(List<WardView> items) {}

    /** 新建请求：编码创建后不可改；aliases 支持清单或分号分隔的原始串（导入历史写法维护入口同口径）。 */
    public record CreateRequest(
            String code,
            String name,
            String wardType,
            List<String> aliases,
            Long defaultOwnerId,
            Integer sortNo) {}

    /** 编辑请求：编码不可改（携带且不同 → 40001）；状态走专用接口。 */
    public record UpdateRequest(
            String code,
            String name,
            String wardType,
            List<String> aliases,
            Long defaultOwnerId,
            Integer sortNo) {}

    /** 状态变更请求（A-WARD-008 停用 / 恢复）。 */
    public record StatusRequest(String status) {}

    /** 成员与患者数（system.md §1 wards/{id}/members）。 */
    public record MemberView(String id, String username, String displayName) {}

    public record MembersView(String wardId, long patientCount, List<MemberView> members) {}

    /** 审计摘要（不含患者数据；alias 为字典值非敏感）。 */
    private record WardSummary(
            String code,
            String name,
            String wardType,
            String aliasCsv,
            Long defaultOwnerId,
            Integer sortNo,
            String status,
            Long patientCount) {}

    // ---------------------------------------------------------------- 查询

    /** 病区列表（含停用，管理页全量视图；按 sort_no、id 升序）。 */
    public WardsView list() {
        return new WardsView(mapper.findAll().stream().map(WardService::toView).toList());
    }

    /** 成员与患者数（负责病区 = 本病区的启用用户）。 */
    public MembersView members(long wardId) {
        WardMapper.WardRow row = requireWard(wardId);
        List<MemberView> members =
                mapper.findMembers(wardId).stream()
                        .map(
                                member ->
                                        new MemberView(
                                                String.valueOf(member.id()),
                                                member.username(),
                                                member.displayName()))
                        .toList();
        return new MembersView(String.valueOf(row.id()), row.patientCount(), members);
    }

    // ---------------------------------------------------------------- 新建（A-WARD-001）

    /** 新建病区：编码唯一（40902）、类型枚举、别名归一、默认负责人须为启用用户；写 WARD_CREATE 审计。 */
    @Transactional
    public WardView create(CreateRequest request) {
        String code = requireCode(request.code());
        String name = requireName(request.name());
        String wardType = normalizeType(request.wardType());
        String aliasCsv = normalizeAliases(request.aliases());
        Long defaultOwnerId = validateOwner(request.defaultOwnerId());
        int sortNo = normalizeSort(request.sortNo());

        if (mapper.findIdByCode(code) != null) {
            throw duplicateCode(code);
        }
        WardInsert row = new WardInsert();
        row.setCode(code);
        row.setName(name);
        row.setWardType(wardType);
        row.setAlias(aliasCsv);
        row.setDefaultOwnerId(defaultOwnerId);
        row.setSortNo(sortNo);
        try {
            mapper.insertWard(row);
        } catch (DuplicateKeyException race) {
            // 并发新建同编码：唯一键兜底，与前置查重同口径
            log.warn("病区编码并发新建冲突，按重复处理（E13-T08）");
            throw duplicateCode(code);
        }

        audit.append(
                AuditEntry.operation("followup", "WARD_CREATE")
                        .objectType("WARD")
                        .objectId(String.valueOf(row.getId()))
                        .description("新建病区：" + name)
                        .afterJson(
                                writeJson(
                                        new WardSummary(
                                                code,
                                                name,
                                                wardType,
                                                aliasCsv,
                                                defaultOwnerId,
                                                sortNo,
                                                "ENABLED",
                                                null)))
                        .result(AuditResult.SUCCESS)
                        .build());
        return toView(mapper.findById(row.getId()));
    }

    // ---------------------------------------------------------------- 编辑（A-WARD-001）

    /** 编辑病区：编码不可改；写 WARD_UPDATE 审计（前后值快照）。 */
    @Transactional
    public WardView update(long wardId, UpdateRequest request) {
        WardMapper.WardRow current = requireWard(wardId);
        if (request.code() != null
                && !request.code().isBlank()
                && !current.code().equals(request.code().trim())) {
            throw new ApiException(400, 40001, "病区编码创建后不可修改");
        }
        String name = requireName(request.name());
        String wardType = normalizeType(request.wardType());
        String aliasCsv = normalizeAliases(request.aliases());
        Long defaultOwnerId = validateOwner(request.defaultOwnerId());
        int sortNo = normalizeSort(request.sortNo());

        mapper.updateWard(wardId, name, wardType, aliasCsv, defaultOwnerId, sortNo);

        audit.append(
                AuditEntry.operation("followup", "WARD_UPDATE")
                        .objectType("WARD")
                        .objectId(String.valueOf(wardId))
                        .description("编辑病区：" + current.name())
                        .beforeJson(
                                writeJson(
                                        new WardSummary(
                                                current.code(),
                                                current.name(),
                                                current.wardType(),
                                                current.alias(),
                                                current.defaultOwnerId(),
                                                current.sortNo(),
                                                current.status(),
                                                current.patientCount())))
                        .afterJson(
                                writeJson(
                                        new WardSummary(
                                                current.code(),
                                                name,
                                                wardType,
                                                aliasCsv,
                                                defaultOwnerId,
                                                sortNo,
                                                current.status(),
                                                null)))
                        .result(AuditResult.SUCCESS)
                        .build());
        return toView(mapper.findById(wardId));
    }

    // ---------------------------------------------------------------- 停用与恢复（A-WARD-008）

    /**
     * 停用 / 启用：病区下仍有在册患者时不能停用，须先转出（40901，DENIED 审计）； 停用后不再进登记 / 转病区候选（候选查询只取
     * ENABLED）；恢复无前置条件。组织事件不自动改派（OQ-10），停用只做字典状态。
     */
    @Transactional
    public WardView changeStatus(long wardId, StatusRequest request) {
        WardMapper.WardRow current = requireWard(wardId);
        String target = normalizeStatus(request);
        if (target.equals(current.status())) {
            throw new ApiException(400, 40001, "ENABLED".equals(target) ? "病区已是启用状态" : "病区已是停用状态");
        }
        if ("DISABLED".equals(target)) {
            long livePatients = mapper.countLivePatients(wardId);
            if (livePatients > 0) {
                audit.append(
                        AuditEntry.operation("followup", "WARD_DISABLE")
                                .objectType("WARD")
                                .objectId(String.valueOf(wardId))
                                .description(
                                        "停用病区被阻止："
                                                + current.name()
                                                + "，在册患者 "
                                                + livePatients
                                                + " 名（A-WARD-008，须先转出）")
                                .result(AuditResult.DENIED)
                                .errorCode(40901)
                                .reason("WARD_HAS_PATIENTS")
                                .build());
                throw new ApiException(
                        409, 40901, "该病区下仍有 " + livePatients + " 名在册患者，不能停用；请先为这些患者转病区后再停用");
            }
        }
        mapper.updateStatus(wardId, target);
        String action = "DISABLED".equals(target) ? "WARD_DISABLE" : "WARD_ENABLE";
        audit.append(
                AuditEntry.operation("followup", action)
                        .objectType("WARD")
                        .objectId(String.valueOf(wardId))
                        .description(
                                ("DISABLED".equals(target) ? "停用病区：" : "启用病区：") + current.name())
                        .beforeJson(
                                writeJson(
                                        new WardSummary(
                                                current.code(),
                                                current.name(),
                                                current.wardType(),
                                                current.alias(),
                                                current.defaultOwnerId(),
                                                current.sortNo(),
                                                current.status(),
                                                current.patientCount())))
                        .afterJson(
                                writeJson(
                                        new WardSummary(
                                                current.code(),
                                                current.name(),
                                                current.wardType(),
                                                current.alias(),
                                                current.defaultOwnerId(),
                                                current.sortNo(),
                                                target,
                                                null)))
                        .result(AuditResult.SUCCESS)
                        .build());
        return toView(mapper.findById(wardId));
    }

    // ---------------------------------------------------------------- 内部

    private WardMapper.WardRow requireWard(long wardId) {
        WardMapper.WardRow row = mapper.findById(wardId);
        if (row == null) {
            throw new ApiException(404, 40400, "病区不存在");
        }
        return row;
    }

    private ApiException duplicateCode(String code) {
        audit.append(
                AuditEntry.operation("followup", "WARD_CREATE")
                        .objectType("WARD")
                        .description("新建病区被阻止：编码已存在（" + code + "）")
                        .result(AuditResult.DENIED)
                        .errorCode(40902)
                        .reason("DUPLICATE_WARD_CODE")
                        .build());
        return new ApiException(409, 40902, "病区编码已存在（" + code + "），请更换编码");
    }

    /** 别名归一：去空白、去空项、去重（保持顺序），CSV「;」存储；单项 / 总长上限校验。 */
    static String normalizeAliases(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String item : aliases) {
            String[] parts = item == null ? new String[0] : item.split("[;；]");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    if (trimmed.length() > ALIAS_ITEM_MAX) {
                        throw new ApiException(400, 40001, "别名单项最多 " + ALIAS_ITEM_MAX + " 个字符");
                    }
                    normalized.add(trimmed);
                }
            }
        }
        if (normalized.isEmpty()) {
            return null;
        }
        String csv = String.join(";", normalized);
        if (csv.length() > ALIAS_TOTAL_MAX) {
            throw new ApiException(400, 40001, "别名总长最多 " + ALIAS_TOTAL_MAX + " 个字符");
        }
        return csv;
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ApiException(400, 40001, "请填写病区编码");
        }
        String trimmed = code.trim();
        if (trimmed.length() > 64) {
            throw new ApiException(400, 40001, "病区编码最多 64 个字符");
        }
        if (!trimmed.matches("[A-Za-z0-9_-]+")) {
            throw new ApiException(400, 40001, "病区编码只支持字母、数字、短横线与下划线");
        }
        return trimmed;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(400, 40001, "请填写病区名称");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 128) {
            throw new ApiException(400, 40001, "病区名称最多 128 个字符");
        }
        return trimmed;
    }

    private static String normalizeType(String wardType) {
        String normalized = wardType == null ? "" : wardType.trim().toUpperCase();
        if (!WARD_TYPES.contains(normalized)) {
            throw new ApiException(400, 40001, "类型只支持病区（WARD）或中心（CENTER）");
        }
        return normalized;
    }

    private Long validateOwner(Long defaultOwnerId) {
        if (defaultOwnerId == null) {
            return null;
        }
        if (defaultOwnerId <= 0 || mapper.countEnabledUser(defaultOwnerId) == 0) {
            throw new ApiException(400, 40001, "默认随访负责人不存在或已停用");
        }
        return defaultOwnerId;
    }

    private static int normalizeSort(Integer sortNo) {
        if (sortNo == null) {
            return 0;
        }
        if (sortNo < 0 || sortNo > 9999) {
            throw new ApiException(400, 40001, "排序取值 0–9999");
        }
        return sortNo;
    }

    private static String normalizeStatus(StatusRequest request) {
        String status = request == null || request.status() == null ? "" : request.status().trim();
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new ApiException(400, 40001, "状态只支持启用（ENABLED）或停用（DISABLED）");
        }
        return status;
    }

    private static WardView toView(WardMapper.WardRow row) {
        List<String> aliases = new ArrayList<>();
        if (row.alias() != null && !row.alias().isBlank()) {
            for (String part : row.alias().split(";")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    aliases.add(trimmed);
                }
            }
        }
        return new WardView(
                String.valueOf(row.id()),
                row.code(),
                row.name(),
                row.wardType(),
                List.copyOf(aliases),
                row.defaultOwnerId() == null ? null : String.valueOf(row.defaultOwnerId()),
                row.defaultOwnerName(),
                row.sortNo(),
                row.status(),
                row.patientCount() == null ? 0 : row.patientCount(),
                row.memberCount() == null ? 0 : row.memberCount());
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            return null;
        }
    }
}
