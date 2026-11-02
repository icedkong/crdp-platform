package com.crdp.form.field.app;

import com.crdp.form.field.domain.FieldDictStatus;
import com.crdp.form.field.domain.FieldType;
import com.crdp.form.field.infra.mapper.FieldRow;
import com.crdp.form.field.infra.mapper.FormFieldMapper;
import com.crdp.form.field.infra.mapper.FormRow;
import com.crdp.form.field.infra.mapper.OptionRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.excel.SimpleXlsx;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E11-T09 / A-FLD-013、C-FLD-008：字段字典批量导入（Excel → 元数据，docs/api/form.md §3）。
 *
 * <p>给实施 / 产品用（E23-T01 录医院 A 的 266 字段、C 字段字典随 E21 录入）：下载模板 → 按行填字段定义 → 上传， 一次性同步进 fe_form /
 * fe_field / fe_option_set / fe_option。与科室 / 用户导入（OQ-52）同一同步一次性模型： 文件 ≤{@link #MAX_ROWS}
 * 行，上传即逐行校验并落库，<b>单行失败不影响其他行</b>，返回逐行 CREATED / UPDATED / FAILED 与原因； 解析用框架 {@link SimpleXlsx}（JDK
 * zip + StAX，不引入 POI），宽表数据导入（E18）仍走框架网关。
 *
 * <p>语义（A-FLD-008「创建后不可改」）：
 *
 * <ul>
 *   <li>按「表单编码 + 稳定键」命中：库内已有 → <b>更新</b>（行是字段的完整定义，可维护与实施列全量覆盖： 显示名、分类、单位、小数位、硬 / 软范围、必填、条件显示 /
 *       条件必填、派生公式、帮助、排序、状态、选项）； 没有 → <b>新增</b>。
 *   <li>不可改列：字段编码、类型、存储方式、敏感标记——更新行里留空表示保留原值，填写则必须与库内一致，否则该行失败。
 *   <li>选项：选项集不存在时自动创建（名称先取编码，随字典维护调整）；选项「编码=显示名」逐项落 fe_option， 新编码插入（带序数 / 互斥 / 需补充说明 /
 *       别名四列实施标记），已有编码更新显示名与实施列，不删除选项（已有数据的选项不能删）。
 *   <li>派生公式列写 {@code AGE_YEARS(f003,f020)}（内置 9 函数，arity 与 FormDerivedEngine 同口径），参数可引用
 *       同文件后面才定义的字段；条件列写 {@code f010=1}（多个条件用逗号分隔 = 全部成立，比较符 = != &gt; &gt;= &lt; &lt;=），
 *       解析为条件树（form.md §2.4）。引用不存在的稳定键、未知函数、参数个数不符 → 该行失败（配置期拦截，坏配置不进库）。
 * </ul>
 *
 * <p>审计与留痕：写一条 {@code FIELD_DICT_IMPORT} 作业级摘要；每个新增 / 更新的字段各写一条 fe_field_dict_log （FIELD_CREATE /
 * FIELD_UPDATE，前后值 + 操作人，A-FLD-009）；失败行不写。模板示例行全部为虚构数据。
 */
@Service
public class FieldImportService {

    /** 列顺序即模板顺序，导入按表头逐列精确匹配（A-FLD-001「字段字典每个字段的属性」全覆盖）。 */
    static final List<String> HEADERS =
            List.of(
                    "表单编码",
                    "稳定键",
                    "字段编码",
                    "显示名",
                    "分类键",
                    "类型",
                    "单位",
                    "小数位",
                    "硬范围下限",
                    "硬范围上限",
                    "提示范围下限",
                    "提示范围上限",
                    "必填",
                    "敏感",
                    "存储方式",
                    "选项集编码",
                    "选项",
                    "选项序数",
                    "互斥选项编码",
                    "需补充说明选项编码",
                    "选项别名",
                    "派生公式",
                    "条件显示",
                    "条件必填",
                    "帮助说明",
                    "排序",
                    "状态");

    /** 医院 A 266 字段一次装得下并留余量。 */
    static final int MAX_ROWS = 500;

    private static final int KEY_MAX = 32;
    private static final int CODE_MAX = 64;
    private static final int LABEL_MAX = 128;
    private static final int UNIT_MAX = 32;
    private static final int HELP_MAX = 500;
    private static final int ALIASES_MAX = 255;
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern DERIVED_PATTERN =
            Pattern.compile("\\s*([A-Za-z0-9_]+)\\s*\\(\\s*([^()]*?)\\s*\\)\\s*");
    private static final Pattern CONDITION_LEAF =
            Pattern.compile("\\s*([A-Za-z][A-Za-z0-9_]*)\\s*(!=|>=|<=|=|>|<)\\s*(.+?)\\s*");

    /** 逐行结果：action = CREATED / UPDATED / FAILED（失败行给原因，行号按 Excel 习惯从 1 计、含表头行）。 */
    public record RowResult(
            int rowNo, String formCode, String fieldKey, String action, String message) {}

    /** 导入报告：合计、新增、更新、失败与逐行明细。 */
    public record ImportReport(
            int total, int created, int updated, int failed, List<RowResult> rows) {}

    /** 解析后的一行（尚未做跨行 / 跨库校验；全部为去空后的原始文本）。 */
    private record ParsedRow(
            int rowNo,
            String formCode,
            String fieldKey,
            String fieldCode,
            String label,
            String categoryKey,
            String type,
            String unit,
            String decimals,
            String hardMin,
            String hardMax,
            String softMin,
            String softMax,
            String required,
            String sensitive,
            String storage,
            String optionSetCode,
            String options,
            String optionOrdinals,
            String exclusiveCodes,
            String needsDetailCodes,
            String optionAliases,
            String derived,
            String visibleWhen,
            String requiredWhen,
            String help,
            String sort,
            String status) {}

    /** 一行解析并通过校验后的落库形态（JSON 列已编码为文本）。 */
    private record FieldDraft(
            ParsedRow row,
            FieldType type,
            Integer decimals,
            String hardRange,
            String softRange,
            boolean required,
            boolean sensitive,
            String storage,
            String optionSetCode,
            String visibleWhen,
            String requiredWhen,
            String derivedDef,
            int sortNo,
            String status,
            List<OptionDraft> options) {}

    /** 选项项：编码 / 显示名 / 序数 / 互斥 / 需补充说明 / 别名（「选项」单元格 + 四个标记列合并而来）。 */
    private record OptionDraft(
            int code,
            String label,
            Integer ordinal,
            boolean exclusive,
            boolean needsDetail,
            String aliases) {}

    private final FormFieldMapper mapper;
    private final AuditLogger audit;
    private final ObjectMapper json = new ObjectMapper();

    public FieldImportService(FormFieldMapper mapper, AuditLogger audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    /** 模板：表头 + 三行示例（全部虚构数据；覆盖数值范围 / 选项 / 派生三种形态）。 */
    public byte[] template() {
        return SimpleXlsx.write(
                HEADERS,
                List.of(
                        rowCells(
                                "A_BASELINE",
                                "f901",
                                "example_number",
                                "示例数值字段（示例）",
                                "basic_info",
                                "number",
                                "mm",
                                "1",
                                "0",
                                "500",
                                "50",
                                "300",
                                "否",
                                "否",
                                "json",
                                "",
                                "",
                                "",
                                "",
                                "",
                                "",
                                "",
                                "",
                                "",
                                "示例：超出提示范围（50–300）黄色提示，超出硬范围（0–500）不可保存",
                                "500",
                                "ENABLED"),
                        rowCells(
                                "A_BASELINE",
                                "f902",
                                "example_radio",
                                "示例单选字段（示例）",
                                "basic_info",
                                "radio",
                                "",
                                "",
                                "",
                                "",
                                "",
                                "",
                                "是",
                                "否",
                                "json",
                                "example_set",
                                "0=否（示例）,1=是（示例）",
                                "",
                                "",
                                "1",
                                "",
                                "",
                                "",
                                "",
                                "示例：显示名与编码分离（A-FLD-002），数据库存 0 / 1",
                                "510",
                                "ENABLED"),
                        rowCells(
                                "A_BASELINE",
                                "f903",
                                "example_derived",
                                "示例派生字段（示例）",
                                "surgery",
                                "derived",
                                "",
                                "",
                                "",
                                "",
                                "",
                                "",
                                "否",
                                "否",
                                "json",
                                "",
                                "",
                                "",
                                "",
                                "",
                                "",
                                "AGE_YEARS(f003,f020)",
                                "",
                                "",
                                "示例：派生公式写「函数(稳定键,…)」，手术年龄 = 手术日期 − 出生日期取周岁",
                                "520",
                                "ENABLED")));
    }

    /** 解析后的单元格 → 模板行（补齐空列，避免 27 列手写）。 */
    private static List<String> rowCells(String... cells) {
        List<String> row = new ArrayList<>(Arrays.asList(cells));
        while (row.size() < HEADERS.size()) {
            row.add("");
        }
        return row;
    }

    /** 导入（一个事务：全部成功行一起提交，失败行只进报告不阻断）。 */
    @Transactional
    public ImportReport importFields(InputStream file) {
        long operator = CurrentSession.requireHospital().userId();
        List<List<String>> grid;
        try {
            grid = SimpleXlsx.read(file);
        } catch (IllegalStateException ex) {
            throw bad("文件无法解析：" + ex.getMessage());
        }
        if (grid.isEmpty()) {
            throw bad("文件为空：请使用下载的模板填写");
        }
        List<String> header = grid.get(0);
        if (header.size() != HEADERS.size()
                || !HEADERS.equals(header.stream().map(String::trim).toList())) {
            throw bad("表头与模板不一致：请下载最新模板（" + String.join("、", HEADERS) + "）");
        }
        List<ParsedRow> rows = parseRows(grid);
        if (rows.isEmpty()) {
            throw bad("文件没有数据行");
        }
        if (rows.size() > MAX_ROWS) {
            throw bad("单次最多导入 " + MAX_ROWS + " 行，当前 " + rows.size() + " 行，请分批上传");
        }

        // 快照：表单（含分类）、各表单库内字段（避免逐行回库；导入在一个事务里，快照即本请求口径）
        Map<String, FormRow> formsByCode = new LinkedHashMap<>();
        for (FormRow form : mapper.findForms()) {
            formsByCode.put(form.code(), form);
        }
        Map<String, Map<String, FieldRow>> fieldsByForm = new LinkedHashMap<>();
        for (ParsedRow row : rows) {
            if (formsByCode.containsKey(row.formCode())
                    && !fieldsByForm.containsKey(row.formCode())) {
                Map<String, FieldRow> byKey = new LinkedHashMap<>();
                for (FieldRow field : mapper.findFields(row.formCode(), null)) {
                    byKey.put(field.fieldKey(), field);
                }
                fieldsByForm.put(row.formCode(), byKey);
            }
        }
        // 文件内重复：同表单同稳定键、同表单同字段编码 → 相关行全部失败（避免「以哪行为准」的歧义）
        Set<String> duplicatedKeys = new LinkedHashSet<>();
        Set<String> duplicatedCodes = new LinkedHashSet<>();
        countDuplicates(rows, duplicatedKeys, duplicatedCodes);
        // 派生公式与条件可引用的稳定键：库内 ∪ 本文件（同表单），前向引用合法
        Map<String, Set<String>> knownKeysByForm = new LinkedHashMap<>();
        for (ParsedRow row : rows) {
            Set<String> known =
                    knownKeysByForm.computeIfAbsent(
                            row.formCode(),
                            code -> {
                                Set<String> keys = new LinkedHashSet<>();
                                Map<String, FieldRow> existing = fieldsByForm.get(code);
                                if (existing != null) {
                                    keys.addAll(existing.keySet());
                                }
                                return keys;
                            });
            known.add(row.fieldKey());
        }

        // 第一趟：逐行校验（失败只记原因，不影响其他行）
        Map<Integer, FieldDraft> drafts = new LinkedHashMap<>();
        Map<Integer, String> errors = new LinkedHashMap<>();
        for (ParsedRow row : rows) {
            String error =
                    duplicatedKeys.contains(row.formCode() + "#" + row.fieldKey())
                            ? "稳定键在文件内重复：" + row.fieldKey()
                            : duplicatedCodes.contains(row.formCode() + "#" + row.fieldCode())
                                    ? "字段编码在文件内重复：" + row.fieldCode()
                                    : null;
            if (error == null) {
                try {
                    drafts.put(
                            row.rowNo(), validate(row, formsByCode, fieldsByForm, knownKeysByForm));
                } catch (ApiException ex) {
                    error = ex.getMessage();
                }
            }
            if (error != null) {
                errors.put(row.rowNo(), error);
            }
        }

        // 第二趟前置：新选项集必须有人给选项（建集前拦截，失败行不产生任何半成品写入）
        Map<String, Boolean> setInDb = new LinkedHashMap<>();
        Map<String, Boolean> setHasOptions = new LinkedHashMap<>();
        for (FieldDraft draft : drafts.values()) {
            if (draft.optionSetCode() == null) {
                continue;
            }
            if (!setInDb.containsKey(draft.optionSetCode())) {
                setInDb.put(
                        draft.optionSetCode(), mapper.findOptionSet(draft.optionSetCode()) != null);
            }
            if (!draft.options().isEmpty()) {
                setHasOptions.put(draft.optionSetCode(), true);
            }
        }
        for (ParsedRow row : rows) {
            if (errors.containsKey(row.rowNo())) {
                continue;
            }
            FieldDraft draft = drafts.get(row.rowNo());
            if (draft.optionSetCode() != null
                    && !Boolean.TRUE.equals(setInDb.get(draft.optionSetCode()))
                    && !Boolean.TRUE.equals(setHasOptions.get(draft.optionSetCode()))) {
                errors.put(row.rowNo(), "新选项集必须给出选项（编码=显示名）：" + draft.optionSetCode());
                drafts.remove(row.rowNo());
            }
        }

        // 第三趟：按行序落库（选项集与选项在内存同步跟踪，同文件复用不重建）
        Map<String, Map<Integer, OptionRow>> optionsBySet = new LinkedHashMap<>();
        List<RowResult> results = new ArrayList<>();
        for (ParsedRow row : rows) {
            String error = errors.get(row.rowNo());
            if (error != null) {
                results.add(
                        new RowResult(
                                row.rowNo(), row.formCode(), row.fieldKey(), "FAILED", error));
                continue;
            }
            FieldDraft draft = drafts.get(row.rowNo());
            FieldRow existing = fieldsByForm.get(row.formCode()).get(row.fieldKey());
            boolean created = existing == null;
            apply(row, draft, existing, optionsBySet, setInDb, operator);
            results.add(
                    new RowResult(
                            row.rowNo(),
                            row.formCode(),
                            row.fieldKey(),
                            created ? "CREATED" : "UPDATED",
                            null));
        }

        results.sort(Comparator.comparing(RowResult::rowNo));
        int created =
                (int) results.stream().filter(item -> "CREATED".equals(item.action())).count();
        int updated =
                (int) results.stream().filter(item -> "UPDATED".equals(item.action())).count();
        int failed = results.size() - created - updated;
        audit.append(
                AuditEntry.operation("form", "FIELD_DICT_IMPORT")
                        .objectType("FE_FIELD")
                        .objectId("import")
                        .description(
                                "批量导入字段字典：共 "
                                        + results.size()
                                        + " 行，新增 "
                                        + created
                                        + "，更新 "
                                        + updated
                                        + "，失败 "
                                        + failed
                                        + (failed > 0 ? "（失败行见导入报告）" : ""))
                        .build());
        return new ImportReport(results.size(), created, updated, failed, results);
    }

    // ---------------------------------------------------------------- 解析与校验

    /** 跳过整行为空的行；全部单元格去空。 */
    private static List<ParsedRow> parseRows(List<List<String>> grid) {
        List<ParsedRow> rows = new ArrayList<>();
        for (int i = 1; i < grid.size(); i++) {
            List<String> cells = grid.get(i);
            if (cells.stream().allMatch(cell -> cell == null || cell.trim().isEmpty())) {
                continue;
            }
            rows.add(
                    new ParsedRow(
                            i + 1, // 行号按 Excel 习惯从 1 计（含表头行）
                            cell(cells, 0),
                            cell(cells, 1),
                            cell(cells, 2),
                            cell(cells, 3),
                            cell(cells, 4),
                            cell(cells, 5),
                            cell(cells, 6),
                            cell(cells, 7),
                            cell(cells, 8),
                            cell(cells, 9),
                            cell(cells, 10),
                            cell(cells, 11),
                            cell(cells, 12),
                            cell(cells, 13),
                            cell(cells, 14),
                            cell(cells, 15),
                            cell(cells, 16),
                            cell(cells, 17),
                            cell(cells, 18),
                            cell(cells, 19),
                            cell(cells, 20),
                            cell(cells, 21),
                            cell(cells, 22),
                            cell(cells, 23),
                            cell(cells, 24),
                            cell(cells, 25),
                            cell(cells, 26)));
        }
        return rows;
    }

    private static void countDuplicates(
            List<ParsedRow> rows, Set<String> duplicatedKeys, Set<String> duplicatedCodes) {
        Map<String, Integer> keyCount = new LinkedHashMap<>();
        Map<String, Integer> codeCount = new LinkedHashMap<>();
        for (ParsedRow row : rows) {
            keyCount.merge(row.formCode() + "#" + row.fieldKey(), 1, Integer::sum);
            codeCount.merge(row.formCode() + "#" + row.fieldCode(), 1, Integer::sum);
        }
        keyCount.forEach(
                (key, count) -> {
                    if (count > 1) {
                        duplicatedKeys.add(key);
                    }
                });
        codeCount.forEach(
                (code, count) -> {
                    if (count > 1) {
                        duplicatedCodes.add(code);
                    }
                });
    }

    /** 一行的全部校验（列内规则 + 表单 / 分类 / 不可改列 / 派生与条件引用），失败抛 40001（只影响本行）。 */
    private FieldDraft validate(
            ParsedRow row,
            Map<String, FormRow> formsByCode,
            Map<String, Map<String, FieldRow>> fieldsByForm,
            Map<String, Set<String>> knownKeysByForm) {
        // 1. 身份列
        FormRow form = formsByCode.get(row.formCode());
        if (form == null) {
            throw bad("表单不存在：" + row.formCode());
        }
        if (row.fieldKey().isBlank()) {
            throw bad("稳定键必填");
        }
        if (row.fieldKey().length() > KEY_MAX || !KEY_PATTERN.matcher(row.fieldKey()).matches()) {
            throw bad("稳定键只允许字母、数字与下划线（≤" + KEY_MAX + " 位）：" + row.fieldKey());
        }
        if (row.fieldCode().isBlank()) {
            throw bad("字段编码必填");
        }
        if (row.fieldCode().length() > CODE_MAX
                || !KEY_PATTERN.matcher(row.fieldCode()).matches()) {
            throw bad("字段编码只允许字母、数字与下划线（≤" + CODE_MAX + " 位）：" + row.fieldCode());
        }
        for (FieldRow other : fieldsByForm.get(row.formCode()).values()) {
            if (!other.fieldKey().equals(row.fieldKey())
                    && other.fieldCode().equals(row.fieldCode())) {
                throw bad("字段编码已被 " + other.fieldKey() + " 使用：" + row.fieldCode());
            }
        }
        if (row.label().isBlank()) {
            throw bad("显示名必填");
        }
        if (row.label().length() > LABEL_MAX) {
            throw bad("显示名过长（最多 " + LABEL_MAX + " 字）");
        }
        if (row.categoryKey().isBlank()) {
            throw bad("分类键必填");
        }
        List<String> categoryKeys = categoryKeys(form);
        if (!categoryKeys.contains(row.categoryKey())) {
            throw bad(
                    "分类键不在表单分类中："
                            + row.categoryKey()
                            + "（可用："
                            + String.join("、", categoryKeys)
                            + "）");
        }
        FieldType type = FieldType.ofCode(row.type()).orElse(null);
        if (type == null) {
            throw bad(
                    "类型只认 "
                            + Arrays.stream(FieldType.values())
                                    .map(FieldType::code)
                                    .reduce((a, b) -> a + "/" + b)
                                    .orElse("")
                            + "："
                            + row.type());
        }

        // 2. 更新路径的不可改列（A-FLD-008）：留空保留原值，填写须与库内一致
        FieldRow existing = fieldsByForm.get(row.formCode()).get(row.fieldKey());
        boolean update = existing != null;
        if (update) {
            if (!existing.fieldCode().equals(row.fieldCode())) {
                throw bad("字段编码不可改（库内为 " + existing.fieldCode() + "，不能改成 " + row.fieldCode() + "）");
            }
            if (!existing.type().equals(type.code())) {
                throw bad("类型不可改（库内为 " + existing.type() + "，不能改成 " + type.code() + "）");
            }
            if (!row.storage().isBlank() && !existing.storageType().equals(row.storage())) {
                throw bad("存储方式不可改（库内为 " + existing.storageType() + "，不能改成 " + row.storage() + "）");
            }
        }
        if (!row.storage().isBlank()
                && !"json".equals(row.storage())
                && !"column".equals(row.storage())
                && !"encrypted".equals(row.storage())) {
            throw bad("存储方式只认 json / column / encrypted：" + row.storage());
        }
        boolean sensitive;
        if (row.sensitive().isBlank()) {
            sensitive = update && existing.sensitiveFlag();
        } else {
            sensitive = parseFlag(row.sensitive(), "敏感");
            if (update && existing.sensitiveFlag() != sensitive) {
                throw bad("敏感标记不可改（库内为 " + (existing.sensitiveFlag() ? "是" : "否") + "）");
            }
        }
        String storage =
                row.storage().isBlank()
                        ? (update ? existing.storageType() : "json")
                        : row.storage();

        // 3. 数值元数据
        if (row.unit().length() > UNIT_MAX) {
            throw bad("单位过长（最多 " + UNIT_MAX + " 字）");
        }
        Integer decimals = parseInt(row.decimals(), "小数位");
        if (decimals != null && decimals < 0) {
            throw bad("小数位不能为负数：" + decimals);
        }
        String hardRange = parseRange(row.hardMin(), row.hardMax(), "硬范围");
        String softRange = parseRange(row.softMin(), row.softMax(), "提示范围");
        Integer sortNo = parseInt(row.sort(), "排序");
        boolean required = parseFlag(row.required(), "必填");
        String status = row.status().isBlank() ? FieldDictStatus.ENABLED : row.status();
        if (!FieldDictStatus.isValid(status)) {
            throw bad("状态只认 ENABLED / DISABLED：" + row.status());
        }

        // 4. 选项集与选项（选项型必填；其他类型不能带）
        String optionSetCode = row.optionSetCode();
        if (type.hasOptionSet()) {
            if (optionSetCode.isBlank()) {
                throw bad("单选 / 下拉 / 多选字段必须给选项集编码");
            }
            if (optionSetCode.length() > CODE_MAX
                    || !KEY_PATTERN.matcher(optionSetCode).matches()) {
                throw bad("选项集编码只允许字母、数字与下划线（≤" + CODE_MAX + " 位）：" + optionSetCode);
            }
        } else if (!optionSetCode.isBlank()) {
            throw bad("该字段不是选项型字段，不能配置选项集：" + optionSetCode);
        }
        List<OptionDraft> options = optionSetCode.isBlank() ? List.of() : parseOptions(row);

        // 5. 派生公式与条件（配置期拦截坏定义，坏配置不进库）
        if (type == FieldType.DERIVED) {
            if (row.derived().isBlank()) {
                throw bad("派生字段必须给派生公式，如 AGE_YEARS(f003,f020)");
            }
        } else if (!row.derived().isBlank()) {
            throw bad("只有派生字段才能配派生公式：" + row.derived());
        }
        Set<String> knownKeys = knownKeysByForm.get(row.formCode());
        String derivedDef = row.derived().isBlank() ? null : parseDerived(row.derived(), knownKeys);
        String visibleWhen =
                row.visibleWhen().isBlank()
                        ? null
                        : parseCondition(row.visibleWhen(), "条件显示", knownKeys);
        String requiredWhen =
                row.requiredWhen().isBlank()
                        ? null
                        : parseCondition(row.requiredWhen(), "条件必填", knownKeys);

        if (row.help().length() > HELP_MAX) {
            throw bad("帮助说明过长（最多 " + HELP_MAX + " 字）");
        }
        return new FieldDraft(
                row,
                type,
                decimals,
                hardRange,
                softRange,
                required,
                sensitive,
                storage,
                optionSetCode.isBlank() ? null : optionSetCode,
                visibleWhen,
                requiredWhen,
                derivedDef,
                sortNo == null ? 0 : sortNo,
                status,
                options);
    }

    /** 选项单元格「0=否,1=是」+ 序数 / 互斥 / 需补充说明 / 别名四列 → 选项清单（标记列作用于本行给出的选项）。 */
    private static List<OptionDraft> parseOptions(ParsedRow row) {
        Map<Integer, OptionDraft> drafts = new LinkedHashMap<>();
        boolean hasOptions = false;
        if (!row.options().isBlank()) {
            hasOptions = true;
            Set<Integer> seen = new LinkedHashSet<>();
            for (String item : row.options().split(",")) {
                if (item.isBlank()) {
                    continue;
                }
                int eq = item.indexOf('=');
                if (eq <= 0) {
                    throw bad("选项格式应为「编码=显示名」，多项用逗号分隔：" + item);
                }
                Integer code = parseInt(item.substring(0, eq), "选项编码");
                if (code == null) {
                    throw bad("选项编码必须是整数：" + item.substring(0, eq));
                }
                String label = item.substring(eq + 1).trim();
                if (label.isBlank()) {
                    throw bad("选项显示名必填：" + item);
                }
                if (label.length() > LABEL_MAX) {
                    throw bad("选项显示名过长（最多 " + LABEL_MAX + " 字）：" + label);
                }
                if (!seen.add(code)) {
                    throw bad("选项编码重复：" + code);
                }
                drafts.put(code, new OptionDraft(code, label, null, false, false, null));
            }
        }
        Map<Integer, Integer> ordinals = parseCodeValuePairs(row.optionOrdinals(), "选项序数");
        List<Integer> exclusiveCodes = parseCodeList(row.exclusiveCodes(), "互斥选项编码");
        List<Integer> needsDetailCodes = parseCodeList(row.needsDetailCodes(), "需补充说明选项编码");
        Map<Integer, String> aliases = parseAliases(row.optionAliases());
        if (!hasOptions
                && (!ordinals.isEmpty()
                        || !exclusiveCodes.isEmpty()
                        || !needsDetailCodes.isEmpty()
                        || !aliases.isEmpty())) {
            throw bad("序数 / 互斥 / 需补充说明 / 别名列要搭配「选项」列（编码=显示名）使用");
        }
        for (Map.Entry<Integer, Integer> entry : ordinals.entrySet()) {
            OptionDraft draft = drafts.get(entry.getKey());
            if (draft != null) {
                drafts.put(
                        entry.getKey(),
                        new OptionDraft(
                                draft.code(),
                                draft.label(),
                                entry.getValue(),
                                draft.exclusive(),
                                draft.needsDetail(),
                                draft.aliases()));
            }
        }
        for (Integer code : exclusiveCodes) {
            OptionDraft draft = drafts.get(code);
            if (draft != null) {
                drafts.put(
                        code,
                        new OptionDraft(
                                draft.code(),
                                draft.label(),
                                draft.ordinal(),
                                true,
                                draft.needsDetail(),
                                draft.aliases()));
            }
        }
        for (Integer code : needsDetailCodes) {
            OptionDraft draft = drafts.get(code);
            if (draft != null) {
                drafts.put(
                        code,
                        new OptionDraft(
                                draft.code(),
                                draft.label(),
                                draft.ordinal(),
                                draft.exclusive(),
                                true,
                                draft.aliases()));
            }
        }
        for (Map.Entry<Integer, String> entry : aliases.entrySet()) {
            OptionDraft draft = drafts.get(entry.getKey());
            if (draft != null) {
                drafts.put(
                        entry.getKey(),
                        new OptionDraft(
                                draft.code(),
                                draft.label(),
                                draft.ordinal(),
                                draft.exclusive(),
                                draft.needsDetail(),
                                entry.getValue()));
            }
        }
        return new ArrayList<>(drafts.values());
    }

    /** 「1=1,2=2」→ 编码 → 数值。 */
    private static Map<Integer, Integer> parseCodeValuePairs(String raw, String name) {
        Map<Integer, Integer> pairs = new LinkedHashMap<>();
        if (raw.isBlank()) {
            return pairs;
        }
        for (String item : raw.split(",")) {
            if (item.isBlank()) {
                continue;
            }
            int eq = item.indexOf('=');
            if (eq <= 0) {
                throw bad(name + "格式应为「编码=值」：" + item);
            }
            Integer code = parseInt(item.substring(0, eq), name + "编码");
            Integer value = parseInt(item.substring(eq + 1), name + "值");
            if (code == null || value == null) {
                throw bad(name + "的编码与值都必须是整数：" + item);
            }
            pairs.put(code, value);
        }
        return pairs;
    }

    /** 「0,6」→ 编码清单。 */
    private static List<Integer> parseCodeList(String raw, String name) {
        List<Integer> codes = new ArrayList<>();
        if (raw.isBlank()) {
            return codes;
        }
        for (String item : raw.split(",")) {
            Integer code = parseInt(item, name);
            if (code == null) {
                throw bad(name + "必须是整数：" + item);
            }
            codes.add(code);
        }
        return codes;
    }

    /** 「0=愈合;切口愈合」→ 编码 → 别名 CSV（分号分隔；数据导入按别名匹配历史写法，E18）。 */
    private static Map<Integer, String> parseAliases(String raw) {
        Map<Integer, String> aliases = new LinkedHashMap<>();
        if (raw.isBlank()) {
            return aliases;
        }
        for (String item : raw.split(",")) {
            if (item.isBlank()) {
                continue;
            }
            int eq = item.indexOf('=');
            if (eq <= 0) {
                throw bad("选项别名的格式应为「编码=别名;别名」：" + item);
            }
            Integer code = parseInt(item.substring(0, eq), "选项别名编码");
            if (code == null) {
                throw bad("选项别名编码必须是整数：" + item.substring(0, eq));
            }
            String value = item.substring(eq + 1).trim();
            if (value.isBlank()) {
                throw bad("选项别名不能为空：" + item);
            }
            if (value.length() > ALIASES_MAX) {
                throw bad("选项别名过长（最多 " + ALIASES_MAX + " 字）：" + value);
            }
            aliases.put(code, value);
        }
        return aliases;
    }

    /** {@code AGE_YEARS(f003,f020)} → {"fn":"AGE_YEARS","args":["f003","f020"]}；函数与参数个数在配置期校验。 */
    private String parseDerived(String raw, Set<String> knownKeys) {
        Matcher matcher = DERIVED_PATTERN.matcher(raw);
        if (!matcher.matches()) {
            throw bad("派生公式格式应为「函数(稳定键,…)」，如 AGE_YEARS(f003,f020)：" + raw);
        }
        String fn = matcher.group(1);
        List<String> args = new ArrayList<>();
        for (String arg : matcher.group(2).split(",")) {
            if (!arg.isBlank()) {
                args.add(arg.trim());
            }
        }
        if (!knownFunction(fn, args.size())) {
            throw bad("派生函数不存在或参数个数不符：" + raw);
        }
        for (String arg : args) {
            if (!knownKeys.contains(arg)) {
                throw bad("派生公式引用的字段不存在：" + arg);
            }
        }
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("fn", fn);
        def.put("args", args);
        return writeJson(def);
    }

    /** 内置 9 函数与参数个数（与 FormDerivedEngine.parseDef 同口径；未知函数在配置期即拒绝， 不像求值端「放行到求值处不出结果」）。 */
    private static boolean knownFunction(String fn, int size) {
        return switch (fn) {
            case "DAYS_INCLUSIVE", "MINUTES_BETWEEN", "HOURS_BETWEEN_1DP", "BMI", "CHANGE_PCT" ->
                    size == 2;
            case "AGE_YEARS", "YEARS_SINCE", "DURATION_YM" -> size == 1 || size == 2;
            case "SUM" -> size >= 1;
            default -> false;
        };
    }

    /**
     * 条件 DSL「f010=1,f020>=2020-01-01」→ {"all":[{field,op,value},…]}（form.md §2.4 条件树； 多个条件用逗号分隔 =
     * 全部成立）。值按写法定型：数字按数字（选项编码 1、阈值 130），其余按字符串（日期、文本）； 引用的稳定键必须存在（库内或本文件）。
     */
    private String parseCondition(String raw, String name, Set<String> knownKeys) {
        List<Map<String, Object>> leaves = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            Matcher matcher = CONDITION_LEAF.matcher(part);
            if (!matcher.matches()) {
                throw bad(name + "格式应为「字段键=值」，多个条件用逗号分隔：" + part);
            }
            String key = matcher.group(1);
            String op = matcher.group(2);
            String rawValue = matcher.group(3);
            if (!knownKeys.contains(key)) {
                throw bad(name + "引用的字段不存在：" + key);
            }
            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("field", key);
            leaf.put("op", opOf(op));
            leaf.put("value", valueOf(rawValue));
            leaves.add(leaf);
        }
        if (leaves.isEmpty()) {
            throw bad(name + "不能为空：" + raw);
        }
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("all", leaves);
        return writeJson(tree);
    }

    private static String opOf(String op) {
        return switch (op) {
            case "!=" -> "ne";
            case ">" -> "gt";
            case ">=" -> "gte";
            case "<" -> "lt";
            case "<=" -> "lte";
            default -> "eq";
        };
    }

    /** 数字按数字（Integer / Double），其余按字符串（严格同型比较要求条件值按值编码写，form.md §2.4）。 */
    private static Object valueOf(String raw) {
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException ignored) {
            // 落到小数或字符串
        }
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException ignored) {
            return raw;
        }
    }

    // ---------------------------------------------------------------- 落库

    private void apply(
            ParsedRow row,
            FieldDraft draft,
            FieldRow existing,
            Map<String, Map<Integer, OptionRow>> optionsBySet,
            Map<String, Boolean> setInDb,
            long operator) {
        boolean created = existing == null;
        if (created) {
            mapper.insertField(
                    row.formCode(),
                    row.fieldKey(),
                    row.fieldCode(),
                    row.label(),
                    row.categoryKey(),
                    draft.type().code(),
                    blankToNull(row.unit()),
                    draft.decimals(),
                    draft.hardRange(),
                    draft.softRange(),
                    draft.optionSetCode(),
                    draft.required(),
                    draft.visibleWhen(),
                    draft.requiredWhen(),
                    draft.derivedDef(),
                    draft.sensitive(),
                    draft.storage(),
                    blankToNull(row.help()),
                    draft.sortNo(),
                    draft.status(),
                    operator);
        } else {
            mapper.updateFieldForImport(
                    row.formCode(),
                    row.fieldKey(),
                    row.label(),
                    row.categoryKey(),
                    blankToNull(row.unit()),
                    draft.decimals(),
                    draft.hardRange(),
                    draft.softRange(),
                    draft.optionSetCode(),
                    draft.required(),
                    draft.visibleWhen(),
                    draft.requiredWhen(),
                    draft.derivedDef(),
                    blankToNull(row.help()),
                    draft.sortNo(),
                    draft.status(),
                    operator);
        }

        // 选项：建集（缺省时）→ 补新编码（带实施四列）→ 已有编码更新显示名与实施列；不删除选项
        if (draft.optionSetCode() != null) {
            Map<Integer, OptionRow> current = optionsBySet.get(draft.optionSetCode());
            if (current == null) {
                if (!Boolean.TRUE.equals(setInDb.get(draft.optionSetCode()))) {
                    mapper.insertOptionSet(draft.optionSetCode(), draft.optionSetCode());
                }
                current = new LinkedHashMap<>();
                for (OptionRow option : mapper.findOptions(draft.optionSetCode())) {
                    current.put(option.optionCode(), option);
                }
                optionsBySet.put(draft.optionSetCode(), current);
            }
            int base = current.values().stream().mapToInt(OptionRow::sortNo).max().orElse(0);
            int position = 0;
            for (OptionDraft option : draft.options()) {
                position++;
                OptionRow known = current.get(option.code());
                if (known == null) {
                    int sortNo = base + position * 10;
                    mapper.insertOptionFull(
                            draft.optionSetCode(),
                            option.code(),
                            option.label(),
                            option.ordinal(),
                            option.exclusive(),
                            option.needsDetail(),
                            option.aliases(),
                            sortNo,
                            FieldDictStatus.ENABLED,
                            operator);
                    current.put(
                            option.code(),
                            new OptionRow(
                                    null,
                                    draft.optionSetCode(),
                                    option.code(),
                                    option.label(),
                                    option.ordinal(),
                                    option.exclusive(),
                                    option.needsDetail(),
                                    option.aliases(),
                                    sortNo,
                                    FieldDictStatus.ENABLED,
                                    operator,
                                    null));
                } else {
                    Integer ordinal = option.ordinal() != null ? option.ordinal() : known.ordinal();
                    boolean exclusive = option.exclusive() || known.exclusive();
                    boolean needsDetail = option.needsDetail() || known.needsDetail();
                    String aliases = option.aliases() != null ? option.aliases() : known.aliases();
                    mapper.updateOptionForImport(
                            draft.optionSetCode(),
                            option.code(),
                            option.label(),
                            ordinal,
                            exclusive,
                            needsDetail,
                            aliases,
                            operator);
                    current.put(
                            option.code(),
                            new OptionRow(
                                    known.id(),
                                    draft.optionSetCode(),
                                    option.code(),
                                    option.label(),
                                    ordinal,
                                    exclusive,
                                    needsDetail,
                                    aliases,
                                    known.sortNo(),
                                    known.status(),
                                    operator,
                                    known.updatedAt()));
                }
            }
        }

        // 变更记录（A-FLD-009）：导入也是字典调整，前后值 + 操作人；失败行不写
        mapper.insertFieldDictLog(
                row.formCode(),
                row.fieldKey(),
                created ? "FIELD_CREATE" : "FIELD_UPDATE",
                created ? null : snapshotOf(existing),
                snapshotOf(row, draft),
                operator);
    }

    /** 调整前快照（取导入开始时的库内形态；范围等 JSON 列以对象形态进快照，与维护口径一致）。 */
    private String snapshotOf(FieldRow field) {
        if (field == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("label", field.label());
        snapshot.put("categoryKey", field.categoryKey());
        snapshot.put("type", field.type());
        snapshot.put("unit", field.unit());
        snapshot.put("decimals", field.decimals());
        snapshot.put("hardRange", readTree(field.hardRange()));
        snapshot.put("softRange", readTree(field.softRange()));
        snapshot.put("optionSetCode", field.optionSetCode());
        snapshot.put("required", field.required());
        snapshot.put("sensitive", field.sensitiveFlag());
        snapshot.put("storage", field.storageType());
        snapshot.put("help", field.helpText());
        snapshot.put("sortNo", field.sortNo());
        snapshot.put("status", field.status());
        return writeJson(snapshot);
    }

    private String snapshotOf(ParsedRow row, FieldDraft draft) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("label", row.label());
        snapshot.put("categoryKey", row.categoryKey());
        snapshot.put("type", draft.type().code());
        snapshot.put("unit", blankToNull(row.unit()));
        snapshot.put("decimals", draft.decimals());
        snapshot.put("hardRange", draft.hardRange() == null ? null : readTree(draft.hardRange()));
        snapshot.put("softRange", draft.softRange() == null ? null : readTree(draft.softRange()));
        snapshot.put("optionSetCode", draft.optionSetCode());
        snapshot.put("required", draft.required());
        snapshot.put("sensitive", draft.sensitive());
        snapshot.put("storage", draft.storage());
        snapshot.put("help", blankToNull(row.help()));
        snapshot.put("sortNo", draft.sortNo());
        snapshot.put("status", draft.status());
        return writeJson(snapshot);
    }

    // ---------------------------------------------------------------- 内部

    /** fe_form.categories（[{key,label,sortNo}]）→ key 清单；脏数据时回落为空集合（分类校验自然失败）。 */
    private static List<String> categoryKeys(FormRow form) {
        List<String> keys = new ArrayList<>();
        if (form.categories() == null || form.categories().isBlank()) {
            return keys;
        }
        try {
            JsonNode node = new ObjectMapper().readTree(form.categories());
            if (node.isArray()) {
                for (JsonNode item : node) {
                    if (item.hasNonNull("key")) {
                        keys.add(item.get("key").asText());
                    }
                }
            }
        } catch (JsonProcessingException ignored) {
            // 脏数据防御：当作没有分类
        }
        return keys;
    }

    /** 「是 / 否」标记：空 / 否 / false / 0 = 否，是 / true / 1 = 是（SimpleXlsx 把布尔单元格读成 TRUE/FALSE）。 */
    private static boolean parseFlag(String raw, String name) {
        if (raw.isBlank() || "否".equals(raw) || "false".equalsIgnoreCase(raw) || "0".equals(raw)) {
            return false;
        }
        if ("是".equals(raw) || "true".equalsIgnoreCase(raw) || "1".equals(raw)) {
            return true;
        }
        throw bad(name + "只认 是 / 否：" + raw);
    }

    private static String parseRange(String min, String max, String name) {
        if (min.isBlank() && max.isBlank()) {
            return null;
        }
        Double minValue = parseDouble(min, name + "下限");
        Double maxValue = parseDouble(max, name + "上限");
        if (minValue != null && maxValue != null && minValue > maxValue) {
            throw bad(name + "的下限不能大于上限");
        }
        Map<String, Object> range = new LinkedHashMap<>();
        if (minValue != null) {
            range.put("min", minValue);
        }
        if (maxValue != null) {
            range.put("max", maxValue);
        }
        return writeJsonStatic(range);
    }

    private static Double parseDouble(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value)) {
                throw bad(name + "必须是有限数字：" + raw);
            }
            return value;
        } catch (NumberFormatException ex) {
            throw bad(name + "必须是数字：" + raw);
        }
    }

    private static Integer parseInt(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException ex) {
            throw bad(name + "必须是整数：" + raw);
        }
    }

    private static String cell(List<String> cells, int index) {
        if (index >= cells.size()) {
            return "";
        }
        String value = cells.get(index);
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private JsonNode readTree(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readTree(raw);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("字段元数据 JSON 序列化失败", ex);
        }
    }

    private static String writeJsonStatic(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("字段元数据 JSON 序列化失败", ex);
        }
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }
}
