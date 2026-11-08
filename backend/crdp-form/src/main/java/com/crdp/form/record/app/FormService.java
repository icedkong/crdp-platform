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
import com.crdp.form.rule.app.FormDerivedEngine;
import com.crdp.form.rule.app.FormProgressEngine;
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
 *
 * <p>E11-T05（A-FLD-005 / C-FLD-006）：写入闸门追加<b>派生重算</b>（tech-plan §6「后端保存时再算一遍并以后端结果为准」）——
 * 来源齐全以计算值为准、来源缺失保留手工值、结果无效的手工值 42200（{@link FormDerivedEngine}）。
 *
 * <p>E11-T11（A-PAT-021 / C-PAT-018，口径 A-PAT-019）：{@link #progress(String, Integer, Map, Map)} 按分类 /
 * 分节汇总 <b>已填 / 应填</b>（{@link FormProgressEngine}，与前端渲染器 progress 事件同一口径、共享用例锁定）。
 */
@Service
public class FormService {

    private final FormFieldMapper fieldMapper;
    private final FormRecordMapper recordMapper;
    private final FormDataValidator validator;
    private final FormRuleMapper ruleMapper;
    private final FormRuleEngine ruleEngine;
    private final FormConditionEngine conditionEngine;
    private final FormDerivedEngine derivedEngine;
    private final FormProgressEngine progressEngine;
    private final AuditLogger audit;
    private final ObjectMapper json = new ObjectMapper();

    public FormService(
            FormFieldMapper fieldMapper,
            FormRecordMapper recordMapper,
            FormDataValidator validator,
            FormRuleMapper ruleMapper,
            FormRuleEngine ruleEngine,
            FormConditionEngine conditionEngine,
            FormDerivedEngine derivedEngine,
            FormProgressEngine progressEngine,
            AuditLogger audit) {
        this.fieldMapper = fieldMapper;
        this.recordMapper = recordMapper;
        this.validator = validator;
        this.ruleMapper = ruleMapper;
        this.ruleEngine = ruleEngine;
        this.conditionEngine = conditionEngine;
        this.derivedEngine = derivedEngine;
        this.progressEngine = progressEngine;
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

    /**
     * 40900 冲突详情（E25 自动保存草稿口径，patient.md §4.1 形态）：currentVersion 供「保留我的并覆盖」 作为 baseVersion 重发（带
     * overwrite，业务层写审计），diffFields 只列本次 changes 里两边取值不同的键（不做字段级合并）。
     */
    public record DraftConflictView(
            int currentVersion,
            String updatedBy,
            String updatedAt,
            List<DiffFieldView> diffFields) {}

    /** 两边不同字段（E25 / NFR-SAVE-003）：mine = 请求值、server = 服务器当前值，label 取记录模板版本快照。 */
    public record DiffFieldView(String key, String label, Object mine, Object server) {}

    // ---------------------------------------------------------------- 模板：按版本读（旧版本可读）

    /** FormSchema：version 省略取当前发布版；版本不存在 40400。rules 为 fe_rule 当前启用规则（实时读取，不进快照）。 */
    public SchemaView schema(String formCode, Integer version) {
        FormRow form = requireForm(formCode);
        int resolved = version == null ? form.currentVersion() : version;
        FormVersionRow snapshot = requireVersion(form, resolved);
        return buildSchema(form, snapshot, recordMapper.findVersionFields(formCode, resolved));
    }

    // ---------------------------------------------------------------- 业务表复用口（E15-T07 /
    // OQ-63①、OQ-83①）
    // 患者域业务记录表（fu_record、后续 pt_baseline_section / rpt_report）自带 JSON 列、不与 fe_record 双写，
    // 但写入闸门（键 / 类型 / 派生重算 / 硬范围）与版本口径必须与引擎一致——以下方法即业务表实现复用的口。

    /** 表单当前发布版本号（业务表创建记录时固化 template_version 用；表单不存在 40400）。 */
    public int publishedVersion(String formCode) {
        return requireForm(formCode).currentVersion();
    }

    /**
     * 写入闸门（业务表复用口，E15-T07）：键 / 类型检查（{@link FormDataValidator}）→ 派生重算（来源齐全以计算值为准、 结果无效 42200）→
     * 硬范围拦截（超硬范围 42200）。与 {@link #createRecord} / {@link #saveRecord} / {@link #saveDraft} 落
     * fe_record 前执行的<b>同一套</b>闸门；返回应持久化的 data（派生重算后的值）。按指定模板版本（记录保存时的版本）取字段口径。
     */
    public Map<String, Object> writeGateData(
            String formCode, int templateVersion, Map<String, Object> data) {
        FormVersionRow snapshot = requireVersion(requireForm(formCode), templateVersion);
        List<VersionFieldRow> fields = recordMapper.findVersionFields(formCode, snapshot.version());
        if (fields.isEmpty()) {
            throw new ApiException(404, 40400, "记录的模板版本不存在（formCode=" + formCode + "）");
        }
        validator.validateData(data, fields);
        Map<String, Object> persisted = applyDerived(fields, data);
        enforceHardRange(persisted, fields);
        return persisted;
    }

    /** 字段稳定键 → 显示名（按模板版本快照；业务表 40900 冲突详情的 diffFields 标签用，口径同 {@link DraftConflictView}）。 */
    public Map<String, String> fieldLabels(String formCode, int templateVersion) {
        FormVersionRow snapshot = requireVersion(requireForm(formCode), templateVersion);
        Map<String, String> labels = new LinkedHashMap<>();
        for (VersionFieldRow field : recordMapper.findVersionFields(formCode, snapshot.version())) {
            labels.put(field.fieldKey(), field.label());
        }
        return labels;
    }

    // ---------------------------------------------------------------- 已填 / 应填进度（E11-T11 /
    // A-PAT-021、C-PAT-018）

    /**
     * 已填 / 应填进度（口径 A-PAT-019，见 {@link FormProgressEngine}）：HTTP 端点 POST /forms/{code}/progress 与
     * 业务接口（E13-T11 / E15「随记录返回」、完整度统计）共用本口。 version 省略取当前发布版；按该版本快照的分类与字段口径 计算（旧版本可读）；data 为记录值（键 =
     * 稳定键）、context 为条件上下文（C 的 device.* 等），均可为空。
     */
    public ProgressView progress(
            String formCode,
            Integer version,
            Map<String, Object> data,
            Map<String, Object> context) {
        FormRow form = requireForm(formCode);
        int resolved = version == null ? form.currentVersion() : version;
        FormVersionRow snapshot = requireVersion(form, resolved);
        List<VersionFieldRow> fields = recordMapper.findVersionFields(formCode, resolved);
        List<FormProgressEngine.Category> categories = progressCategories(snapshot.categories());
        return new ProgressView(
                formCode,
                snapshot.version(),
                progressEngine.progress(fields, categories, data, context));
    }

    /** 分类进度视图（formCode + templateVersion 供调用方把锚点与模板版本配对，E16-T13 报告单位置校验）。 */
    public record ProgressView(
            String formCode,
            int templateVersion,
            List<FormProgressEngine.CategoryProgress> categories) {}

    /**
     * 字段级已填 / 应填进度（E19-T05 / A-DASH-010、A-DASH-011：完整度卡片「最常缺失字段」与缺失钻取；口径与 {@link #progress}
     * 完全一致——同一应填集合，见 {@link FormProgressEngine#fieldProgress}）： 只返回应填字段（显示中且有效必填且 storage=json
     * 且非派生），每字段带 filled / label / categoryKey。 version 省略取当前发布版；记录按保存时固化的 templateVersion
     * 取口径（旧版本可读）。
     */
    public List<FormProgressEngine.FieldProgress> fieldProgress(
            String formCode,
            Integer version,
            Map<String, Object> data,
            Map<String, Object> context) {
        FormRow form = requireForm(formCode);
        int resolved = version == null ? form.currentVersion() : version;
        FormVersionRow snapshot = requireVersion(form, resolved);
        return progressEngine.fieldProgress(
                recordMapper.findVersionFields(formCode, snapshot.version()), data, context);
    }

    /**
     * 字段级进度的批量形态（E19-T05 完整度统计：一次范围加载 = 数千患者 / 记录）： 版本字段只查一次，逐单位（患者基线合并值 / 一条随访记录值）求值，输出与入参顺序一一对应；
     * 单位 data 为 null 按空表单处理。口径与 {@link #fieldProgress} 完全一致。
     */
    public List<List<FormProgressEngine.FieldProgress>> fieldProgressBatch(
            String formCode,
            Integer version,
            List<Map<String, Object>> unitDatas,
            Map<String, Object> context) {
        FormRow form = requireForm(formCode);
        int resolved = version == null ? form.currentVersion() : version;
        FormVersionRow snapshot = requireVersion(form, resolved);
        List<VersionFieldRow> fields = recordMapper.findVersionFields(formCode, snapshot.version());
        List<List<FormProgressEngine.FieldProgress>> results = new ArrayList<>(unitDatas.size());
        for (Map<String, Object> data : unitDatas) {
            results.add(progressEngine.fieldProgress(fields, data, context));
        }
        return List.copyOf(results);
    }

    /** 快照 categories JSON → 分类声明（sortNo 升序、label 空 / 缺回落 key；复用 buildSchema 同一防御口径）。 */
    private List<FormProgressEngine.Category> progressCategories(String categoriesJson) {
        List<FormProgressEngine.Category> categories = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : declaredCategories(categoriesJson)) {
            categories.add(
                    new FormProgressEngine.Category(
                            entry.getKey(),
                            categoryLabelOf(categoriesJson, entry.getKey()),
                            entry.getValue()));
        }
        return categories;
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

    /**
     * 新建记录（DRAFT）：template_version 固化为当前发布版；值经 {@link FormDataValidator} 类型检查、派生重算
     * （E11-T05：来源齐全以计算值为准、缺失保留手工值、结果无效的手工值 42200）与硬范围闸门检查。
     */
    @Transactional
    public RecordView createRecord(
            String formCode, Map<String, Object> data, Map<String, Object> dataMeta) {
        FormRow form = requireForm(formCode);
        FormVersionRow snapshot = requireVersion(form, form.currentVersion());
        List<VersionFieldRow> fields =
                recordMapper.findVersionFields(formCode, form.currentVersion());
        validator.validateData(data, fields);
        Map<String, Object> persisted = applyDerived(fields, data);
        enforceHardRange(persisted, fields);

        long operator = CurrentSession.requireHospital().userId();
        FormRecordInsert insert =
                new FormRecordInsert(
                        formCode,
                        snapshot.version(),
                        writeJson(persisted),
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
        Map<String, Object> persisted = applyDerived(fields, command.data());
        enforceHardRange(persisted, fields);

        long operator = CurrentSession.requireHospital().userId();
        int updated =
                recordMapper.updateRecord(
                        id,
                        command.baseVersion(),
                        writeJson(persisted),
                        writeJson(command.dataMeta()),
                        operator);
        if (updated != 1) {
            FormRecordRow current = requireRecord(id);
            throw conflict(current, command);
        }
        return record(id);
    }

    /**
     * 服务器自动保存草稿（E25-T01 / NFR-SAVE-001、NFR-SAVE-003，tech-plan §6「服务器自动保存与版本冲突」；业务 HTTP 端点
     * followup.md §3 {@code PUT /records/{id}/draft}）。
     *
     * <p>与 {@link #saveRecord} 的差别：只带<b>变更字段</b>（changes 部分合并到既有 data，null 清空该键——前端「只在有新改动时发送」），
     * data_meta 不随草稿更新（changes 只承载字段值）。其余同草稿语义：只过写入闸门（键 / 类型 / 派生重算 / 硬范围），必填、跨字段与软范围
     * 不进闸门（「草稿不触发提交逻辑」）；不写字段历史（提交 / 保存修改时才写，E11-T06）、不逐次写审计（「覆盖他人版本」的审计在业务层， overwrite 标志随业务请求）。
     *
     * <p>乐观锁（NFR-SAVE-003）：baseVersion 与当前不一致（或条件更新 0 行的并发竞态）抛 40900，附 {@link DraftConflictView}
     * （当前版本、更新人显示名、更新时间、两边不同字段；patient.md §4.1）；已提交 40901、记录 / 模板版本不存在 40400。
     */
    @Transactional
    public RecordView saveDraft(long id, Integer baseVersion, Map<String, Object> changes) {
        if (baseVersion == null) {
            throw new ApiException(400, 40001, "自动保存必须携带 baseVersion（乐观锁）");
        }
        FormRecordRow row = requireRecord(id);
        if ("SUBMITTED".equals(row.status())) {
            throw new ApiException(409, 40901, "记录已提交，不再自动保存（修改走已提交记录修改流程）");
        }
        if (row.version() != baseVersion) {
            throw draftConflict(row, changes);
        }
        List<VersionFieldRow> fields =
                recordMapper.findVersionFields(row.formCode(), row.templateVersion());
        if (fields.isEmpty()) {
            throw new ApiException(404, 40400, "记录的模板版本不存在（formCode=" + row.formCode() + "）");
        }
        validator.validateData(changes, fields);
        Map<String, Object> merged = readMap(row.data());
        if (changes != null) {
            for (Map.Entry<String, Object> entry : changes.entrySet()) {
                if (entry.getValue() == null) {
                    merged.remove(entry.getKey());
                } else {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        Map<String, Object> persisted = applyDerived(fields, merged);
        enforceHardRange(persisted, fields);

        long operator = CurrentSession.requireHospital().userId();
        int updated =
                recordMapper.updateRecord(
                        id, baseVersion, writeJson(persisted), row.dataMeta(), operator);
        if (updated != 1) {
            throw draftConflict(requireRecord(id), changes);
        }
        return record(id);
    }

    /** 草稿冲突详情（E25 口径）：只列 changes 里两边取值不同的键，label 取记录模板版本快照的显示名。 */
    private ApiException draftConflict(FormRecordRow row, Map<String, Object> changes) {
        Map<String, Object> server = readMap(row.data());
        List<VersionFieldRow> fields =
                recordMapper.findVersionFields(row.formCode(), row.templateVersion());
        Map<String, String> labels = new LinkedHashMap<>();
        for (VersionFieldRow field : fields) {
            labels.put(field.fieldKey(), field.label());
        }
        List<DiffFieldView> diffFields = new ArrayList<>();
        if (changes != null) {
            for (String key : changes.keySet()) {
                Object mine = changes.get(key);
                Object serverValue = server.get(key);
                if (!java.util.Objects.equals(mine, serverValue)) {
                    diffFields.add(
                            new DiffFieldView(
                                    key, labels.getOrDefault(key, key), mine, serverValue));
                }
            }
        }
        DraftConflictView detail =
                new DraftConflictView(
                        row.version(),
                        row.updatedBy() == null
                                ? null
                                : recordMapper.findDisplayName(row.updatedBy()),
                        Time.format(row.updatedAt()),
                        diffFields);
        return new ApiException(409, 40900, "该记录已被他人更新", detail);
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

    // ---------------------------------------------------------------- 提交（E15-T02 /
    // A-FU-005「可暂存草稿，提交后任务完成」）

    /**
     * 提交结果：提交后的记录视图（status = SUBMITTED、version + 1）+ 提交前 data 与隐藏字段清洗掉的键（调用方写字段历史 diff 用，E11-T06）+
     * 软校验警告（不拦截，调用方可走确认流记 data_meta）。
     */
    public record SubmitOutcome(
            RecordView view,
            Map<String, Object> before,
            List<String> clearedKeys,
            List<FieldIssue> warnings) {}

    /**
     * 提交（DRAFT → SUBMITTED，一次事务内完成全部写入闸门；任务完成联动由业务调用方在同一事务内接线，E15-T02 RecordEntryService）：完整校验（必填 /
     * 条件必填 / 跨字段 / 派生 / 硬范围，违反 42200）→ 隐藏字段值清空（tech-plan §6）→ 派生重算 → 落库（乐观锁：baseVersion 过期
     * 40900，已提交 40901）。字段历史与审计不写在本层（提交属业务动作，E15-T02 记 RECORD_SUBMIT 与 DRAFT→SUBMITTED diff）。
     */
    @Transactional
    public SubmitOutcome submitRecord(
            long id, Integer baseVersion, Map<String, Object> data, Map<String, Object> dataMeta) {
        if (baseVersion == null) {
            throw new ApiException(400, 40001, "提交必须携带 baseVersion（乐观锁）");
        }
        FormRecordRow row = requireRecord(id);
        if ("SUBMITTED".equals(row.status())) {
            throw new ApiException(409, 40901, "记录已提交，不能重复提交（修改走已提交记录修改流程）");
        }
        if (row.version() != baseVersion) {
            throw draftConflict(requireRecord(id), data);
        }
        List<VersionFieldRow> fields =
                recordMapper.findVersionFields(row.formCode(), row.templateVersion());
        if (fields.isEmpty()) {
            throw new ApiException(404, 40400, "记录的模板版本不存在（formCode=" + row.formCode() + "）");
        }
        Map<String, Object> values = data == null ? new LinkedHashMap<>() : data;
        validator.validateData(values, fields);
        List<FieldIssue> warnings =
                validateForSubmit(row.formCode(), row.templateVersion(), values);
        StrippedData stripped =
                stripHiddenFields(row.formCode(), row.templateVersion(), values, Map.of());
        Map<String, Object> persisted = applyDerived(fields, stripped.data());
        enforceHardRange(persisted, fields);
        Map<String, Object> meta =
                dataMeta == null ? readMap(row.dataMeta()) : new LinkedHashMap<>(dataMeta);
        long operator = CurrentSession.requireHospital().userId();
        int updated =
                recordMapper.submitRecord(
                        id, baseVersion, writeJson(persisted), writeJson(meta), operator);
        if (updated != 1) {
            throw draftConflict(requireRecord(id), data);
        }
        return new SubmitOutcome(record(id), readMap(row.data()), stripped.clearedKeys(), warnings);
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

    // ---------------------------------------------------------------- 派生重算（E11-T05 /
    // A-FLD-005、C-FLD-006）

    /**
     * 派生重算（引擎口）：按记录模板版本的字段口径重算全部派生字段，返回每个字段的求值结果（value / status / message）。 业务流程（提交 E15 / E25、导入重算
     * E18、报告单映射 E16-T07）用它 diff 新旧值并经 {@code
     * FieldHistoryService.logChange}（source=SYSTEM_RECALC，triggerFields=派生定义 args）写「系统重算」历史。
     */
    public Map<String, FormDerivedEngine.DerivedOutcome> recalculateDerived(
            String formCode, int templateVersion, Map<String, Object> data) {
        FormVersionRow snapshot = requireVersion(requireForm(formCode), templateVersion);
        List<VersionFieldRow> fields = recordMapper.findVersionFields(formCode, snapshot.version());
        if (fields.isEmpty()) {
            throw new ApiException(404, 40400, "记录的模板版本不存在（formCode=" + formCode + "）");
        }
        return derivedEngine.recalculate(fields, data);
    }

    /**
     * 写入闸门的派生重算（tech-plan §6「后端保存时再算一遍并以后端结果为准」）：来源齐全 → 计算值覆盖； 来源缺失 → 保留手工值；结果无效且带手工值 →
     * 42200（fieldErrors 带 {@code rule=derived}，A-FLD-005「结果无效不允许手工录入」）。 草稿不写字段历史 （form.md
     * §1.2），重算历史的落点在提交流程（E15 / E25）。
     */
    private Map<String, Object> applyDerived(
            List<VersionFieldRow> fields, Map<String, Object> data) {
        FormDerivedEngine.DerivedWrite write = derivedEngine.applyToWrite(fields, data);
        if (!write.errors().isEmpty()) {
            throw new ApiException(422, 42200, "表单校验未通过", Map.of("fieldErrors", write.errors()));
        }
        return write.data();
    }

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
