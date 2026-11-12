package com.crdp.followup.implant.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.implant.infra.mapper.ImplantFeatureMapper;
import com.crdp.followup.implant.infra.mapper.ImplantMapper;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.ImplantInsert;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.ImplantRow;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.LeadInsert;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.LeadRow;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.LeadUpdate;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E21-T02 单元测试（tasks.csv 验收：每种变更保留历史；验收用例集 C-ACC-06 / SW-5）。
 *
 * <p>锁定口径（C 规格 §4「器械更换流程」）：更换（电池耗竭）/ 升级 / 电极修正 / 其他走统一专用流程——每次变更新增一条植入记录、旧记录自动结束为「已更换」（结束日期 =
 * 新植入日期）；沿用电极保留原植入日期（同一电极行挂到新植入记录下）；旧电极标「废弃留置 /
 * 已拔除」；「其他」类型须填说明；电极修正不改脉冲发生器（器械五字段与原记录一致）；取出将在用记录置「已取出」并要求显式处置其在用电极。功能开关 implant 关闭 40301；越范围 /
 * 不存在 40400；目标不在用 / 乐观锁冲突 40900；校验失败 40001。全部虚构数据。
 */
class ImplantReplaceServiceTest {

    private static final long PATIENT_ID = 101L;
    private static final long OPERATOR_ID = 9L;

    /** 新植入记录 / 新电极的生成 id 基线（首个 insertImplant 得 701，其后 insertLead 递增）。 */
    private static final long NEW_IMPLANT_ID = 701L;

    private final ImplantGateway gateway = mock(ImplantGateway.class);
    private final ImplantMapper mapper = mock(ImplantMapper.class);
    private final ImplantFeatureMapper features = mock(ImplantFeatureMapper.class);
    private final ImplantDictPort dicts = mock(ImplantDictPort.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final com.crdp.followup.plan.app.DevicePlanMappingService planMappings =
            mock(com.crdp.followup.plan.app.DevicePlanMappingService.class);

    private final ImplantService service =
            new ImplantService(gateway, mapper, features, dicts, audit, planMappings);

    private final AtomicLong idSeq = new AtomicLong(700);

    /** 在用双腔起搏器（2024-06-01 首次植入，RA / RV 两根在用电极）。 */
    private ImplantRow active;

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        when(features.findEnabled(anyString())).thenReturn(true);
        when(dicts.items(anyString()))
                .thenAnswer(
                        invocation ->
                                ImplantServiceTest.presetDict(
                                        invocation.getArgument(0, String.class)));
        when(gateway.patientIdInScope(PATIENT_ID)).thenReturn(PATIENT_ID);
        when(mapper.insertImplant(any()))
                .thenAnswer(
                        invocation -> {
                            ImplantInsert row = invocation.getArgument(0);
                            row.setId(idSeq.incrementAndGet());
                            return 1;
                        });
        when(mapper.insertLead(any()))
                .thenAnswer(
                        invocation -> {
                            LeadInsert row = invocation.getArgument(0);
                            row.setId(idSeq.incrementAndGet());
                            return 1;
                        });
        when(mapper.updateLead(any())).thenReturn(1);
        when(mapper.supersedeActive(anyLong(), any(), anyString(), anyLong())).thenReturn(1);

        active = implantRow(501L, "FIRST", "ACTIVE", "2024-06-01", "DDD", "DDD", "MEDTRONIC");
        when(gateway.implantInScope(501L)).thenReturn(active);
        when(gateway.leadsOfImplant(501L))
                .thenReturn(
                        List.of(
                                lead(601L, "RA", "RA_APPENDAGE", "2024-06-01", "ACTIVE"),
                                lead(602L, "RV", "RV_APEX", "2024-06-01", "ACTIVE")));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 更换 / 升级（C-PAT-006 主流程）

    @Test
    void upgradeSupersedesOldDeviceAndKeepsLeadHistory() {
        when(gateway.implantsOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                implantRow(
                                        NEW_IMPLANT_ID,
                                        "UPGRADE",
                                        "ACTIVE",
                                        "2026-03-10",
                                        "CRT_D",
                                        "CRT_D",
                                        "MEDTRONIC"),
                                active));
        when(gateway.leadsOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                lead(601L, "RA", "RA_APPENDAGE", "2024-06-01", "ACTIVE"),
                                lead(602L, "RV", "RV_APEX", "2024-06-01", "ACTIVE"),
                                lead(702L, "LV", "CS", "2026-03-10", "ACTIVE")));
        when(mapper.findLinksOfPatient(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new ImplantMapper.ImplantLinkRow(NEW_IMPLANT_ID, 601L),
                                new ImplantMapper.ImplantLinkRow(NEW_IMPLANT_ID, 602L),
                                new ImplantMapper.ImplantLinkRow(NEW_IMPLANT_ID, 702L),
                                new ImplantMapper.ImplantLinkRow(501L, 601L),
                                new ImplantMapper.ImplantLinkRow(501L, 602L)));

        ImplantService.ImplantView view =
                service.replace(
                        501L,
                        new ImplantService.ReplaceRequest(
                                3,
                                "UPGRADE",
                                null,
                                implantPayload("2026-03-10", "CRT_D"),
                                List.of(
                                        new ImplantService.LeadAction("601", "KEEP"),
                                        new ImplantService.LeadAction("602", "KEEP")),
                                List.of(newLead("LV", "CS"))));

        // 旧器械自动结束：已更换、结束日期 = 新植入日期（C 规格 §4 业务规则）
        verify(mapper)
                .supersedeActive(
                        eq(501L), eq(LocalDate.parse("2026-03-10")), anyString(), eq(OPERATOR_ID));
        ArgumentCaptor<ImplantInsert> inserted = ArgumentCaptor.forClass(ImplantInsert.class);
        verify(mapper).insertImplant(inserted.capture());
        assertEquals("UPGRADE", inserted.getValue().getImplantType());
        assertEquals("CRT_D", inserted.getValue().getDeviceType());
        assertTrue(view.current(), "更换后新器械为当前器械");
        // 电极沿用：同一电极行挂到新植入记录下（保留历史关联），新植 LV 电极日期 = 手术日期
        verify(mapper).linkLead(PATIENT_ID, NEW_IMPLANT_ID, 601L);
        verify(mapper).linkLead(PATIENT_ID, NEW_IMPLANT_ID, 602L);
        ArgumentCaptor<LeadInsert> newLead = ArgumentCaptor.forClass(LeadInsert.class);
        verify(mapper).insertLead(newLead.capture());
        assertEquals("LV", newLead.getValue().getChamber());
        assertEquals(LocalDate.parse("2026-03-10"), newLead.getValue().getImplantDate());
        verify(mapper).linkLead(PATIENT_ID, NEW_IMPLANT_ID, newLead.getValue().getId());
        verify(audit).append(any(AuditEntry.class));
    }

    @Test
    void keptLeadKeepsOriginalImplantDateAndRowUntouched() {
        when(gateway.leadsOfImplant(501L))
                .thenReturn(List.of(lead(601L, "RA", "RA_APPENDAGE", "2024-06-01", "ACTIVE")));
        when(gateway.implantsOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                implantRow(
                                        NEW_IMPLANT_ID,
                                        "REPLACE",
                                        "ACTIVE",
                                        "2026-03-10",
                                        "DDD",
                                        "DDD",
                                        "MEDTRONIC"),
                                active));
        when(gateway.leadsOf(PATIENT_ID))
                .thenReturn(List.of(lead(601L, "RA", "RA_APPENDAGE", "2024-06-01", "ACTIVE")));
        when(mapper.findLinksOfPatient(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new ImplantMapper.ImplantLinkRow(NEW_IMPLANT_ID, 601L),
                                new ImplantMapper.ImplantLinkRow(501L, 601L)));

        ImplantService.ImplantView view =
                service.replace(
                        501L,
                        new ImplantService.ReplaceRequest(
                                3,
                                "REPLACE",
                                null,
                                implantPayload("2026-03-10", "DDD"),
                                List.of(new ImplantService.LeadAction("601", "KEEP")),
                                List.of()));

        // 沿用 = 电极行不改（不 updateLead，保留原植入日期 2024-06-01），只补新关联（验收示例）
        verify(mapper, never()).updateLead(any());
        verify(mapper).linkLead(PATIENT_ID, NEW_IMPLANT_ID, 601L);
        assertEquals(
                LocalDate.parse("2024-06-01"),
                view.leads().stream()
                        .filter(lead -> lead.id().equals("601"))
                        .findFirst()
                        .orElseThrow()
                        .implantDate(),
                "沿用电极保留原植入日期");
    }

    @Test
    void abandonedAndExtractedLeadsKeepHistoryUnderOldDevice() {
        when(gateway.implantsOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                implantRow(
                                        NEW_IMPLANT_ID,
                                        "REPLACE",
                                        "ACTIVE",
                                        "2026-03-10",
                                        "DDD",
                                        "DDD",
                                        "MEDTRONIC"),
                                active));

        service.replace(
                501L,
                new ImplantService.ReplaceRequest(
                        3,
                        "REPLACE",
                        null,
                        implantPayload("2026-03-10", "DDD"),
                        List.of(
                                new ImplantService.LeadAction("601", "ABANDON"),
                                new ImplantService.LeadAction("602", "EXTRACT")),
                        List.of()));

        // 旧电极标废弃留置 / 已拔除：行更新状态、不挂到新器械（历史保留在旧记录下）
        ArgumentCaptor<LeadUpdate> updates = ArgumentCaptor.forClass(LeadUpdate.class);
        verify(mapper, times(2)).updateLead(updates.capture());
        assertEquals("ABANDONED", updates.getAllValues().get(0).getStatus());
        assertEquals("EXTRACTED", updates.getAllValues().get(1).getStatus());
        verify(mapper, never()).linkLead(anyLong(), anyLong(), eq(601L));
        verify(mapper, never()).linkLead(anyLong(), anyLong(), eq(602L));
    }

    @Test
    void everyActiveLeadMustHaveExplicitAction() {
        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.replace(
                                        501L,
                                        replaceRequest(
                                                "REPLACE",
                                                implantPayload("2026-03-10", "DDD"),
                                                List.of(
                                                        new ImplantService.LeadAction(
                                                                "601", "KEEP")))));
        assertEquals(40001, failure.code(), "在用电极必须逐根显式选择（沿用 / 废弃留置 / 已拔除）");
        verify(mapper, never()).supersedeActive(anyLong(), any(), anyString(), anyLong());
    }

    @Test
    void keepOnInactiveLeadRejected() {
        when(gateway.leadsOfImplant(501L))
                .thenReturn(
                        List.of(
                                lead(601L, "RA", "RA_APPENDAGE", "2024-06-01", "ACTIVE"),
                                lead(602L, "RV", "RV_APEX", "2024-06-01", "EXTRACTED")));

        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.replace(
                                        501L,
                                        replaceRequest(
                                                "REPLACE",
                                                implantPayload("2026-03-10", "DDD"),
                                                List.of(
                                                        new ImplantService.LeadAction(
                                                                "601", "KEEP"),
                                                        new ImplantService.LeadAction(
                                                                "602", "KEEP")))));
        assertEquals(40001, failure.code(), "非在用电极不可沿用");
    }

    @Test
    void leadOutsideTargetImplantRejected() {
        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.replace(
                                        501L,
                                        replaceRequest(
                                                "REPLACE",
                                                implantPayload("2026-03-10", "DDD"),
                                                List.of(
                                                        new ImplantService.LeadAction(
                                                                "601", "KEEP"),
                                                        new ImplantService.LeadAction(
                                                                "602", "KEEP"),
                                                        new ImplantService.LeadAction(
                                                                "999", "KEEP")))));
        assertEquals(40001, failure.code(), "电极必须属于目标植入记录");
    }

    // ---------------------------------------------------------------- 类型与其他校验

    @Test
    void otherTypeRequiresNote() {
        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.replace(
                                        501L,
                                        replaceRequest(
                                                "OTHER",
                                                implantPayload("2026-03-10", "DDD"),
                                                List.of(
                                                        new ImplantService.LeadAction(
                                                                "601", "KEEP"),
                                                        new ImplantService.LeadAction(
                                                                "602", "KEEP")))));
        assertEquals(40001, failure.code(), "「其他」类型须填说明（C-ACC-06）");
        verify(mapper, never()).insertImplant(any());

        // 补说明后可保存，note 落库
        when(gateway.implantsOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                implantRow(
                                        NEW_IMPLANT_ID,
                                        "OTHER",
                                        "ACTIVE",
                                        "2026-03-10",
                                        "DDD",
                                        "DDD",
                                        "MEDTRONIC"),
                                active));
        service.replace(
                501L,
                new ImplantService.ReplaceRequest(
                        3,
                        "OTHER",
                        "电极脱位再次手术（示例）",
                        implantPayload("2026-03-10", "DDD"),
                        List.of(
                                new ImplantService.LeadAction("601", "KEEP"),
                                new ImplantService.LeadAction("602", "KEEP")),
                        List.of()));
        ArgumentCaptor<ImplantInsert> inserted = ArgumentCaptor.forClass(ImplantInsert.class);
        verify(mapper).insertImplant(inserted.capture());
        assertEquals("电极脱位再次手术（示例）", inserted.getValue().getNote());
    }

    @Test
    void leadRevisionKeepsGeneratorUnchanged() {
        when(gateway.implantsOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                implantRow(
                                        NEW_IMPLANT_ID,
                                        "LEAD_REVISION",
                                        "ACTIVE",
                                        "2026-03-10",
                                        "DDD",
                                        "DDD",
                                        "MEDTRONIC"),
                                active));
        when(gateway.leadsOf(PATIENT_ID))
                .thenReturn(List.of(lead(601L, "RA", "RA_APPENDAGE", "2024-06-01", "ACTIVE")));
        when(mapper.findLinksOfPatient(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new ImplantMapper.ImplantLinkRow(NEW_IMPLANT_ID, 601L),
                                new ImplantMapper.ImplantLinkRow(501L, 601L)));

        // 电极修正：脉冲发生器不变（器械四字段与原记录一致：类型 / 品牌 / 型号 / 序列号）
        ImplantService.ImplantView view =
                service.replace(
                        501L,
                        new ImplantService.ReplaceRequest(
                                3,
                                "LEAD_REVISION",
                                null,
                                sameGeneratorPayload("2026-03-10"),
                                List.of(
                                        new ImplantService.LeadAction("601", "ABANDON"),
                                        new ImplantService.LeadAction("602", "KEEP")),
                                List.of(newLead("RV", "RV_SEPTUM"))));
        assertTrue(view.current(), "电极修正记录为新的当前器械");
        // 旧记录同样结束为已更换（每次变更一条记录，C-ACC-06 / SW-5「新增电极修正记录」）
        verify(mapper)
                .supersedeActive(
                        eq(501L), eq(LocalDate.parse("2026-03-10")), anyString(), eq(OPERATOR_ID));

        // 器械字段不一致 → 40001
        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.replace(
                                        501L,
                                        replaceRequest(
                                                "LEAD_REVISION",
                                                implantPayload("2026-03-10", "CRT_D"),
                                                List.of(
                                                        new ImplantService.LeadAction(
                                                                "601", "KEEP"),
                                                        new ImplantService.LeadAction(
                                                                "602", "KEEP")))));
        assertEquals(40001, failure.code(), "电极修正不改变脉冲发生器");
    }

    @Test
    void firstTypeAndBadActionRejected() {
        ApiException first =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.replace(
                                        501L,
                                        replaceRequest(
                                                "FIRST",
                                                implantPayload("2026-03-10", "DDD"),
                                                actionsKeepBoth())));
        assertEquals(40001, first.code(), "首次植入走登记 / 新建，不走更换流程");

        ApiException action =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.replace(
                                        501L,
                                        replaceRequest(
                                                "REPLACE",
                                                implantPayload("2026-03-10", "DDD"),
                                                List.of(
                                                        new ImplantService.LeadAction(
                                                                "601", "REUSE"),
                                                        new ImplantService.LeadAction(
                                                                "602", "KEEP")))));
        assertEquals(40001, action.code(), "电极处置只支持沿用 / 废弃留置 / 已拔除");
    }

    @Test
    void leadlessNewDeviceCannotCarryLeads() {
        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.replace(
                                        501L,
                                        replaceRequest(
                                                "REPLACE",
                                                implantPayload("2026-03-10", "LEADLESS"),
                                                actionsKeepBoth())));
        assertEquals(40001, failure.code(), "无导线起搏器不录 / 不沿用电极（C-PAT-005）");
        verify(mapper, never()).supersedeActive(anyLong(), any(), anyString(), anyLong());
    }

    @Test
    void dateBeforeOldImplantOrInFutureRejected() {
        ApiException past =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.replace(
                                        501L,
                                        replaceRequest(
                                                "REPLACE",
                                                implantPayload("2024-05-31", "DDD"),
                                                actionsKeepBoth())));
        assertEquals(40001, past.code(), "更换日期不能早于原植入日期");

        ApiException future =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.replace(
                                        501L,
                                        replaceRequest(
                                                "REPLACE",
                                                implantPayload(
                                                        LocalDate.now().plusDays(1).toString(),
                                                        "DDD"),
                                                actionsKeepBoth())));
        assertEquals(40001, future.code(), "更换日期不能晚于今天");
    }

    // ---------------------------------------------------------------- 状态门与权限门

    @Test
    void nonActiveTargetAndVersionConflictRejected() {
        when(gateway.implantInScope(501L))
                .thenReturn(
                        implantRow(
                                501L,
                                "FIRST",
                                "REPLACED",
                                "2024-06-01",
                                "DDD",
                                "DDD",
                                "MEDTRONIC"));
        ApiException state =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.replace(
                                        501L,
                                        replaceRequest(
                                                "REPLACE",
                                                implantPayload("2026-03-10", "DDD"),
                                                actionsKeepBoth())));
        assertEquals(40900, state.code(), "只有在用（当前）器械可以更换 / 升级 / 电极修正");

        when(gateway.implantInScope(501L)).thenReturn(active);
        ApiException version =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.replace(
                                        501L,
                                        new ImplantService.ReplaceRequest(
                                                2,
                                                "REPLACE",
                                                null,
                                                implantPayload("2026-03-10", "DDD"),
                                                actionsKeepBoth(),
                                                List.of())));
        assertEquals(40900, version.code(), "baseVersion 乐观锁");

        // 并发接替：supersedeActive 命中 0 行（他人先保存）
        when(mapper.supersedeActive(anyLong(), any(), anyString(), anyLong())).thenReturn(0);
        ApiException race =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.replace(
                                        501L,
                                        new ImplantService.ReplaceRequest(
                                                3,
                                                "REPLACE",
                                                null,
                                                implantPayload("2026-03-10", "DDD"),
                                                actionsKeepBoth(),
                                                List.of())));
        assertEquals(40900, race.code());
    }

    @Test
    void featureAndScopeGatesApplyToReplaceAndExplant() {
        when(features.findEnabled(anyString())).thenReturn(false);
        ApiException disabled =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.replace(
                                        501L,
                                        replaceRequest(
                                                "REPLACE",
                                                implantPayload("2026-03-10", "DDD"),
                                                actionsKeepBoth())));
        assertEquals(40301, disabled.code(), "功能开关 implant 关闭 40301");
        ApiException disabledExplant =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.explant(
                                        501L,
                                        new ImplantService.ExplantRequest(
                                                3,
                                                LocalDate.parse("2026-05-01"),
                                                "感染取出（示例）",
                                                List.of())));
        assertEquals(40301, disabledExplant.code());

        when(features.findEnabled(anyString())).thenReturn(true);
        when(gateway.implantInScope(501L)).thenReturn(null);
        ApiException outOfScope =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.replace(
                                        501L,
                                        replaceRequest(
                                                "REPLACE",
                                                implantPayload("2026-03-10", "DDD"),
                                                actionsKeepBoth())));
        assertEquals(40400, outOfScope.code(), "越范围 / 不存在 40400（不暴露存在性）");
    }

    // ---------------------------------------------------------------- 取出（EXPLANTED）

    @Test
    void explantMarksDeviceExplantedAndDisposesLeads() {
        ImplantRow explanted =
                implantRow(501L, "FIRST", "EXPLANTED", "2024-06-01", "DDD", "DDD", "MEDTRONIC");
        when(gateway.implantsOf(PATIENT_ID)).thenReturn(List.of(explanted));
        when(gateway.leadsOfImplant(501L))
                .thenReturn(
                        List.of(
                                lead(601L, "RA", "RA_APPENDAGE", "2024-06-01", "ACTIVE"),
                                lead(602L, "RV", "RV_APEX", "2024-06-01", "ACTIVE")));
        when(gateway.leadsOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                lead(601L, "RA", "RA_APPENDAGE", "2024-06-01", "EXTRACTED"),
                                lead(602L, "RV", "RV_APEX", "2024-06-01", "ABANDONED")));
        when(mapper.findLinksOfPatient(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new ImplantMapper.ImplantLinkRow(501L, 601L),
                                new ImplantMapper.ImplantLinkRow(501L, 602L)));
        when(mapper.explantActive(anyLong(), any(), anyString(), anyLong())).thenReturn(1);

        ImplantService.ImplantView view =
                service.explant(
                        501L,
                        new ImplantService.ExplantRequest(
                                3,
                                LocalDate.parse("2026-05-01"),
                                "感染取出（示例）",
                                List.of(
                                        new ImplantService.LeadAction("601", "EXTRACT"),
                                        new ImplantService.LeadAction("602", "ABANDON"))));

        assertFalse(view.current(), "取出后不再是当前器械");
        verify(mapper)
                .explantActive(
                        eq(501L), eq(LocalDate.parse("2026-05-01")), anyString(), eq(OPERATOR_ID));
        // 在用电极显式处置（已拔除 / 废弃留置），行保留在旧记录下
        ArgumentCaptor<LeadUpdate> leadUpdates = ArgumentCaptor.forClass(LeadUpdate.class);
        verify(mapper, times(2)).updateLead(leadUpdates.capture());
        assertEquals("EXTRACTED", leadUpdates.getAllValues().get(0).getStatus());
        assertEquals("ABANDONED", leadUpdates.getAllValues().get(1).getStatus());
        verify(audit).append(any(AuditEntry.class));
    }

    @Test
    void explantRequiresLeadDispositionForActiveLeads() {
        ApiException missing =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.explant(
                                        501L,
                                        new ImplantService.ExplantRequest(
                                                3,
                                                LocalDate.parse("2026-05-01"),
                                                "感染取出（示例）",
                                                List.of())));
        assertEquals(40001, missing.code(), "取出时在用电极须逐根标废弃留置 / 已拔除");

        ApiException keep =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.explant(
                                        501L,
                                        new ImplantService.ExplantRequest(
                                                3,
                                                LocalDate.parse("2026-05-01"),
                                                "感染取出（示例）",
                                                actionsKeepBoth())));
        assertEquals(40001, keep.code(), "取出不产生沿用电极（KEEP 只属于更换流程）");
        verify(mapper, never()).explantActive(anyLong(), any(), anyString(), anyLong());
    }

    @Test
    void explantValidatesDateStateAndConflict() {
        ApiException beforeImplant =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.explant(
                                        501L,
                                        new ImplantService.ExplantRequest(
                                                3,
                                                LocalDate.parse("2024-05-31"),
                                                null,
                                                List.of())));
        assertEquals(40001, beforeImplant.code(), "取出日期不能早于植入日期");

        ApiException future =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.explant(
                                        501L,
                                        new ImplantService.ExplantRequest(
                                                3, LocalDate.now().plusDays(1), null, List.of())));
        assertEquals(40001, future.code());

        when(gateway.implantInScope(501L))
                .thenReturn(
                        implantRow(
                                501L,
                                "FIRST",
                                "REPLACED",
                                "2024-06-01",
                                "DDD",
                                "DDD",
                                "MEDTRONIC"));
        ApiException state =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.explant(
                                        501L,
                                        new ImplantService.ExplantRequest(
                                                3,
                                                LocalDate.parse("2026-05-01"),
                                                null,
                                                List.of())));
        assertEquals(40900, state.code(), "只有在用器械可以取出");
    }

    // ---------------------------------------------------------------- 计划映射自动入组钩子（E21-T05 /
    // C-FU-003）

    @Test
    void upgradeTriggersAutoEnrollmentWithNewDeviceType() {
        when(gateway.implantsOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                implantRow(
                                        NEW_IMPLANT_ID,
                                        "UPGRADE",
                                        "ACTIVE",
                                        "2026-03-10",
                                        "CRT_D",
                                        "CRT_D",
                                        "MEDTRONIC"),
                                active));
        when(gateway.leadsOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                lead(601L, "RA", "RA_APPENDAGE", "2024-06-01", "ACTIVE"),
                                lead(602L, "RV", "RV_APEX", "2024-06-01", "ACTIVE")));
        when(mapper.findLinksOfPatient(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new ImplantMapper.ImplantLinkRow(NEW_IMPLANT_ID, 601L),
                                new ImplantMapper.ImplantLinkRow(NEW_IMPLANT_ID, 602L),
                                new ImplantMapper.ImplantLinkRow(501L, 601L),
                                new ImplantMapper.ImplantLinkRow(501L, 602L)));

        service.replace(
                501L,
                new ImplantService.ReplaceRequest(
                        3,
                        "UPGRADE",
                        null,
                        implantPayload("2026-03-10", "CRT_D"),
                        List.of(
                                new ImplantService.LeadAction("601", "KEEP"),
                                new ImplantService.LeadAction("602", "KEEP")),
                        List.of()));

        // 升级产生新在用器械：按新器械类型（CRT_D → ICD/CRT-D 计划）自动入组，起算日 = 新植入日期
        verify(planMappings)
                .autoEnrollByDeviceType(PATIENT_ID, "CRT_D", LocalDate.parse("2026-03-10"));
    }

    @Test
    void leadRevisionDoesNotTriggerAutoEnrollment() {
        when(gateway.implantsOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                implantRow(
                                        NEW_IMPLANT_ID,
                                        "LEAD_REVISION",
                                        "ACTIVE",
                                        "2026-03-10",
                                        "DDD",
                                        "DDD",
                                        "MEDTRONIC"),
                                active));
        when(gateway.leadsOf(PATIENT_ID))
                .thenReturn(List.of(lead(601L, "RA", "RA_APPENDAGE", "2024-06-01", "ACTIVE")));
        when(mapper.findLinksOfPatient(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new ImplantMapper.ImplantLinkRow(NEW_IMPLANT_ID, 601L),
                                new ImplantMapper.ImplantLinkRow(501L, 601L)));

        service.replace(
                501L,
                new ImplantService.ReplaceRequest(
                        3,
                        "LEAD_REVISION",
                        null,
                        sameGeneratorPayload("2026-03-10"),
                        List.of(
                                new ImplantService.LeadAction("601", "ABANDON"),
                                new ImplantService.LeadAction("602", "KEEP")),
                        List.of(newLead("RV", "RV_SEPTUM"))));

        // 电极修正不切换计划（C-FU-007 切换表）
        verify(planMappings, never()).autoEnrollByDeviceType(anyLong(), anyString(), any());
    }

    // ---------------------------------------------------------------- 夹具

    /** 更换请求快捷组装（无说明、无新电极）。 */
    private static ImplantService.ReplaceRequest replaceRequest(
            String type,
            ImplantService.ImplantPayload implant,
            List<ImplantService.LeadAction> actions) {
        return new ImplantService.ReplaceRequest(3, type, null, implant, actions, List.of());
    }

    private static List<ImplantService.LeadAction> actionsKeepBoth() {
        return List.of(
                new ImplantService.LeadAction("601", "KEEP"),
                new ImplantService.LeadAction("602", "KEEP"));
    }

    /** 新植入记录载荷（implantType 与 leads 由更换流程单独承载，均为 null）。 */
    private static ImplantService.ImplantPayload implantPayload(String date, String deviceType) {
        return new ImplantService.ImplantPayload(
                null,
                LocalDate.parse(date),
                deviceType,
                "MEDTRONIC",
                "示例型号 S-02",
                "DEMO-SN-0002",
                "DDD",
                "虚构示例医院",
                "示例医生",
                "SSS",
                null,
                null,
                null);
    }

    private static ImplantService.LeadPayload newLead(String chamber, String position) {
        return new ImplantService.LeadPayload(null, chamber, position, null, null, null);
    }

    /** 与目标植入记录同脉冲发生器的载荷（电极修正用：类型 / 品牌 / 型号 / 序列号一致）。 */
    private static ImplantService.ImplantPayload sameGeneratorPayload(String date) {
        return new ImplantService.ImplantPayload(
                null,
                LocalDate.parse(date),
                "DDD",
                "MEDTRONIC",
                "示例型号 S-01",
                "DEMO-SN-501",
                "DDD",
                "虚构示例医院",
                "示例医生",
                "SSS",
                null,
                null,
                null);
    }

    private static ImplantRow implantRow(
            long id,
            String type,
            String status,
            String date,
            String deviceType,
            String pacingMode,
            String brand) {
        return new ImplantRow(
                id,
                PATIENT_ID,
                type,
                LocalDate.parse(date),
                deviceType,
                brand,
                "示例型号 S-01",
                "DEMO-SN-" + id,
                pacingMode,
                "虚构示例医院",
                "示例医生",
                "SSS",
                null,
                null,
                status,
                null,
                null,
                3,
                OPERATOR_ID,
                null);
    }

    private static LeadRow lead(
            long id, String chamber, String position, String date, String status) {
        return new LeadRow(
                id, PATIENT_ID, chamber, position, "示例电极 L-01", LocalDate.parse(date), status, 0);
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "democ",
                "虚构示例医院",
                OPERATOR_ID,
                "doctor.demo",
                "演示医生",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of("followup:implant:edit", "followup:patient:view"),
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
