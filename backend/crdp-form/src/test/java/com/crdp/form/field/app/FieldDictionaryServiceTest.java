package com.crdp.form.field.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.form.field.infra.mapper.FieldRow;
import com.crdp.form.field.infra.mapper.FormFieldMapper;
import com.crdp.form.field.infra.mapper.FormRow;
import com.crdp.form.field.infra.mapper.OptionRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E11-T01 / A-FLD-001、A-FLD-002、C-FLD-001：字段字典单元测试。
 *
 * <p>验收原文：显示名与编码分离，字段可启停。锁定：视图同时带稳定键 / 字段编码 / 显示名与选项「编码 + 显示名」（分离）；维护只改显示名 / 帮助 / 单位 / 软范围 / 必填 /
 * 状态与选项显示名 / 排序 / 停用 / 新增（键、编码、类型、硬范围、敏感标记不在更新参数上）；停用写审计并在描述点明；软范围与显示名校验
 * 40001；选项编码不可改不可删、非选项型字段拒绝维护选项；字段 / 表单不存在 40400。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class FieldDictionaryServiceTest {

    private final FormFieldMapper mapper = mock(FormFieldMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final FieldDictionaryService service = new FieldDictionaryService(mapper, audit);

    /** fe_form 种子形态：分类 JSON（锚点键稳定）。 */
    private static final String CATEGORIES =
            "[{\"key\":\"basic_info\",\"label\":\"病例基本信息\",\"sortNo\":10},"
                    + "{\"key\":\"surgery\",\"label\":\"手术信息\",\"sortNo\":20}]";

    private static final FormRow BASELINE =
            new FormRow(1L, "A_BASELINE", "基线档案（示例）", "BASELINE", 1, CATEGORIES, "ENABLED");

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

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例）",
                7L,
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

    /** A-FLD-002 的关键形态：吸烟史——显示名「吸烟史」、选项 0=否 / 1=是。 */
    private static FieldRow smokingRow() {
        return new FieldRow(
                10L,
                "A_BASELINE",
                "f010",
                "smoking_history",
                "吸烟史",
                "basic_info",
                "radio",
                null,
                null,
                null,
                null,
                "yesno",
                true,
                null,
                null,
                null,
                false,
                "json",
                "示例帮助",
                40,
                "ENABLED",
                null,
                LocalDateTime.of(2027, 2, 15, 9, 0));
    }

    private static OptionRow option(int code, String label, int sortNo, String status) {
        return new OptionRow(
                (long) code,
                "yesno",
                code,
                label,
                null,
                false,
                false,
                null,
                sortNo,
                status,
                null,
                LocalDateTime.of(2027, 2, 15, 9, 0));
    }

    // ---------------------------------------------------------------- 读取

    /** 验收「显示名与编码分离」：视图同时给 key / code / label，选项给编码 + 显示名，分类给锚点键 + 显示名。 */
    @Test
    void listViewCarriesKeyCodeAndLabelSeparately() {
        when(mapper.findForm("A_BASELINE")).thenReturn(BASELINE);
        when(mapper.findFields("A_BASELINE", null)).thenReturn(List.of(smokingRow()));
        when(mapper.findOptions("yesno"))
                .thenReturn(List.of(option(0, "否", 10, "ENABLED"), option(1, "是", 20, "ENABLED")));

        List<FieldDictionaryService.FieldView> views = service.fields("A_BASELINE", null);

        assertEquals(1, views.size());
        FieldDictionaryService.FieldView view = views.get(0);
        assertEquals("f010", view.key(), "稳定键（JSON 数据键）");
        assertEquals("smoking_history", view.code(), "字段编码（导入导出名，不可改）");
        assertEquals("吸烟史", view.label(), "显示名（页面只显示它，不带“(否=0，是=1)”）");
        assertEquals("basic_info", view.categoryKey());
        assertEquals("病例基本信息", view.categoryLabel());
        assertEquals(2, view.options().size());
        assertEquals(0, view.options().get(0).code(), "数据库存编码 0");
        assertEquals("否", view.options().get(0).label(), "页面显示显示名「否」");
    }

    /** 软 / 硬范围、条件与派生定义解析为对象（height：软 130–210、硬 50–250）。 */
    @Test
    void listViewParsesRangeAndDefinitions() {
        FieldRow height =
                new FieldRow(
                        11L,
                        "A_BASELINE",
                        "f011",
                        "height_cm",
                        "身高",
                        "basic_info",
                        "number",
                        "cm",
                        1,
                        "{\"min\":50,\"max\":250}",
                        "{\"min\":130,\"max\":210}",
                        null,
                        false,
                        null,
                        null,
                        null,
                        false,
                        "json",
                        null,
                        50,
                        "ENABLED",
                        null,
                        LocalDateTime.of(2027, 2, 15, 9, 0));
        when(mapper.findForm("A_BASELINE")).thenReturn(BASELINE);
        when(mapper.findFields("A_BASELINE", null)).thenReturn(List.of(height));

        FieldDictionaryService.FieldView view = service.fields("A_BASELINE", null).get(0);

        assertEquals(50.0, view.hardRange().min());
        assertEquals(250.0, view.hardRange().max());
        assertEquals(130.0, view.softRange().min());
        assertEquals("cm", view.unit());
        assertTrue(view.options().isEmpty(), "非选项型字段没有选项");
    }

    /** 表单不存在按 40400（不暴露存在）；表单编码必填。 */
    @Test
    void listRejectsUnknownForm() {
        when(mapper.findForm("NOPE")).thenReturn(null);
        ApiException missing = assertThrows(ApiException.class, () -> service.fields("NOPE", null));
        assertEquals(40400, missing.code());

        ApiException blank = assertThrows(ApiException.class, () -> service.fields(" ", null));
        assertEquals(40001, blank.code());
    }

    // ---------------------------------------------------------------- 维护：显示名与软范围

    /** 正常维护：改显示名与软范围，更新语句只带可维护列（键 / 编码 / 类型 / 硬范围不在参数上）。 */
    @Test
    void updateOverwritesEditableColumnsOnly() {
        FieldRow row = smokingRow();
        FieldRow saved =
                new FieldRow(
                        10L,
                        "A_BASELINE",
                        "f010",
                        "smoking_history",
                        "吸烟史（既往）",
                        "basic_info",
                        "radio",
                        null,
                        null,
                        null,
                        null,
                        "yesno",
                        false,
                        null,
                        null,
                        null,
                        false,
                        "json",
                        "帮助说明（示例）",
                        40,
                        "ENABLED",
                        7L,
                        LocalDateTime.of(2027, 2, 15, 9, 30));
        when(mapper.findForm("A_BASELINE")).thenReturn(BASELINE);
        when(mapper.findField("A_BASELINE", "f010")).thenReturn(row, saved);
        when(mapper.findOptions("yesno")).thenReturn(List.of());

        FieldDictionaryService.FieldView view =
                service.update(
                        "A_BASELINE",
                        "f010",
                        new FieldDictionaryService.FieldUpdate(
                                "吸烟史（既往）", "帮助说明（示例）", null, null, false, "ENABLED", null));

        assertEquals("吸烟史（既往）", view.label());
        verify(mapper)
                .updateField(
                        eq("A_BASELINE"),
                        eq("f010"),
                        eq("吸烟史（既往）"),
                        eq("帮助说明（示例）"),
                        any(),
                        any(),
                        eq(false),
                        eq("ENABLED"),
                        eq(7L));
    }

    /** 显示名必填且 ≤128；软范围 min≤max，否则 40001（A-FLD-003 的配置口径）。 */
    @Test
    void updateValidatesLabelAndSoftRange() {
        when(mapper.findForm("A_BASELINE")).thenReturn(BASELINE);
        when(mapper.findField("A_BASELINE", "f010")).thenReturn(smokingRow());

        ApiException blank =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        "A_BASELINE",
                                        "f010",
                                        new FieldDictionaryService.FieldUpdate(
                                                " ", null, null, null, null, "ENABLED", null)));
        assertEquals(40001, blank.code());

        ApiException tooLong =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        "A_BASELINE",
                                        "f010",
                                        new FieldDictionaryService.FieldUpdate(
                                                "长".repeat(129),
                                                null,
                                                null,
                                                null,
                                                null,
                                                "ENABLED",
                                                null)));
        assertEquals(40001, tooLong.code());

        ApiException inverted =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        "A_BASELINE",
                                        "f010",
                                        new FieldDictionaryService.FieldUpdate(
                                                "吸烟史",
                                                null,
                                                null,
                                                new FieldDictionaryService.RangeView(210.0, 130.0),
                                                null,
                                                "ENABLED",
                                                null)));
        assertEquals("提示范围的最小值不能大于最大值", inverted.getMessage());
        verify(mapper, never())
                .updateField(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        anyString(),
                        anyLong());
    }

    /** 字段不存在 40400；请求体与状态值非法 40001。 */
    @Test
    void updateRejectsUnknownFieldAndBadStatus() {
        when(mapper.findForm("A_BASELINE")).thenReturn(BASELINE);
        when(mapper.findField("A_BASELINE", "f999")).thenReturn(null);
        ApiException missing =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        "A_BASELINE",
                                        "f999",
                                        new FieldDictionaryService.FieldUpdate(
                                                "x", null, null, null, null, "ENABLED", null)));
        assertEquals(40400, missing.code());

        when(mapper.findField("A_BASELINE", "f010")).thenReturn(smokingRow());
        ApiException badStatus =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        "A_BASELINE",
                                        "f010",
                                        new FieldDictionaryService.FieldUpdate(
                                                "吸烟史", null, null, null, null, "PAUSED", null)));
        assertEquals(40001, badStatus.code());
    }

    // ---------------------------------------------------------------- 维护：字段启停

    /** 验收「字段可启停」：停用后状态 DISABLED，写 FIELD_DICT_UPDATE 审计且描述点明停用。 */
    @Test
    void disableFieldWritesAuditWithStatusChange() {
        FieldRow row = smokingRow();
        FieldRow disabled =
                new FieldRow(
                        10L,
                        "A_BASELINE",
                        "f010",
                        "smoking_history",
                        "吸烟史",
                        "basic_info",
                        "radio",
                        null,
                        null,
                        null,
                        null,
                        "yesno",
                        true,
                        null,
                        null,
                        null,
                        false,
                        "json",
                        null,
                        40,
                        "DISABLED",
                        7L,
                        LocalDateTime.of(2027, 2, 15, 9, 30));
        when(mapper.findForm("A_BASELINE")).thenReturn(BASELINE);
        when(mapper.findField("A_BASELINE", "f010")).thenReturn(row, disabled);
        when(mapper.findOptions("yesno")).thenReturn(List.of());

        FieldDictionaryService.FieldView view =
                service.update(
                        "A_BASELINE",
                        "f010",
                        new FieldDictionaryService.FieldUpdate(
                                "吸烟史", null, null, null, true, "DISABLED", null));

        assertEquals("DISABLED", view.status());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals("FIELD_DICT_UPDATE", entry.action());
        assertEquals("FE_FIELD", entry.objectType());
        assertEquals("A_BASELINE.f010", entry.objectId());
        assertTrue(entry.description().contains("字段已停用"), "描述点明停用：" + entry.description());
        assertNotNull(entry.beforeJson());
        assertNotNull(entry.afterJson());
    }

    /** 状态不变时不点明启停（描述只说维护）。 */
    @Test
    void updateWithoutStatusChangeKeepsPlainDescription() {
        FieldRow row = smokingRow();
        when(mapper.findForm("A_BASELINE")).thenReturn(BASELINE);
        when(mapper.findField("A_BASELINE", "f010")).thenReturn(row, row);
        when(mapper.findOptions("yesno")).thenReturn(List.of());

        service.update(
                "A_BASELINE",
                "f010",
                new FieldDictionaryService.FieldUpdate(
                        "吸烟史", null, null, null, null, "ENABLED", null));

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertTrue(!captor.getValue().description().contains("停用"));
        assertTrue(!captor.getValue().description().contains("启用"));
    }

    // ---------------------------------------------------------------- 维护：选项

    /** 选项维护：改显示名与排序、停用已有选项、新增选项；已有选项编码用作 WHERE，不会被改。 */
    @Test
    void updateMaintainsOptionsWithoutChangingCodes() {
        FieldRow row = smokingRow();
        when(mapper.findForm("A_BASELINE")).thenReturn(BASELINE);
        when(mapper.findField("A_BASELINE", "f010")).thenReturn(row, row);
        when(mapper.findOptions("yesno"))
                .thenReturn(List.of(option(0, "否", 10, "ENABLED"), option(1, "是", 20, "ENABLED")));

        service.update(
                "A_BASELINE",
                "f010",
                new FieldDictionaryService.FieldUpdate(
                        "吸烟史",
                        null,
                        null,
                        null,
                        null,
                        "ENABLED",
                        List.of(
                                new FieldDictionaryService.OptionUpdate(1, "是（目前吸烟）", 30, null),
                                new FieldDictionaryService.OptionUpdate(2, "已戒烟（示例）", 40, null))));

        // 既有编码 1 → update（显示名 / 排序）；新编码 2 → insert；编码 0 未提交不动
        verify(mapper)
                .updateOption(eq("yesno"), eq(1), eq("是（目前吸烟）"), eq(30), eq("ENABLED"), eq(7L));
        verify(mapper)
                .insertOption(eq("yesno"), eq(2), eq("已戒烟（示例）"), eq(40), eq("ENABLED"), eq(7L));
        verify(mapper, never())
                .updateOption(eq("yesno"), eq(0), anyString(), anyInt(), anyString(), anyLong());
    }

    /** 选项显示名必填、编码必填且不重复；非选项型字段（无选项集）拒绝维护选项。 */
    @Test
    void updateValidatesOptions() {
        when(mapper.findForm("A_BASELINE")).thenReturn(BASELINE);
        when(mapper.findOptions("yesno")).thenReturn(List.of());

        FieldRow row = smokingRow();
        when(mapper.findField("A_BASELINE", "f010")).thenReturn(row);
        ApiException blankLabel =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        "A_BASELINE",
                                        "f010",
                                        new FieldDictionaryService.FieldUpdate(
                                                "吸烟史",
                                                null,
                                                null,
                                                null,
                                                null,
                                                "ENABLED",
                                                List.of(
                                                        new FieldDictionaryService.OptionUpdate(
                                                                1, " ", null, null)))));
        assertEquals(40001, blankLabel.code());

        ApiException duplicated =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        "A_BASELINE",
                                        "f010",
                                        new FieldDictionaryService.FieldUpdate(
                                                "吸烟史",
                                                null,
                                                null,
                                                null,
                                                null,
                                                "ENABLED",
                                                List.of(
                                                        new FieldDictionaryService.OptionUpdate(
                                                                1, "是", null, null),
                                                        new FieldDictionaryService.OptionUpdate(
                                                                1, "否", null, null)))));
        assertEquals("选项编码重复：1", duplicated.getMessage());

        FieldRow height =
                new FieldRow(
                        11L,
                        "A_BASELINE",
                        "f011",
                        "height_cm",
                        "身高",
                        "basic_info",
                        "number",
                        "cm",
                        1,
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        null,
                        false,
                        "json",
                        null,
                        50,
                        "ENABLED",
                        null,
                        LocalDateTime.of(2027, 2, 15, 9, 0));
        when(mapper.findField("A_BASELINE", "f011")).thenReturn(height);
        ApiException notOptionType =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        "A_BASELINE",
                                        "f011",
                                        new FieldDictionaryService.FieldUpdate(
                                                "身高",
                                                null,
                                                null,
                                                null,
                                                null,
                                                "ENABLED",
                                                List.of(
                                                        new FieldDictionaryService.OptionUpdate(
                                                                1, "是", null, null)))));
        assertEquals("该字段不是选项型字段，不能维护选项", notOptionType.getMessage());
    }

    /** 停用选项（已有数据的选项不删除，只 DISABLED）。 */
    @Test
    void updateDisablesOptionInsteadOfDeleting() {
        FieldRow row = smokingRow();
        when(mapper.findForm("A_BASELINE")).thenReturn(BASELINE);
        when(mapper.findField("A_BASELINE", "f010")).thenReturn(row, row);
        when(mapper.findOptions("yesno")).thenReturn(List.of(option(1, "是", 20, "ENABLED")));

        service.update(
                "A_BASELINE",
                "f010",
                new FieldDictionaryService.FieldUpdate(
                        "吸烟史",
                        null,
                        null,
                        null,
                        null,
                        "ENABLED",
                        List.of(new FieldDictionaryService.OptionUpdate(1, "是", 20, "DISABLED"))));

        verify(mapper).updateOption(eq("yesno"), eq(1), eq("是"), eq(20), eq("DISABLED"), eq(7L));
        verify(mapper, never())
                .insertOption(anyString(), anyInt(), anyString(), anyInt(), anyString(), anyLong());
    }

    /** 表单清单视图（登录即可的下拉数据源）。 */
    @Test
    void formsListsSummaries() {
        when(mapper.findForms()).thenReturn(List.of(BASELINE));
        List<FieldDictionaryService.FormSummaryView> forms = service.forms();
        assertEquals(1, forms.size());
        assertEquals("A_BASELINE", forms.get(0).code());
        assertEquals("BASELINE", forms.get(0).usage());
        assertEquals(1, forms.get(0).currentVersion());

        lenient().when(mapper.findForm(anyString())).thenReturn(null);
    }

    // ---------------------------------------------------------------
    // E11-T08：变更记录（A-FLD-009、C-FLD-009）

    /** 维护（含停用）写 fe_field_dict_log：前后值快照 + 操作人（与 FIELD_DICT_UPDATE 审计双写）。 */
    @Test
    void updateWritesFieldDictChangeLog() {
        FieldRow row = smokingRow();
        FieldRow disabled =
                new FieldRow(
                        10L,
                        "A_BASELINE",
                        "f010",
                        "smoking_history",
                        "吸烟史",
                        "basic_info",
                        "radio",
                        null,
                        null,
                        null,
                        null,
                        "yesno",
                        true,
                        null,
                        null,
                        null,
                        false,
                        "json",
                        null,
                        40,
                        "DISABLED",
                        7L,
                        LocalDateTime.of(2027, 2, 15, 9, 30));
        when(mapper.findForm("A_BASELINE")).thenReturn(BASELINE);
        when(mapper.findField("A_BASELINE", "f010")).thenReturn(row, disabled);
        when(mapper.findOptions("yesno")).thenReturn(List.of());

        service.update(
                "A_BASELINE",
                "f010",
                new FieldDictionaryService.FieldUpdate(
                        "吸烟史", null, null, null, true, "DISABLED", null));

        verify(mapper)
                .insertFieldDictLog(
                        eq("A_BASELINE"),
                        eq("f010"),
                        eq("FIELD_UPDATE"),
                        argThat(before -> before != null && before.contains("\"ENABLED\"")),
                        argThat(after -> after != null && after.contains("\"DISABLED\"")),
                        eq(7L));
    }

    /** 变更记录查询（倒序，含操作人姓名）；字段 / 表单不存在 40400。 */
    @Test
    void logsReturnsRecordsAndUnknownFieldIs40400() {
        when(mapper.findForm("A_BASELINE")).thenReturn(BASELINE);
        when(mapper.findField("A_BASELINE", "f010")).thenReturn(smokingRow());
        when(mapper.findFieldDictLogs("A_BASELINE", "f010", 100))
                .thenReturn(
                        List.of(
                                new FormFieldMapper.FieldDictLogRow(
                                        5L,
                                        "A_BASELINE",
                                        "f010",
                                        "FIELD_UPDATE",
                                        "{\"status\":\"ENABLED\"}",
                                        "{\"status\":\"DISABLED\"}",
                                        7L,
                                        "张医生（示例）",
                                        LocalDateTime.of(2027, 3, 1, 9, 0))));

        List<FieldDictionaryService.FieldLogView> logs = service.logs("A_BASELINE", "f010");

        assertEquals(1, logs.size());
        FieldDictionaryService.FieldLogView view = logs.get(0);
        assertEquals("FIELD_UPDATE", view.action());
        assertEquals("张医生（示例）", view.operatorName(), "操作人姓名（sys_user 联查）");
        assertNotNull(view.createdAt());
        assertTrue(view.afterJson().contains("DISABLED"));

        when(mapper.findField("A_BASELINE", "f999")).thenReturn(null);
        ApiException missing =
                assertThrows(ApiException.class, () -> service.logs("A_BASELINE", "f999"));
        assertEquals(40400, missing.code());

        when(mapper.findForm("NOPE")).thenReturn(null);
        ApiException noForm = assertThrows(ApiException.class, () -> service.logs("NOPE", "f010"));
        assertEquals(40400, noForm.code());
    }
}
