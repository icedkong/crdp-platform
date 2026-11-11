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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.implant.infra.mapper.ImplantFeatureMapper;
import com.crdp.followup.implant.infra.mapper.ImplantMapper;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.ImplantInsert;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.ImplantRow;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.ImplantUpdate;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.LeadInsert;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.LeadRow;
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
 * E21-T01 单元测试（tasks.csv 验收：一个患者多次植入、多根电极）。
 *
 * <p>锁定口径：一位患者可有多条植入记录（新器械接替在用时旧记录自动结束为已更换、结束日期 = 新植入日期；补录更早历史器械落已更换）；每条植入记录可挂多根 RA / RV / LV
 * 电极（部位按腔室过滤）；无导线起搏器不录电极（C-PAT-005）；一位患者同一时间仅一条在用； 植入日期允许过去（历史建档 C-PAT-008）但不能晚于今天；功能开关 implant
 * 关闭（含无行）→ 40301；越范围患者 / 植入记录 40400；乐观锁冲突 40900；写 IMPLANT_CREATE / IMPLANT_UPDATE 审计。全部虚构数据。
 */
class ImplantServiceTest {

    private static final long PATIENT_ID = 101L;
    private static final long OPERATOR_ID = 9L;
    private static final LocalDate TODAY = LocalDate.now();

    private final ImplantGateway gateway = mock(ImplantGateway.class);
    private final ImplantMapper mapper = mock(ImplantMapper.class);
    private final ImplantFeatureMapper features = mock(ImplantFeatureMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final ImplantService service = new ImplantService(gateway, mapper, features, audit);

    private final AtomicLong idSeq = new AtomicLong(500);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        when(features.findEnabled(anyString())).thenReturn(true);
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
        when(mapper.updateImplant(any())).thenReturn(1);
        when(mapper.updateLead(any())).thenReturn(1);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 新建（C-PAT-004）

    @Test
    void firstImplantIsActiveWithLeadsDefaultedToImplantDate() {
        when(gateway.activeImplantOf(PATIENT_ID)).thenReturn(null);
        when(gateway.implantsOf(PATIENT_ID))
                .thenReturn(List.of(implant(501L, "ACTIVE", "2018-05-10")));
        when(gateway.leadsOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                lead(601L, "RA", "RA_APPENDAGE", "2018-05-10"),
                                lead(602L, "RV", "RV_APEX", "2018-05-10")));
        when(mapper.findLinksOfPatient(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new ImplantMapper.ImplantLinkRow(501L, 601L),
                                new ImplantMapper.ImplantLinkRow(501L, 602L)));

        ImplantService.ImplantView view =
                service.create(
                        PATIENT_ID,
                        payload(
                                "FIRST",
                                "2018-05-10",
                                "DDD",
                                List.of(
                                        leadPayload("RA", "RA_APPENDAGE"),
                                        leadPayload("RV", "RV_APEX"))));

        assertTrue(view.current(), "首条植入在用");
        assertEquals("双腔起搏器(DDD)", view.deviceTypeLabel(), "显示名一并下发");
        assertEquals(2, view.leads().size(), "多根电极（验收「多根电极」）");

        ArgumentCaptor<LeadInsert> leadCaptor = ArgumentCaptor.forClass(LeadInsert.class);
        verify(mapper, org.mockito.Mockito.times(2)).insertLead(leadCaptor.capture());
        assertEquals(
                LocalDate.parse("2018-05-10"),
                leadCaptor.getAllValues().get(0).getImplantDate(),
                "电极植入日期缺省同植入记录（附录 A 电极 #5）");
        verify(audit).append(any(AuditEntry.class));
    }

    @Test
    void newerImplantSupersedesActiveOne() {
        ImplantRow current = implant(501L, "ACTIVE", "2018-05-10");
        when(gateway.activeImplantOf(PATIENT_ID)).thenReturn(current);
        when(gateway.implantsOf(PATIENT_ID)).thenReturn(List.of(current));
        when(gateway.leadsOf(PATIENT_ID)).thenReturn(List.of());
        when(mapper.findLinksOfPatient(PATIENT_ID)).thenReturn(List.of());

        service.create(PATIENT_ID, payload("REPLACE", "2026-03-02", "DDD", List.of()));

        // 旧记录自动结束：结束日期 = 新植入日期（C 规格 §4 业务规则）
        verify(mapper)
                .supersedeActive(
                        eq(501L), eq(LocalDate.parse("2026-03-02")), anyString(), eq(OPERATOR_ID));
    }

    @Test
    void backdatedImplantIsRecordedAsReplaced() {
        ImplantRow current = implant(501L, "ACTIVE", "2026-03-02");
        when(gateway.activeImplantOf(PATIENT_ID)).thenReturn(current);
        when(gateway.implantsOf(PATIENT_ID)).thenReturn(List.of(current));
        when(gateway.leadsOf(PATIENT_ID)).thenReturn(List.of());
        when(mapper.findLinksOfPatient(PATIENT_ID)).thenReturn(List.of());

        ArgumentCaptor<ImplantInsert> captor = ArgumentCaptor.forClass(ImplantInsert.class);
        service.create(PATIENT_ID, payload("FIRST", "2018-05-10", "SSI", List.of()));
        verify(mapper).insertImplant(captor.capture());

        assertEquals("REPLACED", captor.getValue().getStatus(), "补录早于在用器械的历史记录落已更换");
        assertEquals(LocalDate.parse("2026-03-02"), captor.getValue().getEndDate());
        verify(mapper, never()).supersedeActive(anyLong(), any(), anyString(), anyLong());
    }

    @Test
    void futureImplantDateRejected() {
        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT_ID,
                                        payload(
                                                "FIRST",
                                                TODAY.plusDays(1).toString(),
                                                "DDD",
                                                List.of())));
        assertEquals(40001, failure.code());
    }

    @Test
    void indicationOtherRequiresNote() {
        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT_ID,
                                        new ImplantService.ImplantPayload(
                                                "FIRST",
                                                LocalDate.parse("2026-01-05"),
                                                "DDD",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "OTHER",
                                                null,
                                                null)));
        assertEquals(40001, failure.code(), "指征选「其他」须填补充说明（Q16）");
    }

    // ---------------------------------------------------------------- 电极（C-PAT-005）

    @Test
    void leadlessDeviceRejectsLeads() {
        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT_ID,
                                        payload(
                                                "FIRST",
                                                "2026-01-05",
                                                "LEADLESS",
                                                List.of(leadPayload("RV", "RV_APEX")))));
        assertEquals(40001, failure.code(), "无导线起搏器不录电极（C-PAT-005）");
        verify(mapper, never()).insertLead(any());
    }

    @Test
    void leadPositionFilteredByChamber() {
        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT_ID,
                                        payload(
                                                "FIRST",
                                                "2026-01-05",
                                                "CRT_D",
                                                List.of(leadPayload("RA", "RV_APEX")))));
        assertEquals(40001, failure.code(), "部位按腔室过滤（Q15）：RA 不可选右室心尖");

        when(gateway.activeImplantOf(PATIENT_ID)).thenReturn(null);
        when(gateway.implantsOf(PATIENT_ID))
                .thenReturn(List.of(implant(501L, "ACTIVE", "2026-01-05")));
        when(gateway.leadsOf(PATIENT_ID)).thenReturn(List.of());
        when(mapper.findLinksOfPatient(PATIENT_ID)).thenReturn(List.of());
        service.create(
                PATIENT_ID,
                payload(
                        "FIRST",
                        "2026-01-05",
                        "CRT_D",
                        List.of(
                                leadPayload("LV", "CS"),
                                leadPayload("RA", "RA_APPENDAGE"),
                                leadPayload("RV", "HIS"))));
        verify(mapper, org.mockito.Mockito.times(3)).insertLead(any());
    }

    @Test
    void invalidLeadStatusRejected() {
        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT_ID,
                                        payload(
                                                "FIRST",
                                                "2026-01-05",
                                                "DDD",
                                                List.of(
                                                        new ImplantService.LeadPayload(
                                                                null, "RV", "RV_APEX", null, null,
                                                                "BROKEN")))));
        assertEquals(40001, failure.code(), "电极状态只支持在用 / 废弃留置 / 已拔除");
    }

    // ---------------------------------------------------------------- 编辑（乐观锁与电极替换）

    @Test
    void updateRejectsStaleBaseVersion() {
        when(gateway.implantInScope(501L)).thenReturn(implant(501L, "ACTIVE", "2026-01-05"));

        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        501L,
                                        new ImplantService.UpdateRequest(
                                                0, payload("FIRST", "2026-01-05", "DDD", null))));
        assertEquals(40900, failure.code(), "baseVersion 不一致按冲突提示刷新");
        verify(mapper, never()).updateImplant(any());
    }

    @Test
    void updateReplacesLeadSetAndDeletesUnlinkedLeadRows() {
        ImplantRow existing = implant(501L, "ACTIVE", "2026-01-05");
        when(gateway.implantInScope(501L)).thenReturn(existing);
        LeadRow kept = lead(601L, "RA", "RA_APPENDAGE", "2026-01-05");
        LeadRow removed = lead(602L, "RV", "RV_APEX", "2026-01-05");
        when(gateway.leadsOfImplant(501L)).thenReturn(List.of(kept, removed));
        when(gateway.implantsOf(PATIENT_ID)).thenReturn(List.of(existing));
        when(gateway.leadsOf(PATIENT_ID)).thenReturn(List.of(kept));
        when(mapper.findLinksOfPatient(PATIENT_ID))
                .thenReturn(List.of(new ImplantMapper.ImplantLinkRow(501L, 601L)));

        service.update(
                501L,
                new ImplantService.UpdateRequest(
                        3,
                        payload(
                                "FIRST",
                                "2026-01-05",
                                "DDD",
                                List.of(
                                        new ImplantService.LeadPayload(
                                                "601", "RA", "RA_APPENDAGE", null, null, null),
                                        leadPayload("RV", "RV_SEPTUM")))));

        ArgumentCaptor<ImplantUpdate> captor = ArgumentCaptor.forClass(ImplantUpdate.class);
        verify(mapper).updateImplant(captor.capture());
        assertEquals(3, captor.getValue().getBaseVersion(), "乐观锁基线随请求携带");
        verify(mapper).unlinkLead(501L, 602L);
        verify(mapper).deleteUnlinkedLead(602L, PATIENT_ID);
        verify(audit).append(any(AuditEntry.class));
    }

    @Test
    void updateToLeadlessRejectedWhileLeadsExist() {
        ImplantRow existing = implant(501L, "ACTIVE", "2026-01-05");
        when(gateway.implantInScope(501L)).thenReturn(existing);
        when(gateway.leadsOfImplant(501L))
                .thenReturn(List.of(lead(601L, "RV", "RV_APEX", "2026-01-05")));

        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        501L,
                                        new ImplantService.UpdateRequest(
                                                3,
                                                payload("FIRST", "2026-01-05", "LEADLESS", null))));
        assertEquals(40001, failure.code(), "改为无导线时不可遗留电极");
        verify(mapper, never()).updateImplant(any());
    }

    // ---------------------------------------------------------------- 开关、范围与列表

    @Test
    void featureDisabledReturns40301() {
        when(features.findEnabled("implant")).thenReturn(false);
        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT_ID,
                                        payload("FIRST", "2026-01-05", "DDD", List.of())));
        assertEquals(40301, failure.code());

        when(features.findEnabled("implant")).thenReturn(null);
        ApiException missing =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT_ID,
                                        payload("FIRST", "2026-01-05", "DDD", List.of())));
        assertEquals(40301, missing.code(), "无行视为关闭（与 useFeature 默认关闭同向）");
    }

    @Test
    void outOfScopePatientReturns40400() {
        when(gateway.patientIdInScope(PATIENT_ID)).thenReturn(null);
        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT_ID,
                                        payload("FIRST", "2026-01-05", "DDD", List.of())));
        assertEquals(40400, failure.code());
    }

    @Test
    void listPinsCurrentImplantFirstAndNumbersByDate() {
        ImplantRow first = implant(501L, "REPLACED", "2018-05-10");
        ImplantRow second = implant(502L, "ACTIVE", "2026-03-02");
        // gateway 按日期倒序返回（新在前），服务层把在用置顶
        when(gateway.implantsOf(PATIENT_ID)).thenReturn(List.of(second, first));
        when(gateway.leadsOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                lead(601L, "RA", "RA_APPENDAGE", "2018-05-10"),
                                lead(602L, "RV", "RV_APEX", "2026-03-02")));
        when(mapper.findLinksOfPatient(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new ImplantMapper.ImplantLinkRow(501L, 601L),
                                new ImplantMapper.ImplantLinkRow(502L, 602L)));

        ImplantService.ImplantListView view = service.list(PATIENT_ID);

        assertEquals(2, view.items().size());
        assertEquals("502", view.items().get(0).id(), "当前器械置顶（pages.md §4.4）");
        assertTrue(view.items().get(0).current());
        assertFalse(view.items().get(1).current());
        assertEquals(1, view.items().get(1).implantNo(), "植入序号按日期升序（附录 A 植入 #1）");
        assertEquals(2, view.items().get(0).implantNo());
        assertEquals("右心耳", view.items().get(1).leads().get(0).positionLabel());
    }

    @Test
    void optionsCarryCodesAndLabels() {
        ImplantService.OptionsView options = service.options();
        assertEquals(5, options.implantTypes().size());
        assertEquals(3, options.leadPositions().size(), "电极部位按 RA / RV / LV 分组（Q15）");
        assertTrue(options.deviceTypes().stream().anyMatch(item -> "LEADLESS".equals(item.code())));
    }

    // ---------------------------------------------------------------- 夹具

    private static ImplantService.ImplantPayload payload(
            String type, String date, String deviceType, List<ImplantService.LeadPayload> leads) {
        return new ImplantService.ImplantPayload(
                type,
                LocalDate.parse(date),
                deviceType,
                "MEDTRONIC",
                "示例型号 S-01",
                "DEMO-SN-0001",
                "DDD",
                "虚构示例医院",
                "示例医生",
                "SSS",
                null,
                leads);
    }

    private static ImplantService.LeadPayload leadPayload(String chamber, String position) {
        return new ImplantService.LeadPayload(null, chamber, position, null, null, null);
    }

    private static ImplantRow implant(long id, String status, String date) {
        return new ImplantRow(
                id,
                PATIENT_ID,
                "FIRST",
                LocalDate.parse(date),
                "DDD",
                "MEDTRONIC",
                "示例型号 S-01",
                "DEMO-SN-" + id,
                "DDD",
                "虚构示例医院",
                "示例医生",
                "SSS",
                null,
                status,
                null,
                null,
                3,
                OPERATOR_ID,
                null);
    }

    private static LeadRow lead(long id, String chamber, String position, String date) {
        return new LeadRow(
                id, PATIENT_ID, chamber, position, "示例电极 L-01", LocalDate.parse(date), "ACTIVE", 0);
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
