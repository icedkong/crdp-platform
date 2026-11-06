package com.crdp.followup.task.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.task.infra.mapper.FollowupTaskAssignMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.outbox.OutboxPublisher;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E15-T03 / A-FU-009：认领与改派（claim / batch-assign）的业务层口径。
 *
 * <p>锁定：改派新负责人须启用且负责患者病区（或全院范围，DOCTOR_NOT_IN_WARD 逐条跳过）； 越范围 / 不存在任务 TASK_NOT_FOUND 不暴露存在；终态任务
 * TASK_NOT_OPEN；负责人未变 TASK_UNCHANGED（不发通知）；改派成功同事务写 TASK_REASSIGNED 事件（接收人 = 新负责人，
 * 验收「改派后新责任人收到通知」）； 认领只对待认领（assignee NULL）且未完成的任务开放，越范围 40400、已有负责人 40901、并发 40900。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class FollowupTaskAssignServiceTest {

    private final FollowupTaskAssignMapper mapper = mock(FollowupTaskAssignMapper.class);
    private final PatientContactGateway patientScope = mock(PatientContactGateway.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final CryptoService crypto = mock(CryptoService.class);

    private final FollowupTaskAssignService service =
            new FollowupTaskAssignService(mapper, patientScope, outbox, audit, crypto);

    private static final LocalDate WINDOW_START = LocalDate.of(2026, 10, 29);
    private static final LocalDate WINDOW_END = LocalDate.of(2026, 11, 12);

    /** 造一条可改派任务行（患者 301 / 病区 11 / 节点「第 4 年随访」）。 */
    private static FollowupTaskAssignMapper.AssignTaskRow task(
            long id, String status, Long assigneeId) {
        return new FollowupTaskAssignMapper.AssignTaskRow(
                id,
                301L,
                status,
                assigneeId,
                11L,
                "cipher-name",
                "cipher-mrn",
                "示例二病区",
                "第 4 年随访（示例）",
                WINDOW_START,
                WINDOW_END);
    }

    private void enabledWardAssignee(long userId) {
        when(mapper.countEnabledAssignee(userId)).thenReturn(1);
        when(mapper.countWardMembership(userId, 11L)).thenReturn(1);
    }

    @AfterEach
    void clearSession() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 批量改派（batch-assign）

    @Test
    void batchAssignValidatesAssigneeIdsAndReason() {
        ApiException noAssignee =
                assertThrows(
                        ApiException.class, () -> service.batchAssign(List.of(1L), null, null));
        assertEquals(40001, noAssignee.code());

        when(mapper.countEnabledAssignee(9L)).thenReturn(0);
        ApiException unknown =
                assertThrows(ApiException.class, () -> service.batchAssign(List.of(1L), 9L, null));
        assertEquals(40001, unknown.code());

        enabledWardAssignee(9L);
        ApiException empty =
                assertThrows(ApiException.class, () -> service.batchAssign(null, 9L, null));
        assertEquals(40001, empty.code());

        ApiException tooMany =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.batchAssign(
                                        LongStream.range(1, 502).boxed().toList(), 9L, null));
        assertEquals(40001, tooMany.code());

        ApiException longReason =
                assertThrows(
                        ApiException.class,
                        () -> service.batchAssign(List.of(1L), 9L, "长".repeat(256)));
        assertEquals(40001, longReason.code());
    }

    @Test
    void batchAssignSkipsOutOfScopeTerminalUnchangedAndCrossWardWithoutEvent() {
        when(mapper.countEnabledAssignee(9L)).thenReturn(1);
        when(mapper.countWardMembership(9L, 11L)).thenReturn(0);
        when(mapper.countHospitalWideScope(9L)).thenReturn(0);
        // 1001 越范围（查询不返回）；1002 终态；1003 已是该负责人；1004 医生不负责病区
        when(mapper.findAssignTasksInScope(List.of(1001L, 1002L, 1003L, 1004L)))
                .thenReturn(
                        List.of(
                                task(1002L, "COMPLETED", 7L),
                                task(1003L, "PENDING", 9L),
                                task(1004L, "PENDING", 7L)));

        FollowupTaskAssignService.AssignResultView result =
                service.batchAssign(List.of(1001L, 1002L, 1003L, 1004L), 9L, null);

        assertEquals(0, result.succeeded());
        assertEquals(4, result.skipped());
        assertEquals(
                List.of("TASK_NOT_FOUND", "TASK_NOT_OPEN", "TASK_UNCHANGED", "DOCTOR_NOT_IN_WARD"),
                result.items().stream()
                        .map(FollowupTaskAssignService.AssignItemView::reason)
                        .toList());
        // 越范围行不带患者摘要（不暴露存在）
        assertNull(result.items().get(0).patientName());
        verify(outbox, never()).publish(any(), any());
        verify(mapper, never()).reassignTask(anyLong(), any(), anyLong());
    }

    @Test
    void batchAssignUpdatesPublishesReassignedEventAndWritesAudit() {
        enabledWardAssignee(9L);
        when(mapper.findAssignTasksInScope(List.of(1001L)))
                .thenReturn(List.of(task(1001L, "PENDING", 7L)));
        when(mapper.reassignTask(1001L, 7L, 9L)).thenReturn(1);
        when(crypto.decrypt("cipher-name")).thenReturn("周示");

        FollowupTaskAssignService.AssignResultView result =
                service.batchAssign(List.of(1001L), 9L, "医生休假（示例）");

        assertEquals(1, result.succeeded());
        assertEquals("SUCCESS", result.items().get(0).outcome());
        assertEquals("周*", result.items().get(0).patientName(), "成功行姓名为脱敏值");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outbox).publish(eq("TASK_REASSIGNED"), payload.capture());
        String json = payload.getValue();
        assertTrue(json.contains("\"ownerId\":9"), "事件接收人 = 新负责人：" + json);
        assertTrue(json.contains("\"taskId\":1001"), json);
        assertTrue(json.contains("\"reason\":\"医生休假（示例）\""), json);
        assertTrue(json.contains("\"windowStartDate\":\"2026-10-29\""), json);
        assertTrue(json.contains("\"nodeName\":\"第 4 年随访（示例）\""), json);

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        // 新口径 action 即事件名（event_type 列由写入服务缺省取 action）
        assertEquals("TASK_ASSIGN", entry.getValue().action());
    }

    @Test
    void batchAssignReportsConflictWhenGuardMisses() {
        enabledWardAssignee(9L);
        when(mapper.findAssignTasksInScope(List.of(1001L)))
                .thenReturn(List.of(task(1001L, "PENDING", 7L)));
        when(mapper.reassignTask(1001L, 7L, 9L)).thenReturn(0);

        FollowupTaskAssignService.AssignResultView result =
                service.batchAssign(List.of(1001L), 9L, null);
        assertEquals("TASK_CONFLICT", result.items().get(0).reason());
        verify(outbox, never()).publish(any(), any());
    }

    @Test
    void batchAssignAllowsHospitalWideAssigneeWithoutWardMembership() {
        when(mapper.countEnabledAssignee(9L)).thenReturn(1);
        when(mapper.countWardMembership(9L, 11L)).thenReturn(0);
        when(mapper.countHospitalWideScope(9L)).thenReturn(1);
        when(mapper.findAssignTasksInScope(List.of(1001L)))
                .thenReturn(List.of(task(1001L, "PENDING", 7L)));
        when(mapper.reassignTask(1001L, 7L, 9L)).thenReturn(1);

        assertEquals(1, service.batchAssign(List.of(1001L), 9L, null).succeeded());
    }

    // ---------------------------------------------------------------- 认领（claim）

    @Test
    void claimTakesUnassignedTaskInScope() {
        loginAs(9L);
        when(mapper.findClaimTaskById(1001L))
                .thenReturn(
                        new FollowupTaskAssignMapper.ClaimTaskRow(1001L, 301L, "PENDING", null));
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(mapper.claimTask(1001L, 9L)).thenReturn(1);

        FollowupTaskAssignService.ClaimView view = service.claim(1001L);

        assertEquals("1001", view.taskId());
        assertEquals("PENDING", view.status());
        // 认领是本人自取，不发 TASK_REASSIGNED（通知事件目录只列「任务改派 → 新负责人」）
        verify(outbox, never()).publish(any(), any());
        verify(audit).append(any());
    }

    @Test
    void claimHidesExistenceWhenMissingOrOutOfScope() {
        when(mapper.findClaimTaskById(1001L)).thenReturn(null);
        assertEquals(40400, assertThrows(ApiException.class, () -> service.claim(1001L)).code());

        when(mapper.findClaimTaskById(1002L))
                .thenReturn(
                        new FollowupTaskAssignMapper.ClaimTaskRow(1002L, 302L, "PENDING", null));
        when(patientScope.patientIdInScope(302L)).thenReturn(null);
        assertEquals(40400, assertThrows(ApiException.class, () -> service.claim(1002L)).code());
    }

    @Test
    void claimRejectsTerminalAndAlreadyAssignedTasks() {
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(mapper.findClaimTaskById(1001L))
                .thenReturn(
                        new FollowupTaskAssignMapper.ClaimTaskRow(1001L, 301L, "COMPLETED", null));
        assertEquals(40901, assertThrows(ApiException.class, () -> service.claim(1001L)).code());

        when(mapper.findClaimTaskById(1002L))
                .thenReturn(new FollowupTaskAssignMapper.ClaimTaskRow(1002L, 301L, "PENDING", 7L));
        assertEquals(40901, assertThrows(ApiException.class, () -> service.claim(1002L)).code());
    }

    @Test
    void claimReportsConflictWhenConcurrentlyTaken() {
        loginAs(9L);
        when(mapper.findClaimTaskById(1001L))
                .thenReturn(
                        new FollowupTaskAssignMapper.ClaimTaskRow(1001L, 301L, "PENDING", null));
        when(patientScope.patientIdInScope(301L)).thenReturn(301L);
        when(mapper.claimTask(1001L, 9L)).thenReturn(0);

        assertEquals(40900, assertThrows(ApiException.class, () -> service.claim(1001L)).code());
    }

    // ---------------------------------------------------------------- 会话（认领人 = 当前登录用户）

    private static void loginAs(long userId) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal(userId), null, List.of()));
    }

    private static SessionPrincipal principal(long userId) {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例）",
                userId,
                "it.claim",
                "IT 用户（示例）",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of("followup:task:view"),
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
