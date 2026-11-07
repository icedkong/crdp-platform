package com.crdp.followup.compare.app;

import com.crdp.followup.compare.domain.CompareRuleType;
import com.crdp.followup.compare.infra.mapper.CompareFeatureMapper;
import com.crdp.followup.compare.infra.mapper.CompareRuleMapper;
import com.crdp.followup.compare.infra.mapper.CompareRuleRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 录入时对比标红规则维护（E17-T01 / A-FU-027、C-FU-011 随 E21；docs/api/followup.md §4，配置页 /system/abnormal-rule）。
 *
 * <p>租户管理员只做两件事：开关（status）与调阈值（params 的阈值键，按 {@link CompareRuleType} 键集校验）；
 * 规则条目（字段、类型、结构）由预置迁移落库，不提供增删改结构。每次 PUT 写 COMPARE_RULE_UPDATE 配置审计（PLT-AUD-001
 * 口径：配置变更必审计，含前后值）。功能开关 compare_highlight 关闭 → 40301、 前端隐藏入口（AGENTS.md 规则 7）；无行视为默认开。
 *
 * <p>求值接入：上一次记录解析随 E17-T02（{@link CompareSourceService}）、规则下发与前后端实时求值随 E17-T03（{@link
 * #evaluationRules} + {@link com.crdp.followup.compare.domain.CompareEvaluator}）； 结果持久化与规则变更重算随
 * E17-T04。
 */
@Service
public class CompareRuleService {

    /** 功能开关码（cfg_feature，种子 V100：A 默认开；tech-plan §10「模块功能开关」）。 */
    public static final String FEATURE_CODE = "compare_highlight";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CompareRuleMapper rules;
    private final CompareFeatureMapper features;
    private final AuditLogger audit;

    public CompareRuleService(
            CompareRuleMapper rules, CompareFeatureMapper features, AuditLogger audit) {
        this.rules = rules;
        this.features = features;
        this.audit = audit;
    }

    /** 规则视图（followup.md §4）：params 已解析为对象；字段 / 表单显示名由查询带出。 */
    public record RuleView(
            long id,
            String formCode,
            String formName,
            String ruleCode,
            String name,
            String categoryKey,
            String targetField,
            String fieldLabel,
            String fieldUnit,
            String ruleType,
            String ruleTypeLabel,
            Map<String, Object> params,
            String baselineFormCode,
            String baselineField,
            String baselineFieldLabel,
            String message,
            boolean enabled,
            String updatedAt) {}

    /** PUT 请求体：全量覆盖开关与阈值参数（params 键集按 rule_type 校验）。 */
    public record RuleUpdate(Boolean enabled, Map<String, Object> params) {}

    /** 规则清单（form_code, status, sort_no 序）；formCode 为空返回全部表单。 */
    public List<RuleView> list(String formCode) {
        requireFeatureEnabled();
        List<RuleView> items = new ArrayList<>();
        for (CompareRuleRow row : rules.findAll(blankToNull(formCode))) {
            items.add(toView(row));
        }
        return items;
    }

    /**
     * 求值规则（E17-T03）：该表单的启用规则（前端实时标红 + 后端保存时复算共用；tech-plan §11 「规则定义以 JSON 下发到前端实时求值」）。与 {@link
     * #list} 不同：不检查配置权限（记录页 全员可用，只有启用行）、功能开关关闭返回空表（记录详情本身正常返回，AGENTS.md 规则 7）。
     */
    public List<RuleView> evaluationRules(String formCode) {
        if (Boolean.FALSE.equals(features.findEnabled(FEATURE_CODE))) {
            return List.of();
        }
        List<RuleView> items = new ArrayList<>();
        for (CompareRuleRow row : rules.findAll(blankToNull(formCode))) {
            if ("ENABLED".equals(row.status())) {
                items.add(toView(row));
            }
        }
        return items;
    }

    /** 修改单条规则：功能开关 → 存在性（40400）→ 键集与阈值校验（40001）→ 落库 → 审计；返回更新后的视图。 */
    @Transactional
    public RuleView update(long id, RuleUpdate request) {
        requireFeatureEnabled();
        if (request == null || request.enabled() == null) {
            throw invalid("enabled");
        }
        CompareRuleRow existing = rules.find(id).orElseThrow(() -> notFound(id));
        CompareRuleType type =
                CompareRuleType.fromCode(existing.ruleType())
                        .orElseThrow(
                                () -> invalid("rule_type（未知规则类型 " + existing.ruleType() + "）"));
        Map<String, Object> params = type.validateParams(request.params());

        String status = request.enabled() ? "ENABLED" : "DISABLED";
        long operatorId = CurrentSession.requireHospital().userId();
        RuleView before = toView(existing);
        rules.update(id, status, writeJson(params), operatorId);
        RuleView after = toView(rules.find(id).orElseThrow(() -> notFound(id)));

        audit.append(
                AuditEntry.operation("followup", "COMPARE_RULE_UPDATE")
                        .objectType("FU_COMPARE_RULE")
                        .objectId(existing.ruleCode())
                        .description("修改对比提示规则：" + existing.name())
                        .beforeJson(compact(before))
                        .afterJson(compact(after))
                        .build());
        return after;
    }

    // ---------------------------------------------------------------- 内部

    /** compare_highlight 关闭 → 40301（无行视为默认开，tech-plan §10 A 默认开）。 */
    private void requireFeatureEnabled() {
        Boolean enabled = features.findEnabled(FEATURE_CODE);
        if (Boolean.FALSE.equals(enabled)) {
            throw new ApiException(403, 40301, "对比标红功能未启用");
        }
    }

    private RuleView toView(CompareRuleRow row) {
        return new RuleView(
                row.id(),
                row.formCode(),
                row.formName(),
                row.ruleCode(),
                row.name(),
                row.categoryKey(),
                row.targetField(),
                row.fieldLabel(),
                row.fieldUnit(),
                row.ruleType(),
                CompareRuleType.fromCode(row.ruleType())
                        .map(CompareRuleType::label)
                        .orElse(row.ruleType()),
                readParams(row.params()),
                row.baselineFormCode(),
                row.baselineField(),
                row.baselineFieldLabel(),
                row.message(),
                "ENABLED".equals(row.status()),
                row.updatedAt() == null ? null : row.updatedAt().toString());
    }

    private static Map<String, Object> readParams(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException broken) {
            throw new IllegalStateException("对比规则 params 解析失败（id 脏数据）", broken);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException broken) {
            throw new IllegalStateException("对比规则 params 序列化失败", broken);
        }
    }

    /** 审计前后值：只记开关与阈值（规则结构为预置值，不重复记录）。 */
    private static String compact(RuleView view) {
        return "{\"enabled\":" + view.enabled() + ",\"params\":" + writeJson(view.params()) + "}";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApiException notFound(long id) {
        return new ApiException(404, 40400, "对比规则不存在");
    }

    private static ApiException invalid(String field) {
        return new ApiException(400, 40001, "参数不合法：" + field);
    }
}
