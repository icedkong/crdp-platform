package com.crdp.followup.report.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.file.app.FilePolicyService;
import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
import com.crdp.followup.patient.app.BaselineSectionService;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper;
import com.crdp.followup.report.infra.mapper.ReportInsert;
import com.crdp.followup.report.infra.mapper.ReportMapper;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E16-T02 单测锁定（全部虚构数据，A-ATT-001 / A-ATT-002；C-SCAN-001 / C-SCAN-010）：报告单创建、位置必填
 * 与三种粒度校验、附件上限与归属、指标快照与 ↑ / ↓、位置树与患者内清单。
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    private static final long OPERATOR = 7L;

    private static final long PATIENT = 301L;

    private final ReportMapper mapper = mock(ReportMapper.class);
    private final ReportConfigMapper config = mock(ReportConfigMapper.class);
    private final ReportPatientGateway patientScope = mock(ReportPatientGateway.class);
    private final FormService formService = mock(FormService.class);
    private final FilePolicyService filePolicy = mock(FilePolicyService.class);
    private final SysFileMapper files = mock(SysFileMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final ReportService service =
            new ReportService(mapper, config, patientScope, formService, filePolicy, files, audit);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        lenient().when(patientScope.patientIdInScope(PATIENT)).thenReturn(PATIENT);
        lenient()
                .when(filePolicy.policy())
                .thenReturn(new FilePolicyService.UploadPolicy(List.of(), 20, 50, 10));
        // 基线模板：两个分类（锚点校验与位置树共用）
        lenient()
                .when(formService.publishedVersion(BaselineSectionService.BASELINE_FORM_CODE))
                .thenReturn(3);
        lenient()
                .when(formService.schema(BaselineSectionService.BASELINE_FORM_CODE, 3))
                .thenReturn(
                        schema(3, List.of(category("basic", "基本信息"), category("case", "病例基本信息"))));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 创建（验收：位置至少 1 个、可多选）

    @Test
    void createRejectsEmptyLocationsWith40001() {
        readyTypeAndName();
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        request(
                                                "LAB",
                                                null,
                                                LocalDate.of(2026, 10, 28),
                                                List.of())));

        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("请至少选择 1 个位置"), error.getMessage());
        verify(mapper, never()).insertReport(any());
    }

    @Test
    void createInsertsReportLinksFilesIndicatorsAndAudits() {
        ReportConfigMapper.TypeRow type = typeRow("ECHO", "心脏超声", true);
        ReportConfigMapper.NameRow name = nameRow(12L, "ECHO", "经胸超声心动图（示例）", 55L, null);
        ReportConfigMapper.TplRow tpl =
                new ReportConfigMapper.TplRow(55L, "心脏超声指标（示例）", "ENABLED", null, 3L, 1L);
        when(config.findTypeIdByCode("ECHO")).thenReturn(9L);
        when(config.findType(9L)).thenReturn(type);
        when(config.findName(12L)).thenReturn(name);
        when(config.findTpl(55L)).thenReturn(tpl);
        when(config.findItems(55L))
                .thenReturn(
                        List.of(
                                item(55L, "EF", "射血分数（示例）", "%", "50–70", 10),
                                item(55L, "LVDd", "左室舒张末内径（示例）", "mm", "≤ 55", 20)));
        when(formService.publishedVersion("RPT_ECHO")).thenReturn(1);
        when(formService.writeGateData(anyString(), anyInt(), any()))
                .thenReturn(Map.of("f002", "各腔室内径正常（示例）"));
        RecordContext rec = record(88L, "第2年随访", "TASK", LocalDate.of(2026, 6, 10), 2);
        when(mapper.recordsOfPatient(PATIENT)).thenReturn(rec.rows());
        SysFileRow uploaded = file(501L, "echo-1.jpg（示例）");
        when(files.find(501L)).thenReturn(uploaded);
        when(files.markAttached(501L)).thenReturn(1);
        when(mapper.insertReport(any()))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, ReportInsert.class).setId(900L);
                            return 1;
                        });

        ReportService.CreatedView view =
                service.create(
                        PATIENT,
                        new ReportService.CreateRequest(
                                "ECHO",
                                "12",
                                LocalDate.of(2026, 10, 28),
                                Map.of("f002", "各腔室内径正常（示例）"),
                                List.of(
                                        new ReportService.IndicatorInput("EF", "48", "50–70"),
                                        new ReportService.IndicatorInput("LVDd", "58", "≤ 55")),
                                "各腔室内径正常（示例）",
                                "未见明显异常（示例）",
                                List.of(
                                        new ReportService.LocationInput(
                                                "baseline", null, "category", "case", 3),
                                        new ReportService.LocationInput(
                                                "followup_record", 88L, null, null, 2),
                                        new ReportService.LocationInput(
                                                "followup_record", 88L, "section", "echo", 2)),
                                List.of("501"),
                                "门诊复查（示例）"));

        assertEquals("900", view.id());
        assertEquals(3, view.locationCount(), "三种粒度位置各一行（基线分类 / 整条 / 分组）");
        assertEquals(1, view.fileCount());

        ArgumentCaptor<ReportInsert> reportCaptor = ArgumentCaptor.forClass(ReportInsert.class);
        verify(mapper).insertReport(reportCaptor.capture());
        assertEquals("经胸超声心动图（示例）", reportCaptor.getValue().nameSnapshot());
        assertEquals(OPERATOR, reportCaptor.getValue().createdBy());
        assertNull(reportCaptor.getValue().digitizeStatus(), "A 医院不启用电子化状态");

        ArgumentCaptor<ReportMapper.LinkInsert> linkCaptor =
                ArgumentCaptor.forClass(ReportMapper.LinkInsert.class);
        verify(mapper, org.mockito.Mockito.times(3)).insertLink(linkCaptor.capture());
        List<ReportMapper.LinkInsert> links = linkCaptor.getAllValues();
        assertEquals("baseline", links.get(0).linkType());
        assertEquals("category", links.get(0).anchorType());
        assertEquals("case", links.get(0).anchorKey());
        assertEquals(3, links.get(0).templateVersion(), "基线锚点版本 = 当前发布版");
        assertNull(links.get(1).anchorType(), "整条记录 anchorType 为空");
        assertEquals(2, links.get(1).templateVersion(), "随访锚点版本 = 记录固化版本");
        assertEquals("section", links.get(2).anchorType());

        ArgumentCaptor<ReportMapper.IndicatorInsert> indicatorCaptor =
                ArgumentCaptor.forClass(ReportMapper.IndicatorInsert.class);
        verify(mapper, org.mockito.Mockito.times(2)).insertIndicator(indicatorCaptor.capture());
        assertEquals("DOWN", indicatorCaptor.getAllValues().get(0).flag(), "EF 48 低于 50–70 → ↓");
        assertEquals("UP", indicatorCaptor.getAllValues().get(1).flag(), "LVDd 58 超过 ≤ 55 → ↑");

        verify(mapper).insertFile(any());
        verify(audit).append(any(AuditEntry.class)); // RPT_CREATE
    }

    @Test
    void createMergesDuplicateLocations() {
        readyTypeAndName();
        RecordContext rec = record(88L, "第2年随访", "TASK", LocalDate.of(2026, 6, 10), 2);
        when(mapper.recordsOfPatient(PATIENT)).thenReturn(rec.rows());
        when(mapper.insertReport(any()))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, ReportInsert.class).setId(900L);
                            return 1;
                        });

        service.create(
                PATIENT,
                request(
                        "LAB",
                        null,
                        LocalDate.of(2026, 10, 28),
                        List.of(
                                new ReportService.LocationInput(
                                        "baseline", null, "category", "basic", 3),
                                new ReportService.LocationInput(
                                        "baseline", null, "category", "basic", 3))));

        verify(mapper, org.mockito.Mockito.times(1))
                .insertLink(any()); // 同一报告单同一位置只一行（A-ATT-002 关键字段）
    }

    @Test
    void createRejectsImplantRecordLocationUntilE21() {
        readyTypeAndName();

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        request(
                                                "LAB",
                                                null,
                                                LocalDate.of(2026, 10, 28),
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "implant_record",
                                                                5L,
                                                                null,
                                                                null,
                                                                1)))));

        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("E21"), error.getMessage());
    }

    // ---------------------------------------------------------------- 位置校验（三种粒度，不细到字段）

    @Test
    void createRejectsBaselineLocationWithoutCategoryAnchor() {
        readyTypeAndName();

        ApiException missing =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        request(
                                                "LAB",
                                                null,
                                                LocalDate.of(2026, 10, 28),
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "baseline",
                                                                null,
                                                                null,
                                                                null,
                                                                3)))));
        assertEquals(40001, missing.code(), "基线位置必须落到某个分类");

        ApiException wrongType =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        request(
                                                "LAB",
                                                null,
                                                LocalDate.of(2026, 10, 28),
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "baseline",
                                                                null,
                                                                "section",
                                                                "case",
                                                                3)))));
        assertEquals(40001, wrongType.code(), "基线不支持分组粒度");
    }

    @Test
    void createRejectsUnknownBaselineCategory() {
        readyTypeAndName();

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        request(
                                                "LAB",
                                                null,
                                                LocalDate.of(2026, 10, 28),
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "baseline",
                                                                null,
                                                                "category",
                                                                "nope",
                                                                3)))));

        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("基线分类不存在"), error.getMessage());
    }

    @Test
    void createRejectsMissingOrForeignFollowupRecordWith40400() {
        readyTypeAndName();
        // 88 号记录属于患者；88 之外的记录 id 一律 40400（不区分不存在 / 他人 / 越范围）
        RecordContext rec = record(88L, "第2年随访", "TASK", LocalDate.of(2026, 6, 10), 2);
        when(mapper.recordsOfPatient(PATIENT)).thenReturn(rec.rows());

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        request(
                                                "LAB",
                                                null,
                                                LocalDate.of(2026, 10, 28),
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "followup_record",
                                                                999L,
                                                                null,
                                                                null,
                                                                2)))));

        assertEquals(40400, error.code());
    }

    @Test
    void createRejectsUnknownSectionAnchor() {
        readyTypeAndName();
        RecordContext rec = record(88L, "第2年随访", "TASK", LocalDate.of(2026, 6, 10), 2);
        when(mapper.recordsOfPatient(PATIENT)).thenReturn(rec.rows());

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        request(
                                                "LAB",
                                                null,
                                                LocalDate.of(2026, 10, 28),
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "followup_record",
                                                                88L,
                                                                "section",
                                                                "ghost",
                                                                2)))));

        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("随访分组不存在"), error.getMessage());
    }

    // ---------------------------------------------------------------- 类型 / 名称 / 日期

    @Test
    void createRejectsUnknownTypeWithNameRequiredAndMissingDate() {
        when(patientScope.patientIdInScope(PATIENT)).thenReturn(PATIENT);

        ApiException unknownType =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        request(
                                                "NOPE",
                                                null,
                                                LocalDate.of(2026, 10, 28),
                                                List.of())));
        assertEquals(40400, unknownType.code());

        ReportConfigMapper.TypeRow type = typeRow("ECHO", "心脏超声", true);
        when(config.findTypeIdByCode("ECHO")).thenReturn(9L);
        when(config.findType(9L)).thenReturn(type);

        ApiException missingName =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        request(
                                                "ECHO",
                                                null,
                                                LocalDate.of(2026, 10, 28),
                                                List.of())));
        assertEquals(40001, missingName.code(), "A 预置类型名称必填（OQ-95③）");

        ApiException wrongName =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        request(
                                                "ECHO",
                                                "12",
                                                LocalDate.of(2026, 10, 28),
                                                List.of())));
        assertEquals(40001, wrongName.code(), "名称不存在时拒绝（未到范围校验的桩）");

        ApiException missingDate =
                assertThrows(
                        ApiException.class,
                        () -> service.create(PATIENT, request("ECHO", "12", null, List.of())));
        assertEquals(40001, missingDate.code(), "报告时间必填");
    }

    @Test
    void createRejectsDisabledTypeAndForeignName() {
        ReportConfigMapper.TypeRow disabled = typeRow("ECHO", "心脏超声", true, "DISABLED");
        when(config.findTypeIdByCode("ECHO")).thenReturn(9L);
        when(config.findType(9L)).thenReturn(disabled);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        request(
                                                "ECHO",
                                                null,
                                                LocalDate.of(2026, 10, 28),
                                                List.of())));
        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("已停用"), error.getMessage());

        ReportConfigMapper.TypeRow lab = typeRow("LAB", "检验", true);
        ReportConfigMapper.NameRow foreign = nameRow(12L, "ECHO", "经胸超声心动图（示例）", null, null);
        when(config.findTypeIdByCode("LAB")).thenReturn(8L);
        when(config.findType(8L)).thenReturn(lab);
        when(config.findName(12L)).thenReturn(foreign);

        ApiException wrongType =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        request(
                                                "LAB",
                                                "12",
                                                LocalDate.of(2026, 10, 28),
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "baseline",
                                                                null,
                                                                "category",
                                                                "basic",
                                                                3)))));
        assertEquals(40001, wrongType.code(), "名称必须属于所选类型");
    }

    @Test
    void createRejectsOutOfScopePatientWith40400() {
        when(patientScope.patientIdInScope(PATIENT)).thenReturn(null);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        request(
                                                "LAB",
                                                null,
                                                LocalDate.of(2026, 10, 28),
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "baseline",
                                                                null,
                                                                "category",
                                                                "basic",
                                                                3)))));

        assertEquals(40400, error.code());
    }

    // ---------------------------------------------------------------- 附件与指标

    @Test
    void createRejectsFileCountOverLimitAndForeignFiles() {
        readyTypeAndName();
        lenient()
                .when(mapper.insertReport(any()))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, ReportInsert.class).setId(900L);
                            return 1;
                        });

        List<String> eleven = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
        ApiException over =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        request(
                                                "LAB",
                                                null,
                                                LocalDate.of(2026, 10, 28),
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "baseline",
                                                                null,
                                                                "category",
                                                                "basic",
                                                                3)),
                                                eleven)));
        assertEquals(40001, over.code());
        assertTrue(over.getMessage().contains("最多 10 个"), over.getMessage());

        when(files.find(404L)).thenReturn(null);
        ApiException missing =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        request(
                                                "LAB",
                                                null,
                                                LocalDate.of(2026, 10, 28),
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "baseline",
                                                                null,
                                                                "category",
                                                                "basic",
                                                                3)),
                                                List.of("404"))));
        assertEquals(40400, missing.code(), "附件不存在");

        SysFileRow attached = file(502L, "taken.pdf（示例）");
        when(files.find(502L)).thenReturn(attached);
        when(files.markAttached(502L)).thenReturn(0);
        ApiException reused =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        request(
                                                "LAB",
                                                null,
                                                LocalDate.of(2026, 10, 28),
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "baseline",
                                                                null,
                                                                "category",
                                                                "basic",
                                                                3)),
                                                List.of("502"))));
        assertEquals(40001, reused.code(), "已挂他处的文件不能重复关联");
    }

    @Test
    void createRejectsIndicatorOutsideNameTemplate() {
        readyTypeAndName();
        ReportConfigMapper.NameRow withTpl = nameRow(12L, "LAB", "血常规（示例）", 55L, null);
        ReportConfigMapper.TplRow tpl =
                new ReportConfigMapper.TplRow(55L, "血常规（示例）", "ENABLED", null, 2L, 1L);
        when(config.findName(12L)).thenReturn(withTpl);
        when(config.findTpl(55L)).thenReturn(tpl);
        when(config.findItems(55L))
                .thenReturn(List.of(item(55L, "WBC", "白细胞（示例）", "10^9/L", "3.5–9.5", 10)));
        lenient()
                .when(mapper.insertReport(any()))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, ReportInsert.class).setId(900L);
                            return 1;
                        });

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        new ReportService.CreateRequest(
                                                "LAB",
                                                "12",
                                                LocalDate.of(2026, 10, 28),
                                                Map.of(),
                                                List.of(
                                                        new ReportService.IndicatorInput(
                                                                "HGB", "130", "130–175")),
                                                null,
                                                null,
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "baseline",
                                                                null,
                                                                "category",
                                                                "basic",
                                                                3)),
                                                List.of(),
                                                null)));

        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("HGB"), error.getMessage());

        // 名称未绑定模板时填指标同样拒绝
        ReportConfigMapper.NameRow plain = nameRow(13L, "LAB", "其他检验（示例）", null, null);
        when(config.findName(13L)).thenReturn(plain);
        ApiException noTpl =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        new ReportService.CreateRequest(
                                                "LAB",
                                                "13",
                                                LocalDate.of(2026, 10, 28),
                                                Map.of(),
                                                List.of(
                                                        new ReportService.IndicatorInput(
                                                                "WBC", "6.1", "3.5–9.5")),
                                                null,
                                                null,
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "baseline",
                                                                null,
                                                                "category",
                                                                "basic",
                                                                3)),
                                                List.of(),
                                                null)));
        assertEquals(40001, noTpl.code());
    }

    // ---------------------------------------------------------------- ↑ / ↓ 解析（A-ATT-012）

    @Test
    void indicatorFlagParsesRangesTolerantly() {
        assertEquals("DOWN", ReportService.indicatorFlag("3.5–9.5", "3.2"));
        assertEquals("UP", ReportService.indicatorFlag("3.5-9.5", "10.1"));
        assertNull(ReportService.indicatorFlag("3.5~9.5", "5.0"), "区间内不标");
        assertEquals("UP", ReportService.indicatorFlag("≤ 3.4", "3.5"));
        assertNull(ReportService.indicatorFlag("＜125", "100"));
        assertEquals("DOWN", ReportService.indicatorFlag("≥ 60", "59"));
        assertNull(ReportService.indicatorFlag("＞ 5.0", "5.1"), "＞ 5.0 是下限，5.1 在范围内");
        assertEquals("DOWN", ReportService.indicatorFlag("＞ 5.0", "4.9"));
        assertNull(ReportService.indicatorFlag("阴性", "阳性"), "非数值结果不标");
        assertNull(ReportService.indicatorFlag("3.5–9.5", "见备注"), "结果解析失败不标");
        assertNull(ReportService.indicatorFlag(null, "5.0"));
    }

    // ---------------------------------------------------------------- 位置树（components.md §8）

    @Test
    void reportLocationsTreeCarriesBaselineCategoriesAndRecordSections() {
        RecordContext taskRecord = record(88L, "第2年随访", "TASK", LocalDate.of(2026, 6, 10), 2);
        RecordContext backfill = record(89L, null, "BACKFILL", LocalDate.of(2025, 1, 15), 1);
        when(mapper.recordsOfPatient(PATIENT))
                .thenReturn(List.of(backfill.rows().get(0), taskRecord.rows().get(0)));

        List<ReportService.LocationNodeView> roots = service.reportLocations(PATIENT);

        assertEquals(2, roots.size(), "基线资料 + 随访记录两个分组根");
        ReportService.LocationNodeView baseline = roots.get(0);
        assertEquals("基线资料", baseline.title());
        assertTrue(baseline.selectable() == false, "分组根不可选");
        assertEquals(2, baseline.children().size(), "基线分类 = 模板分类清单");
        assertEquals("baseline:case", baseline.children().get(1).key());
        assertEquals("case", baseline.children().get(1).value().anchorKey());
        assertEquals(3, baseline.children().get(1).value().anchorTemplateVersion());

        ReportService.LocationNodeView followup = roots.get(1);
        assertEquals(2, followup.children().size());
        ReportService.LocationNodeView unplanned = followup.children().get(0);
        assertEquals("历史补录 2025-01-15", unplanned.title(), "无任务记录按来源 + 实际日期命名");
        assertTrue(unplanned.selectable(), "整条记录可选");
        ReportService.LocationNodeView year2 = followup.children().get(1);
        assertEquals("第2年随访 2026-06-10", year2.title());
        assertEquals("88", year2.value().linkRecordId());
        assertEquals("echo", year2.children().get(1).value().anchorKey(), "分组 = 该记录模板版本分类");
    }

    @Test
    void reportLocationsRejectsOutOfScopePatient() {
        when(patientScope.patientIdInScope(PATIENT)).thenReturn(null);

        ApiException error =
                assertThrows(ApiException.class, () -> service.reportLocations(PATIENT));

        assertEquals(40400, error.code());
    }

    // ---------------------------------------------------------------- 患者内清单

    @Test
    void reportsOfFiltersByScopeAndBuildsLocationTags() {
        when(mapper.reportsOfPatient(PATIENT))
                .thenReturn(
                        List.of(
                                new ReportMapper.ReportRow(
                                        900L,
                                        PATIENT,
                                        "ECHO",
                                        "心脏超声",
                                        12L,
                                        "经胸超声心动图（示例）",
                                        LocalDate.of(2026, 10, 28),
                                        null,
                                        null,
                                        null,
                                        null,
                                        0,
                                        OPERATOR,
                                        "管理员（示例）",
                                        "2026-10-28 10:00:00"),
                                new ReportMapper.ReportRow(
                                        901L,
                                        PATIENT,
                                        "LAB",
                                        "检验",
                                        null,
                                        null,
                                        LocalDate.of(2026, 9, 1),
                                        null,
                                        null,
                                        null,
                                        null,
                                        0,
                                        OPERATOR,
                                        "管理员（示例）",
                                        "2026-09-01 09:00:00")));
        RecordContext rec = record(88L, "第2年随访", "TASK", LocalDate.of(2026, 6, 10), 2);
        when(mapper.recordsOfPatient(PATIENT)).thenReturn(rec.rows());
        when(mapper.linksOfPatient(PATIENT))
                .thenReturn(
                        List.of(
                                new ReportMapper.LinkRow(
                                        1L, 900L, "baseline", null, "category", "case", 3, 0),
                                new ReportMapper.LinkRow(
                                        2L, 900L, "followup_record", 88L, "section", "echo", 2, 1),
                                new ReportMapper.LinkRow(
                                        3L, 901L, "followup_record", 88L, null, null, 2, 0)));
        when(mapper.fileCountsOfPatient(PATIENT))
                .thenReturn(List.of(new ReportMapper.FileCountRow(900L, 2L)));

        ReportService.ReportsView all = service.reportsOf(PATIENT, null);
        assertEquals(2, all.items().size());
        ReportService.ReportItemView echo = all.items().get(0);
        assertEquals(2, echo.fileCount());
        assertEquals(2, echo.locations().size());
        assertEquals("病例基本信息", echo.locations().get(0).anchorLabel());
        assertEquals("第2年随访 2026-06-10", echo.locations().get(1).recordLabel());
        assertEquals("超声复查", echo.locations().get(1).anchorLabel());
        assertTrue(echo.locations().get(1).adjusted(), "退回标记随 E16-T15 维护，读出口先带出");

        ReportService.ReportsView baselineOnly = service.reportsOf(PATIENT, "baseline");
        assertEquals(1, baselineOnly.items().size(), "只保留含基线位置的报告单");
        assertEquals("900", baselineOnly.items().get(0).id());

        ReportService.ReportsView followupOnly = service.reportsOf(PATIENT, "followup");
        assertEquals(2, followupOnly.items().size());

        ApiException bad =
                assertThrows(ApiException.class, () -> service.reportsOf(PATIENT, "nope"));
        assertEquals(40001, bad.code());
    }

    // ---------------------------------------------- 批量上传（E16-T03 / A-ATT-014、C-SCAN-007）

    /** 批量请求桩：公共类型 / 名称 / 报告时间 / 位置 + fileIds 与逐份调整（可空）。 */
    private static ReportService.BatchCreateRequest batch(
            String mode, List<String> fileIds, List<ReportService.BatchItemOverride> items) {
        return new ReportService.BatchCreateRequest(
                mode,
                "LAB",
                null,
                LocalDate.of(2026, 10, 28),
                List.of(new ReportService.LocationInput("baseline", null, "category", "basic", 3)),
                fileIds,
                items);
    }

    /** 文件台账桩（全部可挂）+ 报告单自增 ID 桩（900 起）。 */
    private void readyFiles(long... ids) {
        for (long id : ids) {
            when(files.find(id)).thenReturn(file(id, "扫描件-" + id + ".pdf（示例）"));
            when(files.markAttached(id)).thenReturn(1);
        }
        when(mapper.insertReport(any()))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, ReportInsert.class).setId(900L);
                            return 1;
                        });
    }

    @Test
    void batchMergeRejectsFileCountOverPerReportLimit() {
        readyTypeAndName();
        readyFiles(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);

        List<String> eleven = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
        ApiException over =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.batchCreate(
                                        PATIENT,
                                        batch(ReportService.BATCH_MODE_MERGE, eleven, null)));

        assertEquals(40001, over.code(), "验收：单份最多 10 个文件，超限拦截");
        assertTrue(over.getMessage().contains("最多 10 个"), over.getMessage());
        verify(mapper, never()).insertReport(any());
    }

    @Test
    void batchMergeCreatesOneReportWithAllFilesInOrder() {
        readyTypeAndName();
        readyFiles(1, 2, 3);

        ReportService.BatchCreatedView view =
                service.batchCreate(
                        PATIENT,
                        batch(ReportService.BATCH_MODE_MERGE, List.of("1", "2", "3"), null));

        assertEquals("MERGE", view.mode());
        assertEquals(1, view.reportCount(), "合并为一份多页资料");
        assertEquals(3, view.fileCount());
        assertEquals(3, view.reports().get(0).fileCount());
        verify(mapper, times(1)).insertReport(any());
        ArgumentCaptor<ReportMapper.FileInsert> fileRows =
                ArgumentCaptor.forClass(ReportMapper.FileInsert.class);
        verify(mapper, times(3)).insertFile(fileRows.capture());
        assertEquals(
                List.of(0, 1, 2),
                fileRows.getAllValues().stream().map(ReportMapper.FileInsert::sortNo).toList(),
                "合并模式按提交顺序排 sortNo（C-SCAN-006 可调整顺序）");
        assertTrue(auditActions().contains("RPT_BATCH_CREATE"));
    }

    @Test
    void batchOnePerFileCreatesReportPerFileWithOverrides() {
        readyTypeAndName();
        readyFiles(601, 602, 603);
        ReportConfigMapper.NameRow urine = nameRow(13L, "LAB", "尿常规（示例）", null, null);
        when(config.findName(13L)).thenReturn(urine);

        ReportService.BatchCreatedView view =
                service.batchCreate(
                        PATIENT,
                        batch(
                                ReportService.BATCH_MODE_ONE_PER_FILE,
                                List.of("601", "602", "603"),
                                List.of(
                                        new ReportService.BatchItemOverride(
                                                "602", "13", LocalDate.of(2026, 9, 9), null))));

        assertEquals("ONE_PER_FILE", view.mode());
        assertEquals(3, view.reportCount(), "每个文件一份");
        assertEquals(3, view.fileCount());
        verify(mapper, times(3)).insertReport(any());
        assertNull(view.reports().get(0).name(), "公共未选名称时该份无名称");
        assertEquals("尿常规（示例）", view.reports().get(1).name(), "逐份调整名称（A-ATT-014）");
        assertEquals(LocalDate.of(2026, 9, 9), view.reports().get(1).reportDate(), "逐份调整报告时间");
        assertEquals(LocalDate.of(2026, 10, 28), view.reports().get(2).reportDate(), "未调整沿用公共值");
        assertTrue(auditActions().contains("RPT_BATCH_CREATE"));
    }

    @Test
    void batchRejectsOverFiftyFilesEmptyFilesAndUnknownMode() {
        readyTypeAndName();

        List<String> fiftyOne = new ArrayList<>();
        for (long id = 1; id <= 51; id++) {
            fiftyOne.add(String.valueOf(id));
        }
        ApiException overFifty =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.batchCreate(
                                        PATIENT,
                                        batch(
                                                ReportService.BATCH_MODE_ONE_PER_FILE,
                                                fiftyOne,
                                                null)));
        assertEquals(40001, overFifty.code(), "一次最多 50 个文件（A-ATT-014）");
        assertTrue(overFifty.getMessage().contains("50"), overFifty.getMessage());

        ApiException empty =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.batchCreate(
                                        PATIENT,
                                        batch(
                                                ReportService.BATCH_MODE_ONE_PER_FILE,
                                                List.of(),
                                                null)));
        assertEquals(40001, empty.code());

        ApiException unknownMode =
                assertThrows(
                        ApiException.class,
                        () -> service.batchCreate(PATIENT, batch("SIDEWAYS", List.of("1"), null)));
        assertEquals(40001, unknownMode.code());
        verify(mapper, never()).insertReport(any());
    }

    @Test
    void batchRejectsOverrideOutsideFileList() {
        readyTypeAndName();
        readyFiles(601);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.batchCreate(
                                        PATIENT,
                                        batch(
                                                ReportService.BATCH_MODE_ONE_PER_FILE,
                                                List.of("601"),
                                                List.of(
                                                        new ReportService.BatchItemOverride(
                                                                "999", "13", null, null)))));

        assertEquals(40001, error.code());
        verify(mapper, never()).insertReport(any());
    }

    // ---------------------------------------------------------------- 删除（E16-T09 /
    // A-ATT-007、C-SCAN-016：
    // 软删除、原因必填、全部记审计）

    /** 原因必填：空 / 空白 40001「请填写删除原因」，不查报告单、不写审计。 */
    @Test
    void deleteRejectsBlankReason() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.delete(900L, new ReportService.DeleteRequest(" ")));
        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("请填写删除原因"), error.getMessage());
        verify(mapper, never()).findReport(900L);
        verify(audit, never()).append(any(AuditEntry.class));
    }

    /** 越范围 / 不存在的报告单：40400 不暴露存在，写 DENIED 审计（与删除患者同口径）。 */
    @Test
    void deleteOutOfScopeWritesDeniedAuditAnd40400() {
        when(mapper.findReport(900L)).thenReturn(null);
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.delete(900L, new ReportService.DeleteRequest("上传错误，删错患者")));
        assertEquals(40400, error.code());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("RPT_DELETE", captor.getValue().action());
        assertEquals("DENIED", captor.getValue().result().name());
        verify(mapper, never()).softDeleteReport(anyLong(), anyString(), anyLong());
    }

    /** 删除整份报告单：status=DELETED + 原因 / 删除人落列，附件台账一并软删（sys_file DELETED），审计带原因。 */
    @Test
    void deleteSoftDeletesReportWithFilesAndAudits() {
        when(mapper.findReport(900L))
                .thenReturn(
                        new ReportMapper.ReportHeadRow(
                                900L,
                                PATIENT,
                                "ECHO",
                                "心脏超声",
                                "经胸超声心动图（示例）",
                                LocalDate.of(2026, 10, 28)));
        when(mapper.fileRowsOfReport(900L))
                .thenReturn(
                        List.of(
                                new ReportMapper.ReportFileRow(501L, "echo-1.jpg（示例）"),
                                new ReportMapper.ReportFileRow(502L, "echo-2.jpg（示例）")));
        when(mapper.softDeleteReport(900L, "重复上传", OPERATOR)).thenReturn(1);

        ReportService.DeletedView view =
                service.delete(900L, new ReportService.DeleteRequest("重复上传"));

        assertEquals("900", view.id());
        assertEquals("DELETED", view.status());
        verify(mapper).softDeleteReport(900L, "重复上传", OPERATOR);
        verify(files).markDeleted(501L);
        verify(files).markDeleted(502L);
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals("RPT_DELETE", entry.action());
        assertEquals("REPORT", entry.objectType());
        assertEquals("900", entry.objectId());
        assertTrue(entry.description().contains("心脏超声"), entry.description());
        assertTrue(entry.description().contains("经胸超声心动图（示例）"), entry.description());
        assertTrue(entry.description().contains("原因：重复上传"), entry.description());
        assertTrue(entry.afterJson().contains("DELETED"), entry.afterJson());
    }

    /** 并发兜底：校验与更新之间被他人删除（0 行更新）→ 40901，不再软删文件。 */
    @Test
    void deleteAlreadyDeletedReportReturns40901() {
        when(mapper.findReport(900L))
                .thenReturn(
                        new ReportMapper.ReportHeadRow(
                                900L, PATIENT, "ECHO", "心脏超声", null, LocalDate.of(2026, 10, 28)));
        when(mapper.softDeleteReport(900L, "重复上传", OPERATOR)).thenReturn(0);
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.delete(900L, new ReportService.DeleteRequest("重复上传")));
        assertEquals(40901, error.code());
        verify(files, never()).markDeleted(anyLong());
    }

    /** 删除单个文件：sys_file 软删（预览 / 下载随即 40400）、报告单保留，审计带报告单与文件。 */
    @Test
    void deleteFileSoftDeletesLedgerAndAudits() {
        when(mapper.findReport(900L))
                .thenReturn(
                        new ReportMapper.ReportHeadRow(
                                900L,
                                PATIENT,
                                "ECHO",
                                "心脏超声",
                                "经胸超声心动图（示例）",
                                LocalDate.of(2026, 10, 28)));
        when(mapper.findFileOfReport(900L, 501L))
                .thenReturn(new ReportMapper.ReportFileRow(501L, "echo-1.jpg（示例）"));

        ReportService.DeletedView view =
                service.deleteFile(900L, 501L, new ReportService.DeleteRequest("附件模糊不可辨识"));

        assertEquals("900", view.id());
        assertEquals("DELETED", view.status());
        verify(files).markDeleted(501L);
        verify(mapper, never()).softDeleteReport(anyLong(), anyString(), anyLong());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals("RPT_FILE_DELETE", entry.action());
        assertEquals("REPORT", entry.objectType());
        assertEquals("900", entry.objectId());
        assertTrue(entry.description().contains("echo-1.jpg（示例）"), entry.description());
        assertTrue(entry.description().contains("原因：附件模糊不可辨识"), entry.description());
    }

    /** 文件不在该报告单（或已删）：40400；报告单越范围同样 40400 + DENIED。 */
    @Test
    void deleteFileRejectsMissingFileAndOutOfScopeReport() {
        when(mapper.findReport(900L)).thenReturn(null);
        ApiException denied =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.deleteFile(
                                        900L, 501L, new ReportService.DeleteRequest("误传")));
        assertEquals(40400, denied.code());
        verify(audit)
                .append(
                        argThat(
                                entry ->
                                        entry != null
                                                && "RPT_FILE_DELETE".equals(entry.action())
                                                && "DENIED".equals(entry.result().name())));

        when(mapper.findReport(901L))
                .thenReturn(
                        new ReportMapper.ReportHeadRow(
                                901L, PATIENT, "ECHO", "心脏超声", null, LocalDate.of(2026, 10, 28)));
        when(mapper.findFileOfReport(901L, 501L)).thenReturn(null);
        ApiException missing =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.deleteFile(
                                        901L, 501L, new ReportService.DeleteRequest("误传")));
        assertEquals(40400, missing.code());
        verify(files, never()).markDeleted(anyLong());
    }

    // ---------------------------------------------------------------- 修改位置（E16-T13 /
    // A-ATT-020、C-SCAN-020：至少保留一个、增删写操作日志）

    /** 报告单在册 + 两条位置（基线 case 分类、随访记录 88 整条）。 */
    private void readyReportWithTwoLocations() {
        when(mapper.findReport(900L))
                .thenReturn(
                        new ReportMapper.ReportHeadRow(
                                900L,
                                PATIENT,
                                "ECHO",
                                "心脏超声",
                                "经胸超声心动图（示例）",
                                LocalDate.of(2026, 10, 28)));
        RecordContext rec = record(88L, "第2年随访", "TASK", LocalDate.of(2026, 6, 10), 2);
        lenient().when(mapper.recordsOfPatient(PATIENT)).thenReturn(rec.rows());
        when(mapper.linksOfReport(900L))
                .thenReturn(
                        List.of(
                                new ReportMapper.LinkRow(
                                        1L, 900L, "baseline", null, "category", "case", 3, 0),
                                new ReportMapper.LinkRow(
                                        2L, 900L, "followup_record", 88L, null, null, 2, 0)));
    }

    /** 增删同场：add 按当前模板版本校验落行、remove 四元组定位删除、版本 +1、写 RPT_LOCATION_CHANGE 审计。 */
    @Test
    void changeLocationsAddsRemovesBumpsVersionAndAudits() {
        readyReportWithTwoLocations();
        // 第一次调用 = 在册 2 行（基线 case + 记录整条，readyReportWithTwoLocations 同款）；
        // 第二次（执行后回读）= 剩整条记录 + 新增的 echo 分组
        when(mapper.linksOfReport(900L))
                .thenReturn(
                        List.of(
                                new ReportMapper.LinkRow(
                                        1L, 900L, "baseline", null, "category", "case", 3, 0),
                                new ReportMapper.LinkRow(
                                        2L, 900L, "followup_record", 88L, null, null, 2, 0)))
                .thenReturn(
                        List.of(
                                new ReportMapper.LinkRow(
                                        2L, 900L, "followup_record", 88L, null, null, 2, 0),
                                new ReportMapper.LinkRow(
                                        4L,
                                        900L,
                                        "followup_record",
                                        88L,
                                        "section",
                                        "echo",
                                        2,
                                        0)));

        ReportService.LocationsChangedView view =
                service.changeLocations(
                        900L,
                        new ReportService.LocationChangeRequest(
                                List.of(
                                        new ReportService.LocationInput(
                                                "followup_record", 88L, "section", "echo", 2)),
                                List.of(
                                        new ReportService.LocationInput(
                                                "baseline", null, "category", "case", 3))));

        assertEquals("900", view.id());
        assertEquals(2, view.locationCount(), "原 2 个 - 移除 1 个 + 新增 1 个");
        assertEquals("超声复查", view.locations().get(1).anchorLabel(), "回读位置标签");

        ArgumentCaptor<ReportMapper.LinkInsert> linkCaptor =
                ArgumentCaptor.forClass(ReportMapper.LinkInsert.class);
        verify(mapper).insertLink(linkCaptor.capture());
        assertEquals("echo", linkCaptor.getValue().anchorKey());
        assertEquals(PATIENT, linkCaptor.getValue().patientId(), "子表冗余 patient_id 与报告单一致");
        assertEquals(2, linkCaptor.getValue().templateVersion(), "分组锚点版本 = 记录固化版本");
        verify(mapper).deleteLink(900L, "baseline", null, "category", "case");
        verify(mapper).bumpVersion(900L, OPERATOR);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals("RPT_LOCATION_CHANGE", entry.action());
        assertEquals("REPORT", entry.objectType());
        assertEquals("900", entry.objectId());
        assertTrue(entry.description().contains("增加 1 个"), entry.description());
        assertTrue(entry.description().contains("移除 1 个"), entry.description());
        assertTrue(
                entry.description().contains("第2年随访 2026-06-10 · 超声复查"),
                "描述列出增加的位置：" + entry.description());
        assertTrue(entry.description().contains("基线 · 病例基本信息"), "描述列出移除的位置");
        assertTrue(entry.beforeJson().contains("case"), entry.beforeJson());
        assertTrue(entry.afterJson().contains("echo"), entry.afterJson());
    }

    /** 无位置报告单（历史迁移 / 模板调整后失去位置）经 add 补指定第一个位置（A-ATT-020「指定位置」）。 */
    @Test
    void changeLocationsAssignsFirstLocationToUnassignedReport() {
        when(mapper.findReport(900L))
                .thenReturn(
                        new ReportMapper.ReportHeadRow(
                                900L, PATIENT, "ECHO", "心脏超声", null, LocalDate.of(2026, 10, 28)));
        when(mapper.linksOfReport(900L))
                .thenReturn(List.of())
                .thenReturn(
                        List.of(
                                new ReportMapper.LinkRow(
                                        1L, 900L, "baseline", null, "category", "basic", 3, 0)));

        ReportService.LocationsChangedView view =
                service.changeLocations(
                        900L,
                        new ReportService.LocationChangeRequest(
                                List.of(
                                        new ReportService.LocationInput(
                                                "baseline", null, "category", "basic", 3)),
                                List.of()));

        assertEquals(1, view.locationCount());
        verify(mapper, never()).deleteLink(anyLong(), anyString(), any(), anyString(), anyString());
        verify(mapper).bumpVersion(900L, OPERATOR);
        verify(audit)
                .append(
                        argThat(
                                entry ->
                                        entry != null
                                                && "RPT_LOCATION_CHANGE".equals(entry.action())));
    }

    /** 验收：移除全部位置 40001「请至少保留 1 个位置」，不落任何删除。 */
    @Test
    void changeLocationsRejectsRemovingEveryPosition() {
        readyReportWithTwoLocations();

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeLocations(
                                        900L,
                                        new ReportService.LocationChangeRequest(
                                                List.of(),
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "baseline",
                                                                null,
                                                                "category",
                                                                "case",
                                                                3),
                                                        new ReportService.LocationInput(
                                                                "followup_record",
                                                                88L,
                                                                null,
                                                                null,
                                                                2)))));

        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("请至少保留 1 个位置"), error.getMessage());
        verify(mapper, never()).deleteLink(anyLong(), anyString(), any(), anyString(), anyString());
        verify(mapper, never()).insertLink(any());
        verify(mapper, never()).bumpVersion(anyLong(), anyLong());
        verify(audit, never()).append(any(AuditEntry.class));
    }

    /** add / remove 清单都为空 40001；同一位置同时出现在两侧由「add 只收不在册位置」先行拦截（同码 40001）。 */
    @Test
    void changeLocationsRejectsEmptyAndOverlappingLists() {
        readyReportWithTwoLocations();

        ApiException empty =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeLocations(
                                        900L,
                                        new ReportService.LocationChangeRequest(
                                                List.of(), List.of())));
        assertEquals(40001, empty.code());
        assertTrue(empty.getMessage().contains("请增加或移除至少 1 个位置"), empty.getMessage());

        ApiException overlap =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeLocations(
                                        900L,
                                        new ReportService.LocationChangeRequest(
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "followup_record",
                                                                88L,
                                                                null,
                                                                null,
                                                                2)),
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "followup_record",
                                                                88L,
                                                                null,
                                                                null,
                                                                2)))));
        assertEquals(40001, overlap.code(), "add 与 remove 含同一在册位置：add 侧 40001 先拦截");
        assertTrue(overlap.getMessage().contains("已在报告单上"), overlap.getMessage());
    }

    /** 增加的位置须在当前模板版本锚点内（验收：锚点不在当前模板版本时拒绝）。 */
    @Test
    void changeLocationsRejectsAnchorOutsideCurrentTemplateVersion() {
        readyReportWithTwoLocations();

        ApiException unknownCategory =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeLocations(
                                        900L,
                                        new ReportService.LocationChangeRequest(
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "baseline",
                                                                null,
                                                                "category",
                                                                "nope",
                                                                3)),
                                                List.of())));
        assertEquals(40001, unknownCategory.code());
        assertTrue(unknownCategory.getMessage().contains("基线分类不存在"), unknownCategory.getMessage());

        ApiException unknownSection =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeLocations(
                                        900L,
                                        new ReportService.LocationChangeRequest(
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "followup_record",
                                                                88L,
                                                                "section",
                                                                "ghost",
                                                                2)),
                                                List.of())));
        assertEquals(40001, unknownSection.code());
        assertTrue(unknownSection.getMessage().contains("随访分组不存在"), unknownSection.getMessage());

        ApiException foreignRecord =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeLocations(
                                        900L,
                                        new ReportService.LocationChangeRequest(
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "followup_record",
                                                                999L,
                                                                null,
                                                                null,
                                                                2)),
                                                List.of())));
        assertEquals(40400, foreignRecord.code(), "越患者记录不区分不存在 / 他人 / 越范围");
    }

    /** 验收：同一报告单同一位置唯一——重复添加 40001；移除不在册位置 40001。 */
    @Test
    void changeLocationsRejectsDuplicateAddAndUnknownRemove() {
        readyReportWithTwoLocations();

        ApiException duplicate =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeLocations(
                                        900L,
                                        new ReportService.LocationChangeRequest(
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "baseline",
                                                                null,
                                                                "category",
                                                                "case",
                                                                3)),
                                                List.of())));
        assertEquals(40001, duplicate.code());
        assertTrue(duplicate.getMessage().contains("已在报告单上"), duplicate.getMessage());

        ApiException unknownRemove =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeLocations(
                                        900L,
                                        new ReportService.LocationChangeRequest(
                                                List.of(),
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "baseline",
                                                                null,
                                                                "category",
                                                                "basic",
                                                                3)))));
        assertEquals(40001, unknownRemove.code());
        assertTrue(unknownRemove.getMessage().contains("不在报告单上"), unknownRemove.getMessage());

        verify(mapper, never()).insertLink(any());
        verify(mapper, never()).deleteLink(anyLong(), anyString(), any(), anyString(), anyString());
    }

    /** 越范围 / 不存在的报告单：40400 不暴露存在，写 DENIED 审计（与删除同口径）。 */
    @Test
    void changeLocationsOutOfScopeWritesDeniedAuditAnd40400() {
        when(mapper.findReport(900L)).thenReturn(null);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeLocations(
                                        900L,
                                        new ReportService.LocationChangeRequest(
                                                List.of(
                                                        new ReportService.LocationInput(
                                                                "baseline",
                                                                null,
                                                                "category",
                                                                "basic",
                                                                3)),
                                                List.of())));
        assertEquals(40400, error.code());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("RPT_LOCATION_CHANGE", captor.getValue().action());
        assertEquals("DENIED", captor.getValue().result().name());
    }

    /** 已追加的审计动作清单（含每份 RPT_CREATE 与批量的 RPT_BATCH_CREATE）。 */
    private List<String> auditActions() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit, atLeastOnce()).append(captor.capture());
        return captor.getAllValues().stream().map(AuditEntry::action).toList();
    }

    // ---------------------------------------------------------------- 桩与工具

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例租户）",
                OPERATOR,
                "admin.demo",
                "管理员（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of(),
                List.of("followup"),
                1L,
                com.crdp.framework.security.ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                0L,
                null);
    }

    private void readyTypeAndName() {
        ReportConfigMapper.TypeRow type = typeRow("LAB", "检验", false);
        when(config.findTypeIdByCode("LAB")).thenReturn(8L);
        when(config.findType(8L)).thenReturn(type);
    }

    /** 随访记录桩：表单两个分组（概况 / 超声复查），anchorTemplateVersion 与记录固化版本一致。 */
    private RecordContext record(
            long id, String nodeName, String source, LocalDate visit, int version) {
        ReportMapper.RecordRow row =
                new ReportMapper.RecordRow(
                        id,
                        nodeName == null ? null : id + 10,
                        nodeName,
                        source,
                        visit,
                        // 有任务节点的记录带计划日期；无任务记录（补录 / 计划外）计划日期为空
                        nodeName == null ? null : LocalDate.of(2026, 6, 10),
                        "A_FOLLOWUP",
                        version,
                        "SUBMITTED");
        List<FormService.CategoryView> sections =
                List.of(category("overview", "随访概况"), category("echo", "超声复查"));
        lenient()
                .when(formService.schema("A_FOLLOWUP", version))
                .thenReturn(schema(version, sections));
        return new RecordContext(row);
    }

    private record RecordContext(ReportMapper.RecordRow row) {

        List<ReportMapper.RecordRow> rows() {
            return List.of(row());
        }
    }

    private static FormService.SchemaView schema(
            int version, List<FormService.CategoryView> categories) {
        return new FormService.SchemaView(
                "FORM", "表单（示例）", "FOLLOWUP", version, categories, List.of());
    }

    private static FormService.CategoryView category(String key, String label) {
        return new FormService.CategoryView(key, label, 10, List.of());
    }

    private static ReportConfigMapper.TypeRow typeRow(
            String code, String name, boolean nameRequired) {
        return typeRow(code, name, nameRequired, "ENABLED");
    }

    private static ReportConfigMapper.TypeRow typeRow(
            String code, String name, boolean nameRequired, String status) {
        return new ReportConfigMapper.TypeRow(
                9L, code, name, "RPT_" + code, "表单（示例）", nameRequired, null, 10, status, null, 0L);
    }

    private static ReportConfigMapper.NameRow nameRow(
            long id, String typeCode, String name, Long tplId, String override) {
        return new ReportConfigMapper.NameRow(
                id, typeCode, "类型（示例）", name, tplId, "模板（示例）", override, 10, "ENABLED");
    }

    private static ReportConfigMapper.ItemRow item(
            long tplId, String code, String name, String unit, String refRange, int sort) {
        return new ReportConfigMapper.ItemRow(
                1L, tplId, name, code, unit, refRange, null, null, sort);
    }

    private static SysFileRow file(long id, String name) {
        SysFileRow row = new SysFileRow();
        row.setId(id);
        row.setOriginalName(name);
        row.setStatus("PENDING");
        return row;
    }

    private static ReportService.CreateRequest request(
            String typeCode,
            String nameId,
            LocalDate reportDate,
            List<ReportService.LocationInput> locations) {
        return request(typeCode, nameId, reportDate, locations, List.of());
    }

    private static ReportService.CreateRequest request(
            String typeCode,
            String nameId,
            LocalDate reportDate,
            List<ReportService.LocationInput> locations,
            List<String> fileIds) {
        return new ReportService.CreateRequest(
                typeCode,
                nameId,
                reportDate,
                Map.of(),
                List.of(),
                null,
                null,
                locations,
                fileIds,
                null);
    }
}
