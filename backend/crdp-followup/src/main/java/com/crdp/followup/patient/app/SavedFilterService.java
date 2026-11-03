package com.crdp.followup.patient.app;

import com.crdp.followup.patient.domain.AdvancedConditionCompiler;
import com.crdp.followup.patient.infra.mapper.SavedFilterMapper;
import com.crdp.followup.patient.infra.mapper.SavedFilterMapper.SavedFilterInsert;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 个人保存的筛选（E13-T06 / A-PAT-015）：把高级检索条件（连同简单筛选面）保存为个人常用筛选， 可命名、修改、删除；仅本人可见（全部读写带 user_id）。
 *
 * <p>口径：
 *
 * <ul>
 *   <li>payload = {filters?, condition?}，**不含检索词 keyword**（检索词可能是患者姓名，与「检索词不写 URL / 审计不存明文」同口径不落库）；
 *   <li>保存时校验：条件树必须能通过 {@link AdvancedConditionCompiler} 编译（字段目录白名单 + 参数化， A-NFR-009——保存的筛选在应用时同样走
 *       /patients/search 的完整校验与数据范围）；
 *   <li>键收敛：filters 只允许 README §2.4 的筛选键，未知键 40001，防止保存任意结构；
 *   <li>增改删写 OPERATION 审计（AGENTS.md §5.4 配置增删改留痕；描述只含筛选名与结构摘要）。
 * </ul>
 */
@Service
public class SavedFilterService {

    /** 每人上限（建议值；防无界增长）。 */
    private static final int MAX_PER_USER = 20;

    private static final int NAME_MAX = 32;

    /** README §2.4 简单筛选键白名单（与 PatientSearchService.Filters 一致）。 */
    private static final Set<String> FILTER_KEYS =
            Set.of(
                    "wardIds",
                    "deptIds",
                    "status",
                    "doctorId",
                    "dischargeStatus",
                    "patientType",
                    "surgeryFrom",
                    "surgeryTo",
                    "dischargeFrom",
                    "dischargeTo",
                    "nextVisitFrom",
                    "nextVisitTo");

    private final SavedFilterMapper mapper;
    private final PatientSearchFieldService searchFields;
    private final AuditLogger audit;
    private final ObjectMapper json = new ObjectMapper();

    public SavedFilterService(
            SavedFilterMapper mapper, PatientSearchFieldService searchFields, AuditLogger audit) {
        this.mapper = mapper;
        this.searchFields = searchFields;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 视图

    /** 筛选视图（payload 原样回传，应用时前端把它并入检索请求体）。 */
    public record SavedFilterView(String id, String name, JsonNode payload, String updatedAt) {}

    public record SavedFilterList(List<SavedFilterView> items) {}

    // ---------------------------------------------------------------- 用例

    /** 本人筛选清单（新在前）。 */
    public SavedFilterList list() {
        long userId = CurrentSession.requireHospital().userId();
        return new SavedFilterList(mapper.findByUser(userId).stream().map(this::toView).toList());
    }

    /** 保存为个人筛选（A-PAT-015：可命名）。 */
    public SavedFilterView create(String name, JsonNode payload) {
        long userId = CurrentSession.requireHospital().userId();
        String safeName = sanitizeName(name);
        String normalized = normalizePayload(payload);
        if (mapper.countByUser(userId) >= MAX_PER_USER) {
            throw invalid("保存的筛选（每人最多 " + MAX_PER_USER + " 条）");
        }
        SavedFilterInsert insert = new SavedFilterInsert(userId, safeName, normalized);
        mapper.insert(insert);
        SavedFilterMapper.SavedFilterRow row = mapper.findOwn(insert.getId(), userId);
        audit.append(
                AuditEntry.operation("followup", "SAVED_FILTER_CREATE")
                        .objectType("SAVED_FILTER")
                        .objectId(String.valueOf(insert.getId()))
                        .description("保存个人筛选「" + safeName + "」")
                        .success()
                        .build());
        return toView(row);
    }

    /** 修改（重命名 / 覆盖条件；A-PAT-015「可命名、修改」）。 */
    public SavedFilterView update(long id, String name, JsonNode payload) {
        long userId = CurrentSession.requireHospital().userId();
        requireOwn(id, userId);
        String safeName = name == null ? null : sanitizeName(name);
        String normalized = payload == null ? null : normalizePayload(payload);
        if (safeName == null && normalized == null) {
            throw invalid("name / payload（至少提供一项）");
        }
        SavedFilterMapper.SavedFilterRow current = mapper.findOwn(id, userId);
        String nextName = safeName == null ? current.name() : safeName;
        String nextPayload = normalized == null ? current.payload() : normalized;
        mapper.updateOwn(id, userId, nextName, nextPayload);
        audit.append(
                AuditEntry.operation("followup", "SAVED_FILTER_UPDATE")
                        .objectType("SAVED_FILTER")
                        .objectId(String.valueOf(id))
                        .description(
                                "修改个人筛选「"
                                        + nextName
                                        + "」"
                                        + (safeName != null ? "（重命名）" : "")
                                        + (normalized != null ? "（覆盖条件）" : ""))
                        .success()
                        .build());
        return toView(mapper.findOwn(id, userId));
    }

    /** 删除（本人的行；不存在 / 他人的一律 40400，不暴露存在性）。 */
    public void delete(long id) {
        long userId = CurrentSession.requireHospital().userId();
        SavedFilterMapper.SavedFilterRow row = requireOwn(id, userId);
        mapper.deleteOwn(id, userId);
        audit.append(
                AuditEntry.operation("followup", "SAVED_FILTER_DELETE")
                        .objectType("SAVED_FILTER")
                        .objectId(String.valueOf(id))
                        .description("删除个人筛选「" + row.name() + "」")
                        .success()
                        .build());
    }

    // ---------------------------------------------------------------- 内部

    private SavedFilterMapper.SavedFilterRow requireOwn(long id, long userId) {
        SavedFilterMapper.SavedFilterRow row = mapper.findOwn(id, userId);
        if (row == null) {
            throw new ApiException(404, 40400, "筛选不存在");
        }
        return row;
    }

    private SavedFilterView toView(SavedFilterMapper.SavedFilterRow row) {
        return new SavedFilterView(
                String.valueOf(row.id()),
                row.name(),
                readTree(row.payload()),
                row.updatedAt() == null ? null : row.updatedAt().toString());
    }

    /**
     * payload 归一化：只保留 {filters?, condition?}（其余键 40001）；检索词 keyword 显式拒绝； 条件树必须可编译（字段目录白名单 + 参数化）。
     */
    private String normalizePayload(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw invalid("payload（须为对象）");
        }
        ObjectNode normalized = json.createObjectNode();
        boolean any = false;
        for (Iterator<String> keys = payload.fieldNames(); keys.hasNext(); ) {
            String key = keys.next();
            switch (key) {
                case "keyword" -> throw invalid("payload（检索词不进保存的筛选）");
                case "filters" -> {
                    JsonNode filters = payload.get(key);
                    if (!filters.isObject()) {
                        throw invalid("payload.filters（须为对象）");
                    }
                    for (Iterator<String> filterKeys = filters.fieldNames();
                            filterKeys.hasNext(); ) {
                        String filterKey = filterKeys.next();
                        if (!FILTER_KEYS.contains(filterKey)) {
                            throw invalid("payload.filters." + filterKey + "（未知筛选键）");
                        }
                    }
                    if (!filters.isEmpty()) {
                        normalized.set("filters", filters);
                        any = true;
                    }
                }
                case "condition" -> {
                    // 结构化条件树：保存时即按当前字段目录编译校验（字典字段白名单 + 参数化，A-NFR-009）
                    AdvancedConditionCompiler.compile(payload.get(key), searchFields.specs());
                    normalized.set("condition", payload.get(key));
                    any = true;
                }
                default -> throw invalid("payload." + key + "（未知键，只允许 filters / condition）");
            }
        }
        if (!any) {
            throw invalid("payload（至少包含一个筛选条件）");
        }
        return normalized.toString();
    }

    private static String sanitizeName(String name) {
        if (name == null) {
            throw invalid("name（必填）");
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > NAME_MAX) {
            throw invalid("name（1–" + NAME_MAX + " 个字符）");
        }
        return trimmed;
    }

    private JsonNode readTree(String payload) {
        try {
            return json.readTree(payload);
        } catch (JsonProcessingException e) {
            // 库内 JSON 由本服务写入，解析失败即数据缺陷
            throw new ApiException(500, 50000, "保存的筛选数据损坏");
        }
    }

    private static ApiException invalid(String field) {
        return new ApiException(400, 40001, "参数不正确：" + field);
    }
}
