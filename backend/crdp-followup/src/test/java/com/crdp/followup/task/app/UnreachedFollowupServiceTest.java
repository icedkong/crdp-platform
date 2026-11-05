package com.crdp.followup.task.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.task.infra.mapper.FollowupTaskInsertRow;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.outbox.OutboxPublisher;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/**
 * E15-T05 / A-FU-024 未联系到自动跟进单测（tasks.csv 验收「每窗口最多 5 次跟进，窗口结束不再生成」）。 覆盖：次日（间隔可配置）生成跟进任务（窗口与负责人继承、
 * FOLLOWUP + followup_seq、UNREACHED_FOLLOWUP 事件与审计）；窗口结束不生成（开启日晚于 window_end）； 每窗口最多 5
 * 次（MAX(followup_seq) 达上限）；源任务终态不生成；并发撞 uk 按已存在处理（不抛错打断沟通记录）。全部虚构数据。
 */
class UnreachedFollowupServiceTest {

    private static final long TASK_ID = 901L;
    private static final long ENROLLMENT_ID = 31L;
    private static final long PATIENT_ID = 77L;
    private static final long PLAN_ID = 11L;
    private static final long NODE_ID = 200L;
    private static final long OPERATOR = 55L;
    private static final LocalDate WINDOW_START = LocalDate.of(2026, 3, 8);
    private static final LocalDate WINDOW_END = LocalDate.of(2026, 3, 22);

    private final FollowupTaskMapper taskMapper = mock(FollowupTaskMapper.class);
    private final FollowupPolicyService policy = mock(FollowupPolicyService.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final CryptoService crypto = mock(CryptoService.class);

    private final UnreachedFollowupService service =
            new UnreachedFollowupService(taskMapper, policy, outbox, audit, crypto);

    {
        lenient().when(policy.policy()).thenReturn(new FollowupPolicyService.ContactPolicy(3, 1));
    }

    /** 主任务行（默认进行中；窗口 [2026-03-08, 2026-03-22]，第 3 次）。 */
    private FollowupTaskMapper.TaskRow taskRow(String status, int followupSeq) {
        return new FollowupTaskMapper.TaskRow(
                TASK_ID,
                ENROLLMENT_ID,
                PATIENT_ID,
                PLAN_ID,
                NODE_ID,
                3,
                "NORMAL",
                status,
                followupSeq,
                WINDOW_START,
                WINDOW_END);
    }

    private void templateOfSource() {
        lenient()
                .when(taskMapper.findFollowupTemplate(TASK_ID))
                .thenReturn(
                        new FollowupTaskMapper.FollowupTemplateRow(
                                "FORM", "fu_annual_v1", "WARD_DEFAULT", 66L));
    }

    private void notifyRowOf(long followupTaskId) {
        lenient()
                .when(taskMapper.findTaskNotifyRow(followupTaskId))
                .thenReturn(
                        new FollowupTaskMapper.OverdueCandidateRow(
                                followupTaskId,
                                PATIENT_ID,
                                66L,
                                5L,
                                "cipher-a",
                                "第 3 年随访（示例）",
                                WINDOW_START,
                                WINDOW_END));
    }

    /** 插入即回填 id（同生产 useGeneratedKeys 行为）。 */
    private void insertAnswers(long id) {
        when(taskMapper.insertTask(any(FollowupTaskInsertRow.class)))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, FollowupTaskInsertRow.class).setId(id);
                            return 1;
                        });
    }

    // ---------------------------------------------------------------- 生成（验收）

    @Test
    void createsFollowupOpeningNextDayWithInheritedWindow() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("IN_PROGRESS", 0));
        when(taskMapper.findMaxFollowupSeq(ENROLLMENT_ID, NODE_ID, 3)).thenReturn(0);
        templateOfSource();
        insertAnswers(9101L);
        notifyRowOf(9101L);

        Long id =
                service.createForUnreachedContact(
                        TASK_ID, LocalDateTime.of(2026, 3, 15, 16, 30), OPERATOR);

        assertEquals(9101L, id, "生成跟进任务并返回 id");
        ArgumentCaptor<FollowupTaskInsertRow> captor =
                ArgumentCaptor.forClass(FollowupTaskInsertRow.class);
        verify(taskMapper).insertTask(captor.capture());
        FollowupTaskInsertRow row = captor.getValue();
        assertEquals("FOLLOWUP", row.getCreateType(), "创建方式 = 跟进（V48 枚举）");
        assertEquals(1, row.getFollowupSeq(), "窗口内跟进序号 = 已有 0 + 1");
        assertEquals(LocalDate.of(2026, 3, 16), row.getOpenDate(), "开启日 = 沟通日 + 间隔天数（默认 1 = 次日）");
        assertEquals(LocalDate.of(2026, 3, 16), row.getPlanDate());
        assertEquals(LocalDate.of(2026, 3, 16), row.getRemindStart());
        assertEquals(WINDOW_START, row.getWindowStart(), "窗口继承源任务（与逾期刷新同口径）");
        assertEquals(WINDOW_END, row.getWindowEnd());
        assertEquals(LocalDate.of(2026, 3, 15), row.getCreatedLogicalDate(), "创建逻辑日 = 沟通业务日");
        assertEquals("FORM", row.getTaskType(), "任务形态与负责人继承源任务");
        assertEquals("fu_annual_v1", row.getFormCode());
        assertEquals("WARD_DEFAULT", row.getOwnerType());
        assertEquals(Long.valueOf(66L), row.getAssigneeId());
        assertEquals("PENDING", row.getStatus());

        // UNREACHED_FOLLOWUP 事件（负载契约 NotifyEventPayload；患者姓名解密只进站内信密文列）
        verify(outbox)
                .publish(
                        eq("UNREACHED_FOLLOWUP"),
                        contains("\"taskId\":9101,\"ownerId\":66,\"wardId\":5,\"patientId\":77"));
        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(auditCaptor.capture());
        assertEquals("TASK_FOLLOWUP_CREATE", auditCaptor.getValue().action());
    }

    @Test
    void usesConfiguredIntervalDays() {
        when(policy.policy()).thenReturn(new FollowupPolicyService.ContactPolicy(3, 3));
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("PENDING", 0));
        when(taskMapper.findMaxFollowupSeq(ENROLLMENT_ID, NODE_ID, 3)).thenReturn(0);
        templateOfSource();
        insertAnswers(9102L);
        notifyRowOf(9102L);

        service.createForUnreachedContact(TASK_ID, LocalDateTime.of(2026, 3, 15, 16, 30), OPERATOR);

        ArgumentCaptor<FollowupTaskInsertRow> captor =
                ArgumentCaptor.forClass(FollowupTaskInsertRow.class);
        verify(taskMapper).insertTask(captor.capture());
        assertEquals(
                LocalDate.of(2026, 3, 18),
                captor.getValue().getOpenDate(),
                "开启日 = 沟通日 + 间隔天数（租户覆盖 3，A-FU-024 可配置）");
    }

    // ---------------------------------------------------------------- 验收：窗口结束不再生成

    @Test
    void doesNotCreateWhenOpenDateFallsAfterWindowEnd() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("IN_PROGRESS", 0));

        // 沟通发生在窗口最后一日（2026-03-22）：次日 03-23 已出窗口 → 不生成
        assertNull(
                service.createForUnreachedContact(
                        TASK_ID, LocalDateTime.of(2026, 3, 22, 17, 0), OPERATOR),
                "窗口结束后不再生成（A-FU-024）");

        // 窗口结束后的沟通同样不生成
        assertNull(
                service.createForUnreachedContact(
                        TASK_ID, LocalDateTime.of(2026, 3, 24, 9, 0), OPERATOR),
                "窗口已结束的沟通不生成跟进");

        verify(taskMapper, never()).insertTask(any(FollowupTaskInsertRow.class));
        verify(outbox, never()).publish(any(), any());
        verify(audit, never()).append(any());
    }

    @Test
    void createsOnLastPossibleDayWhenOpenDateIsWindowEnd() {
        // 沟通发生在窗口倒数第二日：次日恰为 window_end（含边界）→ 生成
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("IN_PROGRESS", 0));
        when(taskMapper.findMaxFollowupSeq(ENROLLMENT_ID, NODE_ID, 3)).thenReturn(0);
        templateOfSource();
        insertAnswers(9103L);
        notifyRowOf(9103L);

        Long id =
                service.createForUnreachedContact(
                        TASK_ID, LocalDateTime.of(2026, 3, 21, 16, 0), OPERATOR);

        assertEquals(9103L, id, "开启日 = window_end 当天仍属窗口内（含边界）");
    }

    // ---------------------------------------------------------------- 验收：每窗口最多 5 次

    @Test
    void doesNotCreateBeyondFivePerWindow() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("IN_PROGRESS", 0));
        when(taskMapper.findMaxFollowupSeq(ENROLLMENT_ID, NODE_ID, 3))
                .thenReturn(FollowupPolicyService.MAX_FOLLOWUPS_PER_WINDOW);

        assertNull(
                service.createForUnreachedContact(
                        TASK_ID, LocalDateTime.of(2026, 3, 15, 16, 30), OPERATOR),
                "每窗口最多 5 次跟进（A-FU-024）");

        verify(taskMapper, never()).insertTask(any(FollowupTaskInsertRow.class));
        verify(outbox, never()).publish(any(), any());
    }

    @Test
    void createsFifthButNotSixthFollowup() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("IN_PROGRESS", 4));
        when(taskMapper.findMaxFollowupSeq(ENROLLMENT_ID, NODE_ID, 3)).thenReturn(4);
        templateOfSource();
        insertAnswers(9104L);
        notifyRowOf(9104L);

        Long fifth =
                service.createForUnreachedContact(
                        TASK_ID, LocalDateTime.of(2026, 3, 15, 16, 30), OPERATOR);
        assertEquals(9104L, fifth, "第 5 次仍生成");

        when(taskMapper.findMaxFollowupSeq(ENROLLMENT_ID, NODE_ID, 3)).thenReturn(5);
        assertNull(
                service.createForUnreachedContact(
                        TASK_ID, LocalDateTime.of(2026, 3, 16, 16, 30), OPERATOR),
                "第 6 次不再生成");
    }

    // ---------------------------------------------------------------- 其它守卫

    @Test
    void doesNotCreateForTerminalOrMissingSourceTask() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(null);
        assertNull(service.createForUnreachedContact(TASK_ID, LocalDateTime.now(), OPERATOR));

        for (String status : List.of("COMPLETED", "LOST", "TERMINATED", "SKIPPED_SUSPENDED")) {
            when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow(status, 0));
            assertNull(
                    service.createForUnreachedContact(TASK_ID, LocalDateTime.now(), OPERATOR),
                    status + " 源任务不生成跟进");
        }
        verify(taskMapper, never()).insertTask(any(FollowupTaskInsertRow.class));
    }

    @Test
    void duplicateKeyRaceIsSwallowedAsExisting() {
        when(taskMapper.findTaskById(TASK_ID)).thenReturn(taskRow("PENDING", 0));
        when(taskMapper.findMaxFollowupSeq(ENROLLMENT_ID, NODE_ID, 3)).thenReturn(0);
        templateOfSource();
        when(taskMapper.insertTask(any(FollowupTaskInsertRow.class)))
                .thenThrow(new DuplicateKeyException("uk_fu_task_occurrence"));

        assertNull(
                service.createForUnreachedContact(
                        TASK_ID, LocalDateTime.of(2026, 3, 15, 16, 30), OPERATOR),
                "并发撞 uk 按已存在处理，不抛错打断沟通记录");

        verify(outbox, never()).publish(any(), any());
        verify(audit, never()).append(any());
    }
}
