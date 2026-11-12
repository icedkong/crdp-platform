package com.crdp.form.record.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import com.crdp.form.field.app.FormDictPort;
import com.crdp.form.field.infra.mapper.FormFieldMapper;
import com.crdp.form.record.infra.mapper.FormRecordMapper;
import com.crdp.form.record.infra.mapper.FormRecordRow;
import com.crdp.form.record.infra.mapper.VersionFieldRow;
import com.crdp.form.rule.app.FormConditionEngine;
import com.crdp.form.rule.app.FormDerivedEngine;
import com.crdp.form.rule.app.FormProgressEngine;
import com.crdp.form.rule.app.FormRuleEngine;
import com.crdp.form.rule.infra.mapper.FormRuleMapper;
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
 * E25-T01 / NFR-SAVE-001、NFR-SAVE-003：服务器自动保存草稿口径（tech-plan §6「服务器自动保存与版本冲突」）。
 *
 * <p>锁定：只带变更字段（changes 部分合并到既有 data，null 清空该键）；baseVersion 过期返回 40900 并附更新人 （显示名）、更新时间与两边不同字段（key /
 * label / mine / server，值相同的键不列）；并发竞态（条件更新 0 行）同样 40900； 已提交 40901、记录 / 模板版本不存在 40400、缺 baseVersion
 * 40001；草稿只过写入闸门（未知键 / 类型 40001、硬范围 42200）， 必填与跨字段不进草稿闸门（「草稿不触发提交逻辑」）；不写字段历史、不逐次写审计。 全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class FormServiceDraftTest {

    /** 字典端口桩：不绑字典（本测试无 dict_code 字段）。 */
    private static final FormDictPort dictPort = code -> List.of();

    private final FormFieldMapper fieldMapper = mock(FormFieldMapper.class);
    private final FormRecordMapper recordMapper = mock(FormRecordMapper.class);
    private final FormRuleMapper ruleMapper = mock(FormRuleMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final FormService service =
            new FormService(
                    fieldMapper,
                    recordMapper,
                    dictPort,
                    new FormDataValidator(fieldMapper, dictPort),
                    ruleMapper,
                    new FormRuleEngine(new FormConditionEngine()),
                    new FormConditionEngine(),
                    new FormDerivedEngine(),
                    new FormProgressEngine(new FormConditionEngine()),
                    audit);

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

    // ---------------------------------------------------------------- 保存：只合并变更字段

    @Test
    void mergesPartialChangesAndBumpsVersion() {
        FormRecordRow before = record(2, "{\"f010\":1,\"f011\":170}", 8L);
        FormRecordRow after = record(3, "{\"f010\":0,\"f011\":170}", 9L);
        when(recordMapper.findRecord(101L)).thenReturn(before, after);
        when(recordMapper.findVersionFields("A_BASELINE", 1))
                .thenReturn(List.of(smoking(), height()));
        when(fieldMapper.findOptions("yesno")).thenReturn(List.of(option(0, "否"), option(1, "是")));
        when(recordMapper.updateRecord(eq(101L), eq(2), anyString(), any(), eq(9L))).thenReturn(1);

        FormService.RecordView view = service.saveDraft(101L, 2, Map.of("f010", 0));

        assertEquals(3, view.version(), "保存成功 version + 1");
        assertEquals(0, view.data().get("f010"), "变更字段写入");
        assertEquals(170, ((Number) view.data().get("f011")).intValue(), "未提及字段原样保留");
        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        verify(recordMapper)
                .updateRecord(eq(101L), eq(2), data.capture(), eq("{\"meta\":\"kept\"}"), eq(9L));
        assertTrue(data.getValue().contains("\"f011\":170"), "落库 JSON 含未提及字段：" + data.getValue());
        verify(audit, never()).append(any());
    }

    @Test
    void nullChangeClearsTheField() {
        FormRecordRow before = record(2, "{\"f010\":1,\"f011\":170}", 8L);
        FormRecordRow after = record(3, "{\"f011\":170}", 9L);
        when(recordMapper.findRecord(101L)).thenReturn(before, after);
        when(recordMapper.findVersionFields("A_BASELINE", 1))
                .thenReturn(List.of(smoking(), height()));
        when(recordMapper.updateRecord(eq(101L), eq(2), anyString(), any(), eq(9L))).thenReturn(1);

        Map<String, Object> changes = new java.util.LinkedHashMap<>();
        changes.put("f010", null);
        service.saveDraft(101L, 2, changes);

        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        verify(recordMapper).updateRecord(eq(101L), eq(2), data.capture(), any(), eq(9L));
        assertFalse(data.getValue().contains("f010"), "null 变更 = 清空该键：" + data.getValue());
    }

    @Test
    void draftSkipsSubmitValidationButKeepsWriteGate() {
        // f010 必填且本次不提交它：草稿语义「不做必填 / 跨字段校验」（tech-plan §6 草稿语义）
        FormRecordRow before = record(2, "{}", 8L);
        FormRecordRow after = record(3, "{\"f011\":175}", 9L);
        when(recordMapper.findRecord(101L)).thenReturn(before, after);
        when(recordMapper.findVersionFields("A_BASELINE", 1))
                .thenReturn(List.of(smoking(), height()));
        when(recordMapper.updateRecord(eq(101L), eq(2), anyString(), any(), eq(9L))).thenReturn(1);

        FormService.RecordView view = service.saveDraft(101L, 2, Map.of("f011", 175));
        assertEquals(175, ((Number) view.data().get("f011")).intValue(), "缺必填字段的草稿照常保存");
    }

    @Test
    void writeGateRejectsUnknownKeyAndHardRange() {
        when(recordMapper.findRecord(101L)).thenReturn(record(2, "{}", 8L));
        when(recordMapper.findVersionFields("A_BASELINE", 1))
                .thenReturn(List.of(smoking(), height()));

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> service.saveDraft(101L, 2, Map.of("f999", 1)))
                        .code(),
                "未知键不能写入");
        assertEquals(
                42200,
                assertThrows(
                                ApiException.class,
                                () -> service.saveDraft(101L, 2, Map.of("f011", 300)))
                        .code(),
                "超硬范围（>250）拦截");
        verify(recordMapper, never()).updateRecord(anyLong(), anyInt(), any(), any(), anyLong());
    }

    // ---------------------------------------------------------------- 乐观锁与 409 冲突详情

    @Test
    void staleBaseVersionReturns40900WithDiffFields() {
        FormRecordRow row = record(5, "{\"f010\":1,\"f011\":170}", 8L);
        when(recordMapper.findRecord(101L)).thenReturn(row);
        when(recordMapper.findVersionFields("A_BASELINE", 1))
                .thenReturn(List.of(smoking(), height()));
        when(recordMapper.findDisplayName(8L)).thenReturn("张医生（示例）");

        ApiException conflict =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveDraft(
                                        101L,
                                        4,
                                        Map.of(
                                                "f010", 0,
                                                "f011", 170)));
        assertEquals(40900, conflict.code());
        assertEquals(409, conflict.httpStatus());
        assertEquals("该记录已被他人更新", conflict.getMessage(), "patient.md §4.1 冲突文案");
        FormService.DraftConflictView detail = (FormService.DraftConflictView) conflict.data();
        assertEquals(5, detail.currentVersion(), "附服务器当前版本号");
        assertEquals("张医生（示例）", detail.updatedBy(), "附更新人显示名");
        assertNotNull(detail.updatedAt(), "附更新时间");
        assertEquals(1, detail.diffFields().size(), "值相同的键（f011）不列：" + detail.diffFields());
        FormService.DiffFieldView diff = detail.diffFields().get(0);
        assertEquals("f010", diff.key());
        assertEquals("吸烟史", diff.label(), "label 取记录模板版本快照的显示名");
        assertEquals(0, diff.mine());
        assertEquals(1, diff.server());
        verify(recordMapper, never()).updateRecord(anyLong(), anyInt(), any(), any(), anyLong());
        verify(audit, never()).append(any());
    }

    @Test
    void conflictWithoutUpdaterNameStillCarriesDiff() {
        FormRecordRow row = record(5, "{\"f010\":1}", null);
        when(recordMapper.findRecord(101L)).thenReturn(row);
        when(recordMapper.findVersionFields("A_BASELINE", 1)).thenReturn(List.of(smoking()));

        FormService.DraftConflictView detail =
                (FormService.DraftConflictView)
                        assertThrows(
                                        ApiException.class,
                                        () -> service.saveDraft(101L, 4, Map.of("f010", 0)))
                                .data();
        assertNull(detail.updatedBy(), "更新人缺省（系统 / 未知）时不猜名字");
        assertEquals(1, detail.diffFields().size());
        assertEquals("f010", detail.diffFields().get(0).key());
        assertEquals("吸烟史", detail.diffFields().get(0).label());
    }

    @Test
    void concurrentRaceTurnsIntoConflict() {
        FormRecordRow before = record(2, "{\"f010\":1}", 8L);
        FormRecordRow winner = record(3, "{\"f010\":1}", 8L);
        when(recordMapper.findRecord(101L)).thenReturn(before, winner);
        when(recordMapper.findVersionFields("A_BASELINE", 1)).thenReturn(List.of(smoking()));
        when(fieldMapper.findOptions("yesno")).thenReturn(List.of(option(0, "否"), option(1, "是")));
        when(recordMapper.updateRecord(eq(101L), eq(2), anyString(), any(), eq(9L))).thenReturn(0);

        ApiException conflict =
                assertThrows(
                        ApiException.class, () -> service.saveDraft(101L, 2, Map.of("f010", 0)));
        assertEquals(40900, conflict.code(), "条件更新 0 行（他人已抢先保存）同样返回冲突");
        assertEquals(3, ((FormService.DraftConflictView) conflict.data()).currentVersion());
    }

    // ---------------------------------------------------------------- 状态与入参校验

    @Test
    void rejectsMissingBaseVersionSubmittedAndMissingRecord() {
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> service.saveDraft(101L, null, Map.of("f010", 0)))
                        .code(),
                "baseVersion 必填（乐观锁）");

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
                                () -> service.saveDraft(102L, 1, Map.of("f010", 0)))
                        .code(),
                "已提交记录不自动保存（patient.md §4.1）");

        when(recordMapper.findRecord(404L)).thenReturn(null);
        assertEquals(
                40400,
                assertThrows(
                                ApiException.class,
                                () -> service.saveDraft(404L, 1, Map.of("f010", 0)))
                        .code(),
                "记录不存在");
    }

    @Test
    void missingVersionSnapshotRejected() {
        when(recordMapper.findRecord(101L)).thenReturn(record(2, "{}", 8L));
        when(recordMapper.findVersionFields("A_BASELINE", 1)).thenReturn(List.of());

        assertEquals(
                40400,
                assertThrows(
                                ApiException.class,
                                () -> service.saveDraft(101L, 2, Map.of("f010", 0)))
                        .code(),
                "记录的模板版本快照缺失");
    }

    // ---------------------------------------------------------------- 桩

    private static com.crdp.form.field.infra.mapper.OptionRow option(int code, String label) {
        return new com.crdp.form.field.infra.mapper.OptionRow(
                1L, "yesno", code, label, null, false, false, null, code * 10, "ENABLED", null,
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
                "doctor.demo",
                "李医生（示例）",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of("followup:record:edit"),
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

    private static FormRecordRow record(int version, String data, Long updatedBy) {
        return new FormRecordRow(
                101L,
                "A_BASELINE",
                1,
                data,
                "{\"meta\":\"kept\"}",
                version,
                "DRAFT",
                7L,
                LocalDateTime.now(),
                updatedBy,
                LocalDateTime.now());
    }

    private static VersionFieldRow smoking() {
        return new VersionFieldRow(
                1L,
                1L,
                "A_BASELINE",
                1,
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

    private static VersionFieldRow height() {
        return new VersionFieldRow(
                2L,
                1L,
                "A_BASELINE",
                1,
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
                "示例",
                50);
    }
}
