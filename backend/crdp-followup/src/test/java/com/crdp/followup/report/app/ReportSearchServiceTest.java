package com.crdp.followup.report.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.app.BaselineSectionService;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper.TypeRow;
import com.crdp.followup.report.infra.mapper.ReportSearchMapper;
import com.crdp.followup.report.infra.mapper.ReportSearchMapper.Criteria;
import com.crdp.followup.report.infra.mapper.ReportSearchMapper.LinkRowRaw;
import com.crdp.followup.report.infra.mapper.ReportSearchMapper.RowRaw;
import com.crdp.followup.report.infra.mapper.ReportSearchMapper.UserOptionRow;
import com.crdp.followup.support.ViewStubGuard;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.TenantViewFieldGuard;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E16-T05 单测锁定（全部虚构数据，A-ATT-016 / C-SCAN-014；AGENTS.md §5.5：患者姓名夹具带「（示例）」标记，病案号 MRN-000x
 * 为虚构演示号）：全院报告单检索——条件装配（患者盲索引 + 文字关键词、位置含「未指定位置」、电子化状态、病区）、分页校验、行内患者脱敏、位置标签、缩略图首文件、 筛选候选与批量查询审计。
 */
@ExtendWith(MockitoExtension.class)
class ReportSearchServiceTest {

    private static final long OPERATOR = 7L;

    private final ReportSearchMapper mapper = mock(ReportSearchMapper.class);
    private final ReportConfigMapper config = mock(ReportConfigMapper.class);
    private final PatientMapper patients = mock(PatientMapper.class);
    private final FormService formService = mock(FormService.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final ReportSearchService service =
            new ReportSearchService(
                    mapper,
                    config,
                    patients,
                    formService,
                    crypto,
                    audit,
                    TenantViewFieldGuard.passthrough());

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        // 基线模板：两个分类（位置标签解析用）
        lenient()
                .when(formService.publishedVersion(BaselineSectionService.BASELINE_FORM_CODE))
                .thenReturn(3);
        lenient()
                .when(formService.schema(BaselineSectionService.BASELINE_FORM_CODE, 3))
                .thenReturn(schema(List.of(category("basic", "基本信息"), category("echo", "超声检查"))));
        lenient()
                .when(patients.findWards())
                .thenReturn(List.of(new PatientMapper.OptionRow(5L, "示例二病区")));
        lenient()
                .when(config.findTypes())
                .thenReturn(
                        List.of(
                                type("ECHO", "心脏超声", "ENABLED"),
                                type("LAB", "检验", "ENABLED"),
                                type("OLD", "已停用类型", "DISABLED")));
        lenient()
                .when(mapper.uploaderOptions())
                .thenReturn(List.of(new UserOptionRow(7L, "王医生（示例）")));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 行与脱敏（A-ATT-016「患者（脱敏）」列）

    @Test
    void searchMasksPatientIdentityAndResolvesLocationLabels() {
        when(mapper.search(any(), anyInt(), anyInt()))
                .thenReturn(
                        List.of(
                                row(
                                        901L,
                                        "张小明（示例）",
                                        "MRN-0001",
                                        "105-2026-001",
                                        "示例二病区",
                                        "ECHO",
                                        "心脏超声",
                                        "经胸超声心动图",
                                        LocalDate.of(2026, 10, 25),
                                        "PENDING",
                                        "王医生（示例）")));
        when(mapper.count(any())).thenReturn(1L);
        when(mapper.linksOfReports(anyList()))
                .thenReturn(
                        List.of(
                                new LinkRowRaw(
                                        1L,
                                        901L,
                                        "baseline",
                                        null,
                                        "category",
                                        "echo",
                                        3,
                                        0,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null),
                                new LinkRowRaw(
                                        2L,
                                        901L,
                                        "followup_record",
                                        55L,
                                        "section",
                                        "sec-echo",
                                        4,
                                        0,
                                        55L,
                                        "第 3 年随访",
                                        "TASK",
                                        null,
                                        LocalDate.of(2026, 6, 10),
                                        "A_ANNUAL_FU",
                                        4)));
        when(mapper.fileCountsOfReports(anyList()))
                .thenReturn(List.of(new ReportSearchMapper.FileCountRow(901L, 2L)));
        when(mapper.firstFilesOfReports(anyList()))
                .thenReturn(
                        List.of(
                                new ReportSearchMapper.FileRowRaw(901L, 71L, "jpg", "t/n/th.jpg"),
                                new ReportSearchMapper.FileRowRaw(901L, 72L, "pdf", null)));
        lenient()
                .when(formService.schema("A_ANNUAL_FU", 4))
                .thenReturn(schema(List.of(category("sec-echo", "超声复查"))));
        when(crypto.decrypt("name-cipher")).thenReturn("张小明（示例）");
        when(crypto.decrypt("mrn-cipher")).thenReturn("MRN-0001");

        ReportSearchService.ReportPage page =
                service.search(
                        new ReportSearchService.SearchRequest(
                                null, null, null, null, null, null, null, null, null, null, null,
                                null));

        assertEquals(1, page.items().size());
        ReportSearchService.RowView view = page.items().get(0);
        assertEquals("901", view.id());
        assertEquals("张*）", view.patientName(), "行内姓名脱敏（列表不提供逐行明文）");
        assertEquals("MR****01", view.mrn(), "病案号脱敏（前 2 后 2）");
        assertEquals("105-2026-001", view.visitNo(), "随访编号本身不带敏感明文（C-SCAN-014）");
        assertEquals("示例二病区", view.wardName());
        assertEquals(2, view.locations().size(), "位置列标签（+N 折叠由前端做）");
        assertEquals("超声检查", view.locations().get(0).anchorLabel(), "基线分类显示名");
        assertEquals("第 3 年随访 2026-06-10", view.locations().get(1).recordLabel());
        assertEquals("超声复查", view.locations().get(1).anchorLabel());
        assertEquals(2L, view.fileCount());
        assertEquals("71", view.firstFile().fileId(), "缩略图取第一个附件（sort_no 最小）");
        assertEquals("jpg", view.firstFile().format());

        verify(audit).append(any(AuditEntry.class));
    }

    @Test
    void unassignedReportsCarryEmptyLocationList() {
        when(mapper.search(any(), anyInt(), anyInt()))
                .thenReturn(
                        List.of(
                                row(
                                        902L,
                                        "李小红（示例）",
                                        "MRN-0002",
                                        null,
                                        "示例二病区",
                                        "LAB",
                                        "检验",
                                        "血常规",
                                        LocalDate.of(2026, 10, 26),
                                        null,
                                        "王医生（示例）")));
        when(mapper.count(any())).thenReturn(1L);
        when(mapper.linksOfReports(anyList())).thenReturn(List.of());
        when(mapper.fileCountsOfReports(anyList())).thenReturn(List.of());
        when(mapper.firstFilesOfReports(anyList())).thenReturn(List.of());
        when(crypto.decrypt("name-cipher")).thenReturn("李小红（示例）");
        when(crypto.decrypt("mrn-cipher")).thenReturn("MRN-0002");

        ReportSearchService.ReportPage page = service.search(null);

        assertEquals(0, page.items().get(0).locations().size(), "无位置报告单 → 前端显示「未指定位置」");
        assertNull(page.items().get(0).firstFile());
        assertNull(page.items().get(0).digitizeStatus());
    }

    // ---------------------------------------------------------------- 租户视角执行点（E20-T07 /
    // PLT-VIEW-010、011；原件隐藏为 V109 默认规则）

    /** 视角会话（TENANT_VIEW）：平台账号身份 + 被查看租户（与 crdp-server 视角会话同构）。 */
    private static SessionPrincipal viewPrincipal() {
        return new SessionPrincipal(
                "view-token",
                "TENANT_VIEW",
                7L,
                "demoa",
                "虚构示例医院甲",
                1L,
                "platform.admin",
                "平台管理员（示例）",
                List.of("PLATFORM_ADMIN"),
                List.of(),
                List.of(),
                List.of(),
                1L,
                com.crdp.framework.security.ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                900L,
                7200L,
                com.crdp.framework.fieldpolicy.FieldPolicySnapshot.defaults());
    }

    private ReportSearchService serviceOf(TenantViewFieldGuard guard) {
        return new ReportSearchService(mapper, config, patients, formService, crypto, audit, guard);
    }

    private void stubReportRowWithFirstFile() {
        when(mapper.search(any(), anyInt(), anyInt()))
                .thenReturn(
                        List.of(
                                row(
                                        901L,
                                        "张小明（示例）",
                                        "MRN-0001",
                                        "105-2026-001",
                                        "示例二病区",
                                        "ECHO",
                                        "心脏超声",
                                        "经胸超声心动图",
                                        LocalDate.of(2026, 10, 25),
                                        "PENDING",
                                        "王医生（示例）")));
        when(mapper.count(any())).thenReturn(1L);
        when(mapper.linksOfReports(anyList())).thenReturn(List.of());
        when(mapper.fileCountsOfReports(anyList()))
                .thenReturn(List.of(new ReportSearchMapper.FileCountRow(901L, 2L)));
        when(mapper.firstFilesOfReports(anyList()))
                .thenReturn(
                        List.of(new ReportSearchMapper.FileRowRaw(901L, 71L, "jpg", "t/n/th.jpg")));
        when(crypto.decrypt("name-cipher")).thenReturn("张小明（示例）");
        when(crypto.decrypt("mrn-cipher")).thenReturn("MRN-0001");
    }

    @Test
    void searchHidesOriginalsAndBlindsNameForViewSession() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(
                                viewPrincipal(), null, List.of()));
        stubReportRowWithFirstFile();
        // 报告单资源整体隐藏（V109 默认：原件与附件不可预览）+ 患者姓名设盲
        ReportSearchService service =
                serviceOf(ViewStubGuard.of(Set.of("patient:name"), Set.of("report:*")));

        ReportSearchService.ReportPage page = service.search(null);

        ReportSearchService.RowView view = page.items().get(0);
        assertEquals("已设盲", view.patientName(), "报告单行内患者姓名按视角规则设盲（PLT-VIEW-011）");
        assertEquals("MR****01", view.mrn(), "无规则命中的病案号保留脱敏值（OQ-107④）");
        assertNull(view.firstFile(), "原件与附件对视角隐藏：首附件不返回（PLT-VIEW-008 / V109）");
        assertEquals(2L, view.fileCount(), "目录行数照常（只隐藏内容通道，不改台账口径）");
    }

    @Test
    void searchKeepsFirstFileAndMaskedNameForHospitalSession() {
        stubReportRowWithFirstFile();
        ReportSearchService service =
                serviceOf(ViewStubGuard.of(Set.of("patient:name"), Set.of("report:*")));

        ReportSearchService.RowView view = service.search(null).items().get(0);

        assertEquals("张*）", view.patientName(), "医院会话不经视角执行点（行为不变）");
        assertEquals("71", view.firstFile().fileId(), "医院会话首附件照常返回");
    }

    // ---------------------------------------------------------------- 条件装配（验收：位置筛选含未指定位置）

    @Test
    void unassignedLocationScopeGoesIntoCriteria() {
        when(mapper.search(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(mapper.count(any())).thenReturn(0L);

        service.search(
                new ReportSearchService.SearchRequest(
                        1, 20, null, null, null, null, null, null, null, "UNASSIGNED", null, null));

        ArgumentCaptor<Criteria> captor = ArgumentCaptor.forClass(Criteria.class);
        verify(mapper).search(captor.capture(), anyInt(), anyInt());
        assertEquals("UNASSIGNED", captor.getValue().locationScope());
    }

    @Test
    void keywordBecomesBlindIndexesAndTextLike() {
        when(mapper.search(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(mapper.count(any())).thenReturn(0L);
        when(crypto.blindIndex("pt-patient-name", "张小明（示例）")).thenReturn("blind-name");
        when(crypto.blindIndex("pt-patient-mrn", "张小明（示例）")).thenReturn("blind-mrn");

        service.search(
                new ReportSearchService.SearchRequest(
                        1, 20, "张小明（示例）", null, null, null, null, null, null, null, null, null));

        ArgumentCaptor<Criteria> captor = ArgumentCaptor.forClass(Criteria.class);
        verify(mapper).search(captor.capture(), anyInt(), anyInt());
        assertEquals("blind-name", captor.getValue().nameBlind());
        assertEquals("blind-mrn", captor.getValue().mrnBlind());
        assertEquals("张小明（示例）", captor.getValue().textKeyword(), "关键词同时匹配名称 / 所见 / 结论 / 类型字段文字");
        assertEquals("张小明（示例）", captor.getValue().keyword(), "随访编号 / 原系统编号精确匹配");
    }

    @Test
    void filtersAndPagingGoIntoCriteria() {
        when(mapper.search(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(mapper.count(any())).thenReturn(0L);

        service.search(
                new ReportSearchService.SearchRequest(
                        2,
                        50,
                        null,
                        "ECHO",
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31),
                        LocalDate.of(2026, 10, 1),
                        LocalDate.of(2026, 10, 31),
                        7L,
                        "FOLLOWUP",
                        "PENDING",
                        List.of(5L)));

        ArgumentCaptor<Criteria> captor = ArgumentCaptor.forClass(Criteria.class);
        verify(mapper).search(captor.capture(), anyInt(), anyInt());
        Criteria criteria = captor.getValue();
        assertEquals("ECHO", criteria.typeCode());
        assertEquals(LocalDate.of(2026, 1, 1), criteria.reportDateFrom());
        assertEquals(LocalDate.of(2026, 12, 31), criteria.reportDateTo());
        assertEquals(LocalDate.of(2026, 10, 1), criteria.uploadedFrom());
        assertEquals(LocalDate.of(2026, 10, 31), criteria.uploadedTo());
        assertEquals(7L, criteria.creatorId());
        assertEquals("FOLLOWUP", criteria.locationScope());
        assertEquals("PENDING", criteria.digitizeStatus());
        assertEquals(List.of(5L), criteria.wardIds());
    }

    // ---------------------------------------------------------------- 校验（40001）

    @Test
    void rejectsInvalidPagingScopesAndRanges() {
        assertThrows(
                ApiException.class,
                () -> service.search(requestWith(null, null, "BAD_SCOPE", null)));
        ApiException location =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.search(
                                        new ReportSearchService.SearchRequest(
                                                1,
                                                20,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "ANYWHERE",
                                                null,
                                                null)));
        assertEquals(40001, location.code());

        ApiException digitize =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.search(
                                        new ReportSearchService.SearchRequest(
                                                1, 20, null, null, null, null, null, null, null,
                                                null, "DONE?", null)));
        assertEquals(40001, digitize.code());

        ApiException page =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.search(
                                        new ReportSearchService.SearchRequest(
                                                0, 20, null, null, null, null, null, null, null,
                                                null, null, null)));
        assertEquals(40001, page.code());

        ApiException size =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.search(
                                        new ReportSearchService.SearchRequest(
                                                1, 101, null, null, null, null, null, null, null,
                                                null, null, null)));
        assertEquals(40001, size.code());

        ApiException range =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.search(
                                        new ReportSearchService.SearchRequest(
                                                1,
                                                20,
                                                null,
                                                null,
                                                LocalDate.of(2026, 12, 31),
                                                LocalDate.of(2026, 1, 1),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null)));
        assertEquals(40001, range.code());

        ApiException uploadRange =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.search(
                                        new ReportSearchService.SearchRequest(
                                                1,
                                                20,
                                                null,
                                                null,
                                                null,
                                                null,
                                                LocalDate.of(2026, 12, 31),
                                                LocalDate.of(2026, 1, 1),
                                                null,
                                                null,
                                                null,
                                                null)));
        assertEquals(40001, uploadRange.code());

        verify(mapper, never()).search(any(), anyInt(), anyInt());
    }

    // ---------------------------------------------------------------- 筛选候选（类型 / 上传人 / 病区）

    @Test
    void optionsIncludeEnabledTypesUploadersAndWards() {
        when(mapper.search(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(mapper.count(any())).thenReturn(0L);

        ReportSearchService.ReportPage page = service.search(null);

        assertEquals(
                List.of(
                        new ReportSearchService.TypeOptionView("ECHO", "心脏超声"),
                        new ReportSearchService.TypeOptionView("LAB", "检验")),
                page.types(),
                "类型候选只含启用类型（停用不出现在筛选）");
        assertEquals(
                List.of(new ReportSearchService.UserOptionView("7", "王医生（示例）")), page.uploaders());
        assertEquals(List.of(new ReportSearchService.WardOptionView("5", "示例二病区")), page.wards());
    }

    // ---------------------------------------------------------------- 工具

    private static ReportSearchService.SearchRequest requestWith(
            Integer pageNo, Integer pageSize, String locationScope, String digitize) {
        return new ReportSearchService.SearchRequest(
                pageNo,
                pageSize,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                locationScope,
                digitize,
                null);
    }

    private static RowRaw row(
            long id,
            String name,
            String mrn,
            String visitNo,
            String wardName,
            String typeCode,
            String typeName,
            String nameSnapshot,
            LocalDate reportDate,
            String digitizeStatus,
            String creatorName) {
        return new RowRaw(
                id,
                301L,
                "name-cipher",
                "mrn-cipher",
                visitNo,
                wardName,
                typeCode,
                typeName,
                nameSnapshot,
                reportDate,
                digitizeStatus,
                creatorName,
                "2026-10-28 09:30:00.000");
    }

    private static FormService.SchemaView schema(List<FormService.CategoryView> categories) {
        return new FormService.SchemaView("FORM", "表单（示例）", "BASELINE", 3, categories, List.of());
    }

    private static FormService.CategoryView category(String key, String label) {
        return new FormService.CategoryView(key, label, 1, List.of());
    }

    private static TypeRow type(String code, String name, String status) {
        return new TypeRow(
                1L, code, name, "FORM_" + code, "表单（示例）", true, null, 1, status, null, 0L);
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例租户）",
                OPERATOR,
                "doctor.demo",
                "王医生（示例）",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of(),
                List.of("followup"),
                1L,
                com.crdp.framework.security.ScopeSnapshot.allowAll(),
                false,
                null);
    }
}
