package com.crdp.form.field.app;

import com.crdp.form.field.domain.FieldDictStatus;
import com.crdp.form.field.domain.FieldType;
import com.crdp.form.field.infra.mapper.FieldRow;
import com.crdp.form.field.infra.mapper.FormFieldMapper;
import com.crdp.form.field.infra.mapper.FormRow;
import com.crdp.form.field.infra.mapper.OptionRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E11-T01 / A-FLD-001、A-FLD-002、C-FLD-001：字段字典（租户库 fe_form / fe_field / fe_option_set /
 * fe_option，权限 followup:field:edit，接口 docs/api/form.md §3）。
 *
 * <p>模型口径（tech-plan §6「元数据模型」）：字段的稳定键、字段编码（库列名）、类型、存储方式、敏感标记、
 * 硬范围创建后不可改（A-FLD-008「编码不可改」——更新语句根本不含这些列）；显示名与编码分离
 * （A-FLD-002）：显示名租户可调，选项页面显示显示名、数据库存编码。字段可启停：停用不删除，模板下发与 校验都只取启用字段（E11-T02 的 FormService 读取时过滤）。
 *
 * <p>维护范围（form.md §3）：显示名、帮助说明、单位显示、软范围（提示范围）、必填、选项（显示名 / 排序 / 停用 /
 * 新增，不能删除、不能改选项编码）、状态（启停）。写操作写租户审计 FIELD_DICT_UPDATE（含前后值； 状态启停在描述中单独点明，AGENTS.md 规则 4「状态变更写审计」），并写
 * fe_field_dict_log 变更记录（E11-T08 / A-FLD-009、C-FLD-009：每次字典调整的前后值、操作人、 时间；与 cfg_param_log
 * 同款双写）。停用选项不删除——schema 下发带停用标记（下拉不再出现），历史记录的值 照常显示（C-FLD-009）。
 */
@Service
public class FieldDictionaryService {

    private static final int LABEL_MAX = 128;
    private static final int HELP_MAX = 500;
    private static final int UNIT_MAX = 32;

    /** 变更记录查询上限（倒序最近 100 条，与 TenantDictService / TenantParamService 同口径）。 */
    private static final int LOG_LIMIT = 100;

    private final FormFieldMapper mapper;
    private final AuditLogger audit;
    private final ObjectMapper json = new ObjectMapper();

    public FieldDictionaryService(FormFieldMapper mapper, AuditLogger audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 视图

    /** 表单摘要（form.md §1 的列表口径；完整 FormSchema 随 E11-T02）。 */
    public record FormSummaryView(
            String code, String name, String usage, int currentVersion, String status) {}

    /** 数值范围（软 / 硬同构；空端不限制）。 */
    public record RangeView(Double min, Double max) {}

    /** 选项视图：code 是数据库存的编码（0=否 / 1=是这类），label 是页面显示名（A-FLD-002）。 */
    public record OptionView(
            int code,
            String label,
            Integer ordinal,
            boolean exclusive,
            boolean needsDetail,
            int sortNo,
            String status) {}

    /** 字段视图：key=稳定键、code=字段编码（导入导出名）、label=显示名；条件与派生为原始条件树对象。 */
    public record FieldView(
            String formCode,
            String formName,
            String key,
            String code,
            String label,
            String categoryKey,
            String categoryLabel,
            String type,
            String unit,
            Integer decimals,
            RangeView hardRange,
            RangeView softRange,
            List<OptionView> options,
            boolean required,
            Object visibleWhen,
            Object requiredWhen,
            Object derivedDef,
            boolean sensitive,
            String storage,
            String help,
            int sortNo,
            String status,
            String updatedAt) {}

    /** 选项维护：code 已存在为改（显示名 / 排序 / 停用），不存在为新增；编码与删除都不支持。 */
    public record OptionUpdate(Integer code, String label, Integer sortNo, String status) {}

    /** 字段维护（全量覆盖可维护列；键、编码、类型、硬范围、敏感标记不在请求上，天然不可改）。 */
    public record FieldUpdate(
            String label,
            String helpText,
            String unit,
            RangeView softRange,
            Boolean required,
            String status,
            List<OptionUpdate> options) {}

    /** 字段字典变更记录行（A-FLD-009、C-FLD-009：前后值、操作人、时间；倒序）。 */
    public record FieldLogView(
            long id,
            String formCode,
            String fieldKey,
            String action,
            String beforeJson,
            String afterJson,
            Long operatorId,
            String operatorName,
            String createdAt) {}

    // ---------------------------------------------------------------- 读取

    /** 表单清单（登录即可，供页面下拉与模板选择）。 */
    public List<FormSummaryView> forms() {
        List<FormSummaryView> views = new ArrayList<>();
        for (FormRow row : mapper.findForms()) {
            views.add(
                    new FormSummaryView(
                            row.code(),
                            row.name(),
                            row.usageType(),
                            row.currentVersion(),
                            row.status()));
        }
        return views;
    }

    /** 按表单（可再按分类）列字段（A 规格「主要页面」：按分类列出字段）。 */
    public List<FieldView> fields(String formCode, String categoryKey) {
        requireForm(formCode);
        FormRow form = mapper.findForm(formCode);
        Map<String, String> categoryLabels = categoryLabels(form);
        List<FieldView> views = new ArrayList<>();
        for (FieldRow row : mapper.findFields(formCode, categoryKey)) {
            views.add(toView(row, form.name(), categoryLabels));
        }
        return views;
    }

    // ---------------------------------------------------------------- 维护

    /**
     * 维护单个字段（PUT /fields/{key}）：全量覆盖显示名 / 帮助 / 单位 / 软范围 / 必填 / 状态，可一并维护 选项；键与编码不可改（请求不携带）。写
     * FIELD_DICT_UPDATE 审计（前后值，状态启停在描述点明）。
     */
    @Transactional
    public FieldView update(String formCode, String fieldKey, FieldUpdate update) {
        if (update == null) {
            throw bad("请求体不能为空");
        }
        FormRow form = requireForm(formCode);
        FieldRow existing = mapper.findField(formCode, fieldKey);
        if (existing == null) {
            throw new ApiException(404, 40400, "字段不存在");
        }
        FieldType type = parseType(existing.type());
        String label = requireLabel(update.label());
        String helpText = optionalLength(update.helpText(), HELP_MAX, "帮助说明");
        String unit = optionalLength(update.unit(), UNIT_MAX, "单位");
        String status = requireStatus(update.status());
        RangeView softRange = validateRange(update.softRange(), "提示范围");
        boolean required = Boolean.TRUE.equals(update.required());
        long operator = CurrentSession.requireHospital().userId();

        FieldView before = toView(existing, form.name(), categoryLabels(form));
        mapper.updateField(
                formCode,
                fieldKey,
                label,
                helpText,
                unit,
                writeJson(softRange),
                required,
                status,
                operator);
        applyOptionUpdates(existing, type, update.options(), operator);
        FieldRow after = mapper.findField(formCode, fieldKey);
        FieldView view = toView(after, form.name(), categoryLabels(form));

        StringBuilder description =
                new StringBuilder("维护字段字典：" + formCode + "." + fieldKey + "（" + label + "）");
        if (!before.status().equals(view.status())) {
            description.append(
                    FieldDictStatus.DISABLED.equals(view.status()) ? "；字段已停用" : "；字段已启用");
        }
        audit.append(
                AuditEntry.operation("form", "FIELD_DICT_UPDATE")
                        .objectType("FE_FIELD")
                        .objectId(formCode + "." + fieldKey)
                        .description(description.toString())
                        .beforeJson(writeJson(before))
                        .afterJson(writeJson(view))
                        .build());
        // E11-T08 / A-FLD-009：变更记录（前后值 + 操作人 + 时间），与审计双写（同 cfg_param_log 口径）
        mapper.insertFieldDictLog(
                formCode, fieldKey, "FIELD_UPDATE", writeJson(before), writeJson(view), operator);
        return view;
    }

    /**
     * 字段字典变更记录（A-FLD-009、C-FLD-009）：倒序最近 {@value LOG_LIMIT} 条，含前后值快照、 操作人（联查 sys_user 姓名）与时间；字段 /
     * 表单不存在 40400。
     */
    public List<FieldLogView> logs(String formCode, String fieldKey) {
        requireForm(formCode);
        if (mapper.findField(formCode, fieldKey) == null) {
            throw new ApiException(404, 40400, "字段不存在");
        }
        List<FieldLogView> views = new ArrayList<>();
        for (FormFieldMapper.FieldDictLogRow row :
                mapper.findFieldDictLogs(formCode, fieldKey, LOG_LIMIT)) {
            views.add(
                    new FieldLogView(
                            row.id(),
                            row.formCode(),
                            row.fieldKey(),
                            row.action(),
                            row.beforeJson(),
                            row.afterJson(),
                            row.operatorId(),
                            row.operatorName(),
                            Time.format(row.createdAt())));
        }
        return views;
    }

    // ---------------------------------------------------------------- 选项维护

    /** 选项维护：已存在编码 → 改显示名 / 排序 / 停用；新编码 → 新增；重复编码与删除不支持。 */
    private void applyOptionUpdates(
            FieldRow field, FieldType type, List<OptionUpdate> options, long operator) {
        if (options == null || options.isEmpty()) {
            return;
        }
        if (field.optionSetCode() == null || !type.hasOptionSet()) {
            throw bad("该字段不是选项型字段，不能维护选项");
        }
        Set<Integer> seen = new HashSet<>();
        Map<Integer, OptionRow> existingByCode = new LinkedHashMap<>();
        for (OptionRow option : mapper.findOptions(field.optionSetCode())) {
            existingByCode.put(option.optionCode(), option);
        }
        for (OptionUpdate option : options) {
            if (option == null || option.code() == null) {
                throw bad("选项编码不能为空");
            }
            if (!seen.add(option.code())) {
                throw bad("选项编码重复：" + option.code());
            }
            String label = requireLabel(option.label());
            OptionRow existingOption = existingByCode.get(option.code());
            int sortNo =
                    option.sortNo() != null
                            ? option.sortNo()
                            : existingOption != null ? existingOption.sortNo() : 0;
            String status =
                    option.status() != null
                            ? requireStatus(option.status())
                            : existingOption != null
                                    ? existingOption.status()
                                    : FieldDictStatus.ENABLED;
            if (existingOption != null) {
                mapper.updateOption(
                        field.optionSetCode(), option.code(), label, sortNo, status, operator);
            } else {
                mapper.insertOption(
                        field.optionSetCode(), option.code(), label, sortNo, status, operator);
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    private FormRow requireForm(String formCode) {
        if (formCode == null || formCode.isBlank()) {
            throw bad("表单编码不能为空");
        }
        FormRow form = mapper.findForm(formCode);
        if (form == null) {
            throw new ApiException(404, 40400, "表单不存在");
        }
        return form;
    }

    private FieldType parseType(String code) {
        return FieldType.ofCode(code)
                .orElseThrow(() -> new IllegalStateException("未知字段类型：" + code));
    }

    private FieldView toView(FieldRow row, String formName, Map<String, String> categoryLabels) {
        List<OptionView> options = List.of();
        if (row.optionSetCode() != null) {
            List<OptionView> loaded = new ArrayList<>();
            for (OptionRow option : mapper.findOptions(row.optionSetCode())) {
                loaded.add(
                        new OptionView(
                                option.optionCode(),
                                option.label(),
                                option.ordinal(),
                                option.exclusive(),
                                option.needsDetail(),
                                option.sortNo(),
                                option.status()));
            }
            options = loaded;
        }
        return new FieldView(
                row.formCode(),
                formName,
                row.fieldKey(),
                row.fieldCode(),
                row.label(),
                row.categoryKey(),
                categoryLabels.getOrDefault(row.categoryKey(), row.categoryKey()),
                row.type(),
                row.unit(),
                row.decimals(),
                readRange(row.hardRange()),
                readRange(row.softRange()),
                options,
                row.required(),
                readTree(row.visibleWhen()),
                readTree(row.requiredWhen()),
                readTree(row.derivedDef()),
                row.sensitiveFlag(),
                row.storageType(),
                row.helpText(),
                row.sortNo(),
                row.status(),
                Time.format(row.updatedAt()));
    }

    /** fe_form.categories（[{key,label,sortNo}]）→ key→label；脏数据时回落用 key 本身。 */
    private Map<String, String> categoryLabels(FormRow form) {
        Map<String, String> labels = new LinkedHashMap<>();
        JsonNode node = readTree(form.categories());
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.hasNonNull("key")) {
                    labels.put(item.get("key").asText(), item.path("label").asText(null));
                }
            }
        }
        return labels;
    }

    private RangeView readRange(String raw) {
        JsonNode node = readTree(raw);
        if (node == null || node.isNull() || node.isEmpty()) {
            return null;
        }
        return new RangeView(
                node.hasNonNull("min") ? node.get("min").asDouble() : null,
                node.hasNonNull("max") ? node.get("max").asDouble() : null);
    }

    private JsonNode readTree(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(raw);
            return node instanceof NullNode ? null : node;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("字段元数据 JSON 解析失败", e);
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("字段元数据 JSON 序列化失败", e);
        }
    }

    private String requireLabel(String label) {
        if (label == null || label.isBlank()) {
            throw bad("显示名不能为空");
        }
        if (label.length() > LABEL_MAX) {
            throw bad("显示名不能超过 " + LABEL_MAX + " 字");
        }
        return label.trim();
    }

    private String optionalLength(String value, int max, String field) {
        if (value == null) {
            return null;
        }
        if (value.length() > max) {
            throw bad(field + "不能超过 " + max + " 字");
        }
        return value.isBlank() ? null : value.trim();
    }

    private String requireStatus(String status) {
        if (!FieldDictStatus.isValid(status)) {
            throw bad("状态只认 ENABLED / DISABLED");
        }
        return status;
    }

    private RangeView validateRange(RangeView range, String name) {
        if (range == null) {
            return null;
        }
        if (range.min() != null && range.max() != null && range.min() > range.max()) {
            throw bad(name + "的最小值不能大于最大值");
        }
        return range;
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

    /** 时间展示口径（北京时间 ISO-8601 带时区，与 TenantDictService 一致）。 */
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
