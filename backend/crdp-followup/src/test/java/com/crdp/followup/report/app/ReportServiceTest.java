package com.crdp.followup.report.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
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
import com.crdp.framework.audit.AuditResult;
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
    private final com.crdp.followup.report.infra.mapper.ReportFeatureMapper features =
            mock(com.crdp.followup.report.infra.mapper.ReportFeatureMapper.class);
    private final ReportPatientGateway patientScope = mock(ReportPatientGateway.class);
    private final FormService formService = mock(FormService.class);
    private final FilePolicyService filePolicy = mock(FilePolicyService.class);
    private final SysFileMapper files = mock(SysFileMapper.class);
    private final com.crdp.followup.contact.app.ContactLogService contactLogs =
            mock(com.crdp.followup.contact.app.ContactLogService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final ReportService service =
            new ReportService(
                    mapper,
                    config,
                    features,
                    patientScope,
                    formService,
                    filePolicy,
                    files,
                    contactLogs,
                    audit);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        lenient().when(patientScope.patientIdInScope(PATIENT)).thenReturn(PATIENT);
        // 电子化状态开关默认开（医院 C 形态）；关闭用例在各自测试内覆写（E21-T15）
        lenient().when(features.findEnabled("scan_progress")).thenReturn(true);
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
                                "门诊复查（示例）",
                                null));

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
                                                null,
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
                                                null,
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
                                        null,
                                        LocalDate.of(2026, 10, 28),
                                        null,
                                        null,
                                        null,
                                        null,
                                        1,
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
                                        null,
                                        LocalDate.of(2026, 9, 1),
                                        null,
                                        null,
                                        null,
                                        null,
                                        0,
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
        when(mapper.firstFilesOfPatient(PATIENT))
                .thenReturn(
                        List.of(
                                // sort_no 更小的图片行在前：首附件取 sort_no 最小的一条
                                new ReportMapper.FirstFileRow(900L, 77L, "png"),
                                new ReportMapper.FirstFileRow(900L, 78L, "pdf")));

        ReportService.ReportsView all = service.reportsOf(PATIENT, null);
        assertEquals(2, all.items().size());
        ReportService.ReportItemView echo = all.items().get(0);
        assertEquals(2, echo.fileCount());
        assertEquals("77", echo.firstFile().fileId(), "首附件（左栏 / 内联缩略图数据源，E16-T14）");
        assertEquals("png", echo.firstFile().format());
        assertNull(all.items().get(1).firstFile(), "无附件的报告单 firstFile 为 null");
        assertEquals(2, echo.locations().size());
        assertEquals("病例基本信息", echo.locations().get(0).anchorLabel());
        assertEquals("第2年随访 2026-06-10", echo.locations().get(1).recordLabel());
        assertEquals("超声复查", echo.locations().get(1).anchorLabel());
        assertTrue(echo.locations().get(1).adjusted(), "退回标记随 E16-T15 维护，读出口先带出");
        assertTrue(echo.locationAdjusted(), "报告单级「原位置已调整」标记（V66，E16-T15）");
        assertFalse(all.items().get(1).locationAdjusted(), "未退回的报告单不带标记");

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
        verify(mapper).clearReportLocationAdjusted(900L);
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
        verify(mapper).clearReportLocationAdjusted(900L);
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

    // ---------------------------------------------------------------- 移除随访位置自动回待录入（E21-T27 /
    // C-SCAN-020、C 规格 §7；评审 CL-10）

    /**
     * 已电子化报告单移除其最后一个随访记录位置（整条或分组都算；移除后只剩基线位置）→ 状态自动回「待录入」， 写 RPT_DIGITIZE_CHANGE（自动口径），结构化记录不受影响。
     */
    @Test
    void changeLocationsRevertsDoneToPendingWhenLastFollowupLocationRemoved() {
        readyReportWithTwoLocations();
        when(mapper.findDigitizeRow(900L)).thenReturn(digitizeRow("DONE"));
        // 执行后回读：只剩基线位置（随访 88 整条已被移除）
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
                                        1L, 900L, "baseline", null, "category", "case", 3, 0)));

        service.changeLocations(
                900L,
                new ReportService.LocationChangeRequest(
                        List.of(),
                        List.of(
                                new ReportService.LocationInput(
                                        "followup_record", 88L, null, null, 2))));

        verify(mapper).updateDigitizeStatus(900L, "PENDING", OPERATOR);
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        // RPT_LOCATION_CHANGE（位置增删）+ RPT_DIGITIZE_CHANGE（状态自动回退）各一条
        verify(audit, atLeast(2)).append(captor.capture());
        AuditEntry revert =
                captor.getAllValues().stream()
                        .filter(entry -> "RPT_DIGITIZE_CHANGE".equals(entry.action()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("REPORT", revert.objectType());
        assertEquals("900", revert.objectId());
        assertTrue(revert.description().contains("已电子化 → 待录入"), "描述记自动回退：" + revert.description());
        assertTrue(revert.description().contains("移除随访记录位置"), "描述记触发原因：" + revert.description());
        assertTrue(revert.beforeJson().contains("DONE"), revert.beforeJson());
        assertTrue(revert.afterJson().contains("PENDING"), revert.afterJson());
    }

    /** 不触发回退的三种形态：仍保有另一条随访位置（换记录）、只移除基线位置、当前态本就不是已电子化 —— updateDigitizeStatus 一律不调。 */
    @Test
    void changeLocationsKeepsDigitizeStatusWhenFollowupAnchorRemains() {
        // ① 在册两条随访位置（88 整条 + 89 整条），移除 88 后仍有随访位置
        when(mapper.findReport(900L))
                .thenReturn(
                        new ReportMapper.ReportHeadRow(
                                900L,
                                PATIENT,
                                "ECHO",
                                "心脏超声",
                                "经胸超声心动图（示例）",
                                LocalDate.of(2026, 10, 28)));
        RecordContext both = record(88L, "第2年随访", "TASK", LocalDate.of(2026, 6, 10), 2);
        RecordContext other = record(89L, "第3年随访", "TASK", LocalDate.of(2027, 6, 10), 2);
        when(mapper.recordsOfPatient(PATIENT)).thenReturn(List.of(both.row(), other.row()));
        when(mapper.linksOfReport(900L))
                .thenReturn(
                        List.of(
                                new ReportMapper.LinkRow(
                                        2L, 900L, "followup_record", 88L, null, null, 2, 0),
                                new ReportMapper.LinkRow(
                                        3L, 900L, "followup_record", 89L, null, null, 2, 0)))
                .thenReturn(
                        List.of(
                                new ReportMapper.LinkRow(
                                        3L, 900L, "followup_record", 89L, null, null, 2, 0)));
        service.changeLocations(
                900L,
                new ReportService.LocationChangeRequest(
                        List.of(),
                        List.of(
                                new ReportService.LocationInput(
                                        "followup_record", 88L, null, null, 2))));
        verify(mapper, never()).updateDigitizeStatus(anyLong(), anyString(), anyLong());

        // ② 只移除基线位置（随访位置仍在）——readyReportWithTwoLocations 形态
        reset(mapper, audit);
        readyReportWithTwoLocations();
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
                                        2L, 900L, "followup_record", 88L, null, null, 2, 0)));
        service.changeLocations(
                900L,
                new ReportService.LocationChangeRequest(
                        List.of(),
                        List.of(
                                new ReportService.LocationInput(
                                        "baseline", null, "category", "case", 3))));
        verify(mapper, never()).updateDigitizeStatus(anyLong(), anyString(), anyLong());

        // ③ 移除了最后一个随访位置，但当前态是待录入（无变化不写）
        reset(mapper, audit);
        readyReportWithTwoLocations();
        when(mapper.findDigitizeRow(900L)).thenReturn(digitizeRow("PENDING"));
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
                                        1L, 900L, "baseline", null, "category", "case", 3, 0)));
        service.changeLocations(
                900L,
                new ReportService.LocationChangeRequest(
                        List.of(),
                        List.of(
                                new ReportService.LocationInput(
                                        "followup_record", 88L, null, null, 2))));
        verify(mapper, never()).updateDigitizeStatus(anyLong(), anyString(), anyLong());
    }

    // ---------------------------------------------------------------- 电子化状态手工修改（E21-T15 /
    // C-SCAN-011；验收「状态可手工修改并留痕」）

    /** 电子化状态行（PENDING 当前态，version 4）。 */
    private static ReportMapper.DigitizeRow digitizeRow(String status) {
        return new ReportMapper.DigitizeRow(900L, PATIENT, "程控报告单", "常规律控（示例）", status, 4);
    }

    /** 手工改为「无需录入」须填原因（C-SCAN-011）：落库、version + 1、写 RPT_DIGITIZE_CHANGE 前后值与原因。 */
    @Test
    void changeDigitizeStatusMarksNoEntryWithReasonAndAudits() {
        when(mapper.findDigitizeRow(900L)).thenReturn(digitizeRow("PENDING"));

        ReportService.DigitizeChangedView view =
                service.changeDigitizeStatus(
                        900L,
                        new ReportService.DigitizeChangeRequest("NO_ENTRY", "  检验类资料，无需转录（示例）  "));

        assertEquals("900", view.id());
        assertEquals("NO_ENTRY", view.digitizeStatus());
        assertEquals(5, view.version(), "version + 1（与修改位置同口径）");
        verify(mapper).updateDigitizeStatus(900L, "NO_ENTRY", OPERATOR);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals("RPT_DIGITIZE_CHANGE", entry.action());
        assertEquals("REPORT", entry.objectType());
        assertEquals("900", entry.objectId());
        assertEquals(AuditResult.SUCCESS, entry.result());
        assertTrue(entry.description().contains("待录入 → 无需录入"), entry.description());
        assertTrue(entry.description().contains("原因：检验类资料，无需转录（示例）"), entry.description());
        assertTrue(entry.beforeJson().contains("PENDING"), entry.beforeJson());
        assertTrue(entry.afterJson().contains("NO_ENTRY"), entry.afterJson());
    }

    /** 手工改为「已电子化 / 待录入」不要求原因，但填了照记入日志（留痕完整）。 */
    @Test
    void changeDigitizeStatusToDoneWithoutReasonStillAudits() {
        when(mapper.findDigitizeRow(900L)).thenReturn(digitizeRow("PENDING"));

        service.changeDigitizeStatus(900L, new ReportService.DigitizeChangeRequest("DONE", null));

        verify(mapper).updateDigitizeStatus(900L, "DONE", OPERATOR);
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertTrue(
                captor.getValue().description().contains("待录入 → 已电子化"),
                captor.getValue().description());
        assertFalse(captor.getValue().description().contains("原因："), "无原因不拼原因段");
    }

    /** 当前状态为 NULL（类型 / 名称未配置默认值）也可手工设定，before 记「未设置」。 */
    @Test
    void changeDigitizeStatusFromNullAllowed() {
        when(mapper.findDigitizeRow(900L)).thenReturn(digitizeRow(null));

        ReportService.DigitizeChangedView view =
                service.changeDigitizeStatus(
                        900L, new ReportService.DigitizeChangeRequest("PENDING", null));

        assertEquals("PENDING", view.digitizeStatus());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertTrue(
                captor.getValue().description().contains("未设置 → 待录入"),
                captor.getValue().description());
    }

    /** 改为「无需录入」原因必填（空 / 空白 40001），超 255 字 40001。 */
    @Test
    void changeDigitizeStatusRequiresReasonForNoEntry() {
        when(mapper.findDigitizeRow(900L)).thenReturn(digitizeRow("PENDING"));

        ApiException blank =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeDigitizeStatus(
                                        900L,
                                        new ReportService.DigitizeChangeRequest(
                                                "NO_ENTRY", "   ")));
        assertEquals(40001, blank.code());
        assertTrue(blank.getMessage().contains("改为「无需录入」须填写原因"), blank.getMessage());

        ApiException tooLong =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeDigitizeStatus(
                                        900L,
                                        new ReportService.DigitizeChangeRequest(
                                                "NO_ENTRY", "长".repeat(256))));
        assertEquals(40001, tooLong.code());
        assertTrue(tooLong.getMessage().contains("不能超过 255 字"), tooLong.getMessage());
        verify(mapper, never()).updateDigitizeStatus(anyLong(), anyString(), anyLong());
        verify(audit, never()).append(any(AuditEntry.class));
    }

    /** 取值只收三种；目标与当前一致 40001（无变化不留痕）。 */
    @Test
    void changeDigitizeStatusRejectsInvalidAndUnchangedStatus() {
        when(mapper.findDigitizeRow(900L)).thenReturn(digitizeRow("PENDING"));

        ApiException invalid =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeDigitizeStatus(
                                        900L,
                                        new ReportService.DigitizeChangeRequest(
                                                "DONE!", "非法取值（示例）")));
        assertEquals(40001, invalid.code());
        assertTrue(invalid.getMessage().contains("PENDING"), invalid.getMessage());

        ApiException unchanged =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeDigitizeStatus(
                                        900L,
                                        new ReportService.DigitizeChangeRequest("PENDING", null)));
        assertEquals(40001, unchanged.code());
        assertTrue(unchanged.getMessage().contains("未变化"), unchanged.getMessage());
        verify(mapper, never()).updateDigitizeStatus(anyLong(), anyString(), anyLong());
        verify(audit, never()).append(any(AuditEntry.class));
    }

    /** 开关 scan_progress 关闭（含无行）→ 40301（医院 A / B 不启用，AGENTS §5.7）。 */
    @Test
    void changeDigitizeStatusFeatureOffRejected() {
        when(features.findEnabled("scan_progress")).thenReturn(false);

        ApiException off =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeDigitizeStatus(
                                        900L,
                                        new ReportService.DigitizeChangeRequest(
                                                "NO_ENTRY", "开关关闭（示例）")));
        assertEquals(40301, off.code());

        when(features.findEnabled("scan_progress")).thenReturn(null);
        ApiException noRow =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeDigitizeStatus(
                                        900L,
                                        new ReportService.DigitizeChangeRequest("DONE", null)));
        assertEquals(40301, noRow.code(), "无行视为默认关闭");
        verify(mapper, never()).findDigitizeRow(anyLong());
        verify(audit, never()).append(any(AuditEntry.class));
    }

    /** 越范围 / 不存在 / 已删 40400 并写 DENIED 审计（不暴露对象存在）；请求体缺失 40001。 */
    @Test
    void changeDigitizeStatusOutOfScopeDeniedAndAudited() {
        when(mapper.findDigitizeRow(900L)).thenReturn(null);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.changeDigitizeStatus(
                                        900L,
                                        new ReportService.DigitizeChangeRequest("DONE", null)));
        assertEquals(40400, error.code());
        verify(mapper, never()).updateDigitizeStatus(anyLong(), anyString(), anyLong());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("RPT_DIGITIZE_CHANGE", captor.getValue().action());
        assertEquals(AuditResult.DENIED, captor.getValue().result());
        assertEquals(40400, captor.getValue().errorCode());

        ApiException emptyBody =
                assertThrows(ApiException.class, () -> service.changeDigitizeStatus(900L, null));
        assertEquals(40001, emptyBody.code());
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

    // ---------------------------------------------------------------- 通话记录关联（E16-T08 / A-ATT-017）

    @Test
    void createCallReportWithContactLinkAttachesAudiosAndDerivesLocationFromTaskRecord() {
        readyCallTypeAndName("CALL");
        // 关联的沟通记录：挂任务 9001，录音 6001 / 6002
        when(contactLogs.linkableContact(PATIENT, 5001L))
                .thenReturn(
                        new com.crdp.followup.contact.app.ContactLogService.LinkableContact(
                                5001L,
                                9001L,
                                java.time.LocalDateTime.of(2026, 11, 5, 9, 0),
                                List.of(6001L, 6002L)));
        RecordContext rec = record(88L, "第2年随访", "TASK", LocalDate.of(2026, 6, 10), 2);
        // recordsOfPatient 的行带 taskId 9001（任务对应记录，A-ATT-017 位置预选）
        com.crdp.followup.report.infra.mapper.ReportMapper.RecordRow taskRecord =
                new com.crdp.followup.report.infra.mapper.ReportMapper.RecordRow(
                        88L,
                        9001L,
                        "第2年随访",
                        "TASK",
                        null,
                        LocalDate.of(2026, 6, 10),
                        "A_FOLLOWUP",
                        2,
                        "SUBMITTED");
        when(mapper.recordsOfPatient(PATIENT)).thenReturn(List.of(rec.row(), taskRecord));
        when(mapper.insertReport(any()))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, ReportInsert.class).setId(910L);
                            return 1;
                        });

        ReportService.CreatedView view =
                service.create(
                        PATIENT,
                        new ReportService.CreateRequest(
                                "CALL",
                                "41",
                                LocalDate.of(2026, 11, 5),
                                Map.of(),
                                List.of(),
                                null,
                                null,
                                List.of(), // 位置缺省：自动取任务对应随访记录（整条）
                                List.of(),
                                null,
                                "5001"));

        assertEquals("910", view.id());
        assertEquals(1, view.locationCount(), "位置 = 该沟通关联任务的随访记录（整条）");
        assertEquals(2, view.fileCount(), "关联沟通记录的录音即附件（同一批 sys_file，不重复上传）");

        ArgumentCaptor<ReportInsert> reportCaptor = ArgumentCaptor.forClass(ReportInsert.class);
        verify(mapper).insertReport(reportCaptor.capture());
        assertEquals(Long.valueOf(5001L), reportCaptor.getValue().contactLogId());

        ArgumentCaptor<ReportMapper.LinkInsert> linkCaptor =
                ArgumentCaptor.forClass(ReportMapper.LinkInsert.class);
        verify(mapper).insertLink(linkCaptor.capture());
        assertEquals("followup_record", linkCaptor.getValue().linkType());
        assertEquals(Long.valueOf(88L), linkCaptor.getValue().linkRecordId());
        assertNull(linkCaptor.getValue().anchorType());

        // 录音已 ATTACHED（沟通侧挂载）：只挂关联行，不再改台账状态（A-ATT-017 不重复存储）
        verify(files, never()).markAttached(anyLong());
        verify(mapper, times(2)).insertFile(any());
        verify(audit).append(any(AuditEntry.class));
    }

    @Test
    void createCallReportWithLinkAllowsUnassignedLocationWhenNoTaskRecord() {
        readyCallTypeAndName("CALL");
        when(contactLogs.linkableContact(PATIENT, 5002L))
                .thenReturn(
                        new com.crdp.followup.contact.app.ContactLogService.LinkableContact(
                                5002L,
                                null,
                                java.time.LocalDateTime.of(2026, 11, 5, 9, 0),
                                List.of(6001L)));
        when(mapper.recordsOfPatient(PATIENT)).thenReturn(List.of());
        when(mapper.insertReport(any()))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, ReportInsert.class).setId(911L);
                            return 1;
                        });

        ReportService.CreatedView view =
                service.create(
                        PATIENT,
                        new ReportService.CreateRequest(
                                "CALL",
                                "41",
                                LocalDate.of(2026, 11, 5),
                                Map.of(),
                                List.of(),
                                null,
                                null,
                                List.of(),
                                List.of(),
                                null,
                                "5002"));

        assertEquals(0, view.locationCount(), "无对应记录 → 未指定位置（A-ATT-017 / A-ATT-020）");
        verify(mapper, never()).insertLink(any());
    }

    @Test
    void createRejectsContactLinkOnNonCallType() {
        readyTypeAndName();

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        new ReportService.CreateRequest(
                                                "LAB",
                                                null,
                                                LocalDate.of(2026, 10, 28),
                                                Map.of(),
                                                List.of(),
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
                                                null,
                                                "5001")));

        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("通话记录"), error.getMessage());
        verify(contactLogs, never()).linkableContact(anyLong(), anyLong());
    }

    @Test
    void createWithLinkMergesExplicitUploadsAndAudiosWithinLimit() {
        readyCallTypeAndName("CALL");
        when(contactLogs.linkableContact(PATIENT, 5003L))
                .thenReturn(
                        new com.crdp.followup.contact.app.ContactLogService.LinkableContact(
                                5003L,
                                null,
                                java.time.LocalDateTime.of(2026, 11, 5, 9, 0),
                                List.of(6001L)));
        when(mapper.recordsOfPatient(PATIENT)).thenReturn(List.of());
        SysFileRow uploaded = file(501L, "补充材料（示例）.mp3");
        when(files.find(501L)).thenReturn(uploaded);
        when(files.markAttached(501L)).thenReturn(1);
        when(mapper.insertReport(any()))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, ReportInsert.class).setId(912L);
                            return 1;
                        });

        ReportService.CreatedView view =
                service.create(
                        PATIENT,
                        new ReportService.CreateRequest(
                                "CALL",
                                "41",
                                LocalDate.of(2026, 11, 5),
                                Map.of(),
                                List.of(),
                                null,
                                null,
                                List.of(),
                                List.of("501"),
                                null,
                                "5003"));

        assertEquals(2, view.fileCount(), "新上传 + 关联录音合并（≤ 上限）");
        verify(files).markAttached(501L);
        verify(mapper, times(2)).insertFile(any());
    }

    @Test
    void createWithLinkRejectsWhenAudiosPushOverFileLimit() {
        readyCallTypeAndName("CALL");
        when(contactLogs.linkableContact(PATIENT, 5004L))
                .thenReturn(
                        new com.crdp.followup.contact.app.ContactLogService.LinkableContact(
                                5004L,
                                null,
                                java.time.LocalDateTime.of(2026, 11, 5, 9, 0),
                                // 每份上限 10（桩 UploadPolicy）：新上传 9 个 + 录音 2 个 = 11 超限
                                List.of(6001L, 6002L)));
        when(mapper.recordsOfPatient(PATIENT)).thenReturn(List.of());
        for (long id = 501L; id <= 509L; id++) {
            when(files.find(id)).thenReturn(file(id, "通话片段（示例）.mp3"));
            when(files.markAttached(id)).thenReturn(1);
        }

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        new ReportService.CreateRequest(
                                                "CALL",
                                                "41",
                                                LocalDate.of(2026, 11, 5),
                                                Map.of(),
                                                List.of(),
                                                null,
                                                null,
                                                List.of(),
                                                List.of(
                                                        "501", "502", "503", "504", "505", "506",
                                                        "507", "508", "509"),
                                                null,
                                                "5004")));

        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("超过上限"), error.getMessage());
        verify(mapper, never()).insertReport(any());
    }

    @Test
    void createWithoutLinkStillRequiresAtLeastOneLocation() {
        readyTypeAndName();

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        PATIENT,
                                        new ReportService.CreateRequest(
                                                "LAB",
                                                null,
                                                LocalDate.of(2026, 10, 28),
                                                Map.of(),
                                                List.of(),
                                                null,
                                                null,
                                                List.of(),
                                                List.of(),
                                                null,
                                                null)));

        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("请至少选择 1 个位置"), error.getMessage());
    }

    // ---------------------------------------------------------------- 桩与工具

    /** CALL 类型 + 名称 41 桩（通话记录关联用例共用）。 */
    private void readyCallTypeAndName(String typeCode) {
        when(config.findTypeIdByCode(typeCode)).thenReturn(9L);
        when(config.findType(9L)).thenReturn(typeRow("CALL", "通话记录", true));
        lenient().when(config.findName(41L)).thenReturn(nameRow(41L, "CALL", "随访电话录音", null, null));
    }

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
                null,
                null);
    }
}
