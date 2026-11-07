package com.crdp.followup.stats.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.stats.infra.mapper.NodeOccurrenceInsertRow;
import com.crdp.followup.stats.infra.mapper.NodeOccurrenceMapper;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.job.JobScheduler;
import com.crdp.framework.job.TenantJob;
import com.crdp.framework.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E19-T01 刷新作业体单测（验收「按节点出应随访、已随访数」的服务层口径：整表重建 + 正确行落库）。 场景沿用 E14-T04 的 A 年度形态（虚构）：起算 2025-03-15、±7
 * 天窗口，第 1 年窗口 2026-03-08 ~ 2026-03-22。
 */
@ExtendWith(MockitoExtension.class)
class NodeOccurrenceRefreshServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long ENROLLMENT_ID = 31L;
    private static final long PATIENT_ID = 77L;
    private static final long PLAN_ID = 11L;
    private static final long SUBPLAN_ID = 100L;
    private static final long NODE_ID = 200L;
    private static final LocalDate BASIS = LocalDate.of(2025, 3, 15);
    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 1);

    @Mock private JobScheduler jobScheduler;
    @Mock private NodeOccurrenceMapper occurrenceMapper;
    @Mock private PlanMapper planMapper;
    @Mock private AuditLogger audit;
    @Mock private JdbcTemplate jdbc;

    /** 事务直通（整表重算的事务边界由服务保证，测试里直接执行回调；同 ImportCommitServiceTest 口径）。 */
    private final TransactionTemplate transactions =
            new TransactionTemplate(
                    new PlatformTransactionManager() {
                        @Override
                        public TransactionStatus getTransaction(
                                org.springframework.transaction.TransactionDefinition definition) {
                            return new SimpleTransactionStatus();
                        }

                        @Override
                        public void commit(TransactionStatus status) {}

                        @Override
                        public void rollback(TransactionStatus status) {}
                    });

    private NodeOccurrenceRefreshService service() {
        return new NodeOccurrenceRefreshService(
                jobScheduler, occurrenceMapper, planMapper, audit, jdbc, transactions);
    }

    /** 模拟调度框架：在租户上下文内执行作业体（runForEachTenant 的语义）。 */
    private void run(NodeOccurrenceRefreshService service, LocalDate asOf) {
        when(jobScheduler.runForEachTenant(anyString(), eq(asOf), any()))
                .thenAnswer(
                        invocation -> {
                            TenantJob job = invocation.getArgument(2, TenantJob.class);
                            TenantContext.runAsTenant(
                                    TENANT_ID,
                                    "demoa",
                                    () -> {
                                        try {
                                            job.run(asOf);
                                        } catch (Exception ex) {
                                            throw new IllegalStateException(ex);
                                        }
                                    });
                            return List.of();
                        });
        service.refreshForDate(asOf);
    }

    private static PlanMapper.PlanRow planRow() {
        return new PlanMapper.PlanRow(
                PLAN_ID,
                "PLAN-IT-1",
                "示例 IT 年度随访计划",
                2,
                "PUBLISHED",
                "DISCHARGE_DATE",
                "ONGOING",
                null,
                null,
                null,
                0,
                null,
                1L);
    }

    private static PlanMapper.NodeRow subplanRow() {
        return new PlanMapper.NodeRow(
                SUBPLAN_ID,
                null,
                "SUBPLAN",
                "年度随访（示例）",
                "CYCLE",
                null,
                1,
                "YEAR",
                null,
                0,
                "YEAR",
                0,
                "DAY",
                0,
                "DAY",
                0,
                "DAY",
                null,
                null,
                null,
                null,
                null,
                10);
    }

    private static PlanMapper.NodeRow nodeRow() {
        return new PlanMapper.NodeRow(
                NODE_ID,
                SUBPLAN_ID,
                "NODE",
                "电话随访（示例）",
                null,
                null,
                null,
                null,
                "PLAN_START",
                1,
                "YEAR",
                7,
                "DAY",
                7,
                "DAY",
                7,
                "DAY",
                "FORM",
                "DOCTOR",
                "fu_annual_v1",
                "BACKFILL",
                null,
                20);
    }

    private void tenantWithAnnualEnrollment() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(TENANT_ID))).thenReturn(1);
        when(occurrenceMapper.findAllEnrollments())
                .thenReturn(
                        List.of(
                                new NodeOccurrenceMapper.EnrollmentRow(
                                        ENROLLMENT_ID,
                                        PATIENT_ID,
                                        PLAN_ID,
                                        BASIS,
                                        "NORMAL",
                                        LocalDate.of(2025, 3, 20))));
        when(planMapper.findById(PLAN_ID)).thenReturn(planRow());
        when(planMapper.findNodesByPlanId(PLAN_ID)).thenReturn(List.of(subplanRow(), nodeRow()));
        when(occurrenceMapper.findRegularTasks())
                .thenReturn(
                        List.of(
                                new NodeOccurrenceMapper.TaskRow(
                                        901L,
                                        700L,
                                        ENROLLMENT_ID,
                                        NODE_ID,
                                        1,
                                        LocalDate.of(2026, 3, 15),
                                        LocalDate.of(2026, 3, 8),
                                        LocalDate.of(2026, 3, 22),
                                        "NORMAL",
                                        "COMPLETED",
                                        "ON_TIME")));
        lenient().when(occurrenceMapper.findAnchoredRecords()).thenReturn(List.of());
        lenient().when(occurrenceMapper.findFloatingBackfills()).thenReturn(List.of());
        when(occurrenceMapper.findStatusHistory())
                .thenReturn(
                        List.of(
                                new NodeOccurrenceMapper.StatusPointRow(
                                        PATIENT_ID, "ACTIVE", LocalDate.of(2025, 3, 20))));
    }

    // ---------------------------------------------------------------- 验收：按节点出应随访、已随访数

    @Test
    void refreshRebuildsTableWithDueAndFollowedRow() {
        tenantWithAnnualEnrollment();

        run(service(), AS_OF);

        verify(occurrenceMapper).deleteAll();
        ArgumentCaptor<NodeOccurrenceInsertRow> captor =
                ArgumentCaptor.forClass(NodeOccurrenceInsertRow.class);
        verify(occurrenceMapper).insertFact(captor.capture());
        NodeOccurrenceInsertRow row = captor.getValue();
        assertEquals(PATIENT_ID, row.getPatientId());
        assertEquals(ENROLLMENT_ID, row.getEnrollmentId());
        assertEquals(NODE_ID, row.getNodeId());
        assertEquals(1, row.getOccurrence());
        assertTrue(row.isDue(), "窗口已结束且窗口开始日在组 → 应随访");
        assertTrue(row.isFollowed(), "任务已完成 → 已随访");
        assertEquals(700L, row.getRecordId(), "已完成任务带回关联记录 id");
        assertEquals("ON_TIME", row.getCompleteKind());
        assertEquals(AS_OF, row.getRefreshedDate());
        assertEquals("PLAN-IT-1", row.getPlanCode());
        assertEquals(2, row.getPlanVersion());
        verify(audit).append(any());
    }

    @Test
    void moduleClosedTenantWritesNothing() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(TENANT_ID))).thenReturn(0);

        run(service(), AS_OF);

        verify(occurrenceMapper, never()).deleteAll();
        verify(occurrenceMapper, never()).insertFact(any(NodeOccurrenceInsertRow.class));
        verify(audit, never()).append(any());
    }

    @Test
    void noEnrollmentTenantKeepsEmptyTableAndSkipsAudit() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(TENANT_ID))).thenReturn(1);
        when(occurrenceMapper.findAllEnrollments()).thenReturn(List.of());

        run(service(), AS_OF);

        verify(occurrenceMapper).deleteAll();
        verify(occurrenceMapper, never()).insertFact(any(NodeOccurrenceInsertRow.class));
        verify(audit, never()).append(any());
    }

    @Test
    void refreshIsIdempotentReplayRewritesSameRows() {
        tenantWithAnnualEnrollment();
        NodeOccurrenceRefreshService service = service();

        run(service, AS_OF);
        run(service, AS_OF);

        // 每日重建口径：重复执行整表重写、行数不变（job_tenant_run 同日重放由框架幂等键兜底）
        verify(occurrenceMapper, times(2)).deleteAll();
        verify(occurrenceMapper, times(2)).insertFact(any(NodeOccurrenceInsertRow.class));
    }

    @Test
    void aggregatedCountsComeFromFactTableQuery() {
        // 查询侧（E19-T04 卡片共用）：按节点聚合应随访 / 已随访——本任务锁定服务层入口
        when(occurrenceMapper.aggregateByNode(PLAN_ID))
                .thenReturn(
                        List.of(
                                new NodeOccurrenceMapper.NodeCountRow(
                                        NODE_ID, "电话随访（示例）", 1, 2, 1, 1, 0, 0)));
        NodeOccurrenceStatsService statsService = new NodeOccurrenceStatsService(occurrenceMapper);

        List<NodeOccurrenceStatsService.NodeCountView> counts = statsService.nodeCounts(PLAN_ID);

        assertEquals(1, counts.size());
        assertEquals(NODE_ID, counts.get(0).nodeId());
        assertEquals(2, counts.get(0).dueCount(), "两名应随访");
        assertEquals(1, counts.get(0).followedCount(), "一名已随访");
    }
}
