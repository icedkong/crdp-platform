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
 *
 * <p>E21-T04（C-FLD-002、C-FLD-003）：起搏器类型 / 品牌 / 起搏模式（统一字典，Q08 定稿 11 项）/ 植入指征 /
 * 电极部位（按腔室）从业务字典读取——options 只下发生效项（本院停用的平台项不进下拉）、顺序按字典 sortNo；保存校验接受全部已知项（停用项的历史值可原样保存）；字典外取值
 * 40001；显示名随字典（含停用项，C-FLD-009）。
 */
class ImplantServiceTest {

    private static final long PATIENT_ID = 101L;
    private static final long OPERATOR_ID = 9L;
    private static final LocalDate TODAY = LocalDate.now();

    private final ImplantGateway gateway = mock(ImplantGateway.class);
    private final ImplantMapper mapper = mock(ImplantMapper.class);
    private final ImplantFeatureMapper features = mock(ImplantFeatureMapper.class);
    private final ImplantDictPort dicts = mock(ImplantDictPort.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final com.crdp.followup.plan.app.DevicePlanMappingService planMappings =
            mock(com.crdp.followup.plan.app.DevicePlanMappingService.class);

    private final com.crdp.followup.plan.app.PlanSwitchService planSwitch =
            mock(com.crdp.followup.plan.app.PlanSwitchService.class);

    private final com.crdp.followup.task.app.StartDateRecalculationService startDateRecalc =
            mock(com.crdp.followup.task.app.StartDateRecalculationService.class);

    private final ImplantService service =
            new ImplantService(
                    gateway,
                    mapper,
                    features,
                    dicts,
                    audit,
                    planMappings,
                    planSwitch,
                    startDateRecalc);

    private final AtomicLong idSeq = new AtomicLong(500);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        when(features.findEnabled(anyString())).thenReturn(true);
        // 平台库 V70 预置口径（平台项全部生效）；租户覆盖场景由各用例单独覆写
        when(dicts.items(anyString()))
                .thenAnswer(invocation -> presetDict(invocation.getArgument(0, String.class)));
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
        // E21-T06 切换钩子默认「未切换」（回落 E21-T05 自动入组路径），切换场景由 PlanSwitchServiceTest 锁定
        org.mockito.Mockito.lenient()
                .when(planSwitch.onActiveImplantAdded(anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(
                        new com.crdp.followup.plan.app.PlanSwitchService.ImplantSwitchView(
                                PATIENT_ID, false, 0, 0, false, null));
        org.mockito.Mockito.lenient()
                .when(planSwitch.onDeviceTypeCorrected(anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(
                        new com.crdp.followup.plan.app.PlanSwitchService.ImplantSwitchView(
                                PATIENT_ID, false, 0, 0, false, null));
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

    // ---------------------------------------------------------------- 计划映射自动入组钩子（E21-T05 /
    // C-FU-003）

    @Test
    void activeImplantCreateTriggersAutoEnrollmentByDeviceType() {
        when(gateway.activeImplantOf(PATIENT_ID)).thenReturn(null);
        when(gateway.implantsOf(PATIENT_ID))
                .thenReturn(List.of(implant(501L, "ACTIVE", "2018-05-10")));
        when(gateway.leadsOf(PATIENT_ID)).thenReturn(List.of());
        when(mapper.findLinksOfPatient(PATIENT_ID)).thenReturn(List.of());

        service.create(PATIENT_ID, payload("FIRST", "2018-05-10", "DDD", List.of()));

        // 保存后（在用）植入记录 → 按器械类型映射自动入组，起算日 = 植入日期（C-FU-003）
        verify(planMappings)
                .autoEnrollByDeviceType(PATIENT_ID, "DDD", LocalDate.parse("2018-05-10"));
    }

    @Test
    void backdatedHistoricalImplantDoesNotTriggerAutoEnrollment() {
        ImplantRow current = implant(501L, "ACTIVE", "2026-03-02");
        when(gateway.activeImplantOf(PATIENT_ID)).thenReturn(current);
        when(gateway.implantsOf(PATIENT_ID)).thenReturn(List.of(current));
        when(gateway.leadsOf(PATIENT_ID)).thenReturn(List.of());
        when(mapper.findLinksOfPatient(PATIENT_ID)).thenReturn(List.of());

        service.create(PATIENT_ID, payload("FIRST", "2018-05-10", "SSI", List.of()));

        // 补录早于在用的历史器械（落已更换）不是当前器械，不触发自动入组
        verify(planMappings, never()).autoEnrollByDeviceType(anyLong(), anyString(), any());
    }

    @Test
    void registrationFirstImplantTriggersAutoEnrollment() {
        when(gateway.activeImplantOf(PATIENT_ID)).thenReturn(null);

        // C-PAT-001 登记同建首条植入记录：入组随 E21-T05 在同一事务接入（C-FU-003）
        service.createForRegistration(
                PATIENT_ID, payload("FIRST", "2026-01-05", "ICD", List.of()), OPERATOR_ID);

        verify(planMappings)
                .autoEnrollByDeviceType(PATIENT_ID, "ICD", LocalDate.parse("2026-01-05"));
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

    // ---------------------------------------------------------------- 业务字典（E21-T04 / C-FLD-003）

    @Test
    void optionsReadUnifiedPacingModeDictionary() {
        ImplantService.OptionsView options = service.options();
        // 统一起搏模式字典（Q08 定稿 11 项，顺序按字典 sortNo）：E21-T01 暂行并集里的 VDD / VDDR 不在清单内
        assertEquals(
                List.of(
                        "SSI",
                        "VVI",
                        "VVIR",
                        "AAI",
                        "AAIR",
                        "DDD",
                        "DDDR",
                        "AAI_DDD",
                        "AAIR_DDR",
                        "CRT_P",
                        "CRT_D"),
                options.pacingModes().stream().map(ImplantService.OptionItem::code).toList(),
                "起搏模式取值统一为字典生效集（C-FLD-003 验收「起搏模式统一取值」）");
        assertEquals("AAI-DDD", options.pacingModes().get(7).label(), "显示名随字典（AAI_DDD 显示 AAI-DDD）");
        assertFalse(
                options.pacingModes().stream().anyMatch(item -> "VDD".equals(item.code())),
                "VDD 不在 Q08 定稿清单");
        // 电极部位按腔室读各自字典（Q15），顺序按字典 sortNo
        assertTrue(
                options.leadPositions().stream()
                        .anyMatch(
                                group ->
                                        "RV".equals(group.chamber())
                                                && group.positions().stream()
                                                        .anyMatch(
                                                                item ->
                                                                        "HIS".equals(item.code())
                                                                                && "希氏束"
                                                                                        .equals(
                                                                                                item
                                                                                                        .label()))),
                "电极部位按腔室过滤并随字典下发");
    }

    @Test
    void tenantDisabledPlatformItemHiddenFromOptionsButStillSavesAndDisplays() {
        // 本院停用平台项 CRT_D（Q08：SSI、CRT-P、CRT-D 租户可停用）
        when(dicts.items(ImplantDictPort.DICT_PACING_MODE))
                .thenReturn(
                        presetDict(ImplantDictPort.DICT_PACING_MODE).stream()
                                .map(
                                        entry ->
                                                "CRT_D".equals(entry.itemCode())
                                                        ? disabled(
                                                                entry.itemCode(),
                                                                entry.label(),
                                                                entry.sortNo())
                                                        : entry)
                                .toList());

        ImplantService.OptionsView options = service.options();
        assertFalse(
                options.pacingModes().stream().anyMatch(item -> "CRT_D".equals(item.code())),
                "停用项不进下拉（C-FLD-009）");

        // 历史记录携带停用值仍可保存（与 FormDataValidator 对停用选项同向），显示名照常解析
        when(gateway.activeImplantOf(PATIENT_ID)).thenReturn(null);
        when(gateway.implantsOf(PATIENT_ID))
                .thenReturn(
                        List.of(implantRow(501L, "ACTIVE", "2026-01-05", "CRT_D", "CRT_D", null)));
        when(gateway.leadsOf(PATIENT_ID)).thenReturn(List.of());
        when(mapper.findLinksOfPatient(PATIENT_ID)).thenReturn(List.of());

        ImplantService.ImplantView view =
                service.create(
                        PATIENT_ID,
                        new ImplantService.ImplantPayload(
                                "FIRST",
                                LocalDate.parse("2026-01-05"),
                                "CRT_D",
                                null,
                                null,
                                null,
                                "CRT_D",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
        assertEquals("CRT_D", view.pacingMode());
        assertEquals("CRT-D", view.pacingModeLabel(), "停用项的历史值照常显示（C-FLD-009）");
        assertEquals("CRT-D", view.deviceTypeLabel(), "编码 + 显示名一并下发");
    }

    @Test
    void tenantOwnItemAppearsInOptionsAndSaves() {
        // 本院自有项：品牌新增「示例自有品牌」（C-FLD-002 本院可新增取值）
        when(dicts.items(ImplantDictPort.DICT_BRAND))
                .thenReturn(
                        java.util.stream.Stream.concat(
                                        presetDict(ImplantDictPort.DICT_BRAND).stream(),
                                        java.util.stream.Stream.of(
                                                item("OWN_BRAND_DEMO", "示例自有品牌", 95)))
                                .toList());

        ImplantService.OptionsView options = service.options();
        assertTrue(
                options.brands().stream().anyMatch(i -> "OWN_BRAND_DEMO".equals(i.code())),
                "本院自有项进下拉");

        when(gateway.activeImplantOf(PATIENT_ID)).thenReturn(null);
        when(gateway.implantsOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                implantRow(
                                        501L,
                                        "ACTIVE",
                                        "2026-01-05",
                                        "DDD",
                                        null,
                                        "OWN_BRAND_DEMO")));
        when(gateway.leadsOf(PATIENT_ID)).thenReturn(List.of());
        when(mapper.findLinksOfPatient(PATIENT_ID)).thenReturn(List.of());
        ImplantService.ImplantView view =
                service.create(
                        PATIENT_ID,
                        new ImplantService.ImplantPayload(
                                "FIRST",
                                LocalDate.parse("2026-01-05"),
                                "DDD",
                                "OWN_BRAND_DEMO",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
        assertEquals("示例自有品牌", view.brandLabel(), "本院自有项可保存并解析显示名");
    }

    @Test
    void valueOutsideDictionaryRejected() {
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
                                                "VDD",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null)));
        assertEquals(40001, failure.code(), "字典外取值拒绝（起搏模式统一取值）");
        verify(mapper, never()).insertImplant(any());
    }

    @Test
    void missingDictionaryYieldsEmptyOptionsAndRejectsValues() {
        // 字典未预置 / 平台停用：options 为空清单，保存按未知取值拒绝（显式暴露配置缺失而非静默放行）
        when(dicts.items(anyString())).thenReturn(List.of());

        ImplantService.OptionsView options = service.options();
        assertTrue(options.pacingModes().isEmpty());
        assertTrue(options.deviceTypes().isEmpty());

        ApiException failure =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT_ID,
                                        payload("FIRST", "2026-01-05", "DDD", List.of())));
        assertEquals(40001, failure.code());
    }

    // ---------------------------------------------------------------- 夹具

    /** 平台库 V70 预置口径（字典项全量、按 sortNo 升序）；显示名与迁移一致。E21-T02 更换流程用例共用。 */
    static List<ImplantDictPort.DictItem> presetDict(String dictCode) {
        return switch (dictCode) {
            case ImplantDictPort.DICT_PACING_MODE ->
                    List.of(
                            item("SSI", "SSI", 10),
                            item("VVI", "VVI", 20),
                            item("VVIR", "VVIR", 30),
                            item("AAI", "AAI", 40),
                            item("AAIR", "AAIR", 50),
                            item("DDD", "DDD", 60),
                            item("DDDR", "DDDR", 70),
                            item("AAI_DDD", "AAI-DDD", 80),
                            item("AAIR_DDR", "AAIR-DDDR", 90),
                            item("CRT_P", "CRT-P", 100),
                            item("CRT_D", "CRT-D", 110));
            case ImplantDictPort.DICT_DEVICE_TYPE ->
                    List.of(
                            item("SSI", "单腔起搏器(SSI)", 10),
                            item("DDD", "双腔起搏器(DDD)", 20),
                            item("CRT_P", "三腔起搏器(CRT-P)", 30),
                            item("ICD", "ICD", 40),
                            item("CRT_D", "CRT-D", 50),
                            item("HBP_LBBP", "希浦系统起搏(HBP/LBBP)", 60),
                            item("LEADLESS", "无导线起搏器", 70));
            case ImplantDictPort.DICT_BRAND ->
                    List.of(
                            item("MEDTRONIC", "美敦力", 10),
                            item("ABBOTT", "雅培", 20),
                            item("BIOTRONIK", "百多力", 30),
                            item("BOSTON_SCIENTIFIC", "波士顿科学", 40),
                            item("CHUANGLING", "创领心律", 50),
                            item("WEICHUANG", "微创医疗", 60),
                            item("LEPU", "乐普", 70),
                            item("JINSHI", "金仕生物", 80),
                            item("OTHER", "其他", 90));
            case ImplantDictPort.DICT_INDICATION ->
                    List.of(
                            item("SSS", "病态窦房结综合征", 10),
                            item("AVB", "房室传导阻滞", 20),
                            item("AF_PAUSE", "房颤伴长RR间期", 30),
                            item("CRT_IND", "心脏再同步治疗指征", 40),
                            item("OTHER", "其他", 50));
            case "lead_position_ra" ->
                    List.of(
                            item("RA_APPENDAGE", "右心耳", 10),
                            item("RA_SEPTUM", "右房间隔", 20),
                            item("OTHER", "其他", 90));
            case "lead_position_rv" ->
                    List.of(
                            item("RV_APEX", "右室心尖", 10),
                            item("RV_SEPTUM", "右室间隔", 20),
                            item("HIS", "希氏束", 30),
                            item("LBB", "左束支", 40),
                            item("OTHER", "其他", 90));
            case "lead_position_lv" -> List.of(item("CS", "冠状静脉窦", 10), item("OTHER", "其他", 90));
            default -> List.of();
        };
    }

    private static ImplantDictPort.DictItem item(String code, String label, int sortNo) {
        return new ImplantDictPort.DictItem(code, label, sortNo, true);
    }

    /** 本院停用标记（cfg_dict_item 平台项停用行）。 */
    private static ImplantDictPort.DictItem disabled(String code, String label, int sortNo) {
        return new ImplantDictPort.DictItem(code, label, sortNo, false);
    }

    // ---------------------------------------------------------------- 计划切换挂钩（E21-T06 / C-FU-007）

    @Test
    void updateCorrectingDeviceTypeHooksPlanSwitch() {
        ImplantRow existing = implant(501L, "ACTIVE", "2026-01-05");
        when(gateway.implantInScope(501L)).thenReturn(existing);
        when(gateway.leadsOfImplant(501L)).thenReturn(List.of());
        when(gateway.implantsOf(PATIENT_ID)).thenReturn(List.of(existing));

        service.update(
                501L,
                new ImplantService.UpdateRequest(3, payload("FIRST", "2026-01-05", "ICD", null)));

        // SW-6 器械类型更正（录入纠错）：按新类型重新入组，起算仍按原植入日期（切换服务内部锁定）
        verify(planSwitch)
                .onDeviceTypeCorrected(
                        PATIENT_ID, 501L, "DDD", "ICD", LocalDate.parse("2026-01-05"));
        verify(startDateRecalc, never()).recalculateOnBaseDateChange(anyLong(), any());
    }

    @Test
    void updateChangingImplantDateHooksStartDateRecalculation() {
        ImplantRow existing = implant(501L, "ACTIVE", "2026-01-05");
        when(gateway.implantInScope(501L)).thenReturn(existing);
        when(gateway.leadsOfImplant(501L)).thenReturn(List.of());
        when(gateway.implantsOf(PATIENT_ID)).thenReturn(List.of(existing));

        service.update(
                501L,
                new ImplantService.UpdateRequest(3, payload("FIRST", "2026-02-10", "DDD", null)));

        // 类型未改、仅改植入日期：接入 E14-T07 起算重算（未开始任务按新日期重算，C 规格 §4 / OQ-135③）
        verify(startDateRecalc).recalculateOnBaseDateChange(PATIENT_ID, OPERATOR_ID);
        verify(planSwitch, never())
                .onDeviceTypeCorrected(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void updateOnReplacedHistoryRecordDoesNotTouchPlans() {
        ImplantRow existing = implant(501L, "REPLACED", "2026-01-05");
        when(gateway.implantInScope(501L)).thenReturn(existing);
        when(gateway.leadsOfImplant(501L)).thenReturn(List.of());
        when(gateway.implantsOf(PATIENT_ID)).thenReturn(List.of(existing));

        service.update(
                501L,
                new ImplantService.UpdateRequest(3, payload("FIRST", "2026-01-05", "ICD", null)));

        // 只针对在用记录（当前器械）：历史（已更换）记录的类型 / 日期改动不触发切换与重算
        verify(planSwitch, never())
                .onDeviceTypeCorrected(anyLong(), anyLong(), any(), any(), any());
        verify(startDateRecalc, never()).recalculateOnBaseDateChange(anyLong(), any());
    }

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
                null,
                leads);
    }

    private static ImplantService.LeadPayload leadPayload(String chamber, String position) {
        return new ImplantService.LeadPayload(null, chamber, position, null, null, null);
    }

    private static ImplantRow implant(long id, String status, String date) {
        return implantRow(id, status, date, "DDD", "DDD", "MEDTRONIC");
    }

    /** 指定器械类型 / 起搏模式 / 品牌的行（字典标签解析用例）。 */
    private static ImplantRow implantRow(
            long id,
            String status,
            String date,
            String deviceType,
            String pacingMode,
            String brand) {
        return new ImplantRow(
                id,
                PATIENT_ID,
                "FIRST",
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
