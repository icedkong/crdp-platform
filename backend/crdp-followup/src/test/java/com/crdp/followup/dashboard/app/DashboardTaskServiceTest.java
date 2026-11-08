package com.crdp.followup.dashboard.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.contact.app.ContactLogService;
import com.crdp.followup.dashboard.app.DashboardKpiService.DrillView;
import com.crdp.followup.dashboard.app.DashboardTaskService.BoardColumnView;
import com.crdp.followup.dashboard.app.DashboardTaskService.TaskBoardView;
import com.crdp.followup.dashboard.app.DashboardTaskService.TaskTableView;
import com.crdp.followup.dashboard.app.DashboardTaskService.WardOptionView;
import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.dashboard.infra.mapper.DashboardTaskMapper;
import com.crdp.followup.dashboard.infra.mapper.DashboardTaskMapper.TableCountRow;
import com.crdp.followup.dashboard.infra.mapper.DashboardTaskMapper.TaskCardCriteria;
import com.crdp.followup.task.infra.mapper.FollowupTaskQueryMapper.TaskRowRaw;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E19-T03 / A-DASH-003、A-DASH-004、A-DASH-008、C-DASH-003 任务看板与任务表服务单测（验收「受病区隔离」的条件装配与响应结构）：
 *
 * <ul>
 *   <li>看板四列（待随访 / 进行中 / 逾期 / 本月已完成——C 为本周完成）与页签口径、每列前 20 张卡（未完成列按窗口结束日、已完成列按完成时间倒序）；
 *   <li>「数字与列表钻取一致」：各列 / 各页签的钻取 query 与任务列表检索参数逐项一致（tab / status / 窗口 / 完成时间）；
 *   <li>行内姓名 / 病案号脱敏（解密后掩码，列表不提供逐行明文）；
 *   <li>病区候选 = 本人可见病区（快照病区清单交集；全院 / 科室 / 本人快照回落全集）；
 *   <li>参数清洗：scope 枚举与默认 MINE、wardId / assigneeId / tab 校验 40001。
 * </ul>
 *
 * <p>「受病区隔离」的端到端（越范围病区全 0、双租户隔离）与列数 = 列表总数在 DashboardTaskCardsIT（MySQL 门控）覆盖； 本测锁定条件装配与响应结构。全部虚构数据。
 */
class DashboardTaskServiceTest {

    private final DashboardTaskMapper tasks = mock(DashboardTaskMapper.class);
    private final DashboardFeatureMapper features = mock(DashboardFeatureMapper.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final ContactLogService contactLogs = mock(ContactLogService.class);

    private final DashboardTaskService service =
            new DashboardTaskService(tasks, features, crypto, audit, contactLogs);

    {
        lenient().when(features.findEnabled("implant")).thenReturn(false);
        // 计数非零、行空：本测锁定条件装配与结构（行内容单独一例）
        lenient().when(tasks.countByStatus(any())).thenReturn(3L);
        lenient().when(tasks.cardTasks(any(), anyBoolean(), anyInt())).thenReturn(List.of());
        lenient().when(tasks.tableTabCounts(any())).thenReturn(new TableCountRow(4L, 2L, 7L));
        lenient().when(tasks.tableTasks(any(), anyString(), anyInt())).thenReturn(List.of());
        // 解密原样返回（明文进掩码，断言掩码而非密文）
        lenient().when(crypto.decrypt(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearSession() {
        SecurityContextHolder.clearContext();
    }

    @org.junit.jupiter.api.BeforeEach
    void login() {
        // board / table 取当前用户 id（scope MINE / 页签 MINE 用），默认登录一名医生（病区候选两例另行覆盖快照口径）
        loginWith(ScopeSnapshot.allowAll());
    }

    // ---------------------------------------------------------------- 看板（A-DASH-003 / A-DASH-004）

    @Test
    void boardColumnsWithMonthDoneWindowForHospitalA() {
        TaskBoardView view = service.board(null, null, null);

        assertEquals("MINE", view.scope(), "看板筛选缺省我的（A-DASH-004 与任务列表默认一致，OQ-108）");
        assertEquals(4, view.columns().size());
        assertEquals(
                List.of("待随访", "进行中", "逾期", "本月已完成"),
                view.columns().stream().map(BoardColumnView::title).toList());
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        assertEquals(today.withDayOfMonth(1).toString(), view.doneFrom(), "A 已完成列 = 本月");
        assertEquals(today.withDayOfMonth(today.lengthOfMonth()).toString(), view.doneTo());
    }

    @Test
    void boardColumnsWithWeekDoneWindowForHospitalC() {
        when(features.findEnabled("implant")).thenReturn(true);

        TaskBoardView view = service.board(null, "ALL", null);

        assertEquals(
                List.of("待随访", "进行中", "逾期", "本周完成"),
                view.columns().stream().map(BoardColumnView::title).toList(),
                "C-DASH-003：完成列为本周完成");
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        assertEquals(monday.toString(), view.doneFrom(), "本周 = 周一起");
        assertEquals(monday.plusDays(6).toString(), view.doneTo(), "本周 = 周日止");
    }

    @Test
    void boardDrillsMatchTaskListSearchParams() {
        TaskBoardView view = service.board(null, "ALL", 9L);

        // 三张未完成列：钻取 = 任务列表 tab + status（与列表检索同参数）
        for (BoardColumnView column : view.columns().subList(0, 3)) {
            DrillView drill = column.drill();
            assertEquals("TASK_LIST", drill.target());
            assertEquals("all", drill.query().get("tab"));
            assertEquals(column.status(), drill.query().get("status"));
        }
        // 已完成列：另带完成时间窗口（列表 completedFrom / completedTo 同口径）
        BoardColumnView done = view.columns().get(3);
        assertEquals("COMPLETED", done.status());
        assertEquals("all", done.drill().query().get("tab"));
        assertEquals("COMPLETED", done.drill().query().get("status"));
        assertEquals(view.doneFrom(), done.drill().query().get("completedFrom"));
        assertEquals(view.doneTo(), done.drill().query().get("completedTo"));
        // 负责人筛选随钻取带出（A-DASH-004 筛选在列表侧延续）
        assertEquals(9L, done.drill().query().get("doctorId"));
    }

    @Test
    void boardCriteriaCarriesScopeWardAndAssigneeAndDoneWindow() {
        service.board(5L, "WARD", 9L);

        ArgumentCaptor<TaskCardCriteria> captor = ArgumentCaptor.forClass(TaskCardCriteria.class);
        // 未完成列：scope=WARD、wardId=5、assigneeId=9、无完成窗口
        verify(tasks, org.mockito.Mockito.times(4)).countByStatus(captor.capture());
        TaskCardCriteria open = captor.getAllValues().getFirst();
        assertEquals("WARD", open.scope());
        assertEquals(5L, open.wardId());
        assertEquals(9L, open.assigneeId());
        assertEquals("PENDING", open.status());
        assertNull(open.doneFrom());
        // 已完成列：完成时间窗口落当月
        TaskCardCriteria done = captor.getAllValues().getLast();
        assertEquals("COMPLETED", done.status());
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        assertEquals(today.withDayOfMonth(1), done.doneFrom());
        assertEquals(today.withDayOfMonth(today.lengthOfMonth()), done.doneTo());
    }

    @Test
    void boardTasksSortedByWindowEndAndTopTwenty() {
        verify(tasks, never()).cardTasks(any(), eq(true), anyInt());
        service.board(null, "ALL", null);
        // 未完成三列按窗口结束日升序（最紧急在前）、已完成列按完成时间倒序，各取前 20（A 规格 §9）
        verify(tasks, org.mockito.Mockito.times(3)).cardTasks(any(), eq(false), eq(20));
        verify(tasks, org.mockito.Mockito.times(1)).cardTasks(any(), eq(true), eq(20));
    }

    @Test
    void boardRowsMaskedWithLastContactResult() {
        TaskRowRaw raw =
                new TaskRowRaw(
                        11L,
                        21L,
                        "张三明",
                        "MRN001",
                        "LEG-1",
                        "示例二病区",
                        "第4年随访",
                        4,
                        "FORM",
                        "fu_annual_v1",
                        LocalDate.of(2026, 11, 5),
                        LocalDate.of(2026, 10, 29),
                        LocalDate.of(2026, 11, 12),
                        LocalDate.of(2026, 10, 29),
                        "PENDING",
                        null,
                        "NORMAL",
                        null,
                        1L,
                        "王医生（示例）",
                        null);
        when(tasks.countByStatus(any())).thenReturn(1L);
        when(tasks.cardTasks(any(), anyBoolean(), anyInt())).thenReturn(List.of(raw));
        lenient()
                .when(contactLogs.latestResultByPatients(List.of(21L)))
                .thenReturn(Map.of(21L, "NOT_REACHED"));

        TaskBoardView view = service.board(null, "ALL", null);
        var row = view.columns().getFirst().tasks().getFirst();

        assertEquals("11", row.id());
        assertTrue(row.patientName().contains("*"), "姓名脱敏（列表不提供逐行明文）");
        assertTrue(row.mrn().contains("*"), "病案号脱敏");
        assertEquals("NOT_REACHED", row.lastContactResult(), "看板卡片显示最近一次联系结果");
        assertEquals("王医生（示例）", row.assigneeName());
    }

    // ---------------------------------------------------------------- 任务表（A-DASH-008）

    @Test
    void tableTabsWithCountsAndTodayWindow() {
        TaskTableView view = service.table(null, null);

        assertEquals("TODAY", view.tab(), "任务表页签缺省今日任务");
        assertEquals(3, view.tabs().size());
        assertEquals(
                List.of("今日任务", "超时任务", "我负责的任务"),
                view.tabs().stream().map(tab -> tab.title()).toList());
        assertEquals(4L, view.tabs().get(0).total());
        assertEquals(2L, view.tabs().get(1).total());
        assertEquals(7L, view.tabs().get(2).total());
        assertEquals(4L, view.total(), "当前页签总数与页签徽标一致");

        // TODAY 的窗口包含当日（A 规格 §9：今天处于窗口内且未完成，含跟进任务与站内提醒）
        ArgumentCaptor<TaskCardCriteria> captor = ArgumentCaptor.forClass(TaskCardCriteria.class);
        verify(tasks).tableTabCounts(captor.capture());
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        assertEquals(today, captor.getValue().windowOn());
        verify(tasks).tableTasks(any(), eq("TODAY"), eq(10));
    }

    @Test
    void tableDrillsMatchTaskListSearchParams() {
        TaskTableView view = service.table(null, "OVERDUE");

        // 今日任务钻取：列表 tab=all + 未完成五态 + 窗口当日（windowFrom/windowTo）
        var today = view.tabs().get(0);
        assertEquals("TASK_LIST", today.drill().target());
        assertEquals("all", today.drill().query().get("tab"));
        assertEquals(
                "PENDING,IN_PROGRESS,OVERDUE,LOST_CANDIDATE,SKIPPED_SUSPENDED",
                today.drill().query().get("status"));
        LocalDate todayDate = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        assertEquals(todayDate.toString(), today.drill().query().get("windowFrom"));
        assertEquals(todayDate.toString(), today.drill().query().get("windowTo"));
        // 超时任务钻取：status=OVERDUE（与指标卡「当前逾期」同一棵条件树）
        var overdue = view.tabs().get(1);
        assertEquals("OVERDUE", overdue.drill().query().get("status"));
        // 我负责的任务钻取：列表 MINE 页签默认未完成（负责人为本人、尚未完成）
        var mine = view.tabs().get(2);
        assertEquals("mine", mine.drill().query().get("tab"));
        assertNull(mine.drill().query().get("status"));
    }

    @Test
    void tableWardFilterPassedThrough() {
        service.table(6L, "MINE");
        ArgumentCaptor<TaskCardCriteria> captor = ArgumentCaptor.forClass(TaskCardCriteria.class);
        verify(tasks).tableTabCounts(captor.capture());
        assertEquals(6L, captor.getValue().wardId());
        verify(tasks).tableTasks(any(), eq("MINE"), eq(10));
    }

    // ---------------------------------------------------------------- 病区候选（A-DASH-005 /
    // C-DASH-008）

    @Test
    void wardOptionsIntersectSnapshotWards() {
        loginWith(ScopeSnapshot.restricted(List.of(2L, 3L), List.of(), false));
        when(tasks.findWardOptions(List.of(2L, 3L)))
                .thenReturn(
                        List.of(
                                new DashboardTaskMapper.WardOptionRow(2L, "示例一病区"),
                                new DashboardTaskMapper.WardOptionRow(3L, "示例二病区")));

        List<WardOptionView> wards = service.wardOptions().wards();

        assertEquals(2, wards.size());
        assertEquals("2", wards.get(0).id());
        assertEquals("示例一病区", wards.get(0).name());
        verify(tasks).findWardOptions(List.of(2L, 3L));
    }

    @Test
    void wardOptionsFallBackToAllEnabledWhenSnapshotHasNoWards() {
        // 全院快照与科室 / 本人快照都没有病区清单：候选回落全部启用病区（行级隔离仍由 @DataScope 出行）
        loginWith(ScopeSnapshot.allowAll());
        when(tasks.findWardOptions(null)).thenReturn(List.of());

        service.wardOptions();
        verify(tasks).findWardOptions(null);
    }

    // ---------------------------------------------------------------- 参数清洗

    @Test
    void invalidParametersRejected() {
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> service.board(0L, null, null)).code());
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> service.board(null, "TEAM", null)).code());
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> service.board(null, null, -1L)).code());
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> service.table(null, "NEXT_WEEK")).code());
        assertEquals(40001, assertThrows(ApiException.class, () -> service.table(0L, null)).code());
    }

    @Test
    void batchQueryAuditWrittenForBoardAndTable() {
        when(tasks.countByStatus(any())).thenReturn(2L);
        service.board(null, "ALL", null);
        service.table(null, null);
        verify(audit, org.mockito.Mockito.times(2)).append(any(AuditEntry.class));
    }

    // ---------------------------------------------------------------- 辅助

    private void loginWith(ScopeSnapshot scope) {
        SessionPrincipal principal =
                new SessionPrincipal(
                        "unit-token",
                        "HOSPITAL",
                        1L,
                        "demoa",
                        "demoa（示例）",
                        9L,
                        "doctor.unit",
                        "单测医生（示例）",
                        List.of("FOLLOWUP_DOCTOR"),
                        List.of(),
                        List.of("followup:dashboard:view"),
                        List.of("followup"),
                        1L,
                        scope,
                        false,
                        null);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
