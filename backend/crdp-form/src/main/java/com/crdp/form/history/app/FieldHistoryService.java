package com.crdp.form.history.app;

import com.crdp.form.history.domain.ChangeSource;
import com.crdp.form.history.infra.mapper.FieldHistoryInsert;
import com.crdp.form.history.infra.mapper.FieldHistoryMapper;
import com.crdp.form.history.infra.mapper.FieldHistoryRow;
import com.crdp.form.record.infra.mapper.FormRecordMapper;
import com.crdp.form.record.infra.mapper.VersionFieldRow;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E11-T06 / A-PAT-005、C-PAT-011：字段历史与字段级留痕——每个字段改动可查旧值、新值、人、时间。
 *
 * <p>tech-plan §7「FormService 保存时逐字段比较旧值与新值，同一事务写 fe_field_history」：本服务提供逐字段 比较（{@link
 * #logChanges}）与单字段留痕（{@link #logChange}）两个写入口，业务流程（提交 / 保存修改 E15 与 E25、导入更新 E18、 报告单映射
 * E16-T07、派生重算 E11-T05）在自己的事务里调用——{@code @Transactional} 默认 REQUIRED，自动加入调用方事务；
 * <b>草稿不写字段历史</b>（tech-plan §6「草稿语义：不写字段历史，提交或保存修改时才写」），故 FormService 的草稿口（createRecord /
 * saveRecord） 不调用本服务。
 *
 * <p>E11-T03 的隐藏字段清空（tech-plan §6「被隐藏字段的值清空，清空动作写入字段历史」）由 {@link #logClearedHidden} 承接：
 * 旧值保留、新值为空，触发字段取该字段 visibleWhen 引用的稳定键（提交流程把 {@code FormService.stripHiddenFields} 的 {@code
 * clearedKeys} 连同清洗前的值交给本方法）。
 *
 * <p>值口径（tech-plan §6 值编码）：旧值 / 新值按 fe_record.data 同一编码存 JSON；比较按<b>严格同型</b>（1 与 true、 "1" 与 1
 * 算变化，与条件 DSL eq 同口径 OQ-65 ②），数值按值比较（170 与 170.0 不算变化），空值 = null / 空串 / 空数组 （OQ-65
 * ①空值口径），空对空不算改动。敏感字段不经表单 JSON（混合存储走加密实体列），快照里标记敏感的键<b>防御性跳过</b>（不落明文 历史）；实体加密列字段的历史值加密随 E13
 * 患者域接入时在业务侧处理（tech-plan §7「敏感字段的历史值同样加密存储」）。
 *
 * <p>来源（{@link ChangeSource}）与操作人：用户 / 导入 / 报告单映射须有医院会话（40100，A-PAT-005「修改人」必填口径）； 系统重算允许无会话（定时作业经
 * TenantContextTaskDecorator 执行，无 SecurityContext），操作人记「系统重算」；平台支持视角 显示名追加「（平台支持）」，会话号关联随 E20
 * 租户视角落地（不落令牌片段，AGENTS.md §5.5）。留痕行不再另写平台审计——审计由业务操作（提交、导入等） 记一条操作摘要（tech-plan §14「审计中另记一条操作摘要」）。
 */
@Service
public class FieldHistoryService {

    /** 引擎表单记录（fe_record）的记录类型常量；业务记录（PT_BASELINE_SECTION / FU_RECORD 等）随 E13 / E15 接入。 */
    public static final String FORM_RECORD = "FE_RECORD";

    /** 查询默认条数（变更记录页单页口径，超长历史按 id 倒序取最近一段）。 */
    public static final int DEFAULT_LIMIT = 200;

    private static final int MAX_RECORD_TYPE = 32;
    private static final int MAX_FIELD_KEY = 32;
    private static final int MAX_REASON = 500;
    private static final int MAX_TRIGGER_FIELDS = 255;

    private final FormRecordMapper recordMapper;
    private final FieldHistoryMapper historyMapper;
    private final ObjectMapper json = new ObjectMapper();

    public FieldHistoryService(FormRecordMapper recordMapper, FieldHistoryMapper historyMapper) {
        this.recordMapper = recordMapper;
        this.historyMapper = historyMapper;
    }

    // ---------------------------------------------------------------- 视图

    /** 一条字段变更（查询结果）：验收四要素旧值、新值、人（ID + 显示名快照）、时间 + 来源 / 触发字段 / 原因。 */
    public record HistoryView(
            long id,
            String recordType,
            long recordId,
            String formCode,
            Integer templateVersion,
            String fieldKey,
            Object oldValue,
            Object newValue,
            String source,
            String sourceLabel,
            List<String> triggerFields,
            String reason,
            Long operatorId,
            String operatorName,
            String changedAt) {}

    /** 写入口返回的一条改动（调用方确认与统计用，含被清空 / 新填的空端）。 */
    public record FieldChange(String fieldKey, Object oldValue, Object newValue) {}

    // ---------------------------------------------------------------- 写入：逐字段比较（tech-plan §7）

    /**
     * 逐字段比较旧值与新值并逐行写 fe_field_history（一个字段一行，未变不写；行序 = 模板快照的 sortNo 顺序）。 供提交 / 保存修改（E15 / E25，比较
     * DRAFT→SUBMITTED 或上次提交值）、导入更新（E18「只改动有变化的字段并留痕」）、 报告单映射批量写入（E16-T07）调用，同一事务生效。
     *
     * @param before 保存前 data（fe_record.data 解析值；null 视为全空）
     * @param after 保存后 data
     * @return 实际写下的改动（无变化为空列表）
     */
    @Transactional
    public List<FieldChange> logChanges(
            String recordType,
            long recordId,
            String formCode,
            int templateVersion,
            Map<String, Object> before,
            Map<String, Object> after,
            ChangeSource source,
            String reason) {
        validateCommon(recordType, reason, null);
        Operator operator = resolveOperator(source);
        List<VersionFieldRow> fields = requireFields(formCode, templateVersion);

        Map<String, Object> oldValues = before == null ? Map.of() : before;
        Map<String, Object> newValues = after == null ? Map.of() : after;
        List<FieldChange> written = new ArrayList<>();
        for (String key : orderedKeys(fields, oldValues, newValues)) {
            Object oldValue = oldValues.get(key);
            Object newValue = newValues.get(key);
            if (valuesEqual(oldValue, newValue)) {
                continue;
            }
            insert(
                    recordType,
                    recordId,
                    formCode,
                    templateVersion,
                    key,
                    oldValue,
                    newValue,
                    source,
                    null,
                    reason,
                    operator);
            written.add(new FieldChange(key, oldValue, newValue));
        }
        return List.copyOf(written);
    }

    // ---------------------------------------------------------------- 写入：单字段（重算 / 映射 / 平台支持）

    /**
     * 单字段留痕：派生字段系统重算（A 规格 §4「来源标为系统重算，记录触发字段」= 依赖来源字段）、报告单映射写入单个字段、 平台支持视角修正等。值口径与 {@link
     * #logChanges} 相同。
     *
     * @param triggerFields 触发字段稳定键（系统重算 = 依赖来源字段；无则空）
     */
    @Transactional
    public void logChange(
            String recordType,
            long recordId,
            String formCode,
            int templateVersion,
            String fieldKey,
            Object oldValue,
            Object newValue,
            ChangeSource source,
            String reason,
            List<String> triggerFields) {
        validateCommon(recordType, reason, triggerFields);
        if (fieldKey == null || fieldKey.isBlank() || fieldKey.length() > MAX_FIELD_KEY) {
            throw bad("字段稳定键不能为空且不超过 " + MAX_FIELD_KEY + " 字符");
        }
        Operator operator = resolveOperator(source);
        List<VersionFieldRow> fields = requireFields(formCode, templateVersion);
        for (VersionFieldRow field : fields) {
            if (fieldKey.equals(field.fieldKey()) && field.sensitiveFlag()) {
                return; // 防御：敏感字段不经表单 JSON，也不落明文历史（配置不该出现，同 logChanges 口径）
            }
        }
        insert(
                recordType,
                recordId,
                formCode,
                templateVersion,
                fieldKey,
                oldValue,
                newValue,
                source,
                joinKeys(triggerFields),
                reason,
                operator);
    }

    // ---------------------------------------------------------------- 写入：隐藏字段清空（E11-T03 承接）

    /**
     * 隐藏字段清空留痕（tech-plan §6「被隐藏字段的值清空，清空动作写入字段历史」；OQ-65 ④）：提交流程在 {@code
     * FormService.stripHiddenFields} 后，把被清空键的<b>清洗前值</b>交给本方法——旧值保留、新值为空、来源 USER（发生在
     * 用户提交流程）、触发字段取该字段 visibleWhen 引用的稳定键（解析失败 / 无引用则为空，坏配置不炸提交）。
     *
     * @param clearedValues 清洗前有值的隐藏字段（键 → 被清掉的值；空清单不写行）
     */
    @Transactional
    public List<FieldChange> logClearedHidden(
            String recordType,
            long recordId,
            String formCode,
            int templateVersion,
            Map<String, Object> clearedValues) {
        validateCommon(recordType, null, null);
        if (clearedValues == null || clearedValues.isEmpty()) {
            return List.of();
        }
        Operator operator = resolveOperator(ChangeSource.USER);
        List<VersionFieldRow> fields = requireFields(formCode, templateVersion);

        List<FieldChange> written = new ArrayList<>();
        for (String key : orderedKeys(fields, clearedValues, Map.of())) {
            if (!clearedValues.containsKey(key)) {
                continue; // 只写实际被清空的键（orderedKeys 含快照全部键，按表单布局排序）
            }
            Object oldValue = clearedValues.get(key);
            String triggers = joinKeys(conditionFieldRefs(fields, key));
            insert(
                    recordType,
                    recordId,
                    formCode,
                    templateVersion,
                    key,
                    oldValue,
                    null,
                    ChangeSource.USER,
                    triggers,
                    "字段隐藏条件不成立，提交时清空残留值",
                    operator);
            written.add(new FieldChange(key, oldValue, null));
        }
        return List.copyOf(written);
    }

    // ---------------------------------------------------------------- 查询（验收：可查旧值、新值、人、时间）

    /** 按记录查全部字段的历史（新到旧，默认 {@value #DEFAULT_LIMIT} 条；变更记录页列表口径）。 */
    @Transactional(readOnly = true)
    public List<HistoryView> history(String recordType, long recordId) {
        return history(recordType, recordId, null);
    }

    /** 按记录查历史，可按字段过滤（单字段追溯）。fieldKey 缺省 = 不过滤（全部字段），传空白 40001； recordType 空白 40001。 */
    @Transactional(readOnly = true)
    public List<HistoryView> history(String recordType, long recordId, String fieldKey) {
        if (recordType == null || recordType.isBlank()) {
            throw bad("记录类型不能为空");
        }
        boolean filtered = fieldKey != null;
        if (fieldKey != null && fieldKey.isBlank()) {
            throw bad("字段稳定键不能为空（不按字段过滤请缺省该参数）");
        }
        List<FieldHistoryRow> rows =
                filtered
                        ? historyMapper.findByField(recordType, recordId, fieldKey, DEFAULT_LIMIT)
                        : historyMapper.findByRecord(recordType, recordId, DEFAULT_LIMIT);
        List<HistoryView> views = new ArrayList<>(rows.size());
        for (FieldHistoryRow row : rows) {
            views.add(toView(row));
        }
        return views;
    }

    // ---------------------------------------------------------------- 内部

    /** 字段历史行视图：值解析回值编码口径，来源带中文标签，时间北京时间 ISO-8601。 */
    private HistoryView toView(FieldHistoryRow row) {
        return new HistoryView(
                row.id(),
                row.recordType(),
                row.recordId(),
                row.formCode(),
                row.templateVersion(),
                row.fieldKey(),
                readValue(row.oldValue()),
                readValue(row.newValue()),
                row.source(),
                labelOf(row.source()),
                splitKeys(row.triggerFields()),
                row.reason(),
                row.operatorId(),
                row.operatorName(),
                Time.format(row.createdAt()));
    }

    private static String labelOf(String source) {
        for (ChangeSource value : ChangeSource.values()) {
            if (value.name().equals(source)) {
                return value.label();
            }
        }
        return source;
    }

    /** 单行落库（值按值编码存 JSON 文本；空端存 NULL）。 */
    private void insert(
            String recordType,
            long recordId,
            String formCode,
            int templateVersion,
            String fieldKey,
            Object oldValue,
            Object newValue,
            ChangeSource source,
            String triggerFields,
            String reason,
            Operator operator) {
        historyMapper.insert(
                new FieldHistoryInsert(
                        recordType,
                        recordId,
                        formCode,
                        templateVersion,
                        fieldKey,
                        writeJson(oldValue),
                        writeJson(newValue),
                        source.name(),
                        triggerFields,
                        reason,
                        operator.id(),
                        operator.name()));
    }

    /** 记录类型 / 原因 / 触发字段的通用校验（40001，不落部分行）。 */
    private static void validateCommon(
            String recordType, String reason, List<String> triggerFields) {
        if (recordType == null || recordType.isBlank()) {
            throw bad("记录类型不能为空");
        }
        if (recordType.length() > MAX_RECORD_TYPE) {
            throw bad("记录类型不超过 " + MAX_RECORD_TYPE + " 字符");
        }
        if (reason != null && reason.length() > MAX_REASON) {
            throw bad("修改原因不超过 " + MAX_REASON + " 字");
        }
        if (triggerFields != null) {
            String joined = joinKeys(triggerFields);
            if (joined != null && joined.length() > MAX_TRIGGER_FIELDS) {
                throw bad("触发字段过多（拼接后不超过 " + MAX_TRIGGER_FIELDS + " 字符）");
            }
        }
    }

    /** 操作人解析（见类注释：医院会话 / 平台支持 / 无会话系统重算三分支）。 */
    private static Operator resolveOperator(ChangeSource source) {
        return switch (source) {
            case USER, IMPORT, REPORT -> {
                SessionPrincipal principal = CurrentSession.requireHospital();
                yield new Operator(principal.userId(), principal.displayName());
            }
            case PLATFORM_SUPPORT -> {
                SessionPrincipal principal = CurrentSession.require();
                yield new Operator(principal.userId(), principal.displayName() + "（平台支持）");
            }
            case SYSTEM_RECALC ->
                    CurrentSession.current()
                            .map(
                                    principal ->
                                            new Operator(
                                                    principal.userId(), principal.displayName()))
                            .orElseGet(() -> new Operator(null, "系统重算"));
        };
    }

    private record Operator(Long id, String name) {}

    /** 记录模板版本的字段快照（敏感过滤与行序依据）；版本不存在 40400（不静默丢敏感过滤）。 */
    private List<VersionFieldRow> requireFields(String formCode, int templateVersion) {
        List<VersionFieldRow> fields = recordMapper.findVersionFields(formCode, templateVersion);
        if (fields.isEmpty()) {
            throw new ApiException(404, 40400, "模板版本不存在：" + formCode + " v" + templateVersion);
        }
        return fields;
    }

    /**
     * 参与比较的键，按模板快照顺序（sortNo、fieldKey；变更记录页按表单布局展示）；敏感键防御性跳过（含
     * 兜底追加段，不落明文历史），不在快照里的键（已过校验的数据不应出现）排在末尾兜底。
     */
    private static List<String> orderedKeys(
            List<VersionFieldRow> fields,
            Map<String, Object> oldValues,
            Map<String, Object> newValues) {
        List<VersionFieldRow> ordered = new ArrayList<>(fields);
        ordered.sort(
                java.util.Comparator.comparingInt(VersionFieldRow::sortNo)
                        .thenComparing(VersionFieldRow::fieldKey));
        LinkedHashSet<String> sensitiveKeys = new LinkedHashSet<>();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (VersionFieldRow field : ordered) {
            if (field.sensitiveFlag()) {
                sensitiveKeys.add(field.fieldKey()); // 敏感字段不经表单 JSON；出现也不落明文历史
                continue;
            }
            keys.add(field.fieldKey());
        }
        for (String key : new LinkedHashSet<>(concat(oldValues.keySet(), newValues.keySet()))) {
            if (!sensitiveKeys.contains(key)) {
                keys.add(key);
            }
        }
        return List.copyOf(keys);
    }

    /** 该字段 visibleWhen 条件树引用的稳定键（field 叶子，按出现顺序去重；ctx 变量不是字段、坏 JSON 返回空）。 */
    private List<String> conditionFieldRefs(List<VersionFieldRow> fields, String fieldKey) {
        for (VersionFieldRow field : fields) {
            if (fieldKey.equals(field.fieldKey())) {
                LinkedHashSet<String> refs = new LinkedHashSet<>();
                collectFieldRefs(readTree(field.visibleWhen()), refs);
                return List.copyOf(refs);
            }
        }
        return List.of();
    }

    /** 递归收集条件树里的 {field: "f0xx"} 叶子（all / any 嵌套任意深度）。 */
    private static void collectFieldRefs(JsonNode node, LinkedHashSet<String> refs) {
        if (node == null || !node.isObject()) {
            return;
        }
        JsonNode field = node.get("field");
        if (field != null && field.isTextual() && !field.asText().isBlank()) {
            refs.add(field.asText());
        }
        for (String container : List.of("all", "any")) {
            JsonNode children = node.get(container);
            if (children != null && children.isArray()) {
                for (JsonNode child : children) {
                    collectFieldRefs(child, refs);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 值比较（严格同型，与条件 DSL eq 同口径）

    /**
     * 旧值与新值是否相等（不算改动）：空值口径 = null / 空串 / 空数组（OQ-65 ①）；数值按值比较（170 与 170.0 相等）；
     * 布尔与数值、字符串与数值<b>不相等</b>（严格同型，OQ-65 ②）；数组与对象逐元素比较。
     */
    private static boolean valuesEqual(Object oldValue, Object newValue) {
        if (isEmptyValue(oldValue) && isEmptyValue(newValue)) {
            return true;
        }
        if (oldValue instanceof Number oldNum
                && newValue instanceof Number newNum
                && !(oldValue instanceof Boolean)
                && !(newValue instanceof Boolean)) {
            return oldNum.doubleValue() == newNum.doubleValue();
        }
        if (oldValue instanceof List<?> oldList && newValue instanceof List<?> newList) {
            if (oldList.size() != newList.size()) {
                return false;
            }
            for (int i = 0; i < oldList.size(); i++) {
                if (!valuesEqual(oldList.get(i), newList.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (oldValue instanceof Map<?, ?> oldMap && newValue instanceof Map<?, ?> newMap) {
            if (!oldMap.keySet().equals(newMap.keySet())) {
                return false;
            }
            for (Map.Entry<?, ?> entry : oldMap.entrySet()) {
                if (!valuesEqual(entry.getValue(), newMap.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(oldValue, newValue);
    }

    /** 空值口径（OQ-65 ①，与 FormConditionEngine 同）：null / 空串 / 空数组；0 与 false 是已答。 */
    private static boolean isEmptyValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isEmpty();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        return false;
    }

    // ---------------------------------------------------------------- JSON 与文本辅助

    private static String joinKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        return String.join(",", keys);
    }

    private static List<String> splitKeys(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return List.of(joined.split(","));
    }

    private Object readValue(String raw) {
        JsonNode node = readTree(raw);
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return json.readValue(raw, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("字段历史值解析失败", e);
        }
    }

    private JsonNode readTree(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(raw);
            return node.isNull() ? null : node;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("字段历史 JSON 解析失败", e);
        }
    }

    private String writeJson(Object value) {
        if (value == null || isEmptyValue(value)) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("字段历史值序列化失败", e);
        }
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

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

    /** 时间展示口径（北京时间 ISO-8601 带时区，与 FormService / FieldDictionaryService 一致）。 */
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
