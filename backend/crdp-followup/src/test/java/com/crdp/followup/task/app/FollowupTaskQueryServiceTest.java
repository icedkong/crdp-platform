package com.crdp.followup.task.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.contact.app.ContactLogService;
import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.implant.app.ImplantDictPort;
import com.crdp.followup.implant.app.ImplantGateway;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.ImplantRow;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper;
import com.crdp.followup.task.infra.mapper.FollowupTaskQueryMapper;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E21-T23 / C-FU-020 任务列表 C 三列（当前器械类型、随访时间点、上次随访标红项数）的单元测试。
 *
 * <p>锁定口径：{@code implant} 开关开启（医院 C）时行附 deviceType（字典显示名，无在用器械 null）、timepoint（随访时间点 = 任务节点名，C
 * 预置节点名即时间点，C-FU-008「来自任务的子计划」）与 lastHighlightCount（该患者最近一条已提交记录的标红命中数， 无已提交记录 null）；开关关闭（A /
 * B）三列全部 null 且不触发植入 / 标红查询，行为与 E21 前完全一致（AGENTS §5.7）； {@code compare_highlight}
 * 关闭时标红计数按读取侧过滤（followup.md §4，存量 highlight 不清理）为 null。全部虚构数据。
 */
class FollowupTaskQueryServiceTest {

    private static final long USER_ID = 9L;
    private static final long PATIENT_ID = 301L;

    private final FollowupTaskQueryMapper mapper = mock(FollowupTaskQueryMapper.class);
    private final PatientMapper patientMapper = mock(PatientMapper.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final ContactLogService contactLogs = mock(ContactLogService.class);
    private final DashboardFeatureMapper features = mock(DashboardFeatureMapper.class);
    private final ImplantGateway implantGateway = mock(ImplantGateway.class);
    private final ImplantDictPort implantDicts = mock(ImplantDictPort.class);
    private final FollowupRecordMapper recordMapper = mock(FollowupRecordMapper.class);

    private final FollowupTaskQueryService service =
            new FollowupTaskQueryService(
                    mapper,
                    patientMapper,
                    crypto,
                    audit,
                    contactLogs,
                    features,
                    implantGateway,
                    implantDicts,
                    recordMapper);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        when(crypto.decrypt(anyString()))
                .thenAnswer(
                        invocation -> invocation.getArgument(0).toString().replace("CIPHER:", ""));
        // 无行 / 未配置开关 → findEnabled 为 null（implant 默认关；compare_highlight 默认开，由服务层判语义）
        lenient().when(features.findEnabled(anyString())).thenReturn(null);
        lenient().when(contactLogs.latestResultByPatients(anyList())).thenReturn(Map.of());
        lenient()
                .when(mapper.tabCounts(any()))
                .thenReturn(new FollowupTaskQueryMapper.TabCountRow(0L, 0L, 0L));
        lenient().when(mapper.count(any())).thenReturn(0L);
        lenient().when(patientMapper.findWards()).thenReturn(List.of());
        lenient().when(implantDicts.items(anyString())).thenReturn(List.of());
        lenient().when(mapper.search(any(), anyInt(), anyInt())).thenReturn(List.of(row("术后1个月")));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- C 三列（E21-T23 / C-FU-020）

    @Test
    void searchAppendsDeviceTypeTimepointAndLastHighlightWhenImplantEnabled() {
        when(features.findEnabled("implant")).thenReturn(true);
        when(implantGateway.activeImplantOf(PATIENT_ID))
                .thenReturn(implant("DDD", "ACTIVE", LocalDate.of(2026, 1, 5)));
        when(implantDicts.items("pacemaker_type"))
                .thenReturn(
                        List.of(
                                new ImplantDictPort.DictItem("DDD", "双腔(DDD)", 10, true),
                                new ImplantDictPort.DictItem("CRT_D", "CRT-D", 20, true)));
        when(recordMapper.latestSubmittedHighlightCounts(List.of(PATIENT_ID)))
                .thenReturn(List.of(new FollowupRecordMapper.PatientHighlightRow(PATIENT_ID, 2)));

        FollowupTaskQueryService.TaskRowView item = service.search(null).items().get(0);

        assertEquals("双腔(DDD)", item.deviceType(), "当前器械类型 = 在用器械的字典显示名");
        assertEquals("术后1个月", item.timepoint(), "随访时间点 = 任务节点名（C 预置节点即时间点）");
        assertEquals(2, item.lastHighlightCount(), "上次随访标红项数 = 最近一条已提交记录命中数");
    }

    @Test
    void searchTreatsNullHighlightAsZeroButKeepsNoRecordNull() {
        when(features.findEnabled("implant")).thenReturn(true);
        when(mapper.search(any(), anyInt(), anyInt())).thenReturn(List.of(row("常规随访")));
        when(implantGateway.activeImplantOf(PATIENT_ID)).thenReturn(null);
        when(recordMapper.latestSubmittedHighlightCounts(List.of(PATIENT_ID)))
                .thenReturn(
                        List.of(new FollowupRecordMapper.PatientHighlightRow(PATIENT_ID, null)));

        FollowupTaskQueryService.TaskRowView item = service.search(null).items().get(0);

        assertNull(item.deviceType(), "无在用器械（取出后）器械列为 null");
        assertEquals("常规随访", item.timepoint());
        assertEquals(0, item.lastHighlightCount(), "最近记录无命中计 0");
    }

    @Test
    void searchReturnsNoHighlightWhenPatientHasNoSubmittedRecord() {
        when(features.findEnabled("implant")).thenReturn(true);
        when(implantGateway.activeImplantOf(PATIENT_ID)).thenReturn(null);
        when(recordMapper.latestSubmittedHighlightCounts(List.of(PATIENT_ID)))
                .thenReturn(List.of());

        FollowupTaskQueryService.TaskRowView item = service.search(null).items().get(0);

        assertNull(item.lastHighlightCount(), "无已提交记录：无「上次随访」可计，列显示「—」");
    }

    @Test
    void searchOmitsThreeColumnsWhenImplantDisabled() {
        when(features.findEnabled("implant")).thenReturn(null);
        when(mapper.search(any(), anyInt(), anyInt())).thenReturn(List.of(row("第 4 年随访（示例）")));

        FollowupTaskQueryService.TaskRowView item = service.search(null).items().get(0);

        assertNull(item.deviceType(), "A / B：器械列不出（AGENTS §5.7）");
        assertNull(item.timepoint(), "A / B：随访时间点列不出");
        assertNull(item.lastHighlightCount(), "A / B：上次标红列不出");
        verify(implantGateway, never()).activeImplantOf(anyLong());
        verify(recordMapper, never()).latestSubmittedHighlightCounts(anyList());
    }

    @Test
    void searchFiltersHighlightByCompareSwitchOnReadSide() {
        when(features.findEnabled("implant")).thenReturn(true);
        when(features.findEnabled("compare_highlight")).thenReturn(false);
        when(implantGateway.activeImplantOf(PATIENT_ID))
                .thenReturn(implant("DDD", "ACTIVE", LocalDate.of(2026, 1, 5)));

        FollowupTaskQueryService.TaskRowView item = service.search(null).items().get(0);

        assertEquals("DDD", item.deviceType(), "字典未配置时回落原码（C-FLD-009 口径）");
        assertNull(item.lastHighlightCount(), "compare_highlight 关闭：读取侧过滤，不出计数");
        verify(recordMapper, never()).latestSubmittedHighlightCounts(anyList());
    }

    // ---------------------------------------------------------------- 夹具

    /** 造一页一行（患者 301、节点名入参，C 例「术后1个月」）。 */
    private static FollowupTaskQueryMapper.TaskRowRaw row(String nodeName) {
        return new FollowupTaskQueryMapper.TaskRowRaw(
                1001L,
                PATIENT_ID,
                "CIPHER:张小明（示例）",
                "CIPHER:IT-MRN-3001",
                "IT-E21T23-3001",
                "示例二病区",
                nodeName,
                1,
                "FORM",
                "C_PROGRAM_FU",
                LocalDate.of(2026, 11, 5),
                LocalDate.of(2026, 10, 29),
                LocalDate.of(2026, 11, 12),
                LocalDate.of(2026, 10, 29),
                "PENDING",
                null,
                "NORMAL",
                null,
                null,
                null,
                LocalDate.of(2027, 11, 5));
    }

    /** 在用植入记录（E21-T01 行结构）。 */
    private static ImplantRow implant(String deviceType, String status, LocalDate implantDate) {
        return new ImplantRow(
                21L,
                PATIENT_ID,
                "FIRST",
                implantDate,
                deviceType,
                "MEDTRONIC",
                "示例型号 S-01",
                "DEMO-SN-3001",
                "DDD",
                "虚构示例医院",
                "示例医生",
                "SSS",
                null,
                null,
                status,
                null,
                null,
                0,
                7L,
                7L);
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例）",
                USER_ID,
                "it.query",
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
                900L,
                7200L,
                null);
    }
}
