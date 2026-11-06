package com.crdp.form.history.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.form.history.domain.ChangeSource;
import com.crdp.form.history.infra.mapper.FieldHistoryInsert;
import com.crdp.form.history.infra.mapper.FieldHistoryMapper;
import com.crdp.form.history.infra.mapper.FieldHistoryRow;
import com.crdp.form.record.infra.mapper.FormRecordMapper;
import com.crdp.form.record.infra.mapper.VersionFieldRow;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E11-T06 / A-PAT-005、C-PAT-011：字段历史与字段级留痕单元测试。
 *
 * <p>验收原文：每个字段改动可查旧值、新值、人、时间。锁定：逐字段比较旧值与新值、一个字段一行（未变 不写）；数值按值比较（170 与 170.0 不算变化、1 与 true 算变化）、空值口径
 * = null / 空串 / 空数组（OQ-65 ①）；敏感字段不经表单 JSON（tech-plan §6）， 快照里标记敏感的键防御性跳过；来源 USER / SYSTEM_RECALC /
 * IMPORT / REPORT / PLATFORM_SUPPORT（tech-plan §6「来源」）；系统重算无
 * 会话也可写（定时作业，操作人「系统重算」）；隐藏字段清空保留旧值、新值为空，触发字段取自 visibleWhen 引用的字段键；查询按记录 /
 * 字段过滤、新到旧、含旧值新值操作人时间。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class FieldHistoryServiceTest {

    private final FormRecordMapper recordMapper = mock(FormRecordMapper.class);
    private final FieldHistoryMapper historyMapper = mock(FieldHistoryMapper.class);
    private final FieldHistoryService service =
            new FieldHistoryService(recordMapper, historyMapper);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 逐字段比较（tech-plan §7）

    /** 修改 / 导入更新 / 报告单映射写入：逐字段比较，一个字段一行，未变字段不写；行含旧值、新值、来源、操作人。 */
    @Test
    void logsOneRowPerChangedFieldWithOldNewSourceAndOperator() {
        when(recordMapper.findVersionFields("A_BASELINE", 1))
                .thenReturn(List.of(smoking(), height(), surgeryDate(), note()));

        List<FieldHistoryService.FieldChange> changes =
                service.logChanges(
                        FieldHistoryService.FORM_RECORD,
                        101L,
                        "A_BASELINE",
                        1,
                        Map.of("f010", 1, "f011", 170, "f013", "病情稳定（示例）"),
                        Map.of("f010", 0, "f012", "2024-05-06", "f013", "病情稳定（示例）"),
                        ChangeSource.USER,
                        null);

        assertEquals(
                List.of("f012", "f010", "f011"),
                changes.stream().map(FieldHistoryService.FieldChange::fieldKey).toList(),
                "改动字段按快照顺序（sortNo：f012=10、f010=40、f011=50）：f010 改值、f011 清空、f012 新填；f013 未变不写");

        ArgumentCaptor<FieldHistoryInsert> captor =
                ArgumentCaptor.forClass(FieldHistoryInsert.class);
        verify(historyMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        Map<String, FieldHistoryInsert> rows =
                new LinkedHashMap<>(
                        captor.getAllValues().stream()
                                .collect(
                                        java.util.stream.Collectors.toMap(
                                                FieldHistoryInsert::getFieldKey, r -> r)));

        FieldHistoryInsert changed = rows.get("f010");
        assertEquals("FE_RECORD", changed.getRecordType());
        assertEquals(101L, changed.getRecordId());
        assertEquals("A_BASELINE", changed.getFormCode());
        assertEquals(1, changed.getTemplateVersion());
        assertEquals("1", changed.getOldValue(), "旧值按值编码存 JSON");
        assertEquals("0", changed.getNewValue());
        assertEquals("USER", changed.getSource());
        assertEquals(9L, changed.getOperatorId(), "操作人 = 当前医院会话");
        assertEquals("张医生（示例）", changed.getOperatorName(), "操作人显示名快照");

        assertEquals("170", rows.get("f011").getOldValue());
        assertEquals(null, rows.get("f011").getNewValue(), "清空：新值为 NULL");
        assertEquals(null, rows.get("f012").getOldValue(), "新填：旧值为 NULL");
        assertEquals("\"2024-05-06\"", rows.get("f012").getNewValue(), "日期按值编码存字符串");
    }

    /** 值比较口径：数值按值比较（170 与 170.0 不算变化）；1 与 true 算变化（严格同型）；空串与 null 同为空。 */
    @Test
    void comparesValuesByEncodingSemantics() {
        when(recordMapper.findVersionFields("A_BASELINE", 1))
                .thenReturn(List.of(smoking(), height(), note()));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("f011", 170.0);
        after.put("f013", null);
        assertTrue(
                service.logChanges(
                                FieldHistoryService.FORM_RECORD,
                                101L,
                                "A_BASELINE",
                                1,
                                Map.of("f011", 170, "f013", ""),
                                after,
                                ChangeSource.USER,
                                null)
                        .isEmpty(),
                "170 与 170.0、空串与 null 都不算字段改动");
        verify(historyMapper, never()).insert(any(FieldHistoryInsert.class));

        List<FieldHistoryService.FieldChange> typeChange =
                service.logChanges(
                        FieldHistoryService.FORM_RECORD,
                        101L,
                        "A_BASELINE",
                        1,
                        Map.of("f010", 1),
                        Map.of("f010", true),
                        ChangeSource.USER,
                        null);
        assertEquals(List.of("f010"), typeChange.stream().map(c -> c.fieldKey()).toList());
        verify(historyMapper)
                .insert(
                        org.mockito.ArgumentMatchers.argThat(
                                row ->
                                        "f010".equals(row.getFieldKey())
                                                && "1".equals(row.getOldValue())
                                                && "true".equals(row.getNewValue())));
    }

    /** 敏感字段不经表单 JSON（tech-plan §6 混合存储）；快照里标记敏感的键防御性跳过，不落明文历史。 */
    @Test
    void skipsSensitiveKeysDefensively() {
        when(recordMapper.findVersionFields("A_BASELINE", 1))
                .thenReturn(List.of(smoking(), sensitiveName()));

        List<FieldHistoryService.FieldChange> changes =
                service.logChanges(
                        FieldHistoryService.FORM_RECORD,
                        101L,
                        "A_BASELINE",
                        1,
                        Map.of("f010", 1, "f019", "张三（示例）"),
                        Map.of("f010", 0, "f019", "李四（示例）"),
                        ChangeSource.USER,
                        null);
        assertEquals(List.of("f010"), changes.stream().map(c -> c.fieldKey()).toList());
        verify(historyMapper, never())
                .insert(
                        org.mockito.ArgumentMatchers.argThat(
                                row -> "f019".equals(row.getFieldKey())));
    }

    /** 无变化不写任何行（同值保存不产生历史噪音）。 */
    @Test
    void noChangeWritesNothing() {
        when(recordMapper.findVersionFields("A_BASELINE", 1))
                .thenReturn(List.of(smoking(), height()));

        assertTrue(
                service.logChanges(
                                FieldHistoryService.FORM_RECORD,
                                101L,
                                "A_BASELINE",
                                1,
                                Map.of("f010", 1, "f011", 170.5),
                                new LinkedHashMap<>(Map.of("f010", 1, "f011", 170.5)),
                                ChangeSource.USER,
                                null)
                        .isEmpty());
        verify(historyMapper, never()).insert(any(FieldHistoryInsert.class));
    }

    /** 快照为空（表单版本不存在）→ 40400，不静默跳过敏感过滤落库。 */
    @Test
    void unknownVersionRejectsWith40400() {
        when(recordMapper.findVersionFields("A_BASELINE", 9)).thenReturn(List.of());
        assertEquals(
                40400,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.logChanges(
                                                FieldHistoryService.FORM_RECORD,
                                                101L,
                                                "A_BASELINE",
                                                9,
                                                Map.of("f010", 1),
                                                Map.of("f010", 0),
                                                ChangeSource.USER,
                                                null))
                        .code());
    }

    // ---------------------------------------------------------------- 单字段留痕（系统重算 / 报告单 / 平台支持）

    /** 系统重算（A 规格 §4「来源标为系统重算」）：带触发字段（依赖来源）；无会话（定时作业）也可写，操作人「系统重算」。 */
    @Test
    void logsRecalcWithTriggerFieldsWithoutSession() {
        SecurityContextHolder.clearContext();
        when(recordMapper.findVersionFields("A_BASELINE", 1)).thenReturn(List.of(bmi()));

        service.logChange(
                FieldHistoryService.FORM_RECORD,
                101L,
                "A_BASELINE",
                1,
                "f040",
                22.4,
                23.1,
                ChangeSource.SYSTEM_RECALC,
                null,
                List.of("f050", "f051"));

        verify(historyMapper)
                .insert(
                        org.mockito.ArgumentMatchers.argThat(
                                row ->
                                        "f040".equals(row.getFieldKey())
                                                && "22.4".equals(row.getOldValue())
                                                && "23.1".equals(row.getNewValue())
                                                && "SYSTEM_RECALC".equals(row.getSource())
                                                && "f050,f051".equals(row.getTriggerFields())
                                                && row.getOperatorId() == null
                                                && "系统重算".equals(row.getOperatorName())));
    }

    /** 报告单映射写入（E16-T07 前置）：来源 REPORT、带原因；操作人取当前医院会话。 */
    @Test
    void logsReportMappingWithReason() {
        when(recordMapper.findVersionFields("A_BASELINE", 1)).thenReturn(List.of(height()));

        service.logChange(
                FieldHistoryService.FORM_RECORD,
                101L,
                "A_BASELINE",
                1,
                "f011",
                52,
                56,
                ChangeSource.REPORT,
                "心脏超声报告单（示例）映射写入",
                List.of());

        verify(historyMapper)
                .insert(
                        org.mockito.ArgumentMatchers.argThat(
                                row ->
                                        "REPORT".equals(row.getSource())
                                                && "心脏超声报告单（示例）映射写入".equals(row.getReason())
                                                && Long.valueOf(9L).equals(row.getOperatorId())));
    }

    /** 平台支持视角（tech-plan §14）：操作人显示名追加「（平台支持）」；会话号关联随 E20 租户视角落地。 */
    @Test
    void labelsPlatformSupportOperator() {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new SessionPrincipal(
                                        "token",
                                        "PLATFORM",
                                        1L,
                                        "demoa",
                                        "医院 A（示例）",
                                        5L,
                                        "ops.demo",
                                        "平台支持（示例）",
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        1L,
                                        ScopeSnapshot.allowAll(),
                                        false,
                                        null),
                                null,
                                List.of()));
        when(recordMapper.findVersionFields("A_BASELINE", 1)).thenReturn(List.of(height()));

        service.logChange(
                FieldHistoryService.FORM_RECORD,
                101L,
                "A_BASELINE",
                1,
                "f011",
                52,
                56,
                ChangeSource.PLATFORM_SUPPORT,
                "平台支持人员修正录入（示例）",
                List.of());

        verify(historyMapper)
                .insert(
                        org.mockito.ArgumentMatchers.argThat(
                                row ->
                                        "PLATFORM_SUPPORT".equals(row.getSource())
                                                && Long.valueOf(5L).equals(row.getOperatorId())
                                                && "平台支持（示例）（平台支持）".equals(row.getOperatorName())));
    }

    /** 用户来源没有医院会话 → 40100（不静默丢操作人，A-PAT-005「修改人」必填口径）。 */
    @Test
    void userSourceRequiresHospitalSession() {
        SecurityContextHolder.clearContext();
        when(recordMapper.findVersionFields("A_BASELINE", 1)).thenReturn(List.of(height()));

        assertEquals(
                40100,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.logChange(
                                                FieldHistoryService.FORM_RECORD,
                                                101L,
                                                "A_BASELINE",
                                                1,
                                                "f011",
                                                52,
                                                56,
                                                ChangeSource.USER,
                                                null,
                                                List.of()))
                        .code());
        verify(historyMapper, never()).insert(any(FieldHistoryInsert.class));
    }

    // ---------------------------------------------------------------- 隐藏字段清空（E11-T03 clearedKeys）

    /** 清空隐藏字段（tech-plan §6「清空动作写入字段历史」）：旧值保留、新值为空，触发字段取 visibleWhen 引用的字段键。 */
    @Test
    void logsClearedHiddenWithConditionTriggerFields() {
        when(recordMapper.findVersionFields("C_PROGRAM_FU", 1)).thenReturn(List.of(icdDischarge()));

        List<FieldHistoryService.FieldChange> changes =
                service.logClearedHidden(
                        FieldHistoryService.FORM_RECORD,
                        101L,
                        "C_PROGRAM_FU",
                        1,
                        Map.of("f700", 15));

        assertEquals(1, changes.size());
        verify(historyMapper)
                .insert(
                        org.mockito.ArgumentMatchers.argThat(
                                row ->
                                        "f700".equals(row.getFieldKey())
                                                && "15".equals(row.getOldValue())
                                                && row.getNewValue() == null
                                                && "USER".equals(row.getSource())
                                                && "f010,f020".equals(row.getTriggerFields())
                                                && Long.valueOf(9L).equals(row.getOperatorId())));
    }

    /** 空清空清单不写行（stripHiddenFields 已只返回有值键，防御同样成立）。 */
    @Test
    void clearedHiddenWithoutKeysWritesNothing() {
        assertTrue(
                service.logClearedHidden(
                                FieldHistoryService.FORM_RECORD, 101L, "C_PROGRAM_FU", 1, Map.of())
                        .isEmpty());
        verify(historyMapper, never()).insert(any(FieldHistoryInsert.class));
    }

    // ---------------------------------------------------------------- 查询（验收：可查旧值、新值、人、时间）

    /** 按记录查询：新到旧；值解析回值编码口径；来源带中文标签；时间北京时间 ISO-8601。 */
    @Test
    void historyReturnsNewToOldWithParsedValuesOperatorAndTime() {
        when(historyMapper.findByRecord("FE_RECORD", 101L, 200))
                .thenReturn(
                        List.of(
                                row(2L, "f010", "1", "0", "USER", null, 9L, "张医生（示例）"),
                                row(1L, "f010", null, "1", "IMPORT", "宽表导入更新（示例）", 8L, "李医生（示例）")));

        List<FieldHistoryService.HistoryView> views =
                service.history(FieldHistoryService.FORM_RECORD, 101L);

        assertEquals(2, views.size());
        FieldHistoryService.HistoryView latest = views.get(0);
        assertEquals(2L, latest.id());
        assertEquals("f010", latest.fieldKey());
        assertEquals(1, ((Number) latest.oldValue()).intValue(), "旧值解析回 JSON 值");
        assertEquals(0, ((Number) latest.newValue()).intValue());
        assertEquals("USER", latest.source());
        assertEquals("用户", latest.sourceLabel());
        assertEquals(9L, latest.operatorId());
        assertEquals("张医生（示例）", latest.operatorName());
        assertNotNull(latest.changedAt(), "时间（北京时间 ISO-8601）");
        assertEquals("导入", views.get(1).sourceLabel());
        assertEquals("宽表导入更新（示例）", views.get(1).reason());
    }

    /** 按字段过滤：调用方（变更记录页）单字段追溯时下推 field_key 条件。 */
    @Test
    void historyFiltersByFieldKey() {
        when(historyMapper.findByField("FE_RECORD", 101L, "f011", 200))
                .thenReturn(List.of(row(3L, "f011", "170", "175", "USER", null, 9L, "张医生（示例）")));

        List<FieldHistoryService.HistoryView> views =
                service.history(FieldHistoryService.FORM_RECORD, 101L, "f011");
        assertEquals(1, views.size());
        assertEquals("f011", views.get(0).fieldKey());
        assertEquals(175, ((Number) views.get(0).newValue()).intValue());
        verify(historyMapper).findByField("FE_RECORD", 101L, "f011", 200);
    }

    // ---------------------------------------------------------------- 入参校验

    /** 记录类型 / 字段键 / 原因 / 触发字段的长度与必填校验（40001），不落部分行。 */
    @Test
    void validatesCommandShape() {
        when(recordMapper.findVersionFields("A_BASELINE", 1)).thenReturn(List.of(height()));

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.logChange(
                                                " ",
                                                101L,
                                                "A_BASELINE",
                                                1,
                                                "f011",
                                                52,
                                                56,
                                                ChangeSource.USER,
                                                null,
                                                List.of()))
                        .code(),
                "记录类型必填");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.logChange(
                                                FieldHistoryService.FORM_RECORD,
                                                101L,
                                                "A_BASELINE",
                                                1,
                                                "",
                                                52,
                                                56,
                                                ChangeSource.USER,
                                                null,
                                                List.of()))
                        .code(),
                "字段键必填");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.logChange(
                                                FieldHistoryService.FORM_RECORD,
                                                101L,
                                                "A_BASELINE",
                                                1,
                                                "f011",
                                                52,
                                                56,
                                                ChangeSource.USER,
                                                "原因（示例）".repeat(100),
                                                List.of()))
                        .code(),
                "原因 ≤500 字");
        verify(historyMapper, never()).insert(any(FieldHistoryInsert.class));

        // 查询入参同样校验
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> service.history(FieldHistoryService.FORM_RECORD, 101L, " "))
                        .code());
    }

    /** 记录类型超过列宽（VARCHAR(32)）拒绝，避免数据库截断。 */
    @Test
    void rejectsOverlongRecordType() {
        when(recordMapper.findVersionFields("A_BASELINE", 1)).thenReturn(List.of(height()));
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.logChange(
                                                "X".repeat(33),
                                                101L,
                                                "A_BASELINE",
                                                1,
                                                "f011",
                                                52,
                                                56,
                                                ChangeSource.USER,
                                                null,
                                                List.of()))
                        .code());
    }

    /** 触发字段拼接后超列宽（VARCHAR(255)）拒绝。 */
    @Test
    void rejectsOverlongTriggerFields() {
        when(recordMapper.findVersionFields("A_BASELINE", 1)).thenReturn(List.of(height()));
        List<String> many =
                java.util.stream.IntStream.range(0, 30)
                        .mapToObj(i -> "key" + String.format("%07d", i))
                        .toList();
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.logChange(
                                        FieldHistoryService.FORM_RECORD,
                                        101L,
                                        "A_BASELINE",
                                        1,
                                        "f011",
                                        52,
                                        56,
                                        ChangeSource.USER,
                                        null,
                                        many));
        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("触发字段"));
    }

    // ---------------------------------------------------------------- 辅助（全部虚构）

    private static FieldHistoryRow row(
            long id,
            String fieldKey,
            String oldValue,
            String newValue,
            String source,
            String reason,
            Long operatorId,
            String operatorName) {
        return new FieldHistoryRow(
                id,
                "FE_RECORD",
                101L,
                "A_BASELINE",
                1,
                fieldKey,
                oldValue,
                newValue,
                source,
                null,
                reason,
                operatorId,
                operatorName,
                LocalDateTime.of(2027, 2, 22, 10, 30, 0));
    }

    private static VersionFieldRow field(
            String key, String code, String label, String type, boolean sensitive, int sortNo) {
        return new VersionFieldRow(
                1L,
                1L,
                "A_BASELINE",
                1,
                key,
                code,
                label,
                "basic_info",
                type,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                sensitive,
                "json",
                null,
                sortNo);
    }

    private static VersionFieldRow smoking() {
        return field("f010", "smoking_history", "吸烟史", "radio", false, 40);
    }

    private static VersionFieldRow height() {
        return field("f011", "height_cm", "身高", "number", false, 50);
    }

    private static VersionFieldRow surgeryDate() {
        return field("f012", "surgery_date", "手术日期", "date", false, 10);
    }

    private static VersionFieldRow note() {
        return field("f013", "general_note", "一般备注", "textarea", false, 90);
    }

    /** 敏感字段（防御：不应出现在 data JSON，出现也不落明文历史）。 */
    private static VersionFieldRow sensitiveName() {
        return field("f019", "patient_name", "姓名", "text", true, 5);
    }

    /** BMI（派生字段示例：来源字段 f050 体重 / f051 身高）。 */
    private static VersionFieldRow bmi() {
        return field("f040", "bmi", "BMI", "derived", false, 60);
    }

    /** ICD 放电次数（示例：visibleWhen 引用 f700 自身分组与 f010 / f020）。 */
    private static VersionFieldRow icdDischarge() {
        return new VersionFieldRow(
                1L,
                1L,
                "C_PROGRAM_FU",
                1,
                "f700",
                "icd_discharge",
                "ICD 放电次数（示例）",
                "icd",
                "number",
                null,
                null,
                null,
                null,
                null,
                false,
                "{\"any\":[{\"field\":\"f010\",\"op\":\"eq\",\"value\":1},{\"all\":[{\"field\":\"f020\",\"op\":\"notEmpty\"}]}]}",
                null,
                null,
                false,
                "json",
                null,
                10);
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例）",
                9L,
                "nurse.demo",
                "张医生（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of("followup:field:edit"),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                0L,
                null);
    }
}
