package com.crdp.followup.plan.app;

import com.crdp.followup.plan.domain.PlanNodeRule;
import com.crdp.followup.plan.infra.mapper.PlanInsertRow;
import com.crdp.followup.plan.infra.mapper.PlanLogInsertRow;
import com.crdp.followup.plan.infra.mapper.PlanLogMapper;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.plan.infra.mapper.PlanNodeInsertRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 随访计划用例（E14-T01 / A-FU-016、A-FU-018、A-FU-019、C-FU-001；接口 docs/api/followup.md §1，页面 pages.md §8）。
 *
 * <p>版本锁定（验收「发布后版本只读，新入组用新版本」，A-FU-019 / C-FU-001）：
 *
 * <ul>
 *   <li>每个版本一行 fu_plan（code 跨版本不变、version 递增）；编辑与节点保存仅 UNPUBLISHED，已发布 / 暂停 / 停用一律 40901（DENIED
 *       审计）——已发布版本整体只读；
 *   <li>「复制为新版本」从已发布版本复制出 UNPUBLISHED 草稿（节点深拷贝），同族同时至多一份草稿；
 *   <li>发布新版本时，同族旧的 PUBLISHED / PAUSED 版本自动转 DISABLED（已停用 = 不再允许入组，A-FU-018）——旧版本在组患者不迁移、
 *       沿用原版本继续按需创建任务（A-FU-019；迁移到新版本 A-FU-020 首期不做），而新入组必然落在新版本；
 *   <li>状态机（A-FU-018）：UNPUBLISHED →（发布）PUBLISHED →（暂停）PAUSED →（恢复）PUBLISHED；PUBLISHED / PAUSED
 *       →（停用） DISABLED（终态，需要新版本走复制）；「随访中 / RUNNING」不入库，由「PUBLISHED 且有在组入组」派生展示。
 * </ul>
 *
 * <p>乐观锁（AGENTS §5.10）：基本信息与节点共用 lock_version，保存带 baseVersion，冲突 40900。
 *
 * <p>计划变更审计（E14-T08 / A-FU-021，验收「计划改动全记审计」）：创建、编辑、保存节点、发布（含发布新版本时
 * 同族旧版本自动停用）、复制、暂停、恢复、停用每次成功改动都双写——aud_log 审计（操作人 / 时间 / 结果）与 fu_plan_log 变更记录（前后内容快照 + 可读说明）。
 * 变更记录页 GET /plans/{id}/logs 从 fu_plan_log 倒序出（审计只追加且缺 (object_type, object_id) 索引，口径同
 * fe_field_dict_log，OQ-69③）； 被拒操作只写审计 DENIED、不落变更记录（没有发生改动）。
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    /** 变更记录页条数上限（与字段字典 / 字典变更记录同口径）。 */
    private static final int LOG_LIMIT = 100;

    /** 起算日期字段（A-FU-016：出院 / 手术 / 建档 / 自定义；植入日期为 C 的起算字段）。 */
    public static final Set<String> START_DATE_FIELDS =
            Set.of("DISCHARGE_DATE", "SURGERY_DATE", "REGISTRATION_DATE", "IMPLANT_DATE", "CUSTOM");

    /** 管理周期类型（A-FU-016：N 年或长期）。 */
    public static final Set<String> CYCLE_TYPES = Set.of("YEARS", "ONGOING");

    /** 入库状态（RUNNING 为派生展示态，不入库）。 */
    public static final String UNPUBLISHED = "UNPUBLISHED";

    public static final String PUBLISHED = "PUBLISHED";

    public static final String PAUSED = "PAUSED";

    public static final String DISABLED = "DISABLED";

    /** 派生展示态：PUBLISHED 且有在组入组（A-FU-018「随访中（已有患者入组）」）。 */
    public static final String RUNNING = "RUNNING";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlanMapper mapper;
    private final PlanLogMapper logMapper;
    private final AuditLogger audit;

    public PlanService(PlanMapper mapper, PlanLogMapper logMapper, AuditLogger audit) {
        this.mapper = mapper;
        this.logMapper = logMapper;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 计划行（列表与详情共用；status 为派生后的展示态，autoEnroll = 自动入组开关（Q35），enrolledCount = 在组人数）。 */
    public record PlanView(
            String id,
            String code,
            String name,
            int version,
            String status,
            String startDateField,
            String cycleType,
            Integer cycleYears,
            boolean autoEnroll,
            String remark,
            int lockVersion,
            long enrolledCount,
            String publishedAt) {}

    public record PlansView(List<PlanView> items) {}

    /** 节点视图（SUBPLAN / NODE 共形，用不到的一层为 null / 默认值）。 */
    public record NodeView(
            String id,
            String parentId,
            String nodeType,
            String name,
            String repeatMode,
            Integer repeatCount,
            Integer cycleValue,
            String cycleUnit,
            String basis,
            int offsetValue,
            String offsetUnit,
            int remindValue,
            String remindUnit,
            int windowBeforeValue,
            String windowBeforeUnit,
            int windowAfterValue,
            String windowAfterUnit,
            String taskType,
            String ownerType,
            String formCode,
            String passedWindowAction,
            String enrollMode,
            int sortNo) {}

    public record PlanDetailView(PlanView plan, List<NodeView> nodes) {}

    /** 新建请求（新建即未发布；code 创建后不可改；autoEnroll = 自动入组开关，Q35 / E14-T05）。 */
    public record CreateRequest(
            String code,
            String name,
            String startDateField,
            String cycleType,
            Integer cycleYears,
            Boolean autoEnroll,
            String remark) {}

    /** 编辑请求（仅未发布；带 baseVersion 乐观锁；autoEnroll = 自动入组开关，Q35 / E14-T05）。 */
    public record UpdateRequest(
            String name,
            String startDateField,
            String cycleType,
            Integer cycleYears,
            Boolean autoEnroll,
            String remark) {}

    /** 节点整体保存请求（docs/api/followup.md §1：{ version, nodes[] }）。 */
    public record SaveNodesRequest(int baseVersion, List<PlanNodeRule.NodeInput> nodes) {}

    /** 审计摘要（不含患者数据；E14-T08 起含备注，A-FU-021「前后内容」全量口径；E14-T05 起含自动入组开关）。 */
    private record PlanSummary(
            String code,
            String name,
            int version,
            String status,
            String startDateField,
            String cycleType,
            Integer cycleYears,
            boolean autoEnroll,
            String remark) {}

    /** 变更记录行（A-FU-021：操作人、时间与前后内容；GET /plans/{id}/logs）。 */
    public record PlanLogView(
            String id,
            String planId,
            String planCode,
            String action,
            String description,
            String beforeJson,
            String afterJson,
            String operatorId,
            String operatorName,
            String createdAt) {}

    /** 变更记录响应（倒序最近 100 条）。 */
    public record PlanLogsView(List<PlanLogView> items) {}

    // ---------------------------------------------------------------- 查询

    /** 计划列表（含全部版本与状态；每族最新版本在前）。 */
    public PlansView list() {
        return new PlansView(mapper.findAll().stream().map(PlanService::toView).toList());
    }

    /** 计划详情：基本信息 + 节点两层（SUBPLAN 在前、NODE 按父行分组跟随）。 */
    public PlanDetailView detail(long planId) {
        PlanMapper.PlanRow row = requirePlan(planId);
        return new PlanDetailView(toView(row), nodeViews(mapper.findNodesByPlanId(planId)));
    }

    /** 变更记录（A-FU-021：倒序最近 100 条；计划不存在 40400）。 */
    public PlanLogsView logs(long planId) {
        requirePlan(planId);
        List<PlanLogView> items = new ArrayList<>();
        for (PlanLogMapper.PlanLogRow row : logMapper.findLogsByPlanId(planId, LOG_LIMIT)) {
            items.add(
                    new PlanLogView(
                            String.valueOf(row.id()),
                            String.valueOf(row.planId()),
                            row.planCode(),
                            row.action(),
                            row.description(),
                            row.beforeJson(),
                            row.afterJson(),
                            row.operatorId() == null ? null : String.valueOf(row.operatorId()),
                            row.operatorName(),
                            Time.format(row.createdAt())));
        }
        return new PlanLogsView(items);
    }

    // ---------------------------------------------------------------- 新建（A-FU-016）

    /** 新建计划（未发布，v1）；code 归一、本院族唯一（40902）。 */
    @Transactional
    public PlanView create(CreateRequest request) {
        String code = requireCode(request.code());
        String name = requireName(request.name());
        String startDateField = normalizeStartDateField(request.startDateField());
        String cycleType = normalizeCycleType(request.cycleType());
        Integer cycleYears = validateCycleYears(cycleType, request.cycleYears());
        boolean autoEnroll = normalizeAutoEnroll(request.autoEnroll());
        String remark = normalizeRemark(request.remark());

        PlanInsertRow row =
                new PlanInsertRow(
                        code,
                        name,
                        1,
                        UNPUBLISHED,
                        startDateField,
                        cycleType,
                        cycleYears,
                        autoEnroll,
                        remark,
                        currentUserId());
        try {
            mapper.insertPlan(row);
        } catch (DuplicateKeyException race) {
            // (code, version) 唯一键兜底：并发新建同 code 时与前置查重同口径
            log.warn("计划编码并发新建冲突，按重复处理（E14-T01）");
            throw duplicateCode(code);
        }
        String afterJson =
                writeJson(
                        summary(
                                code,
                                name,
                                1,
                                UNPUBLISHED,
                                startDateField,
                                cycleType,
                                cycleYears,
                                autoEnroll,
                                remark));
        String description = "新建计划（未发布）：" + name;
        audit.append(
                AuditEntry.operation("followup", "PLAN_CREATE")
                        .objectType("PLAN")
                        .objectId(String.valueOf(row.getId()))
                        .description(description)
                        .afterJson(afterJson)
                        .result(AuditResult.SUCCESS)
                        .build());
        recordChange(row.getId(), code, "PLAN_CREATE", description, null, afterJson);
        return toView(requirePlan(row.getId()));
    }

    // ---------------------------------------------------------------- 编辑（仅未发布，A-FU-019）

    /** 编辑基本信息：仅未发布；已发布 / 暂停 / 停用 40901（DENIED 审计）；乐观锁冲突 40900。 */
    @Transactional
    public PlanView update(long planId, int baseVersion, UpdateRequest request) {
        PlanMapper.PlanRow current = requirePlan(planId);
        requireEditable(current, "编辑计划");
        String name = requireName(request.name());
        String startDateField = normalizeStartDateField(request.startDateField());
        String cycleType = normalizeCycleType(request.cycleType());
        Integer cycleYears = validateCycleYears(cycleType, request.cycleYears());
        boolean autoEnroll = normalizeAutoEnroll(request.autoEnroll());
        String remark = normalizeRemark(request.remark());
        int rows =
                mapper.updateBasics(
                        planId,
                        name,
                        startDateField,
                        cycleType,
                        cycleYears,
                        autoEnroll,
                        remark,
                        baseVersion);
        if (rows == 0) {
            throw lockOrStateConflict(current, baseVersion);
        }
        String beforeJson = writeJson(summaryOf(current));
        String afterJson =
                writeJson(
                        summary(
                                current.code(),
                                name,
                                current.version(),
                                UNPUBLISHED,
                                startDateField,
                                cycleType,
                                cycleYears,
                                autoEnroll,
                                remark));
        String description = "编辑计划（未发布）：" + name;
        audit.append(
                AuditEntry.operation("followup", "PLAN_UPDATE")
                        .objectType("PLAN")
                        .objectId(String.valueOf(planId))
                        .description(description)
                        .beforeJson(beforeJson)
                        .afterJson(afterJson)
                        .result(AuditResult.SUCCESS)
                        .build());
        recordChange(planId, current.code(), "PLAN_UPDATE", description, beforeJson, afterJson);
        return toView(requirePlan(planId));
    }

    /** 节点整体保存（两层树整体替换；仅未发布；前后节点快照入审计与变更记录，A-FU-021）。 */
    @Transactional
    public PlanDetailView saveNodes(long planId, SaveNodesRequest request) {
        PlanMapper.PlanRow current = requirePlan(planId);
        requireEditable(current, "保存计划节点");
        int baseVersion = request == null ? -1 : request.baseVersion();
        List<PlanNodeRule.NodeInput> inputs = request == null ? null : request.nodes();
        List<PlanNodeRule.NormalizedNode> normalized = PlanNodeRule.normalize(inputs);
        // 先校验再清空重建；乐观锁推进与状态守卫在同一条 UPDATE（命中 0 行 = 冲突或已被发布）
        if (mapper.bumpLockVersion(planId, baseVersion) == 0) {
            throw lockOrStateConflict(current, baseVersion);
        }
        // 替换前快照（A-FU-021「前后内容」：节点数组全量）
        List<PlanMapper.NodeRow> beforeNodes = mapper.findNodesByPlanId(planId);
        mapper.deleteNodesByPlanId(planId);
        // 第一遍插子计划行，建立「请求内 key → 新行 id」映射；第二遍挂节点行（parentKey 必须指向子计划行）
        Map<String, Long> keyToId = new HashMap<>();
        for (int i = 0; i < normalized.size(); i++) {
            if ("SUBPLAN".equals(normalized.get(i).nodeType())) {
                PlanNodeInsertRow row = toInsertRow(planId, null, normalized.get(i));
                mapper.insertNode(row);
                keyToId.put(inputs.get(i).key().trim(), row.getId());
            }
        }
        for (PlanNodeRule.NormalizedNode node : normalized) {
            if ("NODE".equals(node.nodeType())) {
                Long parentId = keyToId.get(node.parentKey());
                if (parentId == null) {
                    throw new ApiException(400, 40001, "节点的上级子计划不存在：" + node.parentKey());
                }
                mapper.insertNode(toInsertRow(planId, parentId, node));
            }
        }
        String beforeJson = writeJson(beforeNodes);
        String afterJson = writeJson(mapper.findNodesByPlanId(planId));
        String description = "保存计划节点（未发布）：" + current.name() + "，共 " + normalized.size() + " 行";
        audit.append(
                AuditEntry.operation("followup", "PLAN_NODE_SAVE")
                        .objectType("PLAN")
                        .objectId(String.valueOf(planId))
                        .description(description)
                        .beforeJson(beforeJson)
                        .afterJson(afterJson)
                        .result(AuditResult.SUCCESS)
                        .build());
        recordChange(planId, current.code(), "PLAN_NODE_SAVE", description, beforeJson, afterJson);
        return detail(planId);
    }

    // ---------------------------------------------------------------- 发布与版本（A-FU-018 / A-FU-019）

    /**
     * 发布：UNPUBLISHED → PUBLISHED。前置：至少一个子计划与一个节点（A-FU-016 三层结构）； 同族旧 PUBLISHED / PAUSED 版本自动转
     * DISABLED（新入组用新版本；在组患者沿用原版本不迁移，A-FU-020 延后）。
     */
    @Transactional
    public PlanView publish(long planId) {
        PlanMapper.PlanRow current = requirePlan(planId);
        if (!UNPUBLISHED.equals(current.status())) {
            audit.append(
                    AuditEntry.operation("followup", "PLAN_PUBLISH")
                            .objectType("PLAN")
                            .objectId(String.valueOf(planId))
                            .description(
                                    "发布计划被阻止：" + current.name() + "（当前状态 " + current.status() + "）")
                            .result(AuditResult.DENIED)
                            .errorCode(40901)
                            .reason("PLAN_NOT_UNPUBLISHED")
                            .build());
            throw new ApiException(409, 40901, "只有未发布的计划才能发布；已发布的计划不能修改，请复制为新版本");
        }
        if (mapper.countSubplans(planId) == 0 || mapper.countNodes(planId) == 0) {
            audit.append(
                    AuditEntry.operation("followup", "PLAN_PUBLISH")
                            .objectType("PLAN")
                            .objectId(String.valueOf(planId))
                            .description("发布计划被阻止：" + current.name() + "（缺少子计划或节点）")
                            .result(AuditResult.DENIED)
                            .errorCode(40001)
                            .reason("PLAN_HAS_NO_NODE")
                            .build());
            throw new ApiException(400, 40001, "发布前请至少配置一个子计划及其节点（计划 > 子计划 > 任务，A-FU-016）");
        }
        int rows = mapper.transitionStatus(planId, UNPUBLISHED, PUBLISHED);
        if (rows == 0) {
            throw new ApiException(409, 40901, "计划状态已变化，请刷新后重试");
        }
        mapper.markPublished(planId, LocalDateTime.now(), currentUserId());
        List<Long> superseded = mapper.findSupersededIds(current.code(), planId);
        for (Long oldId : superseded) {
            // 同族旧版本自动停用（A-FU-021：停用也是计划改动，逐版本留痕）
            PlanMapper.PlanRow oldRow = requirePlan(oldId);
            mapper.transitionStatus(oldId, PUBLISHED, DISABLED);
            mapper.transitionStatus(oldId, PAUSED, DISABLED);
            String oldDescription =
                    "停用计划（新版本 v"
                            + current.version()
                            + " 发布后自动停用，不再允许入组；在组患者沿用本版本）："
                            + oldRow.name()
                            + " v"
                            + oldRow.version();
            audit.append(
                    AuditEntry.operation("followup", "PLAN_DISABLE")
                            .objectType("PLAN")
                            .objectId(String.valueOf(oldId))
                            .description(oldDescription)
                            .beforeJson(writeJson(summaryOf(oldRow)))
                            .afterJson(
                                    writeJson(
                                            summary(
                                                    oldRow.code(),
                                                    oldRow.name(),
                                                    oldRow.version(),
                                                    DISABLED,
                                                    oldRow.startDateField(),
                                                    oldRow.cycleType(),
                                                    oldRow.cycleYears(),
                                                    oldRow.autoEnroll() != null
                                                            && oldRow.autoEnroll(),
                                                    oldRow.remark())))
                            .result(AuditResult.SUCCESS)
                            .build());
            recordChange(
                    oldId,
                    oldRow.code(),
                    "PLAN_DISABLE",
                    oldDescription,
                    writeJson(summaryOf(oldRow)),
                    writeJson(
                            summary(
                                    oldRow.code(),
                                    oldRow.name(),
                                    oldRow.version(),
                                    DISABLED,
                                    oldRow.startDateField(),
                                    oldRow.cycleType(),
                                    oldRow.cycleYears(),
                                    oldRow.autoEnroll() != null && oldRow.autoEnroll(),
                                    oldRow.remark())));
        }
        String beforeJson = writeJson(summaryOf(current));
        String afterJson =
                writeJson(
                        summary(
                                current.code(),
                                current.name(),
                                current.version(),
                                PUBLISHED,
                                current.startDateField(),
                                current.cycleType(),
                                current.cycleYears(),
                                current.autoEnroll() != null && current.autoEnroll(),
                                current.remark()));
        String description =
                "发布计划："
                        + current.name()
                        + " v"
                        + current.version()
                        + (superseded.isEmpty() ? "" : "；同族旧版本自动停用（不再允许入组，新入组用新版本）" + superseded);
        audit.append(
                AuditEntry.operation("followup", "PLAN_PUBLISH")
                        .objectType("PLAN")
                        .objectId(String.valueOf(planId))
                        .description(description)
                        .beforeJson(beforeJson)
                        .afterJson(afterJson)
                        .result(AuditResult.SUCCESS)
                        .build());
        recordChange(planId, current.code(), "PLAN_PUBLISH", description, beforeJson, afterJson);
        return toView(requirePlan(planId));
    }

    /**
     * 复制为新版本（A-FU-019）：从已发布 / 暂停 / 停用版本复制出 UNPUBLISHED 草稿（节点深拷贝， version = 族内最大 +1）；同族已有草稿
     * 40901（要改草稿直接编辑）。
     */
    @Transactional
    public PlanView copy(long planId) {
        PlanMapper.PlanRow source = requirePlan(planId);
        if (UNPUBLISHED.equals(source.status())) {
            throw new ApiException(409, 40901, "未发布的计划可直接编辑，无需复制；请先发布再复制出新版本");
        }
        if (mapper.findDraftIdByCode(source.code()) != null) {
            audit.append(
                    AuditEntry.operation("followup", "PLAN_COPY")
                            .objectType("PLAN")
                            .objectId(String.valueOf(planId))
                            .description("复制计划被阻止：" + source.name() + "（同族已有未发布新版本）")
                            .result(AuditResult.DENIED)
                            .errorCode(40901)
                            .reason("PLAN_DRAFT_EXISTS")
                            .build());
            throw new ApiException(409, 40901, "该计划已有未发布的新版本，请先处理（编辑或发布）后再复制");
        }
        int nextVersion = mapper.findMaxVersionByCode(source.code()) + 1;
        PlanInsertRow row =
                new PlanInsertRow(
                        source.code(),
                        source.name(),
                        nextVersion,
                        UNPUBLISHED,
                        source.startDateField(),
                        source.cycleType(),
                        source.cycleYears(),
                        source.autoEnroll() != null && source.autoEnroll(),
                        source.remark(),
                        currentUserId());
        mapper.insertPlan(row);
        List<PlanMapper.NodeRow> sourceNodes = mapper.findNodesByPlanId(planId);
        Map<Long, Long> oldToNew = new HashMap<>();
        for (PlanMapper.NodeRow node : sourceNodes) {
            if ("SUBPLAN".equals(node.nodeType())) {
                PlanNodeInsertRow insert = toInsertRow(row.getId(), null, node);
                mapper.insertNode(insert);
                oldToNew.put(node.id(), insert.getId());
            }
        }
        for (PlanMapper.NodeRow node : sourceNodes) {
            if ("NODE".equals(node.nodeType())) {
                Long newParentId = oldToNew.get(node.parentId());
                if (newParentId == null) {
                    throw new ApiException(400, 40001, "计划节点结构不完整（节点缺少子计划），无法复制");
                }
                mapper.insertNode(toInsertRow(row.getId(), newParentId, node));
            }
        }
        Map<String, Object> afterContent = new HashMap<>();
        afterContent.put("code", source.code());
        afterContent.put("name", source.name());
        afterContent.put("version", nextVersion);
        afterContent.put("status", UNPUBLISHED);
        afterContent.put("startDateField", source.startDateField());
        afterContent.put("cycleType", source.cycleType());
        afterContent.put("cycleYears", source.cycleYears());
        afterContent.put("remark", source.remark());
        afterContent.put("copiedFromPlanId", source.id());
        afterContent.put("copiedFromVersion", source.version());
        afterContent.put("copiedNodeCount", sourceNodes.size());
        String afterJson = writeJson(afterContent);
        String description =
                "复制计划为新版本："
                        + source.name()
                        + " v"
                        + source.version()
                        + " → v"
                        + nextVersion
                        + "（未发布，"
                        + sourceNodes.size()
                        + " 个节点行）";
        audit.append(
                AuditEntry.operation("followup", "PLAN_COPY")
                        .objectType("PLAN")
                        .objectId(String.valueOf(row.getId()))
                        .description(description)
                        .afterJson(afterJson)
                        .result(AuditResult.SUCCESS)
                        .build());
        recordChange(row.getId(), source.code(), "PLAN_COPY", description, null, afterJson);
        return toView(requirePlan(row.getId()));
    }

    // ---------------------------------------------------------------- 状态迁移（A-FU-018）

    /** 暂停：PUBLISHED → PAUSED（不开启新任务、不发通知）。 */
    @Transactional
    public PlanView pause(long planId) {
        return transition(planId, PUBLISHED, PAUSED, "PLAN_PAUSE", "暂停计划");
    }

    /** 恢复：PAUSED → PUBLISHED（从下一个窗口起继续按需创建任务）。 */
    @Transactional
    public PlanView resume(long planId) {
        return transition(planId, PAUSED, PUBLISHED, "PLAN_RESUME", "恢复计划");
    }

    /** 停用：PUBLISHED / PAUSED → DISABLED（不再允许入组；在组患者按原版本继续）。 */
    @Transactional
    public PlanView disable(long planId) {
        PlanMapper.PlanRow current = requirePlan(planId);
        if (PAUSED.equals(current.status())) {
            return transition(planId, PAUSED, DISABLED, "PLAN_DISABLE", "停用计划");
        }
        return transition(planId, PUBLISHED, DISABLED, "PLAN_DISABLE", "停用计划");
    }

    private PlanView transition(long planId, String from, String to, String action, String verb) {
        PlanMapper.PlanRow current = requirePlan(planId);
        if (!from.equals(current.status())) {
            audit.append(
                    AuditEntry.operation("followup", action)
                            .objectType("PLAN")
                            .objectId(String.valueOf(planId))
                            .description(
                                    verb
                                            + "被阻止："
                                            + current.name()
                                            + "（当前状态 "
                                            + current.status()
                                            + "）")
                            .result(AuditResult.DENIED)
                            .errorCode(40901)
                            .reason("PLAN_STATUS_" + current.status())
                            .build());
            throw statusConflict(current, to);
        }
        if (mapper.transitionStatus(planId, from, to) == 0) {
            throw new ApiException(409, 40901, "计划状态已变化，请刷新后重试");
        }
        String beforeJson = writeJson(summaryOf(current));
        String afterJson =
                writeJson(
                        summary(
                                current.code(),
                                current.name(),
                                current.version(),
                                to,
                                current.startDateField(),
                                current.cycleType(),
                                current.cycleYears(),
                                current.autoEnroll() != null && current.autoEnroll(),
                                current.remark()));
        String description = verb + "：" + current.name() + " v" + current.version();
        audit.append(
                AuditEntry.operation("followup", action)
                        .objectType("PLAN")
                        .objectId(String.valueOf(planId))
                        .description(description)
                        .beforeJson(beforeJson)
                        .afterJson(afterJson)
                        .result(AuditResult.SUCCESS)
                        .build());
        recordChange(planId, current.code(), action, description, beforeJson, afterJson);
        return toView(requirePlan(planId));
    }

    // ---------------------------------------------------------------- 内部

    private PlanMapper.PlanRow requirePlan(long planId) {
        PlanMapper.PlanRow row = mapper.findById(planId);
        if (row == null) {
            throw new ApiException(404, 40400, "计划不存在");
        }
        return row;
    }

    /** 已发布 / 暂停 / 停用的版本只读（A-FU-019）：40901 + DENIED 审计。 */
    private void requireEditable(PlanMapper.PlanRow row, String verb) {
        if (!UNPUBLISHED.equals(row.status())) {
            audit.append(
                    AuditEntry.operation("followup", "PLAN_UPDATE")
                            .objectType("PLAN")
                            .objectId(String.valueOf(row.id()))
                            .description(
                                    verb
                                            + "被阻止："
                                            + row.name()
                                            + " v"
                                            + row.version()
                                            + "（"
                                            + row.status()
                                            + "，已发布版本只读）")
                            .result(AuditResult.DENIED)
                            .errorCode(40901)
                            .reason("PLAN_READ_ONLY")
                            .build());
            throw new ApiException(409, 40901, "已发布的计划不能修改，请复制为新版本（A-FU-019）");
        }
    }

    /** 乐观锁或状态守卫未命中：按当前行区分 40900（版本冲突）与 40901（状态只读）。 */
    private ApiException lockOrStateConflict(PlanMapper.PlanRow current, int baseVersion) {
        PlanMapper.PlanRow fresh = requirePlan(current.id());
        if (!UNPUBLISHED.equals(fresh.status())) {
            return new ApiException(409, 40901, "已发布的计划不能修改，请复制为新版本（A-FU-019）");
        }
        return new ApiException(409, 40900, "计划已被他人修改（当前版本 v" + fresh.lockVersion() + "），请刷新后重试");
    }

    private ApiException statusConflict(PlanMapper.PlanRow current, String target) {
        return switch (target) {
            case PAUSED -> new ApiException(409, 40901, "只有已发布（或随访中）的计划才能暂停；未发布的直接编辑即可");
            case PUBLISHED -> new ApiException(409, 40901, "只有已暂停的计划才能恢复");
            case DISABLED -> new ApiException(409, 40901, "只有已发布（或随访中）/ 已暂停的计划才能停用；未发布的直接编辑即可");
            default -> new ApiException(409, 40901, "计划状态不允许该操作");
        };
    }

    private ApiException duplicateCode(String code) {
        audit.append(
                AuditEntry.operation("followup", "PLAN_CREATE")
                        .objectType("PLAN")
                        .description("新建计划被阻止：编码已存在（" + code + "）")
                        .result(AuditResult.DENIED)
                        .errorCode(40902)
                        .reason("DUPLICATE_PLAN_CODE")
                        .build());
        return new ApiException(409, 40902, "计划编码已存在（" + code + "），请更换编码");
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ApiException(400, 40001, "请填写计划编码");
        }
        String trimmed = code.trim();
        if (trimmed.length() > 64) {
            throw new ApiException(400, 40001, "计划编码最多 64 个字符");
        }
        if (!trimmed.matches("[A-Za-z0-9_-]+")) {
            throw new ApiException(400, 40001, "计划编码只支持字母、数字、短横线与下划线");
        }
        return trimmed.toUpperCase();
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(400, 40001, "请填写计划名称");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 128) {
            throw new ApiException(400, 40001, "计划名称最多 128 个字符");
        }
        return trimmed;
    }

    private static String normalizeRemark(String remark) {
        if (remark == null || remark.isBlank()) {
            return null;
        }
        String trimmed = remark.trim();
        if (trimmed.length() > 255) {
            throw new ApiException(400, 40001, "备注最多 255 个字符");
        }
        return trimmed;
    }

    private static String normalizeStartDateField(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!START_DATE_FIELDS.contains(normalized)) {
            throw new ApiException(400, 40001, "起算日期只支持出院日期、手术日期、建档日期、植入日期或自定义（A-FU-016）");
        }
        return normalized;
    }

    private static String normalizeCycleType(String value) {
        String normalized =
                value == null || value.isBlank() ? "ONGOING" : value.trim().toUpperCase();
        if (!CYCLE_TYPES.contains(normalized)) {
            throw new ApiException(400, 40001, "管理周期只支持固定 N 年（YEARS）或长期（ONGOING）");
        }
        return normalized;
    }

    private static Integer validateCycleYears(String cycleType, Integer cycleYears) {
        if ("YEARS".equals(cycleType)) {
            if (cycleYears == null || cycleYears < 1 || cycleYears > 100) {
                throw new ApiException(400, 40001, "管理周期年数取值 1–100");
            }
            return cycleYears;
        }
        return null;
    }

    private static Long currentUserId() {
        return CurrentSession.current().map(principal -> principal.userId()).orElse(null);
    }

    private static PlanSummary summaryOf(PlanMapper.PlanRow row) {
        return summary(
                row.code(),
                row.name(),
                row.version(),
                row.status(),
                row.startDateField(),
                row.cycleType(),
                row.cycleYears(),
                row.autoEnroll() != null && row.autoEnroll(),
                row.remark());
    }

    private static PlanSummary summary(
            String code,
            String name,
            int version,
            String status,
            String startDateField,
            String cycleType,
            Integer cycleYears,
            boolean autoEnroll,
            String remark) {
        return new PlanSummary(
                code,
                name,
                version,
                status,
                startDateField,
                cycleType,
                cycleYears,
                autoEnroll,
                remark);
    }

    /**
     * 追加计划变更记录（E14-T08 / A-FU-021）：与 aud_log 审计双写，fu_plan_log 供变更记录页查询 （审计只追加且缺 (object_type,
     * object_id) 索引，口径同 fe_field_dict_log，OQ-69③）。 只在成功改动后调用；被拒操作只写审计 DENIED。
     */
    private void recordChange(
            long planId,
            String planCode,
            String action,
            String description,
            String beforeJson,
            String afterJson) {
        logMapper.insertLog(
                new PlanLogInsertRow(
                        planId,
                        planCode,
                        action,
                        description,
                        beforeJson,
                        afterJson,
                        currentUserId()));
    }

    private static PlanView toView(PlanMapper.PlanRow row) {
        long active = row.activeCount() == null ? 0 : row.activeCount();
        String status = PUBLISHED.equals(row.status()) && active > 0 ? RUNNING : row.status();
        return new PlanView(
                String.valueOf(row.id()),
                row.code(),
                row.name(),
                row.version(),
                status,
                row.startDateField(),
                row.cycleType(),
                row.cycleYears(),
                row.autoEnroll() != null && row.autoEnroll(),
                row.remark(),
                row.lockVersion(),
                active,
                row.publishedAt() == null ? null : row.publishedAt().toString());
    }

    /** 自动入组开关（Q35 / E14-T05）：null 视为关；发布前后含义一致（只有 PUBLISHED 计划参与自动入组）。 */
    private static boolean normalizeAutoEnroll(Boolean autoEnroll) {
        return Boolean.TRUE.equals(autoEnroll);
    }

    private static List<NodeView> nodeViews(List<PlanMapper.NodeRow> rows) {
        List<NodeView> views = new ArrayList<>(rows.size());
        for (PlanMapper.NodeRow row : rows) {
            views.add(
                    new NodeView(
                            String.valueOf(row.id()),
                            row.parentId() == null ? null : String.valueOf(row.parentId()),
                            row.nodeType(),
                            row.name(),
                            row.repeatMode(),
                            row.repeatCount(),
                            row.cycleValue(),
                            row.cycleUnit(),
                            row.basis(),
                            row.offsetValue(),
                            row.offsetUnit(),
                            row.remindValue(),
                            row.remindUnit(),
                            row.windowBeforeValue(),
                            row.windowBeforeUnit(),
                            row.windowAfterValue(),
                            row.windowAfterUnit(),
                            row.taskType(),
                            row.ownerType(),
                            row.formCode(),
                            row.passedWindowAction(),
                            row.enrollMode(),
                            row.sortNo()));
        }
        return views;
    }

    private static PlanNodeInsertRow toInsertRow(
            Long planId, Long parentId, PlanNodeRule.NormalizedNode node) {
        return new PlanNodeInsertRow(
                planId,
                parentId,
                node.nodeType(),
                node.name(),
                node.repeatMode(),
                node.repeatCount(),
                node.cycleValue(),
                node.cycleUnit(),
                node.basis(),
                node.offsetValue(),
                node.offsetUnit(),
                node.remindValue(),
                node.remindUnit(),
                node.windowBeforeValue(),
                node.windowBeforeUnit(),
                node.windowAfterValue(),
                node.windowAfterUnit(),
                node.taskType(),
                node.ownerType(),
                node.formCode(),
                node.passedWindowAction(),
                node.enrollMode(),
                node.sortNo());
    }

    private static PlanNodeInsertRow toInsertRow(
            Long planId, Long parentId, PlanMapper.NodeRow node) {
        return new PlanNodeInsertRow(
                planId,
                parentId,
                node.nodeType(),
                node.name(),
                node.repeatMode(),
                node.repeatCount(),
                node.cycleValue(),
                node.cycleUnit(),
                node.basis(),
                node.offsetValue(),
                node.offsetUnit(),
                node.remindValue(),
                node.remindUnit(),
                node.windowBeforeValue(),
                node.windowBeforeUnit(),
                node.windowAfterValue(),
                node.windowAfterUnit(),
                node.taskType(),
                node.ownerType(),
                node.formCode(),
                node.passedWindowAction(),
                node.enrollMode(),
                node.sortNo());
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            return null;
        }
    }

    /** 接口时间口径：ISO-8601 带时区（北京时间，AGENTS §6）。 */
    static final class Time {
        private Time() {}

        static String format(java.time.LocalDateTime time) {
            return time == null
                    ? null
                    : java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                            .withZone(java.time.ZoneId.of("Asia/Shanghai"))
                            .format(time.atZone(java.time.ZoneId.systemDefault()).toInstant());
        }
    }
}
