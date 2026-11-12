package com.crdp.form.record.app;

import com.crdp.form.field.app.FormDictPort;
import com.crdp.form.field.domain.FieldType;
import com.crdp.form.field.infra.mapper.FormFieldMapper;
import com.crdp.form.field.infra.mapper.OptionRow;
import com.crdp.form.record.infra.mapper.VersionFieldRow;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 表单数据 JSON 的写入闸门（E11-T02 / tech-plan §6：「写入只能经 FormService……禁止绕过服务直接写
 * JSON」）。只做<b>键合法性与类型检查</b>（技术方案「草稿语义」：草稿保存只做类型检查）；范围（硬 / 软）、 必填、条件与跨字段校验随 E11-T03 / E11-T04。
 *
 * <p>值编码口径（tech-plan §6「混合存储」）：单选 / 下拉存选项编码（整数），多选存编码数组，数值存数字， 日期 YYYY-MM-DD，日期时间
 * YYYY-MM-DDTHH:mm，是否存 true/false，数值 × 2 存 {length,width} 对象（键为 稳定键 f0xx）。派生字段不查类型（E11-T05
 * 按定义重算，来源缺失时允许手工录入）。绑定业务字典的字段（fe_field.dict_code，E23-T20 / OQ-69④）编码为<b>字符串</b>（cfg_dict 稳定编码）。
 */
@Component
public class FormDataValidator {

    /** 数值 × 2 对象的键（长 / 宽，FieldType.PAIR_NUMBER 口径）。 */
    static final Set<String> PAIR_KEYS = Set.of("length", "width");

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern DATETIME =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?");

    private final FormFieldMapper fieldMapper;
    private final FormDictPort dictPort;

    public FormDataValidator(FormFieldMapper fieldMapper, FormDictPort dictPort) {
        this.fieldMapper = fieldMapper;
        this.dictPort = dictPort;
    }

    /**
     * 校验 data（键 = 稳定键）。键必须存在于记录的模板版本且为 json 存储；null 值放行（清空）。
     *
     * @param fields 记录模板版本的字段快照（FormService 已按版本加载）
     */
    public void validateData(Map<String, Object> data, List<VersionFieldRow> fields) {
        if (data == null || data.isEmpty()) {
            return;
        }
        Map<String, VersionFieldRow> byKey = new LinkedHashMap<>();
        for (VersionFieldRow field : fields) {
            byKey.put(field.fieldKey(), field);
        }
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            VersionFieldRow field = byKey.get(key);
            if (field == null) {
                throw bad("字段 " + key + " 不在记录的模板版本内，不能写入");
            }
            if (!"json".equals(field.storageType())) {
                throw bad(
                        "字段「"
                                + field.label()
                                + "」（"
                                + key
                                + "）为"
                                + storageText(field.storageType())
                                + "，值不经表单 JSON 记录写入");
            }
            Object value = entry.getValue();
            if (value == null) {
                continue; // 清空
            }
            FieldType type =
                    FieldType.ofCode(field.type())
                            .orElseThrow(() -> new IllegalStateException("未知字段类型：" + field.type()));
            checkValue(field, type, value);
        }
    }

    // ---------------------------------------------------------------- 各类型检查

    private void checkValue(VersionFieldRow field, FieldType type, Object value) {
        switch (type) {
            case NUMBER -> requireNumber(field, value);
            case PAIR_NUMBER -> checkPair(field, value);
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) {
                    throw bad(typeError(field, "是否值（true/false）"));
                }
            }
            case TEXT, TEXTAREA -> {
                if (!(value instanceof String)) {
                    throw bad(typeError(field, "文本"));
                }
            }
            case DATE -> checkDate(field, value);
            case DATETIME -> checkDatetime(field, value);
            case SELECT, RADIO -> checkOption(field, value, false);
            case MULTI_SELECT -> checkOption(field, value, true);
            case DERIVED -> {
                // 派生字段只读、由系统计算（E11-T05）；来源缺失时允许手工录入（tech-plan §6），不查类型
            }
        }
    }

    private void requireNumber(VersionFieldRow field, Object value) {
        if (value instanceof Number number && !(value instanceof Boolean)) {
            if (Double.isFinite(number.doubleValue())) {
                return;
            }
            throw bad(typeError(field, "有限数值"));
        }
        throw bad(typeError(field, "数值"));
    }

    private void checkPair(VersionFieldRow field, Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw bad(typeError(field, "对象 {length, width}"));
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String pairKey) || !PAIR_KEYS.contains(pairKey)) {
                throw bad(typeError(field, "对象 {length, width}（多余键 " + entry.getKey() + "）"));
            }
            if (entry.getValue() != null) {
                requireNumber(field, entry.getValue());
            }
        }
    }

    private void checkDate(VersionFieldRow field, Object value) {
        if (!(value instanceof String text) || !DATE.matcher(text).matches()) {
            throw bad(typeError(field, "日期（YYYY-MM-DD）"));
        }
        try {
            LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw bad(typeError(field, "合法日期（YYYY-MM-DD）"));
        }
    }

    private void checkDatetime(VersionFieldRow field, Object value) {
        if (!(value instanceof String text) || !DATETIME.matcher(text).matches()) {
            throw bad(typeError(field, "日期时间（YYYY-MM-DDTHH:mm）"));
        }
        try {
            LocalDateTime.parse(text.length() == 16 ? text + ":00" : text);
        } catch (DateTimeParseException e) {
            throw bad(typeError(field, "合法日期时间（YYYY-MM-DDTHH:mm）"));
        }
    }

    /**
     * 单选存编码整数、多选存编码数组；编码必须在选项集内（停用选项的旧值可保存，不删除即不失效）。 绑定业务字典的字段（dictCode
     * 非空，OQ-69④）编码为字符串：单选存字符串、多选存字符串数组，编码须在字典全量项内（含停用项，C-FLD-009）。
     */
    private void checkOption(VersionFieldRow field, Object value, boolean multi) {
        if (field.dictCode() != null) {
            checkDictOption(field, value, multi);
            return;
        }
        Set<Integer> codes = optionCodes(field);
        if (!multi) {
            Integer code = asInteger(value);
            if (code == null || !codes.contains(code)) {
                throw bad(
                        typeError(
                                field,
                                "选项编码（"
                                        + codes.stream()
                                                .sorted()
                                                .map(String::valueOf)
                                                .reduce((a, b) -> a + " / " + b)
                                                .orElse("选项集为空")
                                        + "）"));
            }
            return;
        }
        if (!(value instanceof List<?> list)) {
            throw bad(typeError(field, "选项编码数组"));
        }
        Set<Integer> seen = new HashSet<>();
        for (Object item : list) {
            Integer code = asInteger(item);
            if (code == null || !codes.contains(code)) {
                throw bad(typeError(field, "选项编码数组（编码须在选项集内）"));
            }
            if (!seen.add(code)) {
                throw bad(typeError(field, "选项编码数组（编码 " + code + " 重复）"));
            }
        }
    }

    /** 字典绑定字段的选项检查（字符串编码；停用项的历史值可保存，C-FLD-009；无停用标记区分，全量项都收）。 */
    private void checkDictOption(VersionFieldRow field, Object value, boolean multi) {
        Set<String> codes = new HashSet<>();
        for (FormDictPort.DictItem item : dictPort.items(field.dictCode())) {
            codes.add(item.itemCode());
        }
        if (!multi) {
            if (!(value instanceof String code) || !codes.contains(code)) {
                throw bad(typeError(field, "字典编码（" + String.join(" / ", codes) + "）"));
            }
            return;
        }
        if (!(value instanceof List<?> list)) {
            throw bad(typeError(field, "字典编码数组"));
        }
        Set<String> seen = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof String code) || !codes.contains(code)) {
                throw bad(typeError(field, "字典编码数组（编码须在字典项内）"));
            }
            if (!seen.add(code)) {
                throw bad(typeError(field, "字典编码数组（编码 " + code + " 重复）"));
            }
        }
    }

    /** JSON 数值 → 精确整数（1.5、true、字符串都不是选项编码）；null 表示不是整数。 */
    private static Integer asInteger(Object value) {
        if (value instanceof Number number && !(value instanceof Boolean)) {
            double raw = number.doubleValue();
            int code = (int) raw;
            return raw == code ? code : null;
        }
        return null;
    }

    private Set<Integer> optionCodes(VersionFieldRow field) {
        Set<Integer> codes = new HashSet<>();
        if (field.optionSetCode() != null) {
            for (OptionRow option : fieldMapper.findOptions(field.optionSetCode())) {
                codes.add(option.optionCode());
            }
        }
        return codes;
    }

    private static String typeError(VersionFieldRow field, String expected) {
        return "字段「" + field.label() + "」（" + field.fieldKey() + "）应为" + expected;
    }

    private static String storageText(String storageType) {
        return switch (storageType) {
            case "column" -> "实体列存储";
            case "encrypted" -> "加密列存储（敏感字段）";
            default -> storageType + " 存储字段";
        };
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }
}
