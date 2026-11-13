package com.crdp.followup.patient.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.implant.app.ImplantService;
import com.crdp.followup.patient.infra.mapper.DeptHistoryInsert;
import com.crdp.followup.patient.infra.mapper.PatientDeptHistoryMapper;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.followup.patient.infra.mapper.PatientWardHistoryMapper;
import com.crdp.followup.patient.infra.mapper.WardHistoryInsert;
import com.crdp.followup.ward.infra.mapper.WardMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E13-T08 / A-WARD-005 单元测试（tasks.csv 验收：转病区后原病区医生不可见——隔离由数据范围改写承担， 服务只改 pt_patient.ward_id
 * 一行）。锁定口径：范围内校验 40400（DENIED 审计）、目标病区须启用、同病区拦截、原因必填、 历史只追加（from / to / 原因 / 操作人）。全部虚构数据。
 *
 * <p>E21-T21 / C-WARD-006 扩展：转科室（deptId）与转病区同一入口，wardId / deptId 至少一项、可同时 携带（一条 UPDATE、两条历史、一条审计）；
 * 转科室须 implant 开关开启（A / B 40001）；科室历史 pt_dept_history 与 病区历史同构；科室与病区两分支各自独立生效（OR 由 E07-T02
 * 改写承担，服务只改归属列）。
 */
class PatientTransferServiceTest {

    private static final long PATIENT_ID = 101L;
    private static final long WARD_A = 3L;
    private static final long WARD_B = 4L;
    private static final long DEPT_CARDIO = 7L;
    private static final long DEPT_CVS = 8L;
    private static final long OPERATOR_ID = 9L;

    private final PatientTransferGateway gateway = mock(PatientTransferGateway.class);
    private final PatientContactGateway patientScope = mock(PatientContactGateway.class);
    private final PatientMapper patientMapper = mock(PatientMapper.class);
    private final PatientWardHistoryMapper historyMapper = mock(PatientWardHistoryMapper.class);
    private final PatientDeptHistoryMapper deptHistoryMapper = mock(PatientDeptHistoryMapper.class);
    private final WardMapper wardMapper = mock(WardMapper.class);
    private final ImplantService implants = mock(ImplantService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final PatientTransferService service =
            new PatientTransferService(
                    gateway,
                    patientScope,
                    patientMapper,
                    historyMapper,
                    deptHistoryMapper,
                    wardMapper,
                    implants,
                    audit);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        when(implants.implantEnabled()).thenReturn(true);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 转病区（A-WARD-005）

    @Test
    void transferUpdatesWardAndAppendsHistoryInOneAction() {
        when(gateway.patientWardInScope(PATIENT_ID))
                .thenReturn(new PatientMapper.WardRef(PATIENT_ID, WARD_A, DEPT_CARDIO));
        when(wardMapper.findById(WARD_B))
                .thenReturn(
                        new WardMapper.WardRow(
                                WARD_B, "W-IT-B", "示例二病区", "WARD", null, null, null, 0, "ENABLED",
                                0L, 0L));

        PatientTransferService.TransferredView view =
                service.transfer(
                        PATIENT_ID,
                        new PatientTransferService.TransferRequest(WARD_B, null, "患者术后转入本病区（示例）"));

        assertEquals(String.valueOf(WARD_B), view.wardId());
        assertEquals("示例二病区", view.wardName());

        // 只改患者一行（任务 / 附件经 patient_id 关联随患者迁移，子表不搬），科室列不下发
        verify(patientMapper).updateWardAndDept(PATIENT_ID, WARD_B, null, OPERATOR_ID);

        ArgumentCaptor<WardHistoryInsert> history =
                ArgumentCaptor.forClass(WardHistoryInsert.class);
        verify(historyMapper).insertHistory(history.capture());
        assertEquals(WARD_A, history.getValue().getFromWardId());
        assertEquals(WARD_B, history.getValue().getToWardId());
        assertEquals("患者术后转入本病区（示例）", history.getValue().getReason());
        assertEquals(OPERATOR_ID, history.getValue().getTransferredBy());
        // 只转病区：不写科室历史
        verify(deptHistoryMapper, never()).insertHistory(any(DeptHistoryInsert.class));

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("PATIENT_TRANSFER", entry.getValue().action());
        assertEquals(AuditResult.SUCCESS, entry.getValue().result());
    }

    @Test
    void transferOutOfScopeReturns40400WithDeniedAudit() {
        when(gateway.patientWardInScope(PATIENT_ID)).thenReturn(null);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.transfer(
                                        PATIENT_ID,
                                        new PatientTransferService.TransferRequest(
                                                WARD_B, null, "越范围（示例）")));

        assertEquals(40400, error.code(), "越范围不暴露对象存在");
        verify(patientMapper, never())
                .updateWardAndDept(anyLong(), anyLong(), anyLong(), anyLong());
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals(AuditResult.DENIED, entry.getValue().result());
        assertEquals(40400, entry.getValue().errorCode());
    }

    @Test
    void transferValidatesTargetAndReason() {
        when(gateway.patientWardInScope(PATIENT_ID))
                .thenReturn(new PatientMapper.WardRef(PATIENT_ID, WARD_A, DEPT_CARDIO));
        when(wardMapper.findById(WARD_B))
                .thenReturn(
                        new WardMapper.WardRow(
                                WARD_B,
                                "W-IT-B",
                                "示例二病区",
                                "WARD",
                                null,
                                null,
                                null,
                                0,
                                "DISABLED",
                                0L,
                                0L));

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.transfer(
                                                PATIENT_ID,
                                                new PatientTransferService.TransferRequest(
                                                        null, null, "原因（示例）")))
                        .code(),
                "病区与科室至少一项");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.transfer(
                                                PATIENT_ID,
                                                new PatientTransferService.TransferRequest(
                                                        999L, null, "原因（示例）")))
                        .code(),
                "目标病区不存在");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.transfer(
                                                PATIENT_ID,
                                                new PatientTransferService.TransferRequest(
                                                        WARD_B, null, "原因（示例）")))
                        .code(),
                "目标病区已停用不能转入");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.transfer(
                                                PATIENT_ID,
                                                new PatientTransferService.TransferRequest(
                                                        WARD_A, null, " ")))
                        .code(),
                "原因必填");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.transfer(
                                                PATIENT_ID,
                                                new PatientTransferService.TransferRequest(
                                                        WARD_A, null, "原因（示例）")))
                        .code(),
                "同病区无需转病区");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.transfer(
                                                PATIENT_ID,
                                                new PatientTransferService.TransferRequest(
                                                        WARD_B, null, "长".repeat(256))))
                        .code(),
                "原因长度上限 255");
        verify(patientMapper, never())
                .updateWardAndDept(anyLong(), anyLong(), anyLong(), anyLong());
    }

    // ---------------------------------------------------------------- 转科室（E21-T21 / C-WARD-006）

    @Test
    void transferDeptOnlyUpdatesDeptAndAppendsDeptHistory() {
        when(gateway.patientWardInScope(PATIENT_ID))
                .thenReturn(new PatientMapper.WardRef(PATIENT_ID, WARD_A, DEPT_CARDIO));
        when(patientMapper.findDeptById(DEPT_CVS))
                .thenReturn(new PatientMapper.DeptRow(DEPT_CVS, "示例心外科", "ENABLED"));

        PatientTransferService.TransferredView view =
                service.transfer(
                        PATIENT_ID,
                        new PatientTransferService.TransferRequest(null, DEPT_CVS, "患者转科至心外科（示例）"));

        assertEquals(String.valueOf(DEPT_CVS), view.deptId());
        assertEquals("示例心外科", view.deptName());
        // 只转科室：病区列不下发、病区回显不变
        verify(patientMapper).updateWardAndDept(PATIENT_ID, null, DEPT_CVS, OPERATOR_ID);
        assertEquals(String.valueOf(WARD_A), view.wardId());

        ArgumentCaptor<DeptHistoryInsert> history =
                ArgumentCaptor.forClass(DeptHistoryInsert.class);
        verify(deptHistoryMapper).insertHistory(history.capture());
        assertEquals(DEPT_CARDIO, history.getValue().getFromDeptId());
        assertEquals(DEPT_CVS, history.getValue().getToDeptId());
        assertEquals("患者转科至心外科（示例）", history.getValue().getReason());
        assertEquals(OPERATOR_ID, history.getValue().getTransferredBy());
        // 只转科室：不写病区历史（C-WARD-006：两维度各自独立）
        verify(historyMapper, never()).insertHistory(any(WardHistoryInsert.class));

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("PATIENT_TRANSFER", entry.getValue().action());
        assertEquals(AuditResult.SUCCESS, entry.getValue().result());
    }

    @Test
    void transferWardAndDeptTogetherWritesBothHistoriesOnce() {
        when(gateway.patientWardInScope(PATIENT_ID))
                .thenReturn(new PatientMapper.WardRef(PATIENT_ID, WARD_A, DEPT_CARDIO));
        when(wardMapper.findById(WARD_B))
                .thenReturn(
                        new WardMapper.WardRow(
                                WARD_B, "W-IT-B", "示例二病区", "WARD", null, null, null, 0, "ENABLED",
                                0L, 0L));
        when(patientMapper.findDeptById(DEPT_CVS))
                .thenReturn(new PatientMapper.DeptRow(DEPT_CVS, "示例心外科", "ENABLED"));

        PatientTransferService.TransferredView view =
                service.transfer(
                        PATIENT_ID,
                        new PatientTransferService.TransferRequest(
                                WARD_B, DEPT_CVS, "转入心外科二病区（示例）"));

        // 同时转科室 + 病区 = 一次管理动作：一条 UPDATE、两条历史、一条审计
        verify(patientMapper).updateWardAndDept(PATIENT_ID, WARD_B, DEPT_CVS, OPERATOR_ID);
        verify(historyMapper).insertHistory(any(WardHistoryInsert.class));
        verify(deptHistoryMapper).insertHistory(any(DeptHistoryInsert.class));
        verify(audit).append(any(AuditEntry.class));
        assertEquals(String.valueOf(WARD_B), view.wardId());
        assertEquals(String.valueOf(DEPT_CVS), view.deptId());
    }

    @Test
    void transferDeptValidatesTargetAndGate() {
        when(gateway.patientWardInScope(PATIENT_ID))
                .thenReturn(new PatientMapper.WardRef(PATIENT_ID, WARD_A, DEPT_CARDIO));

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.transfer(
                                                PATIENT_ID,
                                                new PatientTransferService.TransferRequest(
                                                        null, 999L, "原因（示例）")))
                        .code(),
                "目标科室不存在");
        when(patientMapper.findDeptById(DEPT_CVS))
                .thenReturn(new PatientMapper.DeptRow(DEPT_CVS, "示例心外科", "DISABLED"));
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.transfer(
                                                PATIENT_ID,
                                                new PatientTransferService.TransferRequest(
                                                        null, DEPT_CVS, "原因（示例）")))
                        .code(),
                "目标科室已停用不能转入");
        when(patientMapper.findDeptById(DEPT_CARDIO))
                .thenReturn(new PatientMapper.DeptRow(DEPT_CARDIO, "示例心内科", "ENABLED"));
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.transfer(
                                                PATIENT_ID,
                                                new PatientTransferService.TransferRequest(
                                                        null, DEPT_CARDIO, "原因（示例）")))
                        .code(),
                "同科室无需转科室");

        // A / B 口径（implant 开关关闭）：携带 deptId → 40001（与登记页 visitNo 同口径）
        when(implants.implantEnabled()).thenReturn(false);
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.transfer(
                                                PATIENT_ID,
                                                new PatientTransferService.TransferRequest(
                                                        null, DEPT_CVS, "原因（示例）")))
                        .code(),
                "转科室为医院 C 口径，开关关闭不接收");
        // 开关关闭时 wardId 转病区照常（A-WARD-005 不受影响）
        when(wardMapper.findById(WARD_B))
                .thenReturn(
                        new WardMapper.WardRow(
                                WARD_B, "W-IT-B", "示例二病区", "WARD", null, null, null, 0, "ENABLED",
                                0L, 0L));
        assertEquals(
                String.valueOf(WARD_B),
                service.transfer(
                                PATIENT_ID,
                                new PatientTransferService.TransferRequest(
                                        WARD_B, null, "A 口径转病区（示例）"))
                        .wardId(),
                "A / B 转病区不受 implant 开关影响");
        verify(patientMapper, never())
                .updateWardAndDept(anyLong(), anyLong(), anyLong(), anyLong());
    }

    // ----------------------------------------------------------------
    // 历史（A-WARD-005、C-WARD-006「保留转科历史」）

    @Test
    void historyOfRequiresScopeAndEnrichesWardNames() {
        when(patientScope.patientIdInScope(PATIENT_ID)).thenReturn(PATIENT_ID);
        when(wardMapper.findAll())
                .thenReturn(
                        List.of(
                                new WardMapper.WardRow(
                                        WARD_A, "W-A", "示例一病区", "WARD", null, null, null, 0,
                                        "ENABLED", 0L, 0L),
                                new WardMapper.WardRow(
                                        WARD_B, "W-B", "示例二病区", "WARD", null, null, null, 0,
                                        "ENABLED", 0L, 0L)));
        when(gateway.wardHistoryOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new PatientWardHistoryMapper.WardHistoryRow(
                                        1L,
                                        WARD_A,
                                        WARD_B,
                                        "转入二病区（示例）",
                                        OPERATOR_ID,
                                        "演示医生",
                                        null)));

        PatientTransferService.WardHistoryView view = service.historyOf(PATIENT_ID);

        assertEquals(1, view.items().size());
        assertEquals("示例一病区", view.items().get(0).fromWardName());
        assertEquals("示例二病区", view.items().get(0).toWardName());
        assertEquals("演示医生", view.items().get(0).operatorName());

        when(patientScope.patientIdInScope(PATIENT_ID)).thenReturn(null);
        assertEquals(
                40400,
                assertThrows(ApiException.class, () -> service.historyOf(PATIENT_ID)).code());
    }

    @Test
    void historyOfDeletedWardShowsPlaceholder() {
        when(patientScope.patientIdInScope(PATIENT_ID)).thenReturn(PATIENT_ID);
        when(wardMapper.findAll()).thenReturn(List.of());
        when(gateway.wardHistoryOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new PatientWardHistoryMapper.WardHistoryRow(
                                        1L, WARD_A, WARD_B, "原因（示例）", OPERATOR_ID, null, null)));

        PatientTransferService.WardHistoryItemView item =
                service.historyOf(PATIENT_ID).items().get(0);

        assertEquals("（已删除）", item.fromWardName());
        assertNull(item.operatorName(), "操作人缺失显示空，不报错");
    }

    @Test
    void deptHistoryRequiresScopeAndEnrichesNames() {
        when(patientScope.patientIdInScope(PATIENT_ID)).thenReturn(PATIENT_ID);
        // findDepts 只回启用项：DEPT_CARDIO 启用、DEPT_CVS 已停用（按 id 补齐名称）
        when(patientMapper.findDepts())
                .thenReturn(List.of(new PatientMapper.OptionRow(DEPT_CARDIO, "示例心内科")));
        when(patientMapper.findDeptById(DEPT_CVS))
                .thenReturn(new PatientMapper.DeptRow(DEPT_CVS, "示例心外科", "DISABLED"));
        when(gateway.deptHistoryOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new PatientDeptHistoryMapper.DeptHistoryRow(
                                        2L,
                                        DEPT_CARDIO,
                                        DEPT_CVS,
                                        "转科至心外科（示例）",
                                        OPERATOR_ID,
                                        "演示主任",
                                        null)));

        PatientTransferService.DeptHistoryView view = service.deptHistoryOf(PATIENT_ID);

        assertEquals(1, view.items().size());
        assertEquals("示例心内科", view.items().get(0).fromDeptName());
        assertEquals("示例心外科", view.items().get(0).toDeptName());
        assertEquals("演示主任", view.items().get(0).operatorName());

        when(patientScope.patientIdInScope(PATIENT_ID)).thenReturn(null);
        assertEquals(
                40400,
                assertThrows(ApiException.class, () -> service.deptHistoryOf(PATIENT_ID)).code(),
                "越范围 / 不存在的患者 40400（不暴露对象存在）");
    }

    // ---------------------------------------------------------------- 辅助

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "虚构示例医院",
                OPERATOR_ID,
                "doctor.demo",
                "演示医生",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of("followup:patient:transfer"),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                null,
                0L,
                0L,
                0L,
                0L, // 绝对时长用部署配置的全局值,
                null);
    }
}
