package com.crdp.followup.report.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.file.app.FilePolicyService;
import com.crdp.followup.file.app.FileUploadService;
import com.crdp.followup.report.app.MobileUploadTokenStore.Issued;
import com.crdp.followup.report.app.MobileUploadTokenStore.Payload;
import com.crdp.followup.report.app.ReportService.CreatedView;
import com.crdp.followup.report.app.ReportService.LocationInput;
import com.crdp.followup.report.app.ReportService.LocationNodeView;
import com.crdp.followup.report.app.ReportService.LocationValueView;
import com.crdp.followup.report.infra.mapper.MobileUploadMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E16-T10 单测锁定（A-ATT-015 / C-SCAN-008 / C-NFR-006，全部虚构数据）：一次性二维码签发（范围外患者 40400）、H5 信息（脱敏 + 位置下拉 +
 * 预选）、上传（附件上限）、提交（复用电脑端创建、附件取令牌清单、令牌作废）、过期 / 已用统一 40101、公开口限流。
 */
@ExtendWith(MockitoExtension.class)
class MobileUploadServiceTest {

    private static final long PATIENT = 42L;

    private static final long OPERATOR = 7L;

    private final MobileUploadTokenStore tokens = mock(MobileUploadTokenStore.class);
    private final MobileUploadProperties properties = new MobileUploadProperties();
    private final MobileUploadMapper mapper = mock(MobileUploadMapper.class);
    private final ReportPatientGateway patientScope = mock(ReportPatientGateway.class);
    private final ReportService reports = mock(ReportService.class);
    private final ReportConfigService catalog = mock(ReportConfigService.class);
    private final FileUploadService uploads = mock(FileUploadService.class);
    private final FilePolicyService filePolicy = mock(FilePolicyService.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private MobileUploadService service;

    @BeforeEach
    void login() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        service =
                new MobileUploadService(
                        tokens,
                        properties,
                        mapper,
                        patientScope,
                        reports,
                        catalog,
                        uploads,
                        filePolicy,
                        crypto,
                        audit,
                        redis);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        lenient().when(patientScope.patientIdInScope(PATIENT)).thenReturn(PATIENT);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 签发（电脑端）

    @Test
    void issueBindsCurrentUserAndPatientAndBuildsQrUrlFromFragment() {
        when(tokens.issue(anyString(), anyLong(), anyString(), anyLong(), any()))
                .thenReturn(new Issued("tok-1", 1_800_000_000L));

        MobileUploadService.IssueView view = service.issue(PATIENT, null);

        assertEquals("tok-1", view.token());
        assertEquals(properties.getMobileBaseUrl() + "/#/u/tok-1", view.qrUrl());
        assertEquals(1_800_000_000L, view.expiresAtEpochSecond());
        verify(audit).append(any(AuditEntry.class));
    }

    @Test
    void issueRejectsPatientOutsideDataScopeWith40400() {
        when(patientScope.patientIdInScope(PATIENT)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> service.issue(PATIENT, null));
        assertEquals(40400, error.code());
        verify(tokens, never()).issue(anyString(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void statusReportsSubmittedWithReportIdForDesktopPolling() {
        when(tokens.find("tok-1"))
                .thenReturn(
                        new Payload(
                                "issuer",
                                1L,
                                "demoa",
                                PATIENT,
                                null,
                                List.of("101"),
                                MobileUploadTokenStore.STATUS_SUBMITTED,
                                "9001"));
        when(tokens.remainingSeconds("tok-1")).thenReturn(30L);

        MobileUploadService.StatusView view = service.status("tok-1");

        assertEquals("SUBMITTED", view.status());
        assertEquals("9001", view.reportId());
    }

    @Test
    void statusReportsExpiredWhenTokenGone() {
        when(tokens.find("tok-1")).thenReturn(null);
        assertEquals("EXPIRED", service.status("tok-1").status());
    }

    // ---------------------------------------------------------------- H5 信息

    @Test
    void infoMasksPatientAndFlattensLocationsWithPresetKey() {
        stubPendingToken(presetLocation());
        when(mapper.findPatientBrief(PATIENT))
                .thenReturn(new MobileUploadMapper.PatientBriefRow("张三密文", "MRN001密文", "示例二病区"));
        when(crypto.decrypt("张三密文")).thenReturn("张小明（示例）");
        when(crypto.decrypt("MRN001密文")).thenReturn("MRN-0001");
        when(reports.reportLocations(PATIENT)).thenReturn(locationTree());
        when(catalog.catalogTypes())
                .thenReturn(
                        List.of(
                                new ReportConfigService.CatalogTypeView(
                                        "ECHO", "心脏超声", true, "PENDING", List.of())));
        when(catalog.catalogNames("ECHO"))
                .thenReturn(
                        List.of(
                                new ReportConfigService.CatalogNameView(
                                        "55", "ECHO", "经胸超声心动图", null, List.of())));
        lenient().when(mapper.findLoginRequired()).thenReturn(false);

        MobileUploadService.InfoView view = service.info("tok-1");

        // 姓名「张*）」（夹具带（示例）标记）、病案号「前 2 后 2」脱敏（SensitiveValueMasker 同列表口径）
        assertEquals("张*）", view.patientName());
        assertEquals("MR****01", view.medicalRecordNo());
        assertEquals("示例二病区", view.wardName());
        assertEquals(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")), view.today());
        // 位置下拉拍平：基线分类 + 随访整条 + 分组，label 带祖先标题
        assertEquals(3, view.locationOptions().size());
        assertEquals("基线资料 · 超声检查", view.locationOptions().get(0).label());
        assertEquals("随访记录 · 第2年随访 2026-06-10", view.locationOptions().get(1).label());
        assertEquals("随访记录 · 第2年随访 2026-06-10 · 电池", view.locationOptions().get(2).label());
        // 预选 = 二维码带来的分类位置（模板版本一致才预选）
        assertEquals("baseline:echo", view.presetKey());
        // 类型含名称必填标记与名称清单
        assertEquals(1, view.types().size());
        assertTrue(view.types().getFirst().nameRequired());
        assertEquals("经胸超声心动图", view.types().getFirst().names().getFirst().name());
    }

    @Test
    void infoDoesNotPresetWhenTemplateVersionDrifted() {
        stubPendingToken(new LocationInput("BASELINE", null, "CATEGORY", "echo", 9) /* 版本已调整 */);
        when(mapper.findPatientBrief(PATIENT))
                .thenReturn(new MobileUploadMapper.PatientBriefRow(null, null, null));
        when(reports.reportLocations(PATIENT)).thenReturn(locationTree());
        lenient().when(catalog.catalogTypes()).thenReturn(List.of());
        lenient().when(mapper.findLoginRequired()).thenReturn(false);

        assertNull(service.info("tok-1").presetKey());
    }

    @Test
    void expiredOrUsedTokenFailsWith40101AndSingleMessage() {
        when(tokens.find("gone")).thenReturn(null);
        when(tokens.find("used"))
                .thenReturn(
                        new Payload(
                                "issuer",
                                1L,
                                "demoa",
                                PATIENT,
                                null,
                                List.of("101"),
                                MobileUploadTokenStore.STATUS_SUBMITTED,
                                "9001"));

        for (String token : List.of("gone", "used")) {
            ApiException error = assertThrows(ApiException.class, () -> service.info(token));
            assertEquals(40101, error.code());
            assertEquals("二维码已过期，请在电脑上重新生成", error.getMessage());
        }
    }

    // ---------------------------------------------------------------- H5 上传 / 提交

    @Test
    void uploadAppendsFileIdIntoTokenAndDelegatesValidationToFileUploadService() {
        stubPendingToken(null);
        when(filePolicy.policy())
                .thenReturn(new FilePolicyService.UploadPolicy(List.of(), 20, 50, 10));
        when(uploads.upload("报告单.jpg", new byte[] {1}))
                .thenReturn(
                        new FileUploadService.FileUploadView(
                                "101", "sha", 1, "image/jpeg", "jpg", "报告单.jpg", 1));

        FileUploadService.FileUploadView view = service.upload("tok-1", "报告单.jpg", new byte[] {1});

        assertEquals("101", view.fileId());
        verify(tokens).appendFile(eq("tok-1"), any(), eq("101"));
    }

    @Test
    void uploadRejectsWhenQueueAlreadyReachesPerReportCap() {
        stubPendingToken(null);
        Payload full =
                new Payload(
                        "issuer",
                        1L,
                        "demoa",
                        PATIENT,
                        null,
                        List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"),
                        MobileUploadTokenStore.STATUS_PENDING,
                        null);
        when(tokens.find("tok-1")).thenReturn(full);
        when(filePolicy.policy())
                .thenReturn(new FilePolicyService.UploadPolicy(List.of(), 20, 50, 10));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.upload("tok-1", "第11张.jpg", new byte[] {1}));
        assertEquals(40001, error.code());
        verify(uploads, never()).upload(anyString(), any());
    }

    @Test
    void submitCreatesReportWithTokenFilesAndConsumesToken() {
        Payload payload = stubPendingToken(null);
        List<LocationInput> locations =
                List.of(new LocationInput("BASELINE", null, "CATEGORY", "echo", 3));
        when(reports.create(eq(PATIENT), any()))
                .thenReturn(
                        new CreatedView(
                                "9001",
                                "ECHO",
                                "心脏超声",
                                "经胸超声心动图",
                                LocalDate.of(2026, 10, 25),
                                1,
                                2));

        CreatedView view =
                service.submit(
                        "tok-1",
                        new MobileUploadService.SubmitCommand(
                                "ECHO", "55", LocalDate.of(2026, 10, 25), locations));

        assertEquals("9001", view.id());
        // 附件取令牌内清单、不由手机端回传（防拼接他人文件）
        ArgumentCaptor<ReportService.CreateRequest> request =
                ArgumentCaptor.forClass(ReportService.CreateRequest.class);
        verify(reports).create(eq(PATIENT), request.capture());
        assertEquals(List.of("101", "102"), request.getValue().fileIds());
        assertEquals(locations, request.getValue().locations());
        assertNull(request.getValue().fields());
        verify(tokens).markSubmitted("tok-1", payload, "9001");
        verify(audit).append(any(AuditEntry.class));
    }

    @Test
    void submitRequiresAtLeastOnePhoto() {
        stubPendingToken(null);
        when(tokens.find("tok-1"))
                .thenReturn(
                        new Payload(
                                "issuer",
                                1L,
                                "demoa",
                                PATIENT,
                                null,
                                List.of(),
                                MobileUploadTokenStore.STATUS_PENDING,
                                null));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.submit(
                                        "tok-1",
                                        new MobileUploadService.SubmitCommand(
                                                "ECHO",
                                                null,
                                                LocalDate.of(2026, 10, 25),
                                                List.of())));
        assertEquals(40001, error.code());
        verify(reports, never()).create(anyLong(), any());
    }

    @Test
    void submitSurfacesDesktopCreateValidationForLocations() {
        stubPendingToken(null);
        when(reports.create(eq(PATIENT), any()))
                .thenThrow(new ApiException(400, 40001, "请至少选择 1 个位置"));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.submit(
                                        "tok-1",
                                        new MobileUploadService.SubmitCommand(
                                                "ECHO",
                                                null,
                                                LocalDate.of(2026, 10, 25),
                                                List.of())));
        assertEquals(40001, error.code());
        // 创建失败不消费令牌（手机端修正后可重试提交）
        verify(tokens, never()).markSubmitted(anyString(), any(), anyString());
    }

    // ---------------------------------------------------------------- 公开口限流

    @Test
    void rateLimitBlocksIpOverHourlyQuota() {
        when(values.increment("crdp:h5-upload:ip:1.2.3.4")).thenReturn(601L);

        ApiException error = assertThrows(ApiException.class, () -> service.rateLimit("1.2.3.4"));
        assertEquals(42900, error.code());
    }

    @Test
    void contextExposesTenantAndLoginRequirementWithoutPatientData() {
        stubPendingToken(null);
        when(mapper.findLoginRequired()).thenReturn(true);

        MobileUploadService.ContextView view = service.context("tok-1");

        assertEquals("demoa", view.tenantCode());
        assertTrue(view.requireLogin());
    }

    // ---------------------------------------------------------------- 桩

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "issuer",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例租户）",
                OPERATOR,
                "doctor.demo",
                "王医生（示例）",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of("followup:scan:upload"),
                List.of("followup"),
                1L,
                com.crdp.framework.security.ScopeSnapshot.allowAll(),
                false,
                null);
    }

    private Payload stubPendingToken(LocationInput preset) {
        Payload payload =
                new Payload(
                        "issuer",
                        1L,
                        "demoa",
                        PATIENT,
                        preset,
                        List.of("101", "102"),
                        MobileUploadTokenStore.STATUS_PENDING,
                        null);
        lenient().when(tokens.find("tok-1")).thenReturn(payload);
        lenient().when(tokens.remainingSeconds("tok-1")).thenReturn(600L);
        return payload;
    }

    private static LocationInput presetLocation() {
        return new LocationInput("BASELINE", null, "CATEGORY", "echo", 3);
    }

    private static List<LocationNodeView> locationTree() {
        LocationValueView baselineEcho =
                new LocationValueView("BASELINE", null, "CATEGORY", "echo", 3);
        LocationValueView recordWhole =
                new LocationValueView("FOLLOWUP_RECORD", "12", null, null, 5);
        LocationValueView recordSection =
                new LocationValueView("FOLLOWUP_RECORD", "12", "SECTION", "battery", 5);
        return List.of(
                new LocationNodeView(
                        "baseline",
                        "基线资料",
                        false,
                        null,
                        List.of(
                                new LocationNodeView(
                                        "baseline:echo", "超声检查", true, baselineEcho, null))),
                new LocationNodeView(
                        "followup",
                        "随访记录",
                        false,
                        null,
                        List.of(
                                new LocationNodeView(
                                        "rec:12",
                                        "第2年随访 2026-06-10",
                                        true,
                                        recordWhole,
                                        List.of(
                                                new LocationNodeView(
                                                        "rec:12:sec:battery",
                                                        "电池",
                                                        true,
                                                        recordSection,
                                                        null))))));
    }
}
