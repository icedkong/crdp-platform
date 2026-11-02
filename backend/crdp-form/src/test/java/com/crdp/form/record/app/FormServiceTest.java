package com.crdp.form.record.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDateTime;
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
 * E11-T02 / A-NFR-002、C-NFR-002：JSON 存储与 FormService 单元测试。
 *
 * <p>验收原文：表单按版本读写，旧版本可读。锁定：FormSchema 不传 version 取当前发布版、指定旧版本取旧快照 （字段口径与最新版不同也不受影响）、版本 / 表单不存在
 * 40400；发布固化启用字段并前移 current_version、写 FORM_PUBLISH 审计、并发发布 40900；记录创建固化 templateVersion、保存带
 * baseVersion（缺失 40001、过期 40900 附更新人与不同字段、已提交 40901）、保存按<b>记录版本</b>校验值；值闸门见 {@link
 * FormDataValidatorTest}。 全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class FormServiceTest {

    private static final String CATEGORIES =
            "[{\"key\":\"basic_info\",\"label\":\"病例基本信息\",\"sortNo\":10},"
                    + "{\"key\":\"surgery\",\"label\":\"手术信息\",\"sortNo\":20}]";

    private final FormFieldMapper fieldMapper = mock(FormFieldMapper.class);
    private final FormRecordMapper recordMapper = mock(FormRecordMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final FormService service =
            new FormService(fieldMapper, recordMapper, new FormDataValidator(fieldMapper), audit);

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

    // ---------------------------------------------------------------- 模板：按版本读（旧版本可读）

    @Test
    void readsCurrentSchemaAndOldVersionSnapshot() {
        FormRow form =
                new FormRow(1L, "A_BASELINE", "基线档案（示例）", "BASELINE", 2, CATEGORIES, "ENABLED");
        when(fieldMapper.findForm("A_BASELINE")).thenReturn(form);
        // v2：吸烟史已停用（快照不含）；v1：仍含吸烟史
        when(recordMapper.findVersion("A_BASELINE", 2)).thenReturn(versionRow(2L, 2, 6));
        when(recordMapper.findVersion("A_BASELINE", 1)).thenReturn(versionRow(1L, 1, 7));
        when(recordMapper.findVersionFields("A_BASELINE", 2))
                .thenReturn(List.of(surgeryDate(1), height(1)));
        when(recordMapper.findVersionFields("A_BASELINE", 1))
                .thenReturn(List.of(smoking(1), surgeryDate(1), height(1)));
        when(fieldMapper.findOptions("yesno")).thenReturn(List.of(option(0, "否"), option(1, "是")));

        FormService.SchemaView current = service.schema("A_BASELINE", null);
        assertEquals(2, current.version(), "不传 version 取当前发布版");
        assertEquals("基线档案（示例）", current.name());
        assertEquals("BASELINE", current.usage());
        assertTrue(current.rules().isEmpty(), "rules 随 E11-T04");
        assertEquals(
                List.of("basic_info", "surgery"),
                current.categories().stream().map(FormService.CategoryView::key).toList(),
                "分类按 sortNo 排序");
        assertEquals(
                List.of("f011"),
                current.categories().get(0).fields().stream()
                        .map(FormService.FieldView::key)
                        .toList(),
                "当前版不含已停用的吸烟史");

        FormService.SchemaView v1 = service.schema("A_BASELINE", 1);
        assertEquals(1, v1.version(), "旧版本可读");
        assertTrue(
                v1.categories().get(0).fields().stream()
                        .anyMatch(field -> "f010".equals(field.key())),
                "旧版本快照仍含后停用的字段");
        FormService.FieldView smokingField =
                v1.categories().get(0).fields().stream()
                        .filter(field -> "f010".equals(field.key()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("radio", smokingField.type());
        assertEquals(2, smokingField.optionSet().size(), "选项编码 + 显示名");
        assertEquals("否", smokingField.optionSet().get(0).label());

        FormService.FieldView heightField =
                v1.categories().get(0).fields().stream()
                        .filter(field -> "f011".equals(field.key()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(50.0, heightField.hardRange().min());
        assertEquals(1, heightField.decimals());
        assertEquals("cm", heightField.unit());
    }

    @Test
    void rejectsUnknownFormOrVersion() {
        when(fieldMapper.findForm("NOPE")).thenReturn(null);
        assertEquals(
                40400, assertThrows(ApiException.class, () -> service.schema("NOPE", null)).code());

        when(fieldMapper.findForm("A_BASELINE"))
                .thenReturn(
                        new FormRow(
                                1L,
                                "A_BASELINE",
                                "基线档案（示例）",
                                "BASELINE",
                                1,
                                CATEGORIES,
                                "ENABLED"));
        when(recordMapper.findVersion("A_BASELINE", 9)).thenReturn(null);
        assertEquals(
                40400,
                assertThrows(ApiException.class, () -> service.schema("A_BASELINE", 9)).code());
    }

    /** 字段引用了 categories 未登记的分类：兜底输出该分类（键即标签），不让字段凭空消失。 */
    @Test
    void fallsBackForUndeclaredCategory() {
        when(fieldMapper.findForm("A_BASELINE"))
                .thenReturn(
                        new FormRow(
                                1L,
                                "A_BASELINE",
                                "基线档案（示例）",
                                "BASELINE",
                                1,
                                CATEGORIES,
                                "ENABLED"));
        when(recordMapper.findVersion("A_BASELINE", 1)).thenReturn(versionRow(1L, 1, 1));
        when(recordMapper.findVersionFields("A_BASELINE", 1))
                .thenReturn(
                        List.of(
                                new VersionFieldRow(
                                        1L,
                                        1L,
                                        "A_BASELINE",
                                        1,
                                        "f099",
                                        "extra",
                                        "额外字段（示例）",
                                        "other",
                                        "text",
                                        null,
                                        null,
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
                                        0)));

        FormService.SchemaView schema = service.schema("A_BASELINE", null);
        assertEquals(3, schema.categories().size());
        assertEquals("other", schema.categories().get(2).key());
        assertEquals(1, schema.categories().get(2).fields().size());
    }

    // ---------------------------------------------------------------- 发布

    @Test
    void publishSnapshotsEnabledFieldsAndBumpsVersion() {
        when(fieldMapper.findForm("A_BASELINE"))
                .thenReturn(
                        new FormRow(
                                1L,
                                "A_BASELINE",
                                "基线档案（示例）",
                                "BASELINE",
                                1,
                                CATEGORIES,
                                "ENABLED"));
        when(fieldMapper.findFields("A_BASELINE", null))
                .thenReturn(List.of(enabled("f011", "ENABLED"), enabled("f010", "DISABLED")));
        when(recordMapper.bumpCurrentVersion("A_BASELINE", 1, 2)).thenReturn(1);
        when(recordMapper.snapshotFields("A_BASELINE", 2)).thenReturn(1);
        when(recordMapper.findVersion("A_BASELINE", 2))
                .thenReturn(
                        new FormVersionRow(
                                9L, "A_BASELINE", 2, CATEGORIES, 1, 7L, LocalDateTime.now()));

        FormService.PublishView view = service.publish("A_BASELINE");
        assertEquals(2, view.version());
        assertEquals(1, view.fieldCount(), "只固化启用字段（停用字段不进新版本）");
        assertNotNull(view.publishedAt());

        ArgumentCaptor<FormVersionInsert> version =
                ArgumentCaptor.forClass(FormVersionInsert.class);
        verify(recordMapper).insertVersion(version.capture());
        assertEquals(CATEGORIES, version.getValue().getCategories(), "分类随版本固化");
        verify(recordMapper).snapshotFields("A_BASELINE", 2);
        verify(audit).append(any(AuditEntry.class));
    }

    @Test
    void publishRejectsDisabledFormAndConcurrentBump() {
        when(fieldMapper.findForm("A_OFF"))
                .thenReturn(
                        new FormRow(
                                2L, "A_OFF", "已停用表单（示例）", "BASELINE", 1, CATEGORIES, "DISABLED"));
        assertEquals(
                40001, assertThrows(ApiException.class, () -> service.publish("A_OFF")).code());

        when(fieldMapper.findForm("A_BASELINE"))
                .thenReturn(
                        new FormRow(
                                1L,
                                "A_BASELINE",
                                "基线档案（示例）",
                                "BASELINE",
                                1,
                                CATEGORIES,
                                "ENABLED"));
        when(recordMapper.bumpCurrentVersion("A_BASELINE", 1, 2)).thenReturn(0);
        assertEquals(
                40900,
                assertThrows(ApiException.class, () -> service.publish("A_BASELINE")).code());
        verify(recordMapper, never()).insertVersion(any(FormVersionInsert.class));
    }

    // ---------------------------------------------------------------- 记录：读 / 写 / 版本

    @Test
    void createsRecordPinnedToCurrentVersion() {
        when(fieldMapper.findForm("A_BASELINE"))
                .thenReturn(
                        new FormRow(
                                1L,
                                "A_BASELINE",
                                "基线档案（示例）",
                                "BASELINE",
                                3,
                                CATEGORIES,
                                "ENABLED"));
        when(recordMapper.findVersion("A_BASELINE", 3)).thenReturn(versionRow(30L, 3, 6));
        when(recordMapper.findVersionFields("A_BASELINE", 3))
                .thenReturn(List.of(smoking(3), height(3)));
        when(fieldMapper.findOptions("yesno")).thenReturn(List.of(option(0, "否"), option(1, "是")));
        when(recordMapper.insertRecord(any(FormRecordInsert.class)))
                .thenAnswer(
                        invocation -> {
                            FormRecordInsert insert = invocation.getArgument(0);
                            insert.setId(101L);
                            return 1;
                        });
        when(recordMapper.findRecord(101L))
                .thenReturn(
                        new FormRecordRow(
                                101L,
                                "A_BASELINE",
                                3,
                                "{\"f010\":1}",
                                null,
                                0,
                                "DRAFT",
                                7L,
                                LocalDateTime.now(),
                                null,
                                LocalDateTime.now()));

        FormService.RecordView view =
                service.createRecord("A_BASELINE", Map.of("f010", 1), Map.of());
        assertEquals(101L, view.id());
        assertEquals(3, view.templateVersion(), "记录版本创建时固化 = 当时 current_version");
        assertEquals("DRAFT", view.status());
        assertEquals(0, view.version());
        assertEquals(Map.of("f010", 1), view.data());

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> service.createRecord("A_BASELINE", Map.of("f999", 1), null))
                        .code(),
                "写入闸门：未知键拒绝");
    }

    @Test
    void savesDraftWithOptimisticLock() {
        FormRecordRow row =
                new FormRecordRow(
                        101L,
                        "A_BASELINE",
                        1,
                        "{\"f010\":1}",
                        null,
                        2,
                        "DRAFT",
                        7L,
                        LocalDateTime.now(),
                        8L,
                        LocalDateTime.now());
        FormRecordRow saved =
                new FormRecordRow(
                        101L,
                        "A_BASELINE",
                        1,
                        "{\"f010\":0}",
                        null,
                        3,
                        "DRAFT",
                        7L,
                        LocalDateTime.now(),
                        9L,
                        LocalDateTime.now());
        // findRecord 依次返回保存前旧值 / 保存后新值
        when(recordMapper.findRecord(101L)).thenReturn(row, saved);
        when(recordMapper.findVersionFields("A_BASELINE", 1))
                .thenReturn(List.of(smoking(1), height(1)));
        when(fieldMapper.findOptions("yesno")).thenReturn(List.of(option(0, "否"), option(1, "是")));
        when(recordMapper.updateRecord(eq(101L), eq(2), anyString(), any(), eq(9L))).thenReturn(1);

        FormService.RecordView view =
                service.saveRecord(
                        101L, new FormService.SaveRecordCommand(2, Map.of("f010", 0), null));
        assertEquals(3, view.version(), "保存成功 version + 1");
        assertEquals(Map.of("f010", 0), view.data());
    }

    @Test
    void saveRequiresBaseVersionAndRejectsSubmittedRecord() {
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.saveRecord(
                                                101L,
                                                new FormService.SaveRecordCommand(
                                                        null, Map.of(), null)))
                        .code());
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> service.saveRecord(101L, null)).code());

        when(recordMapper.findRecord(102L))
                .thenReturn(
                        new FormRecordRow(
                                102L,
                                "A_BASELINE",
                                1,
                                "{}",
                                null,
                                1,
                                "SUBMITTED",
                                7L,
                                LocalDateTime.now(),
                                7L,
                                LocalDateTime.now()));
        assertEquals(
                40901,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.saveRecord(
                                                102L,
                                                new FormService.SaveRecordCommand(
                                                        1, Map.of(), null)))
                        .code(),
                "已提交记录的修改走 E15 编辑流程");
    }

    @Test
    void staleBaseVersionReturns40900WithConflictDetail() {
        FormRecordRow row =
                new FormRecordRow(
                        101L,
                        "A_BASELINE",
                        1,
                        "{\"f010\":1,\"f011\":170}",
                        null,
                        5,
                        "DRAFT",
                        7L,
                        LocalDateTime.now(),
                        8L,
                        LocalDateTime.now());
        when(recordMapper.findRecord(101L)).thenReturn(row);

        ApiException conflict =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveRecord(
                                        101L,
                                        new FormService.SaveRecordCommand(
                                                4, Map.of("f010", 0, "f020", "2024-05-06"), null)));
        assertEquals(40900, conflict.code());
        FormService.VersionConflictView detail = (FormService.VersionConflictView) conflict.data();
        assertEquals(5, detail.serverVersion());
        assertEquals("8", detail.updatedBy(), "附更新人");
        assertNotNull(detail.updatedAt());
        assertTrue(
                detail.changedFields().containsAll(List.of("f010", "f011", "f020")),
                "两边不同字段：" + detail.changedFields());
        verify(recordMapper, never()).updateRecord(anyLong(), anyInt(), any(), any(), anyLong());
    }

    /** 更新落库时版本已被并发推进（条件更新 0 行）→ 同样按 40900 冲突返回，不误报成功。 */
    @Test
    void conditionalUpdateMissTurnsIntoConflict() {
        FormRecordRow row =
                new FormRecordRow(
                        101L,
                        "A_BASELINE",
                        1,
                        "{}",
                        null,
                        2,
                        "DRAFT",
                        7L,
                        LocalDateTime.now(),
                        7L,
                        LocalDateTime.now());
        FormRecordRow raced =
                new FormRecordRow(
                        101L,
                        "A_BASELINE",
                        1,
                        "{\"f010\":1}",
                        null,
                        3,
                        "DRAFT",
                        7L,
                        LocalDateTime.now(),
                        8L,
                        LocalDateTime.now());
        when(recordMapper.findRecord(101L)).thenReturn(row, raced);
        when(recordMapper.findVersionFields("A_BASELINE", 1))
                .thenReturn(List.of(smoking(1), height(1)));
        when(fieldMapper.findOptions("yesno")).thenReturn(List.of(option(0, "否"), option(1, "是")));
        when(recordMapper.updateRecord(eq(101L), eq(2), any(), any(), eq(9L))).thenReturn(0);

        ApiException conflict =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveRecord(
                                        101L,
                                        new FormService.SaveRecordCommand(
                                                2, Map.of("f010", 0), null)));
        assertEquals(40900, conflict.code());
        assertEquals(3, ((FormService.VersionConflictView) conflict.data()).serverVersion());
    }

    /** 保存按<b>记录版本</b>校验：记录在 v1，键只认 v1 快照（最新版新增字段不许写进旧记录）。 */
    @Test
    void saveValidatesAgainstRecordVersionNotLatest() {
        FormRecordRow row =
                new FormRecordRow(
                        101L,
                        "A_BASELINE",
                        1,
                        "{}",
                        null,
                        0,
                        "DRAFT",
                        7L,
                        LocalDateTime.now(),
                        null,
                        LocalDateTime.now());
        when(recordMapper.findRecord(101L)).thenReturn(row);
        // v1 快照只有 f010 / f011；f500 是 v2 新增字段
        when(recordMapper.findVersionFields("A_BASELINE", 1))
                .thenReturn(List.of(smoking(1), height(1)));

        ApiException rejected =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveRecord(
                                        101L,
                                        new FormService.SaveRecordCommand(
                                                0, Map.of("f500", 1), null)));
        assertEquals(40001, rejected.code());
        verify(recordMapper, never()).updateRecord(anyLong(), anyInt(), any(), any(), anyLong());
    }

    @Test
    void unknownRecordReturns40400() {
        when(recordMapper.findRecord(404L)).thenReturn(null);
        assertEquals(40400, assertThrows(ApiException.class, () -> service.record(404L)).code());
    }

    // ---------------------------------------------------------------- 辅助（全部虚构）

    private static FormVersionRow versionRow(long id, int version, int fieldCount) {
        return new FormVersionRow(id, "A_BASELINE", version, CATEGORIES, fieldCount, null, null);
    }

    private static OptionRow option(int code, String label) {
        return new OptionRow(
                1L, "yesno", code, label, null, false, false, null, code * 10, "ENABLED", null,
                null);
    }

    private static VersionFieldRow smoking(int version) {
        return new VersionFieldRow(
                1L,
                (long) version,
                "A_BASELINE",
                version,
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
                "示例（A-FLD-002）",
                40);
    }

    private static VersionFieldRow height(int version) {
        return new VersionFieldRow(
                2L,
                (long) version,
                "A_BASELINE",
                version,
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
                50);
    }

    private static VersionFieldRow surgeryDate(int version) {
        return new VersionFieldRow(
                3L,
                (long) version,
                "A_BASELINE",
                version,
                "f020",
                "surgery_date",
                "手术日期",
                "surgery",
                "date",
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                false,
                "column",
                null,
                10);
    }

    private static FieldRow enabled(String key, String status) {
        return new FieldRow(
                1L,
                "A_BASELINE",
                key,
                key + "_code",
                "字段（示例）",
                "basic_info",
                "text",
                null,
                null,
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
                0,
                status,
                null,
                null);
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
                null);
    }
}
