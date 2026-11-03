package com.crdp.followup.ward.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.ward.infra.mapper.WardInsert;
import com.crdp.followup.ward.infra.mapper.WardMapper;
import com.crdp.followup.ward.infra.mapper.WardMapper.WardRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E13-T08 / A-WARD-001、A-WARD-008 单元测试（验收：病区字典维护 + 病区下仍有在随访患者时不能停用）。 锁定口径：编码唯一且不可改（40902 /
 * 40001）、类型与别名校验、停用拦截（40901 + DENIED 审计）、增改停全写审计。全部虚构数据。
 */
class WardServiceTest {

    private static final long WARD_ID = 7L;

    private final WardMapper mapper = mock(WardMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final WardService service = new WardService(mapper, audit);

    private static WardRow row(String status, long patientCount) {
        return row("W-ICU-1", status, patientCount);
    }

    private static WardRow row(String code, String status, long patientCount) {
        return row(code, status, patientCount, "心外一;心外1", 10);
    }

    private static WardRow row(
            String code, String status, long patientCount, String alias, int sortNo) {
        return new WardRow(
                WARD_ID,
                code,
                "示例心外一病区",
                "WARD",
                alias,
                9L,
                "王医生（示例）",
                sortNo,
                status,
                patientCount,
                2L);
    }

    // ---------------------------------------------------------------- 新建（A-WARD-001）

    @Test
    void createStoresDictionaryAndWritesAudit() {
        when(mapper.findIdByCode("W-IT-NEW")).thenReturn(null);
        when(mapper.countEnabledUser(9L)).thenReturn(1);
        when(mapper.findById(anyLong()))
                .thenReturn(row("W-IT-NEW", "ENABLED", 0, "IT 病区;示例病区乙", 20));

        WardService.WardView view =
                service.create(
                        new WardService.CreateRequest(
                                " W-IT-NEW ",
                                "示例 IT 病区",
                                "ward",
                                List.of("IT 病区 ", "IT 病区", "示例病区乙"),
                                9L,
                                20));

        assertEquals("W-IT-NEW", view.code(), "编码去空白保留大小写");
        assertEquals("WARD", view.wardType(), "类型归一大写");
        assertEquals(List.of("IT 病区", "示例病区乙"), view.aliases(), "别名去空白、去重、保序");
        assertEquals(0, view.patientCount());

        ArgumentCaptor<WardInsert> captor = ArgumentCaptor.forClass(WardInsert.class);
        verify(mapper).insertWard(captor.capture());
        assertEquals("IT 病区;示例病区乙", captor.getValue().getAlias(), "别名以 ; 存储");

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("WARD_CREATE", entry.getValue().action());
        assertEquals(AuditResult.SUCCESS, entry.getValue().result());
    }

    @Test
    void createRejectsDuplicateCodeWith40902AndDeniedAudit() {
        when(mapper.findIdByCode("W-ICU-1")).thenReturn(WARD_ID);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        new WardService.CreateRequest(
                                                "W-ICU-1", "重复病区（示例）", "WARD", null, null, 0)));

        assertEquals(40902, error.code());
        verify(mapper, never()).insertWard(any());
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals(AuditResult.DENIED, entry.getValue().result());
        assertEquals(40902, entry.getValue().errorCode());
    }

    @Test
    void createValidatesFields() {
        assertValidation(() -> service.create(req(null, "名称（示例）", "WARD", null, null, 0)), "编码必填");
        assertValidation(() -> service.create(req("W-A", " ", "WARD", null, null, 0)), "名称必填");
        assertValidation(
                () -> service.create(req("W-A", "名称（示例）", "CLINIC", null, null, 0)), "类型枚举");
        assertValidation(
                () -> service.create(req("W A", "名称（示例）", "WARD", null, null, 0)), "编码字符集");
        assertValidation(
                () -> service.create(req("W-A", "名称（示例）", "WARD", null, 123L, 0)), "负责人须为启用用户");
        assertValidation(
                () -> service.create(req("W-A", "名称（示例）", "WARD", null, null, -1)), "排序范围");
        assertValidation(
                () ->
                        service.create(
                                req("W-A", "名称（示例）", "WARD", List.of("别".repeat(65)), null, 0)),
                "别名单项长度");
        assertValidation(
                () ->
                        service.create(
                                req("W-A", "名称（示例）", "WARD", List.of("别名".repeat(130)), null, 0)),
                "别名总长");
    }

    // ---------------------------------------------------------------- 编辑（A-WARD-001）

    @Test
    void updateRejectsCodeChange() {
        when(mapper.findById(WARD_ID)).thenReturn(row("ENABLED", 1));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        WARD_ID,
                                        new WardService.UpdateRequest(
                                                "W-OTHER", "改名（示例）", "WARD", null, null, 0)));

        assertEquals(40001, error.code());
        verify(mapper, never())
                .updateWard(anyLong(), anyString(), anyString(), any(), any(), anyInt());
    }

    @Test
    void updateKeepsCodeAndWritesBeforeAfterAudit() {
        when(mapper.findById(WARD_ID)).thenReturn(row("ENABLED", 1));
        when(mapper.countEnabledUser(8L)).thenReturn(1);

        service.update(
                WARD_ID,
                new WardService.UpdateRequest(
                        "W-ICU-1", "示例心外一病区（更名）", "CENTER", List.of("心外一"), 8L, 30));

        verify(mapper).updateWard(WARD_ID, "示例心外一病区（更名）", "CENTER", "心外一", 8L, 30);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("WARD_UPDATE", entry.getValue().action());
        assertNotNull(entry.getValue().beforeJson(), "编辑审计带前值");
        assertNotNull(entry.getValue().afterJson(), "编辑审计带后值");
    }

    // ---------------------------------------------------------------- 停用与恢复（A-WARD-008）

    @Test
    void disableBlockedWhilePatientsRemain() {
        when(mapper.findById(WARD_ID)).thenReturn(row("ENABLED", 3));
        when(mapper.countLivePatients(WARD_ID)).thenReturn(3L);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeStatus(
                                        WARD_ID, new WardService.StatusRequest("DISABLED")));

        assertEquals(40901, error.code());
        assertTrue(error.getMessage().contains("3"), "提示须先转出：" + error.getMessage());
        verify(mapper, never()).updateStatus(anyLong(), anyString());
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("WARD_DISABLE", entry.getValue().action());
        assertEquals(AuditResult.DENIED, entry.getValue().result());
    }

    @Test
    void disableSucceedsAfterPatientsTransferredOut() {
        when(mapper.findById(WARD_ID)).thenReturn(row("ENABLED", 0));
        when(mapper.countLivePatients(WARD_ID)).thenReturn(0L);

        service.changeStatus(WARD_ID, new WardService.StatusRequest("DISABLED"));

        verify(mapper).updateStatus(WARD_ID, "DISABLED");
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("WARD_DISABLE", entry.getValue().action());
        assertEquals(AuditResult.SUCCESS, entry.getValue().result());
    }

    @Test
    void enableAndSameStatusGuard() {
        when(mapper.findById(WARD_ID)).thenReturn(row("DISABLED", 0));

        service.changeStatus(WARD_ID, new WardService.StatusRequest("ENABLED"));
        verify(mapper).updateStatus(WARD_ID, "ENABLED");

        when(mapper.findById(WARD_ID)).thenReturn(row("DISABLED", 0));
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeStatus(
                                        WARD_ID, new WardService.StatusRequest("DISABLED")));
        assertEquals(40001, error.code(), "已是目标状态时拒绝空操作");

        assertThrows(
                ApiException.class,
                () -> service.changeStatus(WARD_ID, new WardService.StatusRequest("PAUSED")));
    }

    // ---------------------------------------------------------------- 成员（pages.md §10）

    @Test
    void membersListsEnabledUsersWithPatientCount() {
        when(mapper.findById(WARD_ID)).thenReturn(row("ENABLED", 5));
        when(mapper.findMembers(WARD_ID))
                .thenReturn(List.of(new WardMapper.MemberRow(9L, "doctor.demo", "演示医生")));

        WardService.MembersView view = service.members(WARD_ID);

        assertEquals(5, view.patientCount());
        assertEquals(1, view.members().size());
        assertEquals("演示医生", view.members().get(0).displayName());
    }

    @Test
    void unknownWardReturns40400() {
        when(mapper.findById(404L)).thenReturn(null);
        assertEquals(40400, assertThrows(ApiException.class, () -> service.members(404L)).code());
        assertEquals(
                40400,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.changeStatus(
                                                404L, new WardService.StatusRequest("DISABLED")))
                        .code());
    }

    // ---------------------------------------------------------------- 辅助

    private static WardService.CreateRequest req(
            String code, String name, String type, List<String> aliases, Long owner, Integer sort) {
        return new WardService.CreateRequest(code, name, type, aliases, owner, sort);
    }

    private static void assertValidation(Runnable action, String label) {
        ApiException error = assertThrows(ApiException.class, action::run, label);
        assertEquals(40001, error.code(), label + "：应为 40001，实际 " + error.code());
    }
}
