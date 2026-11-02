package com.crdp.form.record.app;

import com.crdp.form.field.infra.mapper.FieldRow;
import com.crdp.form.field.infra.mapper.FormFieldMapper;
import com.crdp.form.field.infra.mapper.FormRow;
import com.crdp.form.field.infra.mapper.OptionRow;
import com.crdp.form.record.infra.mapper.FormRecordInsert;
import com.crdp.form.record.infra.mapper.FormRecordMapper;
import com.crdp.form.record.infra.mapper.FormRecordRow;
import com.crdp.form.record.infra.mapper.FormVersionInsert;
import com.crdp.form.record.infra.mapper.FormVersionRow;
import com.crdp.form.record.infra.mapper.VersionFieldRow;
import com.crdp.form.rule.app.FieldIssue;
import com.crdp.form.rule.app.FormConditionEngine;
import com.crdp.form.rule.app.FormRuleEngine;
import com.crdp.form.rule.app.RuleResult;
import com.crdp.form.rule.infra.mapper.FormRuleMapper;
import com.crdp.form.rule.infra.mapper.RuleRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E11-T02 / A-NFR-002、C-NFR-002：JSON 存储与 FormService——模板按版本读、记录按版本读写（验收「表单按版本 读写，旧版本可读」）。
 *
 * <p>模板版本：publish 把当时的分类与<b>启用字段</b>固化进 fe_form_version / fe_form_version_field 快照并前移
 * fe_form.current_version；快照不可变，旧版本永久可读（旧记录按其保存时版本渲染与校验，模板改版——停用字段、 改必填 /
 * 范围、加新字段——不影响旧版本口径）。选项（fe_option）不进快照：编码不可改、停用不删除，显示名读取时现取。
 *
 * <p>记录（fe_record）：data / data_meta JSON，键 = 稳定键；写入只经本服务（{@link FormDataValidator} 做键与
 * 类型检查，tech-plan「禁止绕过服务直接写 JSON」）。template_version 创建时固化、保存不升级（升级属业务重映射， 随 E13+）；version
 * 乐观锁，baseVersion 不一致返回 40900 并附更新人与两边不同字段（E25 草稿接口复用本口径）。
 * 草稿不逐次写审计（技术方案「服务器自动保存」），提交与覆盖他人版本的审计随业务接口（E15 / E25）。
 *
 * <p>E11-T04（A-FLD-003 / A-FLD-006 / C-FLD-007）：写入闸门在类型检查后加<b>硬范围</b>拦截（超硬范围不可保存， 42200 带
 * fieldErrors）；软范围与跨字段规则不在草稿闸门里（form.md §2 草稿语义），提交 / 保存修改前调 {@link #validateForSubmit(String, int,
 * Map)} 再执行一次（硬规则违反 42200，软规则返回警告由调用方做确认流）。
 *
 * <p>E11-T03（A-FLD-004 / C-FLD-005）：提交校验追加<b>条件必填</b>（有效必填 = 显示 &&（required || requiredWhen），缺失
 * errors {@code rule=required}）并对<b>隐藏字段的值做掩蔽</b>（不查范围 / 跨字段）；上下文变量（C 的 device.type 等）由调用方随 {@link
 * #validateForSubmit(String, int, Map, Map)} 传入。tech-plan §6「提交时被隐藏字段的值清空」由 {@link
 * #stripHiddenFields(String, int, Map, Map)} 提供（提交流程调用；清空写 fe_field_history 随 E11-T06）。
 */
@Service
public class FormService {

    private final FormFieldMapper fieldMapper;
    private final FormRecordMapper recordMapper;
    private final FormDataValidator validator;
    private final FormRuleMapper ruleMapper;
    private final FormRuleEngine ruleEngine;
    private final FormConditionEngine conditionEngine;
    private final AuditLogger audit;
    private final ObjectMapper json = new ObjectMapper();

    public FormService(
            FormFieldMapper fieldMapper,
            FormRecordMapper recordMapper,
            FormDataValidator validator,
            FormRuleMapper ruleMapper,
            FormRuleEngine ruleEngine,
            FormConditionEngine conditionEngine,
            AuditLogger audit) {
        this.fieldMapper = fieldMapper;
        this.recordMapper = recordMapper;
        this.validator = validator;
        this.ruleMapper = ruleMapper;
        this.ruleEngine = ruleEngine;
        this.conditionEngine = conditionEngine;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 视图（components.md §10.1
    // FormSchema）

    /**
     * FormSchema（§10.1 契约 + name / usage 便于直接展示；rules 为 fe_rule 跨字段规则 E11-T04、compareRules 随 E17）。
     */
    public record SchemaView(
            String code,
            String name,
            String usage,
            int version,
            List<CategoryView> categories,
            List<RuleView> rules) {}

    /**
     * 跨字段规则下发口径（components.md §10.1 rules[]；与 42200 fieldErrors 同源：key / categoryKey / rule= id）。
     */
    public record RuleView(
            String id,
            String severity,
            String categoryKey,
            String key,
            Object params,
            String message) {}

    /** 分类（基线「分类」/ 随访「分组」统一口径；key 为稳定锚点键）。 */
    public record CategoryView(String key, String label, int order, List<FieldView> fields) {}

    /** 数值范围（components.md §10.1 hardRange / softRange）。 */
    public record RangeView(Double min, Double max) {}

    /** 选项（编码 + 显示名 + 停用标记；显示名读取时现取，见类注释）。 */
    public record OptionItemView(
            int code,
            String label,
            Integer ordinal,
            boolean exclusive,
            boolean needsDetail,
            boolean disabled) {}

    /** FormField（§10.1）：key = 稳定键、code = 字段编码（导入导出名）、label = 显示名。 */
    public record FieldView(
            String key,
            String code,
            String label,
            String type,
            String unit,
            Integer decimals,
            RangeView hardRange,
            RangeView softRange,
            List<OptionItemView> optionSet,
            boolean required,
            Object visibleWhen,
            Object requiredWhen,
            Object derived,
            boolean sensitive,
            String storage,
            String help) {}

    /** 发布结果。 */
    public record PublishView(
            String code, String name, int version, int fieldCount, String publishedAt) {}

    /** 表单数据记录视图（followup.md §3 记录形状的引擎子集：formVersion 即 templateVersion）。 */
    public record RecordView(
            long id,
            String formCode,
            int templateVersion,
            String status,
            int version,
            Map<String, Object> data,
            Map<String, Object> dataMeta,
            String createdAt,
            String updatedAt) {}

    /** 保存请求：data 全量提交（草稿语义），baseVersion 必填（乐观锁）。 */
    public record SaveRecordCommand(
            Integer baseVersion, Map<String, Object> data, Map<String, Object> dataMeta) {}

    /** 40900 冲突详情（README §错误码：带冲突详情；技术方案：附更新人、更新时间与两边不同字段）。 */
    public record VersionConflictView(
            int serverVersion, List<String> changedFields, String updatedBy, String updatedAt) {}

    // ---------------------------------------------------------------- 模板：按版本读（旧版本可读）

    /** FormSchema：version 省略取当前发布版；版本不存在 40400。rules 为 fe_rule 当前启用规则（实时读取，不进快照）。 */
    public SchemaView schema(String formCode, Integer version) {
        FormRow form = requireForm(formCode);
        int resolved = version == null ? form.currentVersion() : version;
        FormVersionRow snapshot = requireVersion(form, resolved);
        return buildSchema(form, snapshot, recordMapper.findVersionFields(formCode, resolved));
    }

    // ---------------------------------------------------------------- 模板：发布新版本

    /**
     * 发布（POST /forms/{code}/publish，followup:field:edit）：把当前字典固化成新版本快照并前移 current_version。写
     * FORM_PUBLISH 审计。发布不改动字典本身；没有变化也允许发布（版本是显式动作）。
     */
    @Transactional
    public PublishView publish(String formCode) {
        FormRow form = requireForm(formCode);
        if (!"ENABLED".equals(form.status())) {
            throw new ApiException(400, 40001, "表单已停用，不能发布新版本");
        }
        long operator = CurrentSession.requireHospital().userId();
        int fromVersion = form.currentVersion();
        int newVersion = fromVersion + 1;

        // 先占位（current_version 条件更新），并发发布的后一个在此失败
        if (recordMapper.bumpCurrentVersion(formCode, fromVersion, newVersion) != 1) {
            throw new ApiException(409, 40900, "模板版本已被他人更新，请刷新后重试");
        }
        int fieldCount = countEnabledFields(formCode);
        FormVersionInsert insert =
                new FormVersionInsert(
                        formCode, newVersion, form.categories(), fieldCount, operator);
        recordMapper.insertVersion(insert);
        int snapshotted = recordMapper.snapshotFields(formCode, newVersion);
        if (snapshotted != fieldCount) {
            throw new IllegalStateException("发布快照与启用字段数不一致：" + snapshotted + " / " + fieldCount);
        }

        audit.append(
                AuditEntry.operation("form", "FORM_PUBLISH")
                        .objectType("FE_FORM")
                        .objectId(formCode)
                        .description(
                                "发布表单模板新版本："
                                        + formCode
                                        + " v"
                                        + fromVersion
                                        + " → v"
                                        + newVersion
                                        + "（固化启用字段 "
                                        + fieldCount
                                        + " 个；旧版本快照保留可读）")
                        .beforeJson("{\"currentVersion\":" + fromVersion + "}")
                        .afterJson(
                                "{\"currentVersion\":"
                                        + newVersion
                                        + ",\"fieldCount\":"
                                        + fieldCount
                                        + "}")
                        .build());

        FormVersionRow published = recordMapper.findVersion(formCode, newVersion);
        return new PublishView(
                formCode,
                form.name(),
                newVersion,
                fieldCount,
                Time.format(published == null ? null : published.publishedAt()));
    }

    // ---------------------------------------------------------------- 记录：JSON 读写（版本固化 + 乐观锁）

    /** 新建记录（DRAFT）：template_version 固化为当前发布版；值经 {@link FormDataValidator} 与硬范围闸门检查。 */
    @Transactional
    public RecordView createRecord(
            String formCode, Map<String, Object> data, Map<String, Object> dataMeta) {
        FormRow form = requireForm(formCode);
        FormVersionRow snapshot = requireVersion(form, form.currentVersion());
        List<VersionFieldRow> fields =
                recordMapper.findVersionFields(formCode, form.currentVersion());
        validator.validateData(data, fields);
        enforceHardRange(data, fields);

        long operator = CurrentSession.requireHospital().userId();
        FormRecordInsert insert =
                new FormRecordInsert(
                        formCode,
                        snapshot.version(),
                        writeJson(data),
                        writeJson(dataMeta),
                        operator);
        recordMapper.insertRecord(insert);
        return record(insert.getId());
    }

    /** 读取记录（含其保存时的模板版本；不存在 40400）。 */
    public RecordView record(long id) {
        FormRecordRow row = requireRecord(id);
        return toView(row);
    }

    /**
     * 保存修改（草稿语义：只做键、类型与硬范围检查——硬范围属写入闸门（A-FLD-003「超出硬范围不可保存」、tech-plan §6），软范围与跨字段不做，form.md
     * §2「草稿保存只做类型检查，不做必填与跨字段校验」）。
     *
     * <p>baseVersion 必填且须等于当前 version，否则 40900（附更新人、更新时间、两边不同字段）；已提交记录走 修改流程（E15 edit-session），这里
     * 40901。值按<b>记录的模板版本</b>校验（不是最新版）。
     */
    @Transactional
    public RecordView saveRecord(long id, SaveRecordCommand command) {
        if (command == null || command.baseVersion() == null) {
            throw new ApiException(400, 40001, "保存必须携带 baseVersion（乐观锁）");
        }
        FormRecordRow row = requireRecord(id);
        if ("SUBMITTED".equals(row.status())) {
            throw new ApiException(409, 40901, "记录已提交，修改请走已提交记录修改流程");
        }
        if (row.version() != command.baseVersion()) {
            throw conflict(row, command);
        }
        List<VersionFieldRow> fields =
                recordMapper.findVersionFields(row.formCode(), row.templateVersion());
        if (fields.isEmpty()) {
            throw new ApiException(404, 40400, "记录的模板版本不存在（formCode=" + row.formCode() + "）");
        }
        validator.validateData(command.data(), fields);
        enforceHardRange(command.data(), fields);

        long operator = CurrentSession.requireHospital().userId();
        int updated =
                recordMapper.updateRecord(
                        id,
                        command.baseVersion(),
                        writeJson(command.data()),
                        writeJson(command.dataMeta()),
                        operator);
        if (updated != 1) {
            FormRecordRow current = requireRecord(id);
            throw conflict(current, command);
        }
        return record(id);
    }

    /**
     * 提交 / 保存修改前的完整规则校验（form.md §2「后端在提交 / 保存修改时用同一份规则定义再执行一次」；业务接口 E15 / E25 在状态流转前调用）。
     *
     * <p>执行范围（硬 + 软）与跨字段规则（按 fe_rule severity）：硬校验违反抛 42200（data.fieldErrors 与前端提示 同构）；软范围 /
     * 软规则命中<b>不拦截</b>，作为警告返回（A-FLD-003「提示但可保存」、C-FLD-007「黄色提示并要求确认」），由调用方 走确认流（确认人记
     * data_meta，tech-plan §6）。按记录的模板版本取字段口径。E11-T03 追加<b>条件必填</b>（缺失 {@code
     * rule=required}）与<b>隐藏字段掩蔽</b> （残留值不查范围 / 跨字段）。无上下文（ctx 条件变量全部不成立）。
     *
     * @return 软校验警告（可能为空，顺序与 FormSchema 一致）
     */
    public List<FieldIssue> validateForSubmit(
            String formCode, int templateVersion, Map<String, Object> data) {
        return validateForSubmit(formCode, templateVersion, data, Map.of());
    }

    /**
     * 同 {@link #validateForSubmit(String, int, Map)}，带条件上下文（tech-plan §6「操作数可以是上下文变量」，如医院 C 的当前器械
     * device.type / device.hasRaLead，由随访记录接口按随访日期匹配器械后传入，E21-T03）。
     */
    public List<FieldIssue> validateForSubmit(
            String formCode,
            int templateVersion,
            Map<String, Object> data,
            Map<String, Object> context) {
        FormVersionRow snapshot = requireVersion(requireForm(formCode), templateVersion);
        List<VersionFieldRow> fields = recordMapper.findVersionFields(formCode, snapshot.version());
        if (fields.isEmpty()) {
            throw new ApiException(404, 40400, "记录的模板版本不存在（formCode=" + formCode + "）");
        }
        validator.validateData(data, fields);
        RuleResult result =
                ruleEngine.validate(data, fields, ruleMapper.findRules(formCode), context);
        if (!result.ok()) {
            throw new ApiException(422, 42200, "表单校验未通过", Map.of("fieldErrors", result.errors()));
        }
        return result.warnings();
    }

    /**
     * tech-plan §6「被隐藏字段的值清空」：按当前答案与上下文求值 visibleWhen，移除隐藏字段残留的值（E11-T03）。 提交流程（E15 / E25）在 {@link
     * #validateForSubmit} 通过后、落库前调用；清空动作写 fe_field_history 随 E11-T06。 只移除有值的键（已空 / 缺键无需清空），返回清洗后的
     * data 与被清空的稳定键（cleared-hidden 口径）。
     */
    @Transactional(readOnly = true)
    public StrippedData stripHiddenFields(
            String formCode,
            int templateVersion,
            Map<String, Object> data,
            Map<String, Object> context) {
        FormVersionRow snapshot = requireVersion(requireForm(formCode), templateVersion);
        List<VersionFieldRow> fields = recordMapper.findVersionFields(formCode, snapshot.version());
        if (fields.isEmpty()) {
            throw new ApiException(404, 40400, "记录的模板版本不存在（formCode=" + formCode + "）");
        }
        Map<String, Object> values = data == null ? new LinkedHashMap<>() : data;
        Map<String, Object> cleaned = new LinkedHashMap<>(values);
        List<String> cleared = new ArrayList<>();
        for (String key : conditionEngine.hiddenFieldKeys(fields, values, context)) {
            Object value = cleaned.remove(key);
            if (value != null) {
                cleared.add(key);
            }
        }
        return new StrippedData(cleaned, List.copyOf(cleared));
    }

    /** 隐藏字段清洗结果：清洗后的 data 与被清空的稳定键（写 cleared-hidden 与字段历史用，E11-T06）。 */
    public record StrippedData(Map<String, Object> data, List<String> clearedKeys) {}

    // ---------------------------------------------------------------- 内部

    private FormRow requireForm(String formCode) {
        if (formCode == null || formCode.isBlank()) {
            throw new ApiException(400, 40001, "表单编码不能为空");
        }
        FormRow form = fieldMapper.findForm(formCode);
        if (form == null) {
            throw new ApiException(404, 40400, "表单不存在");
        }
        return form;
    }

    private FormVersionRow requireVersion(FormRow form, int version) {
        FormVersionRow snapshot = recordMapper.findVersion(form.code(), version);
        if (snapshot == null) {
            throw new ApiException(
                    404, 40400, "模板版本不存在：" + form.code() + " v" + version + "（旧版本快照发布后保留，不会删除）");
        }
        return snapshot;
    }

    private FormRecordRow requireRecord(long id) {
        FormRecordRow row = recordMapper.findRecord(id);
        if (row == null) {
            throw new ApiException(404, 40400, "表单记录不存在");
        }
        return row;
    }

    private int countEnabledFields(String formCode) {
        int count = 0;
        for (FieldRow field : fieldMapper.findFields(formCode, null)) {
            if ("ENABLED".equals(field.status())) {
                count++;
            }
        }
        return count;
    }

    private SchemaView buildSchema(
            FormRow form, FormVersionRow snapshot, List<VersionFieldRow> fields) {
        Map<String, List<FieldView>> byCategory = new LinkedHashMap<>();
        for (VersionFieldRow field : fields) {
            byCategory
                    .computeIfAbsent(field.categoryKey(), key -> new ArrayList<>())
                    .add(toFieldView(field));
        }
        List<CategoryView> categories = new ArrayList<>();
        List<Map.Entry<String, Integer>> declared = declaredCategories(snapshot.categories());
        for (Map.Entry<String, Integer> entry : declared) {
            List<FieldView> categoryFields = byCategory.getOrDefault(entry.getKey(), List.of());
            categories.add(
                    new CategoryView(
                            entry.getKey(),
                            categoryLabelOf(snapshot.categories(), entry.getKey()),
                            entry.getValue(),
                            categoryFields));
        }
        // 防御：字段引用了 categories 未登记的分类时兜底输出（键即标签），不让字段凭空消失
        for (Map.Entry<String, List<FieldView>> entry : byCategory.entrySet()) {
            if (declared.stream()
                    .noneMatch(declaredKey -> declaredKey.getKey().equals(entry.getKey()))) {
                categories.add(
                        new CategoryView(entry.getKey(), entry.getKey(), 9_999, entry.getValue()));
            }
        }
        return new SchemaView(
                form.code(),
                form.name(),
                form.usageType(),
                snapshot.version(),
                categories,
                ruleViews(form.code()));
    }

    /** fe_rule 当前启用规则（实时读取、不进版本快照；停用即不生效）。 */
    private List<RuleView> ruleViews(String formCode) {
        List<RuleView> rules = new ArrayList<>();
        for (RuleRow rule : ruleMapper.findRules(formCode)) {
            rules.add(
                    new RuleView(
                            rule.ruleId(),
                            rule.severity(),
                            rule.categoryKey(),
                            rule.targetField(),
                            readParams(rule.params()),
                            rule.message()));
        }
        return rules;
    }

    /** 规则参数 JSON → 对象（components.md §10.1 params；非对象返回 null 防御）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readParams(String raw) {
        JsonNode node = readTree(raw);
        if (node == null || !node.isObject()) {
            return null;
        }
        try {
            return json.readValue(raw, LinkedHashMap.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("表单规则参数解析失败", e);
        }
    }

    /** 硬范围写入闸门（A-FLD-003「超出硬范围不可保存」）：违反 42200，fieldErrors 指到具体字段。 */
    private void enforceHardRange(Map<String, Object> data, List<VersionFieldRow> fields) {
        List<FieldIssue> errors = ruleEngine.rangeErrors(data, fields);
        if (!errors.isEmpty()) {
            throw new ApiException(422, 42200, "表单校验未通过", Map.of("fieldErrors", errors));
        }
    }

    /** 快照 categories JSON（[{key,label,sortNo}]）→ 有序 (key, order)；解析失败回落空（字段兜底输出）。 */
    private List<Map.Entry<String, Integer>> declaredCategories(String categoriesJson) {
        List<Map.Entry<String, Integer>> declared = new ArrayList<>();
        JsonNode node = readTree(categoriesJson);
        if (node != null && node.isArray()) {
            List<Map.Entry<String, Integer>> collected = new ArrayList<>();
            for (JsonNode item : node) {
                if (item.hasNonNull("key")) {
                    collected.add(
                            Map.entry(item.get("key").asText(), item.path("sortNo").asInt(0)));
                }
            }
            collected.sort(Map.Entry.comparingByValue());
            declared = collected;
        }
        return declared;
    }

    private String categoryLabelOf(String categoriesJson, String key) {
        JsonNode node = readTree(categoriesJson);
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (key.equals(item.path("key").asText(null))) {
                    String label = item.path("label").asText(null);
                    return label == null || label.isBlank() ? key : label;
                }
            }
        }
        return key;
    }

    private FieldView toFieldView(VersionFieldRow field) {
        List<OptionItemView> options = null;
        if (field.optionSetCode() != null) {
            List<OptionItemView> loaded = new ArrayList<>();
            for (OptionRow option : fieldMapper.findOptions(field.optionSetCode())) {
                loaded.add(
                        new OptionItemView(
                                option.optionCode(),
                                option.label(),
                                option.ordinal(),
                                option.exclusive(),
                                option.needsDetail(),
                                "DISABLED".equals(option.status())));
            }
            options = loaded;
        }
        return new FieldView(
                field.fieldKey(),
                field.fieldCode(),
                field.label(),
                field.type(),
                field.unit(),
                field.decimals(),
                readRange(field.hardRange()),
                readRange(field.softRange()),
                options,
                field.required(),
                readTree(field.visibleWhen()),
                readTree(field.requiredWhen()),
                readTree(field.derivedDef()),
                field.sensitiveFlag(),
                field.storageType(),
                field.helpText());
    }

    private RecordView toView(FormRecordRow row) {
        return new RecordView(
                row.id(),
                row.formCode(),
                row.templateVersion(),
                row.status(),
                row.version(),
                readMap(row.data()),
                readMap(row.dataMeta()),
                Time.format(row.createdAt()),
                Time.format(row.updatedAt()));
    }

    private ApiException conflict(FormRecordRow row, SaveRecordCommand command) {
        Map<String, Object> server = readMap(row.data());
        Map<String, Object> client = command.data() == null ? Map.of() : command.data();
        List<String> changed = new ArrayList<>();
        for (String key : new LinkedHashSet<>(concat(server.keySet(), client.keySet()))) {
            if (!java.util.Objects.equals(server.get(key), client.get(key))) {
                changed.add(key);
            }
        }
        VersionConflictView detail =
                new VersionConflictView(
                        row.version(),
                        changed,
                        row.updatedBy() == null ? null : String.valueOf(row.updatedBy()),
                        Time.format(row.updatedAt()));
        return new ApiException(409, 40900, "记录已被他人保存，请处理版本冲突", detail);
    }

    private static List<String> concat(java.util.Set<String> first, java.util.Set<String> second) {
        List<String> keys = new ArrayList<>(first);
        for (String key : second) {
            if (!first.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
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
            return node.isNull() ? null : node;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("表单 JSON 解析失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String raw) {
        JsonNode node = readTree(raw);
        if (node == null || !node.isObject()) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(raw, LinkedHashMap.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("表单记录 JSON 解析失败", e);
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("表单记录 JSON 序列化失败", e);
        }
    }

    /** 时间展示口径（北京时间 ISO-8601 带时区，与 FieldDictionaryService 一致）。 */
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
