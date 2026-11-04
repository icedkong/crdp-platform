package com.crdp.followup.plan.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.plan.domain.PlanNodeRule;
import com.crdp.followup.plan.infra.mapper.PlanInsertRow;
import com.crdp.followup.plan.infra.mapper.PlanLogInsertRow;
import com.crdp.followup.plan.infra.mapper.PlanLogMapper;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.plan.infra.mapper.PlanNodeInsertRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E14-T01 / A-FU-018、A-FU-019 单元测试（验收：发布后版本只读，新入组用新版本）。 锁定口径：已发布版本编辑 40901 + DENIED 审计；乐观锁冲突
 * 40900；发布自动停用同族旧版本（新入组用新版本）；复制出新版本（草稿唯一）； 状态机迁移与非法迁移 40901；「随访中」为派生展示态。
 *
 * <p>E14-T08 / A-FU-021（验收「计划改动全记审计」）：每次成功改动除审计外双写 fu_plan_log 变更记录（含前后内容快照）；
 * 节点保存的前后节点数组、发布自动停用旧版本的逐版本 PLAN_DISABLE 记录；被拒操作不落变更记录。全部虚构数据。
 */
class PlanServiceTest {

    private static final long PLAN_ID = 11L;

    private final PlanMapper mapper = mock(PlanMapper.class);
    private final PlanLogMapper logMapper = mock(PlanLogMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final PlanService service = new PlanService(mapper, logMapper, audit);

    private static PlanMapper.PlanRow row(String status) {
        return row(status, 1, 0L);
    }

    private static PlanMapper.PlanRow row(String status, int version, long activeCount) {
        return row(status, version, activeCount, 3);
    }

    private static PlanMapper.PlanRow row(
            String status, int version, long activeCount, int lockVersion) {
        return new PlanMapper.PlanRow(
                PLAN_ID,
                "PLAN-IT-1",
                "示例 IT 年度随访计划",
                version,
                status,
                "DISCHARGE_DATE",
                "ONGOING",
                null,
                null,
                null,
                lockVersion,
                null,
                activeCount);
    }

    private static List<PlanNodeRule.NodeInput> annualNodes() {
        return List.of(
                new PlanNodeRule.NodeInput(
                        "s1",
                        null,
                        "年度随访（示例）",
                        "CYCLE",
                        null,
                        1,
                        "YEAR",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        10),
                new PlanNodeRule.NodeInput(
                        "n1",
                        "s1",
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
                        "WARD_DEFAULT",
                        "fu_annual_v1",
                        null,
                        null,
                        20));
    }

    // ---------------------------------------------------------------- 新建与编辑

    @Test
    void createStoresUnpublishedV1AndWritesAudit() {
        when(mapper.findById(anyLong())).thenReturn(row("UNPUBLISHED"));

        PlanService.PlanView view =
                service.create(
                        new PlanService.CreateRequest(
                                " plan-it-1 ",
                                "示例 IT 年度随访计划",
                                "discharge_date",
                                "ongoing",
                                null,
                                null,
                                null));

        assertEquals("PLAN-IT-1", view.code(), "编码归一为大写");
        assertEquals(1, view.version());
        assertEquals("UNPUBLISHED", view.status());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("PLAN_CREATE", captor.getValue().action());
        assertEquals(AuditResult.SUCCESS, captor.getValue().result());
    }

    @Test
    void publishedPlanIsReadOnly() {
        when(mapper.findById(anyLong())).thenReturn(row("PUBLISHED"));

        ApiException denied =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        PLAN_ID,
                                        3,
                                        new PlanService.UpdateRequest(
                                                "改名（示例）",
                                                "DISCHARGE_DATE",
                                                "ONGOING",
                                                null,
                                                null,
                                                null)));
        assertEquals(40901, denied.code());
        assertTrue(denied.getMessage().contains("复制为新版本"), denied.getMessage());

        ApiException nodesDenied =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveNodes(
                                        PLAN_ID,
                                        new PlanService.SaveNodesRequest(3, annualNodes())));
        assertEquals(40901, nodesDenied.code());

        verify(mapper, never())
                .updateBasics(
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        anyBoolean(),
                        any(),
                        eq(3));
        // 被拒写 DENIED 审计（A-FU-021 计划变更审计含被拒操作）
        verify(audit, org.mockito.Mockito.times(2)).append(any(AuditEntry.class));
    }

    @Test
    void staleBaseVersionIsVersionConflict() {
        when(mapper.findById(PLAN_ID)).thenReturn(row("UNPUBLISHED", 1, 0, 5));

        ApiException conflict =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.saveNodes(
                                        PLAN_ID,
                                        new PlanService.SaveNodesRequest(4, annualNodes())));
        assertEquals(40900, conflict.code(), "乐观锁冲突 40900（AGENTS §5.10）");
    }

    // ---------------------------------------------------------------- 发布与版本（验收核心）

    @Test
    void publishRequiresSubplanAndNode() {
        when(mapper.findById(anyLong())).thenReturn(row("UNPUBLISHED"));
        when(mapper.countSubplans(PLAN_ID)).thenReturn(0);

        ApiException failure = assertThrows(ApiException.class, () -> service.publish(PLAN_ID));
        assertEquals(40001, failure.code());
        assertTrue(failure.getMessage().contains("子计划"));
    }

    @Test
    void publishSupersedesOlderPublishedVersions() {
        when(mapper.findById(PLAN_ID)).thenReturn(row("UNPUBLISHED", 2, 0));
        when(mapper.countSubplans(PLAN_ID)).thenReturn(1);
        when(mapper.countNodes(PLAN_ID)).thenReturn(1);
        when(mapper.findSupersededIds("PLAN-IT-1", PLAN_ID)).thenReturn(List.of(5L));
        when(mapper.transitionStatus(anyLong(), anyString(), anyString())).thenReturn(1);
        // E14-T08：被顶替的旧版本逐版本留痕（PLAN_DISABLE 审计 + 变更记录）需要读到旧行
        when(mapper.findById(5L)).thenReturn(row("PUBLISHED", 1, 3L));

        service.publish(PLAN_ID);

        // 新版本 v2 发布；同族旧已发布版本 5 自动转 DISABLED（不再允许入组 → 新入组用新版本）
        verify(mapper).transitionStatus(PLAN_ID, "UNPUBLISHED", "PUBLISHED");
        verify(mapper).transitionStatus(5L, "PUBLISHED", "DISABLED");
        verify(mapper).markPublished(eq(PLAN_ID), any(), any());
    }

    @Test
    void publishedStatusDerivesRunningWhenEnrolled() {
        when(mapper.findById(anyLong())).thenReturn(row("PUBLISHED", 1, 12L));

        assertEquals("RUNNING", service.detail(PLAN_ID).plan().status(), "有在组入组 → 派生「随访中」");
        assertEquals(12, service.detail(PLAN_ID).plan().enrolledCount());
    }

    @Test
    void copyCreatesNextDraftVersionAndCopiesNodes() {
        when(mapper.findById(PLAN_ID)).thenReturn(row("PUBLISHED", 1, 3L));
        when(mapper.findDraftIdByCode("PLAN-IT-1")).thenReturn(null);
        when(mapper.findMaxVersionByCode("PLAN-IT-1")).thenReturn(3);
        when(mapper.findNodesByPlanId(PLAN_ID))
                .thenReturn(
                        List.of(
                                new PlanMapper.NodeRow(
                                        101L,
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
                                        "FORM",
                                        "WARD_DEFAULT",
                                        null,
                                        null,
                                        null,
                                        10),
                                new PlanMapper.NodeRow(
                                        102L,
                                        101L,
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
                                        "WARD_DEFAULT",
                                        "fu_annual_v1",
                                        null,
                                        null,
                                        20)));
        PlanMapper.PlanRow newDraft =
                new PlanMapper.PlanRow(
                        21L,
                        "PLAN-IT-1",
                        "示例 IT 年度随访计划",
                        4,
                        "UNPUBLISHED",
                        "DISCHARGE_DATE",
                        "ONGOING",
                        null,
                        null,
                        null,
                        0,
                        null,
                        0L);
        when(mapper.findById(21L)).thenReturn(newDraft);
        // 模拟自增回填 id
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            invocation.getArgument(0, PlanInsertRow.class).setId(21L);
                            return null;
                        })
                .when(mapper)
                .insertPlan(any(PlanInsertRow.class));

        PlanService.PlanView view = service.copy(PLAN_ID);

        assertEquals(4, view.version(), "新草稿版本 = 族内最大 3 + 1");
        assertEquals("UNPUBLISHED", view.status());
        // 节点深拷贝：子计划行 + 挂到新子计划行的节点行
        verify(mapper, org.mockito.Mockito.times(2)).insertNode(any(PlanNodeInsertRow.class));
    }

    @Test
    void copyRejectedWhileDraftExistsOrSourceUnpublished() {
        when(mapper.findById(PLAN_ID)).thenReturn(row("PUBLISHED", 1, 0));
        when(mapper.findDraftIdByCode("PLAN-IT-1")).thenReturn(21L);

        ApiException draftExists = assertThrows(ApiException.class, () -> service.copy(PLAN_ID));
        assertEquals(40901, draftExists.code());
        assertTrue(draftExists.getMessage().contains("未发布的新版本"));

        when(mapper.findDraftIdByCode("PLAN-IT-1")).thenReturn(null);
        when(mapper.findById(PLAN_ID)).thenReturn(row("UNPUBLISHED", 1, 0));
        ApiException fromDraft = assertThrows(ApiException.class, () -> service.copy(PLAN_ID));
        assertEquals(40901, fromDraft.code());
        assertTrue(fromDraft.getMessage().contains("直接编辑"));
    }

    // ---------------------------------------------------------------- 状态机（A-FU-018）

    @Test
    void statusTransitionsGuarded() {
        when(mapper.findById(anyLong())).thenReturn(row("PUBLISHED", 1, 5L));
        when(mapper.transitionStatus(anyLong(), anyString(), anyString())).thenReturn(1);

        service.pause(PLAN_ID);
        verify(mapper).transitionStatus(PLAN_ID, "PUBLISHED", "PAUSED");

        when(mapper.findById(anyLong())).thenReturn(row("PAUSED", 1, 5L));
        service.resume(PLAN_ID);
        verify(mapper).transitionStatus(PLAN_ID, "PAUSED", "PUBLISHED");

        when(mapper.findById(anyLong())).thenReturn(row("PAUSED", 1, 5L));
        service.disable(PLAN_ID);
        verify(mapper).transitionStatus(PLAN_ID, "PAUSED", "DISABLED");

        // 非法迁移：未发布不能暂停（直接编辑）；停用是终态、不能恢复
        when(mapper.findById(anyLong())).thenReturn(row("UNPUBLISHED", 1, 0));
        ApiException pauseDraft = assertThrows(ApiException.class, () -> service.pause(PLAN_ID));
        assertEquals(40901, pauseDraft.code());

        when(mapper.findById(anyLong())).thenReturn(row("DISABLED", 1, 0));
        ApiException resumeDisabled =
                assertThrows(ApiException.class, () -> service.resume(PLAN_ID));
        assertEquals(40901, resumeDisabled.code());
        ApiException disableAgain =
                assertThrows(ApiException.class, () -> service.disable(PLAN_ID));
        assertEquals(40901, disableAgain.code());
    }

    // ---------------------------------------------------------------- 变更审计（E14-T08 / A-FU-021）

    @Test
    void createWritesChangeLogWithAfterSummary() {
        when(mapper.findById(anyLong())).thenReturn(row("UNPUBLISHED"));
        when(logMapper.findLogsByPlanId(anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        service.create(
                new PlanService.CreateRequest(
                        "PLAN-IT-1",
                        "示例 IT 年度随访计划",
                        "DISCHARGE_DATE",
                        "ONGOING",
                        null,
                        null,
                        "备注（示例）"));

        ArgumentCaptor<PlanLogInsertRow> captor = ArgumentCaptor.forClass(PlanLogInsertRow.class);
        verify(logMapper).insertLog(captor.capture());
        PlanLogInsertRow logRow = captor.getValue();
        assertEquals("PLAN_CREATE", logRow.getAction());
        assertEquals("PLAN-IT-1", logRow.getPlanCode());
        assertNull(logRow.getBeforeJson(), "新建无前内容");
        assertTrue(
                logRow.getAfterJson().contains("\"remark\":\"备注（示例）\""),
                "后内容含备注（A-FU-021 全量前后内容）：" + logRow.getAfterJson());
    }

    @Test
    void saveNodesWritesBeforeAfterNodeSnapshotsIntoAuditAndChangeLog() {
        when(mapper.findById(anyLong())).thenReturn(row("UNPUBLISHED"));
        when(mapper.bumpLockVersion(anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(1);
        PlanMapper.NodeRow beforeNode =
                new PlanMapper.NodeRow(
                        101L,
                        null,
                        "SUBPLAN",
                        "旧子计划（示例）",
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
                        "FORM",
                        "WARD_DEFAULT",
                        null,
                        null,
                        null,
                        10);
        PlanMapper.NodeRow afterNode =
                new PlanMapper.NodeRow(
                        201L,
                        null,
                        "SUBPLAN",
                        "新子计划（示例）",
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
                        "FORM",
                        "WARD_DEFAULT",
                        null,
                        null,
                        null,
                        10);
        // 保存前快照 → 替换 → 保存后快照（detail 再读一次沿用 after）
        when(mapper.findNodesByPlanId(PLAN_ID))
                .thenReturn(List.of(beforeNode))
                .thenReturn(List.of(afterNode))
                .thenReturn(List.of(afterNode));

        service.saveNodes(PLAN_ID, new PlanService.SaveNodesRequest(3, annualNodes()));

        ArgumentCaptor<PlanLogInsertRow> logCaptor =
                ArgumentCaptor.forClass(PlanLogInsertRow.class);
        verify(logMapper).insertLog(logCaptor.capture());
        PlanLogInsertRow logRow = logCaptor.getValue();
        assertEquals("PLAN_NODE_SAVE", logRow.getAction());
        assertTrue(
                logRow.getBeforeJson().contains("旧子计划（示例）"),
                "前内容 = 替换前节点数组：" + logRow.getBeforeJson());
        assertTrue(
                logRow.getAfterJson().contains("新子计划（示例）"),
                "后内容 = 替换后节点数组：" + logRow.getAfterJson());
        // 审计同样带前后内容（A-FU-021）
        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(auditCaptor.capture());
        assertEquals("PLAN_NODE_SAVE", auditCaptor.getValue().action());
        assertTrue(auditCaptor.getValue().beforeJson().contains("旧子计划（示例）"));
        assertTrue(auditCaptor.getValue().afterJson().contains("新子计划（示例）"));
    }

    @Test
    void publishWritesChangeLogForNewAndSupersededVersions() {
        when(mapper.findById(PLAN_ID)).thenReturn(row("UNPUBLISHED", 2, 0));
        when(mapper.countSubplans(PLAN_ID)).thenReturn(1);
        when(mapper.countNodes(PLAN_ID)).thenReturn(1);
        when(mapper.findSupersededIds("PLAN-IT-1", PLAN_ID)).thenReturn(List.of(5L));
        when(mapper.transitionStatus(anyLong(), anyString(), anyString())).thenReturn(1);
        when(mapper.findById(5L)).thenReturn(row("PUBLISHED", 1, 3L));

        service.publish(PLAN_ID);

        ArgumentCaptor<PlanLogInsertRow> captor = ArgumentCaptor.forClass(PlanLogInsertRow.class);
        verify(logMapper, org.mockito.Mockito.times(2)).insertLog(captor.capture());
        // 被顶替的旧版本 5：PLAN_DISABLE 记录（发布新版本引起的停用也是计划改动）
        PlanLogInsertRow supersededLog = captor.getAllValues().get(0);
        assertEquals(5L, supersededLog.getPlanId());
        assertEquals("PLAN_DISABLE", supersededLog.getAction());
        assertTrue(supersededLog.getBeforeJson().contains("\"status\":\"PUBLISHED\""));
        assertTrue(supersededLog.getAfterJson().contains("\"status\":\"DISABLED\""));
        // 新版本：PLAN_PUBLISH 记录
        PlanLogInsertRow publishLog = captor.getAllValues().get(1);
        assertEquals(PLAN_ID, publishLog.getPlanId());
        assertEquals("PLAN_PUBLISH", publishLog.getAction());
        assertTrue(publishLog.getBeforeJson().contains("\"status\":\"UNPUBLISHED\""));
        assertTrue(publishLog.getAfterJson().contains("\"status\":\"PUBLISHED\""));
        // 审计同样逐版本留痕（旧版本 PLAN_DISABLE + 新版本 PLAN_PUBLISH）
        verify(audit, org.mockito.Mockito.times(2)).append(any(AuditEntry.class));
    }

    @Test
    void deniedOperationsDoNotWriteChangeLog() {
        when(mapper.findById(anyLong())).thenReturn(row("PUBLISHED"));

        ApiException denied =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        PLAN_ID,
                                        3,
                                        new PlanService.UpdateRequest(
                                                "改名（示例）",
                                                "DISCHARGE_DATE",
                                                "ONGOING",
                                                null,
                                                null,
                                                null)));
        assertEquals(40901, denied.code());
        verify(logMapper, never()).insertLog(any(PlanLogInsertRow.class));
    }

    @Test
    void logsReturnsRowsWithOperatorAndTime() {
        when(mapper.findById(anyLong())).thenReturn(row("PUBLISHED"));
        when(logMapper.findLogsByPlanId(PLAN_ID, 100))
                .thenReturn(
                        List.of(
                                new PlanLogMapper.PlanLogRow(
                                        9L,
                                        PLAN_ID,
                                        "PLAN-IT-1",
                                        "PLAN_PAUSE",
                                        "暂停计划：示例 IT 年度随访计划 v1",
                                        "{\"status\":\"PUBLISHED\"}",
                                        "{\"status\":\"PAUSED\"}",
                                        7L,
                                        "甲医生（示例）",
                                        java.time.LocalDateTime.of(2026, 3, 1, 8, 0))));

        PlanService.PlanLogsView view = service.logs(PLAN_ID);

        assertEquals(1, view.items().size());
        PlanService.PlanLogView item = view.items().get(0);
        assertEquals("PLAN_PAUSE", item.action());
        assertEquals("甲医生（示例）", item.operatorName(), "操作人姓名联查 sys_user");
        assertTrue(item.createdAt().startsWith("2026-03-01T"), "ISO-8601 带时区：" + item.createdAt());

        when(mapper.findById(anyLong())).thenReturn(null);
        ApiException missing = assertThrows(ApiException.class, () -> service.logs(PLAN_ID));
        assertEquals(40400, missing.code());
    }
}
