package com.crdp.followup.report.app;

import com.crdp.followup.report.infra.mapper.NameInsert;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper;
import com.crdp.followup.report.infra.mapper.ReportMapper;
import com.crdp.followup.report.infra.mapper.TplInsert;
import com.crdp.followup.report.infra.mapper.TypeInsert;
import com.crdp.form.field.infra.mapper.FieldRow;
import com.crdp.form.field.infra.mapper.FormFieldMapper;
import com.crdp.form.field.infra.mapper.FormRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报告单 / 资料租户配置用例（E16-T01 / A-ATT-003、A-ATT-011、A-ATT-012、A-ATT-018；C-SCAN-002、003、004； 接口
 * docs/api/report.md §2、§6，页面 /system/report-config，pages.md §10）。
 *
 * <p>平台通用、按院配置（A-ATT-018）：同一套表与接口，医院差异全部落在配置行；不写 {@code if (tenant == …)} （AGENTS §5.7）。A 医院预置 10
 * 类（迁移 V62）；医院 C 的 10 类（含默认电子化状态）随 E23 以本院预置处置。
 *
 * <p>口径：
 *
 * <ul>
 *   <li>类型：编码本院唯一、创建后不可改（A-ATT-003「可增减、停用、排序」——停用不删除，添加报告单不再可选）； 字段集引用 fe_form（usage_type =
 *       REPORT），选类型后表单随之变化（C-SCAN-002）；
 *   <li>名称：同类型下唯一（A-ATT-011）；同名报告单可多次添加、按报告时间区分——字典只管取值，不限制重复添加； 检查、检验类名称是否必填由类型行 name_required
 *       决定（C-SCAN-003），报告单创建侧（E16-T02）据此校验；
 *   <li>指标模板：项目名称、代码、参考范围（可按性别区分）、单位、顺序（A-ATT-012 / C-SCAN-004）；模板停用后 新报告单不再带出项目，已保存的指标行不变（C
 *       规格「修改只影响之后新建的报告单」）；
 *   <li>电子化状态列（default_digitize_status / digitize_status_override，C-SCAN-011）本任务只建列与校验取值 （PENDING /
 *       NO_ENTRY），A 预置为 NULL（A-ATT-018：A 不启用电子化状态）；消费侧随 E21。
 * </ul>
 *
 * <p>删除守卫：类型下仍有名称时不能删（40901）；模板仍被名称绑定时不能删（40901）。报告单（rpt_report）随 E16-T02 落地后，类型 /
 * 名称被报告单引用的删除守卫与「已存报告单不联动」口径届时补上。增删改全部写审计 （AGENTS §5.4 配置变更；module=followup，objectType=REPORT_TYPE
 * / REPORT_NAME / REPORT_INDICATOR_TPL）。
 */
@Service
public class ReportConfigService {

    private static final Logger log = LoggerFactory.getLogger(ReportConfigService.class);

    private static final int NAME_MAX = 128;
    private static final int CODE_MAX = 32;
    private static final int UNIT_MAX = 32;
    private static final int RANGE_MAX = 64;
    private static final int REMARK_MAX = 500;
    private static final int ITEM_MAX = 200;

    /** 电子化状态取值（C-SCAN-011，report.md §3）：待录入 / 无需录入；DONE 只由转录产生，不作配置默认值。 */
    private static final Set<String> DIGITIZE_STATUSES = Set.of("PENDING", "NO_ENTRY");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ReportConfigMapper mapper;
    private final FormFieldMapper forms;
    private final ReportMapper reports;
    private final AuditLogger audit;

    public ReportConfigService(
            ReportConfigMapper mapper,
            FormFieldMapper forms,
            ReportMapper reports,
            AuditLogger audit) {
        this.mapper = mapper;
        this.forms = forms;
        this.reports = reports;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 视图与请求

    /** 资料类型视图（nameCount = 类型下名称数，含停用）。 */
    public record TypeView(
            String id,
            String code,
            String name,
            String formCode,
            String formName,
            boolean nameRequired,
            String defaultDigitizeStatus,
            int sortNo,
            String status,
            String remark,
            long nameCount) {}

    /** 名称字典视图。 */
    public record NameView(
            String id,
            String typeCode,
            String typeName,
            String name,
            String indicatorTplId,
            String indicatorTplName,
            String digitizeStatusOverride,
            int sortNo,
            String status) {}

    /** 指标模板项目视图（A-ATT-012：项目名称、代码、参考范围、单位、顺序）。 */
    public record IndicatorItemView(
            String itemName,
            String itemCode,
            String unit,
            String refRange,
            String refRangeMale,
            String refRangeFemale,
            int sortNo) {}

    /** 指标模板视图（items 按顺序；boundNameCount = 绑定该模板的名称数，含停用）。 */
    public record IndicatorTplView(
            String id,
            String name,
            String status,
            String remark,
            List<IndicatorItemView> items,
            long boundNameCount) {}

    /** 新建类型请求（A-ATT-003：租户管理员可增减类型）。 */
    public record TypeCreateRequest(
            String code,
            String name,
            String formCode,
            Boolean nameRequired,
            String defaultDigitizeStatus,
            Integer sortNo,
            String remark) {}

    /** 编辑类型请求（编码不可改；status 承载启停）。 */
    public record TypeUpdateRequest(
            String name,
            String formCode,
            Boolean nameRequired,
            String defaultDigitizeStatus,
            Integer sortNo,
            String status,
            String remark) {}

    /** 新建名称请求（indicatorTplId 可空 = 不绑定模板）。 */
    public record NameCreateRequest(
            String typeCode,
            String name,
            String indicatorTplId,
            String digitizeStatusOverride,
            Integer sortNo) {}

    /** 编辑名称请求（所属类型不可改；status 承载启停）。 */
    public record NameUpdateRequest(
            String name,
            String indicatorTplId,
            String digitizeStatusOverride,
            Integer sortNo,
            String status) {}

    /** 指标模板项目请求。 */
    public record IndicatorItemRequest(
            String itemName,
            String itemCode,
            String unit,
            String refRange,
            String refRangeMale,
            String refRangeFemale,
            Integer sortNo) {}

    /** 新建模板请求（items 全量给出）。 */
    public record TplCreateRequest(String name, String remark, List<IndicatorItemRequest> items) {}

    /** 编辑模板请求（items 全量替换：C-SCAN-004 模板维护口径）。 */
    public record TplUpdateRequest(
            String name, String remark, String status, List<IndicatorItemRequest> items) {}

    // ---------------------------------------------------------------- 读取（配置页）

    /** 类型清单（含停用，按排序号）。 */
    public List<TypeView> types() {
        return mapper.findTypes().stream().map(ReportConfigService::toTypeView).toList();
    }

    /** 名称清单（typeCode 可空 = 全部；含停用）。 */
    public List<NameView> names(String typeCode) {
        String code = optionalCode(typeCode, "类型编码");
        if (code != null && mapper.findTypeIdByCode(code) == null) {
            throw new ApiException(404, 40400, "资料类型不存在");
        }
        return mapper.findNames(code, false).stream().map(ReportConfigService::toNameView).toList();
    }

    /** 指标模板清单（含停用，模板带全部项目）。 */
    public List<IndicatorTplView> templates() {
        List<IndicatorTplView> views = new ArrayList<>();
        for (ReportConfigMapper.TplRow row : mapper.findTpls()) {
            views.add(toTplView(row, mapper.findItems(row.id())));
        }
        return views;
    }

    // ---------------------------------------------------------------- 运行时读取（添加报告单对话框，report.md §2）

    /** 生效类型清单（只取 ENABLED；A-ATT-003 / C-SCAN-002「选类型后表单随之变化」）。 */
    public List<TypeView> enabledTypes() {
        return mapper.findTypes().stream()
                .filter(row -> "ENABLED".equals(row.status()))
                .map(ReportConfigService::toTypeView)
                .toList();
    }

    /** 某类型下生效名称（含绑定的模板与项目；E16-T02 选择名称自动带出指标行用）。 */
    public List<NameView> enabledNames(String typeCode) {
        String code = requireCode(typeCode, "类型编码");
        if (mapper.findTypeIdByCode(code) == null) {
            throw new ApiException(404, 40400, "资料类型不存在");
        }
        return mapper.findNames(code, true).stream().map(ReportConfigService::toNameView).toList();
    }

    /** 模板项目（报告单添加 / 详情按名称带出；模板停用不再带出）。 */
    public List<IndicatorItemView> templateItems(Long tplId) {
        if (tplId == null || mapper.findTpl(tplId) == null) {
            return List.of();
        }
        return mapper.findItems(tplId).stream().map(ReportConfigService::toItemView).toList();
    }

    /** 添加报告单对话框的类型专属字段（紧凑视图；只取启用字段，字段集随 fe_form 维护）。 */
    public record CatalogFieldView(
            String key,
            String code,
            String label,
            String type,
            String unit,
            boolean required,
            int sortNo) {}

    /** 生效类型 + 字段集（report.md §2「类型与字段集」；E16-T02 对话框按此渲染）。 */
    public record CatalogTypeView(
            String code,
            String name,
            boolean nameRequired,
            String defaultDigitizeStatus,
            List<CatalogFieldView> fields) {}

    /** 生效名称 + 模板项目（report.md §2「名称字典，含指标模板」；选名称自动带出指标行）。 */
    public record CatalogNameView(
            String id,
            String typeCode,
            String name,
            String indicatorTplId,
            List<IndicatorItemView> items) {}

    /** 生效类型与字段集（C-SCAN-002「选择类型后表单随之变化」）。 */
    public List<CatalogTypeView> catalogTypes() {
        List<CatalogTypeView> views = new ArrayList<>();
        for (ReportConfigMapper.TypeRow row : mapper.findTypes()) {
            if (!"ENABLED".equals(row.status())) {
                continue;
            }
            List<CatalogFieldView> fields = new ArrayList<>();
            for (FieldRow field : forms.findFields(row.formCode(), null)) {
                if (!"ENABLED".equals(field.status())) {
                    continue;
                }
                fields.add(
                        new CatalogFieldView(
                                field.fieldKey(),
                                field.fieldCode(),
                                field.label(),
                                field.type(),
                                field.unit(),
                                field.required(),
                                field.sortNo()));
            }
            views.add(
                    new CatalogTypeView(
                            row.code(),
                            row.name(),
                            row.nameRequired(),
                            row.defaultDigitizeStatus(),
                            fields));
        }
        return views;
    }

    /** 某类型下生效名称与各自模板项目（模板停用不再带出项目）。 */
    public List<CatalogNameView> catalogNames(String typeCode) {
        String code = requireCode(typeCode, "类型编码");
        if (mapper.findTypeIdByCode(code) == null) {
            throw new ApiException(404, 40400, "资料类型不存在");
        }
        List<CatalogNameView> views = new ArrayList<>();
        for (ReportConfigMapper.NameRow row : mapper.findNames(code, true)) {
            List<IndicatorItemView> items = List.of();
            if (row.indicatorTplId() != null) {
                ReportConfigMapper.TplRow tpl = mapper.findTpl(row.indicatorTplId());
                if (tpl != null && "ENABLED".equals(tpl.status())) {
                    items =
                            mapper.findItems(tpl.id()).stream()
                                    .map(ReportConfigService::toItemView)
                                    .toList();
                }
            }
            views.add(
                    new CatalogNameView(
                            String.valueOf(row.id()),
                            row.typeCode(),
                            row.name(),
                            row.indicatorTplId() == null
                                    ? null
                                    : String.valueOf(row.indicatorTplId()),
                            items));
        }
        return views;
    }

    // ---------------------------------------------------------------- 类型维护（A-ATT-003）

    /** 新建类型：编码唯一（40902）、字段集必须是 REPORT 表单（40400）；写 RPT_TYPE_CREATE 审计。 */
    @Transactional
    public TypeView createType(TypeCreateRequest request) {
        if (request == null) {
            throw bad("请求体不能为空");
        }
        String code = requireCode(request.code(), "类型编码");
        String name = requireName(request.name(), "类型名称");
        String formCode = requireReportForm(request.formCode());
        boolean nameRequired = !Boolean.FALSE.equals(request.nameRequired());
        String digitize = normalizeDigitize(request.defaultDigitizeStatus(), "默认电子化状态");
        int sortNo = normalizeSort(request.sortNo());
        String remark = optionalLength(request.remark(), REMARK_MAX, "说明");
        long operator = CurrentSession.requireHospital().userId();

        if (mapper.findTypeIdByCode(code) != null) {
            throw duplicateTypeCode(code);
        }
        TypeInsert row = new TypeInsert();
        row.setCode(code);
        row.setName(name);
        row.setFormCode(formCode);
        row.setNameRequired(nameRequired);
        row.setDefaultDigitizeStatus(digitize);
        row.setSortNo(sortNo);
        row.setRemark(remark);
        row.setUpdatedBy(operator);
        try {
            mapper.insertType(row);
        } catch (DuplicateKeyException race) {
            log.warn("资料类型编码并发新建冲突，按重复处理（E16-T01）");
            throw duplicateTypeCode(code);
        }
        TypeView view = toTypeView(mapper.findType(row.getId()));
        audit.append(
                AuditEntry.operation("followup", "RPT_TYPE_CREATE")
                        .objectType("REPORT_TYPE")
                        .objectId(code)
                        .description("新建资料类型：" + name)
                        .afterJson(writeJson(view))
                        .build());
        return view;
    }

    /** 编辑类型：编码不可改（携带且不同 → 40001）；启停在描述点明；写 RPT_TYPE_UPDATE 审计（前后值）。 */
    @Transactional
    public TypeView updateType(long id, TypeUpdateRequest request) {
        if (request == null) {
            throw bad("请求体不能为空");
        }
        ReportConfigMapper.TypeRow current = requireType(id);
        String name = requireName(request.name(), "类型名称");
        String formCode = requireReportForm(request.formCode());
        boolean nameRequired = !Boolean.FALSE.equals(request.nameRequired());
        String digitize = normalizeDigitize(request.defaultDigitizeStatus(), "默认电子化状态");
        int sortNo = normalizeSort(request.sortNo());
        String status = requireStatus(request.status(), "类型");
        String remark = optionalLength(request.remark(), REMARK_MAX, "说明");
        long operator = CurrentSession.requireHospital().userId();

        mapper.updateType(
                id, name, formCode, nameRequired, digitize, sortNo, status, remark, operator);
        TypeView view = toTypeView(mapper.findType(id));
        StringBuilder description = new StringBuilder("编辑资料类型：" + current.name());
        if (!current.status().equals(view.status())) {
            description.append("DISABLED".equals(view.status()) ? "；类型已停用" : "；类型已启用");
        }
        audit.append(
                AuditEntry.operation("followup", "RPT_TYPE_UPDATE")
                        .objectType("REPORT_TYPE")
                        .objectId(current.code())
                        .description(description.toString())
                        .beforeJson(writeJson(toTypeView(current)))
                        .afterJson(writeJson(view))
                        .build());
        return view;
    }

    /** 删除类型：类型下仍有名称（40901，先删名称）或已被报告单引用（40901，OQ-95② 随 E16-T02 落地）时拦截； 写 RPT_TYPE_DELETE 审计。 */
    @Transactional
    public void deleteType(long id) {
        ReportConfigMapper.TypeRow current = requireType(id);
        long names = mapper.countNamesByTypeCode(current.code());
        long referenced = reports.countReportsOfType(current.code());
        if (referenced > 0) {
            audit.append(
                    AuditEntry.operation("followup", "RPT_TYPE_DELETE")
                            .objectType("REPORT_TYPE")
                            .objectId(current.code())
                            .description(
                                    "删除资料类型被阻止："
                                            + current.name()
                                            + "，已有 "
                                            + referenced
                                            + " 份报告单使用该类型（OQ-95②，可停用不可删）")
                            .result(AuditResult.DENIED)
                            .errorCode(40901)
                            .reason("TYPE_HAS_REPORTS")
                            .build());
            throw new ApiException(409, 40901, "已有 " + referenced + " 份报告单使用该类型，不能删除（可停用）");
        }
        if (names > 0) {
            audit.append(
                    AuditEntry.operation("followup", "RPT_TYPE_DELETE")
                            .objectType("REPORT_TYPE")
                            .objectId(current.code())
                            .description(
                                    "删除资料类型被阻止："
                                            + current.name()
                                            + "，类型下仍有 "
                                            + names
                                            + " 个名称（A-ATT-003，先删除名称）")
                            .result(AuditResult.DENIED)
                            .errorCode(40901)
                            .reason("TYPE_HAS_NAMES")
                            .build());
            throw new ApiException(409, 40901, "该类型下仍有 " + names + " 个名称，请先删除名称后再删除类型");
        }
        mapper.deleteType(id);
        audit.append(
                AuditEntry.operation("followup", "RPT_TYPE_DELETE")
                        .objectType("REPORT_TYPE")
                        .objectId(current.code())
                        .description("删除资料类型：" + current.name())
                        .beforeJson(writeJson(toTypeView(current)))
                        .build());
    }

    // ---------------------------------------------------------------- 名称维护（A-ATT-011）

    /** 新建名称：类型须存在；同类型唯一（40902）；绑定模板须存在；写 RPT_NAME_CREATE 审计。 */
    @Transactional
    public NameView createName(NameCreateRequest request) {
        if (request == null) {
            throw bad("请求体不能为空");
        }
        String typeCode = requireCode(request.typeCode(), "类型编码");
        String name = requireName(request.name(), "名称");
        ReportConfigMapper.TypeRow type = requireTypeByCode(typeCode);
        Long tplId = normalizeTplId(request.indicatorTplId());
        String digitize = normalizeDigitize(request.digitizeStatusOverride(), "电子化状态覆盖");
        int sortNo = normalizeSort(request.sortNo());
        long operator = CurrentSession.requireHospital().userId();

        if (mapper.findNameId(typeCode, name) != null) {
            throw duplicateName(type, name);
        }
        NameInsert row = new NameInsert();
        row.setTypeCode(typeCode);
        row.setName(name);
        row.setIndicatorTplId(tplId);
        row.setDigitizeStatusOverride(digitize);
        row.setSortNo(sortNo);
        row.setUpdatedBy(operator);
        try {
            mapper.insertName(row);
        } catch (DuplicateKeyException race) {
            log.warn("名称字典并发新建冲突，按重复处理（E16-T01）");
            throw duplicateName(type, name);
        }
        NameView view = toNameView(mapper.findName(row.getId()));
        audit.append(
                AuditEntry.operation("followup", "RPT_NAME_CREATE")
                        .objectType("REPORT_NAME")
                        .objectId(typeCode + "." + name)
                        .description("新建名称：" + type.name() + " › " + name)
                        .afterJson(writeJson(view))
                        .build());
        return view;
    }

    /** 编辑名称：所属类型不可改；同类型下重名拦截（40902）；启停在描述点明。 */
    @Transactional
    public NameView updateName(long id, NameUpdateRequest request) {
        if (request == null) {
            throw bad("请求体不能为空");
        }
        ReportConfigMapper.NameRow current = requireName(id);
        String name = requireName(request.name(), "名称");
        Long tplId = normalizeTplId(request.indicatorTplId());
        String digitize = normalizeDigitize(request.digitizeStatusOverride(), "电子化状态覆盖");
        int sortNo = normalizeSort(request.sortNo());
        String status = requireStatus(request.status(), "名称");
        long operator = CurrentSession.requireHospital().userId();

        Long clash = mapper.findNameId(current.typeCode(), name);
        if (clash != null && clash != id) {
            throw duplicateNameByCode(current.typeCode(), name);
        }
        mapper.updateName(id, name, tplId, digitize, sortNo, status, operator);
        NameView view = toNameView(mapper.findName(id));
        StringBuilder description =
                new StringBuilder("编辑名称：" + current.typeName() + " › " + current.name());
        if (!current.status().equals(view.status())) {
            description.append("DISABLED".equals(view.status()) ? "；名称已停用" : "；名称已启用");
        }
        audit.append(
                AuditEntry.operation("followup", "RPT_NAME_UPDATE")
                        .objectType("REPORT_NAME")
                        .objectId(current.typeCode() + "." + current.name())
                        .description(description.toString())
                        .beforeJson(writeJson(toNameView(current)))
                        .afterJson(writeJson(view))
                        .build());
        return view;
    }

    /** 删除名称（报告单引用守卫随 E16-T02 rpt_report 落地）；写 RPT_NAME_DELETE 审计。 */
    @Transactional
    public void deleteName(long id) {
        ReportConfigMapper.NameRow current = requireName(id);
        long referenced = reports.countReportsOfName(id);
        if (referenced > 0) {
            audit.append(
                    AuditEntry.operation("followup", "RPT_NAME_DELETE")
                            .objectType("REPORT_NAME")
                            .objectId(current.typeCode() + "." + current.name())
                            .description(
                                    "删除名称被阻止："
                                            + current.name()
                                            + "，已有 "
                                            + referenced
                                            + " 份报告单使用该名称（OQ-95②，可停用不可删）")
                            .result(AuditResult.DENIED)
                            .errorCode(40901)
                            .reason("NAME_HAS_REPORTS")
                            .build());
            throw new ApiException(409, 40901, "已有 " + referenced + " 份报告单使用该名称，不能删除（可停用）");
        }
        mapper.deleteName(id);
        audit.append(
                AuditEntry.operation("followup", "RPT_NAME_DELETE")
                        .objectType("REPORT_NAME")
                        .objectId(current.typeCode() + "." + current.name())
                        .description("删除名称：" + current.typeName() + " › " + current.name())
                        .beforeJson(writeJson(toNameView(current)))
                        .build());
    }

    // ---------------------------------------------------------------- 指标模板维护（A-ATT-012 /
    // C-SCAN-004）

    /** 新建模板：名称唯一（40902）、项目全量给出（代码模板内唯一）；写 RPT_INDICATOR_TPL_CREATE 审计。 */
    @Transactional
    public IndicatorTplView createTemplate(TplCreateRequest request) {
        if (request == null) {
            throw bad("请求体不能为空");
        }
        String name = requireName(request.name(), "模板名称");
        String remark = optionalLength(request.remark(), REMARK_MAX, "说明");
        List<IndicatorItemRequest> items = validateItems(request.items());
        long operator = CurrentSession.requireHospital().userId();

        if (mapper.findTplIdByName(name) != null) {
            throw duplicateTplName(name);
        }
        TplInsert row = new TplInsert();
        row.setName(name);
        row.setRemark(remark);
        row.setUpdatedBy(operator);
        try {
            mapper.insertTpl(row);
        } catch (DuplicateKeyException race) {
            log.warn("指标模板并发新建冲突，按重复处理（E16-T01）");
            throw duplicateTplName(name);
        }
        for (IndicatorItemRequest item : items) {
            mapper.insertItem(
                    row.getId(),
                    item.itemName(),
                    item.itemCode(),
                    optionalLength(item.unit(), UNIT_MAX, "单位"),
                    optionalLength(item.refRange(), RANGE_MAX, "参考范围"),
                    optionalLength(item.refRangeMale(), RANGE_MAX, "男性参考范围"),
                    optionalLength(item.refRangeFemale(), RANGE_MAX, "女性参考范围"),
                    item.sortNo() == null ? 0 : item.sortNo());
        }
        IndicatorTplView view =
                toTplView(mapper.findTpl(row.getId()), mapper.findItems(row.getId()));
        audit.append(
                AuditEntry.operation("followup", "RPT_INDICATOR_TPL_CREATE")
                        .objectType("REPORT_INDICATOR_TPL")
                        .objectId(name)
                        .description("新建指标模板：" + name + "（" + items.size() + " 项）")
                        .afterJson(writeJson(view))
                        .build());
        return view;
    }

    /** 编辑模板：项目全量替换（先清后插）；仍被名称绑定时可改名 / 停用，名称行引用不变；写审计（前后值）。 */
    @Transactional
    public IndicatorTplView updateTemplate(long id, TplUpdateRequest request) {
        if (request == null) {
            throw bad("请求体不能为空");
        }
        ReportConfigMapper.TplRow current = requireTpl(id);
        String name = requireName(request.name(), "模板名称");
        String remark = optionalLength(request.remark(), REMARK_MAX, "说明");
        String status = requireStatus(request.status(), "模板");
        List<IndicatorItemRequest> items = validateItems(request.items());
        long operator = CurrentSession.requireHospital().userId();
        // 项目全量替换，先把替换前项目行取好（审计 before 值；替换后 findItems 已是新清单）
        IndicatorTplView before = toTplView(current, mapper.findItems(id));

        Long clash = mapper.findTplIdByName(name);
        if (clash != null && clash != id) {
            throw duplicateTplName(name);
        }
        mapper.updateTpl(id, name, status, remark, operator);
        mapper.deleteItems(id);
        for (IndicatorItemRequest item : items) {
            mapper.insertItem(
                    id,
                    item.itemName(),
                    item.itemCode(),
                    optionalLength(item.unit(), UNIT_MAX, "单位"),
                    optionalLength(item.refRange(), RANGE_MAX, "参考范围"),
                    optionalLength(item.refRangeMale(), RANGE_MAX, "男性参考范围"),
                    optionalLength(item.refRangeFemale(), RANGE_MAX, "女性参考范围"),
                    item.sortNo() == null ? 0 : item.sortNo());
        }
        IndicatorTplView view = toTplView(mapper.findTpl(id), mapper.findItems(id));
        StringBuilder description = new StringBuilder("编辑指标模板：" + current.name());
        if (!current.status().equals(view.status())) {
            description.append("DISABLED".equals(view.status()) ? "；模板已停用" : "；模板已启用");
        }
        audit.append(
                AuditEntry.operation("followup", "RPT_INDICATOR_TPL_UPDATE")
                        .objectType("REPORT_INDICATOR_TPL")
                        .objectId(current.name())
                        .description(description.toString())
                        .beforeJson(writeJson(before))
                        .afterJson(writeJson(view))
                        .build());
        return view;
    }

    /** 删除模板：仍被名称绑定（含停用名称）时拦截（40901，先解绑）；写 RPT_INDICATOR_TPL_DELETE 审计。 */
    @Transactional
    public void deleteTemplate(long id) {
        ReportConfigMapper.TplRow current = requireTpl(id);
        // 审计 before 值在删除前取好（含项目行；删除后 findItems 已为空）
        IndicatorTplView before = toTplView(current, mapper.findItems(id));
        if (current.boundNameCount() != null && current.boundNameCount() > 0) {
            audit.append(
                    AuditEntry.operation("followup", "RPT_INDICATOR_TPL_DELETE")
                            .objectType("REPORT_INDICATOR_TPL")
                            .objectId(current.name())
                            .description(
                                    "删除指标模板被阻止："
                                            + current.name()
                                            + "，仍有 "
                                            + current.boundNameCount()
                                            + " 个名称绑定该模板（A-ATT-012，先解绑）")
                            .result(AuditResult.DENIED)
                            .errorCode(40901)
                            .reason("TPL_BOUND_BY_NAMES")
                            .build());
            throw new ApiException(
                    409, 40901, "仍有 " + current.boundNameCount() + " 个名称绑定该模板，请先解绑后再删除");
        }
        mapper.deleteItems(id);
        mapper.deleteTpl(id);
        audit.append(
                AuditEntry.operation("followup", "RPT_INDICATOR_TPL_DELETE")
                        .objectType("REPORT_INDICATOR_TPL")
                        .objectId(current.name())
                        .description("删除指标模板：" + current.name())
                        .beforeJson(writeJson(before))
                        .build());
    }

    // ---------------------------------------------------------------- 校验与内部

    private ReportConfigMapper.TypeRow requireType(long id) {
        ReportConfigMapper.TypeRow row = mapper.findType(id);
        if (row == null) {
            throw new ApiException(404, 40400, "资料类型不存在");
        }
        return row;
    }

    private ReportConfigMapper.TypeRow requireTypeByCode(String code) {
        Long id = mapper.findTypeIdByCode(code);
        if (id == null) {
            throw new ApiException(404, 40400, "资料类型不存在");
        }
        return mapper.findType(id);
    }

    private ReportConfigMapper.NameRow requireName(long id) {
        ReportConfigMapper.NameRow row = mapper.findName(id);
        if (row == null) {
            throw new ApiException(404, 40400, "名称不存在");
        }
        return row;
    }

    private ReportConfigMapper.TplRow requireTpl(long id) {
        ReportConfigMapper.TplRow row = mapper.findTpl(id);
        if (row == null) {
            throw new ApiException(404, 40400, "指标模板不存在");
        }
        return row;
    }

    /** 字段集校验（C-SCAN-002）：必须是存在的 usage_type = REPORT 表单（字段集随 fe_form 维护）。 */
    private String requireReportForm(String formCode) {
        String code = requireCode(formCode, "字段集编码");
        FormRow form = forms.findForm(code);
        if (form == null || !"REPORT".equals(form.usageType())) {
            throw new ApiException(400, 40001, "字段集必须是报告单类型的表单（usage_type = REPORT）：" + code);
        }
        return code;
    }

    /** 模板 id 归一：空串 / "0" 视为未绑定；不存在 → 40001。 */
    private Long normalizeTplId(String indicatorTplId) {
        if (indicatorTplId == null || indicatorTplId.isBlank()) {
            return null;
        }
        long id;
        try {
            id = Long.parseLong(indicatorTplId.trim());
        } catch (NumberFormatException failure) {
            throw bad("指标模板 id 格式不正确");
        }
        if (id <= 0 || mapper.findTpl(id) == null) {
            throw new ApiException(400, 40001, "指标模板不存在");
        }
        return id;
    }

    /** 项目清单校验：名称与代码必填、代码模板内唯一、数量上限（C-SCAN-004）。 */
    private List<IndicatorItemRequest> validateItems(List<IndicatorItemRequest> items) {
        if (items == null || items.isEmpty()) {
            throw bad("指标模板至少需要一行项目");
        }
        if (items.size() > ITEM_MAX) {
            throw bad("指标模板项目最多 " + ITEM_MAX + " 行");
        }
        Set<String> codes = new HashSet<>();
        for (IndicatorItemRequest item : items) {
            if (item == null) {
                throw bad("项目行不能为空");
            }
            requireName(item.itemName(), "项目名称");
            String code = requireCode(item.itemCode(), "项目代码");
            if (!codes.add(code)) {
                throw bad("项目代码重复：" + code);
            }
            if (item.sortNo() != null && (item.sortNo() < 0 || item.sortNo() > 9999)) {
                throw bad("项目顺序取值 0–9999");
            }
        }
        return items;
    }

    private static String requireCode(String code, String field) {
        if (code == null || code.isBlank()) {
            throw new ApiException(400, 40001, "请填写" + field);
        }
        String trimmed = code.trim();
        if (trimmed.length() > CODE_MAX) {
            throw new ApiException(400, 40001, field + "最多 " + CODE_MAX + " 个字符");
        }
        if (!trimmed.matches("[A-Za-z0-9_-]+")) {
            throw new ApiException(400, 40001, field + "只支持字母、数字、短横线与下划线");
        }
        return trimmed;
    }

    private static String optionalCode(String code, String field) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return requireCode(code, field);
    }

    private static String requireName(String name, String field) {
        if (name == null || name.isBlank()) {
            throw new ApiException(400, 40001, "请填写" + field);
        }
        String trimmed = name.trim();
        if (trimmed.length() > NAME_MAX) {
            throw new ApiException(400, 40001, field + "最多 " + NAME_MAX + " 个字符");
        }
        return trimmed;
    }

    private static String optionalLength(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new ApiException(400, 40001, field + "最多 " + max + " 个字符");
        }
        return trimmed;
    }

    private static String normalizeDigitize(String status, String field) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String trimmed = status.trim().toUpperCase();
        if (!DIGITIZE_STATUSES.contains(trimmed)) {
            throw new ApiException(400, 40001, field + "只支持 PENDING（待录入）或 NO_ENTRY（无需录入）");
        }
        return trimmed;
    }

    private static int normalizeSort(Integer sortNo) {
        if (sortNo == null) {
            return 0;
        }
        if (sortNo < 0 || sortNo > 9999) {
            throw bad("排序取值 0–9999");
        }
        return sortNo;
    }

    private static String requireStatus(String status, String field) {
        if (status == null) {
            throw bad("请填写" + field + "状态");
        }
        String trimmed = status.trim();
        if (!"ENABLED".equals(trimmed) && !"DISABLED".equals(trimmed)) {
            throw new ApiException(400, 40001, field + "状态只支持启用（ENABLED）或停用（DISABLED）");
        }
        return trimmed;
    }

    private ApiException duplicateTypeCode(String code) {
        audit.append(
                AuditEntry.operation("followup", "RPT_TYPE_CREATE")
                        .objectType("REPORT_TYPE")
                        .description("新建资料类型被阻止：编码已存在（" + code + "）")
                        .result(AuditResult.DENIED)
                        .errorCode(40902)
                        .reason("DUPLICATE_RPT_TYPE_CODE")
                        .build());
        return new ApiException(409, 40902, "类型编码已存在（" + code + "），请更换编码");
    }

    private ApiException duplicateName(ReportConfigMapper.TypeRow type, String name) {
        return duplicateNameByCode(type.code(), name);
    }

    private ApiException duplicateNameByCode(String typeCode, String name) {
        audit.append(
                AuditEntry.operation("followup", "RPT_NAME_CREATE")
                        .objectType("REPORT_NAME")
                        .description("新建名称被阻止：同类型下名称已存在（" + typeCode + " › " + name + "）")
                        .result(AuditResult.DENIED)
                        .errorCode(40902)
                        .reason("DUPLICATE_RPT_NAME")
                        .build());
        return new ApiException(409, 40902, "该类型下名称「" + name + "」已存在，同一类型下名称不能重复");
    }

    private ApiException duplicateTplName(String name) {
        audit.append(
                AuditEntry.operation("followup", "RPT_INDICATOR_TPL_CREATE")
                        .objectType("REPORT_INDICATOR_TPL")
                        .description("新建指标模板被阻止：名称已存在（" + name + "）")
                        .result(AuditResult.DENIED)
                        .errorCode(40902)
                        .reason("DUPLICATE_RPT_INDICATOR_TPL")
                        .build());
        return new ApiException(409, 40902, "指标模板名称已存在（" + name + "），请更换名称");
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

    private static TypeView toTypeView(ReportConfigMapper.TypeRow row) {
        return new TypeView(
                String.valueOf(row.id()),
                row.code(),
                row.name(),
                row.formCode(),
                row.formName(),
                row.nameRequired(),
                row.defaultDigitizeStatus(),
                row.sortNo() == null ? 0 : row.sortNo(),
                row.status(),
                row.remark(),
                row.nameCount() == null ? 0 : row.nameCount());
    }

    private static NameView toNameView(ReportConfigMapper.NameRow row) {
        return new NameView(
                String.valueOf(row.id()),
                row.typeCode(),
                row.typeName(),
                row.name(),
                row.indicatorTplId() == null ? null : String.valueOf(row.indicatorTplId()),
                row.indicatorTplName(),
                row.digitizeStatusOverride(),
                row.sortNo() == null ? 0 : row.sortNo(),
                row.status());
    }

    private static IndicatorItemView toItemView(ReportConfigMapper.ItemRow row) {
        return new IndicatorItemView(
                row.itemName(),
                row.itemCode(),
                row.unit(),
                row.refRange(),
                row.refRangeMale(),
                row.refRangeFemale(),
                row.sortNo() == null ? 0 : row.sortNo());
    }

    private static IndicatorTplView toTplView(
            ReportConfigMapper.TplRow row, List<ReportConfigMapper.ItemRow> items) {
        return new IndicatorTplView(
                String.valueOf(row.id()),
                row.name(),
                row.status(),
                row.remark(),
                items.stream().map(ReportConfigService::toItemView).toList(),
                row.boundNameCount() == null ? 0 : row.boundNameCount());
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            return null;
        }
    }
}
