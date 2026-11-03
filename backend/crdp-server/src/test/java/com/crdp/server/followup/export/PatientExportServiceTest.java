package com.crdp.server.followup.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.app.PatientSearchService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.system.audit.AuditExportRunner;
import com.crdp.server.system.audit.AuditExportStore;
import com.crdp.server.system.audit.AuditViews.ApprovalAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E13-T09 / A-PAT-009 单元测试（tasks.csv 验收：导出需审批）。锁定口径：租户管理员免审批直接生成、 普通用户待审批、申请人不能自审（40901）、审批
 * CAS（重复审批 40901）、未审批 / 生成中 / 过期下载 40901、他人作业 40400、越范围患者不进入文件（生成走 @DataScope 网关，按冻结范围快照）、
 * 姓名病案号脱敏（OQ-20）、水印（OQ-22）+ 水印码（绑定作业与申请人）、 valueMode 编码 / 显示名、冻结范围缺失失败关闭、EXPORT / APPROVE /
 * DOWNLOAD 审计。 全部虚构数据。
 */
class PatientExportServiceTest {

    private static final long EXPORT_ID = 77L;
    private static final long ADMIN_ID = 5L;
    private static final long USER_ID = 9L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PatientSearchService patientSearch = mock(PatientSearchService.class);
    private final AuditExportStore store = mock(AuditExportStore.class);
    private final AuditExportRunner runner = mock(AuditExportRunner.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final ObjectMapper json = new ObjectMapper();

    private final PatientExportService service =
            new PatientExportService(
                    jdbc, patientSearch, store, runner, audit, input -> sha256Hex(input), json);

    @BeforeEach
    void setUp() {
        TenantContext.useTenant(1L, "demoa");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 提交（验收「导出需审批」）

    @Test
    void tenantAdminSubmitSkipsApprovalAndGeneratesMaskedCsvWithWatermarkCode() {
        login(adminPrincipal());
        when(store.insertJob(
                        any(),
                        any(),
                        eq("PATIENT"),
                        eq("PATIENT"),
                        anyString(),
                        any(),
                        eq(ADMIN_ID),
                        eq("nurse.demo"),
                        eq(AuditExportStore.STATUS_GENERATING)))
                .thenReturn(EXPORT_ID);
        stubJob("GENERATING", ADMIN_ID);
        when(patientSearch.exportRows(any(), eq(50_000))).thenReturn(List.of(row()));
        when(jdbc.queryForList(
                        eq("SELECT display_name FROM sys_user WHERE id = ?"),
                        eq(String.class),
                        eq(ADMIN_ID)))
                .thenReturn(List.of("演示管理"));
        // 异步执行器：同步执行任务，便于断言生成产物
        doAnswer(
                        invocation -> {
                            AuditExportRunner.GenerationTask task = invocation.getArgument(1);
                            task.run(EXPORT_ID);
                            return null;
                        })
                .when(runner)
                .generate(anyLong(), any());

        com.crdp.server.system.audit.AuditViews.ExportSubmitted submitted =
                service.submit(
                        new PatientExportService.ExportRequest(
                                "PATIENT",
                                "LABEL",
                                null,
                                new PatientSearchService.ExportQuery(null, null),
                                "年度报表（示例）"));

        assertEquals(EXPORT_ID, submitted.exportId());
        assertEquals("GENERATING", submitted.status(), "租户管理员本人导出无需审批");
        // 冻结范围已落库（生成按导出人范围取数）
        verify(jdbc)
                .update(
                        eq("UPDATE job_export SET scope_json = ? WHERE id = ?"),
                        anyString(),
                        eq(EXPORT_ID));

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(store)
                .markReady(
                        eq(EXPORT_ID),
                        eq(1L),
                        org.mockito.ArgumentMatchers.startsWith("patient-export-"),
                        anyString(),
                        body.capture(),
                        any(LocalDateTime.class));
        String csv = new String(body.getValue(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("演示管理（nurse.demo）· 虚构示例医院 · "), "首列水印按 OQ-22");
        assertTrue(csv.contains("水印码 " + expectedCode(1L)), "水印码绑定作业 + 申请人 + 行数");
        assertTrue(csv.contains("张*（示例）"), "姓名只出脱敏值（OQ-20）");
        assertTrue(csv.contains("MR**34"), "病案号只出脱敏值（OQ-20）");
        assertTrue(csv.contains("男") && csv.contains("随访中"), "LABEL 模式出显示名");
        assertTrue(csv.startsWith("\uFEFF"), "CSV 带 BOM（Excel 可直接打开）");

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("EXPORT", entry.getValue().accessMethod());
        assertEquals("followup", entry.getValue().module());
        assertEquals(AuditResult.SUCCESS, entry.getValue().result());
        // 生成结束清理合成会话（异步线程不残留身份；本用例同步执行等价覆盖）
        assertEquals(null, SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void normalUserSubmitWaitsForTenantAdminApproval() {
        login(userPrincipal());
        when(store.insertJob(
                        any(),
                        any(),
                        eq("PATIENT"),
                        eq("PATIENT"),
                        anyString(),
                        any(),
                        eq(USER_ID),
                        eq("doctor.demo"),
                        eq(AuditExportStore.STATUS_PENDING)))
                .thenReturn(EXPORT_ID);

        com.crdp.server.system.audit.AuditViews.ExportSubmitted submitted =
                service.submit(
                        new PatientExportService.ExportRequest(null, null, null, null, null));

        assertEquals("PENDING_APPROVAL", submitted.status(), "普通用户导出待租户管理员审批");
        verify(runner, never()).generate(anyLong(), any());
    }

    @Test
    void submitValidatesScopeValueModeFieldsAndReason() {
        login(userPrincipal());

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.submit(
                                                new PatientExportService.ExportRequest(
                                                        "BASELINE", null, null, null, null)))
                        .code(),
                "基线数据随 E13-T02 开放");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.submit(
                                                new PatientExportService.ExportRequest(
                                                        "PATIENT", "MIXED", null, null, null)))
                        .code(),
                "valueMode 只允许 CODE / LABEL");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.submit(
                                                new PatientExportService.ExportRequest(
                                                        "PATIENT",
                                                        null,
                                                        List.of("hack"),
                                                        null,
                                                        null)))
                        .code(),
                "导出列只允许白名单");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.submit(
                                                new PatientExportService.ExportRequest(
                                                        "PATIENT",
                                                        null,
                                                        null,
                                                        null,
                                                        "长".repeat(201))))
                        .code(),
                "申请理由上限 200 字");
        verify(store, never())
                .insertJob(
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        anyLong(),
                        anyString(),
                        anyString());
    }

    // ---------------------------------------------------------------- 审批（申请人不能自审 / CAS）

    @Test
    void approveRejectsSelfApprovalAndNonPendingState() {
        login(adminPrincipal());
        stubJob("PENDING_APPROVAL", ADMIN_ID);
        assertEquals(
                40901,
                assertThrows(
                                ApiException.class,
                                () -> service.approve(EXPORT_ID, new ApprovalAction("自审（示例）")))
                        .code(),
                "申请人不能审批自己的申请");

        stubJob("PENDING_APPROVAL", USER_ID);
        when(store.decide(eq(EXPORT_ID), anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(false);
        assertEquals(
                40901,
                assertThrows(
                                ApiException.class,
                                () -> service.approve(EXPORT_ID, new ApprovalAction("重复审批（示例）")))
                        .code(),
                "不在待审批状态");
        verify(audit, never()).append(any(AuditEntry.class));
    }

    @Test
    void approveDispatchesGenerationWithFrozenApplicantScope() {
        login(adminPrincipal());
        when(store.decide(
                        eq(EXPORT_ID),
                        eq("PENDING_APPROVAL"),
                        eq("GENERATING"),
                        eq(ADMIN_ID),
                        eq("nurse.demo"),
                        eq("同意（示例）")))
                .thenReturn(true);
        stubJob("GENERATING", USER_ID);
        when(patientSearch.exportRows(any(), eq(50_000))).thenReturn(List.of(row()));
        doAnswer(
                        invocation -> {
                            AuditExportRunner.GenerationTask task = invocation.getArgument(1);
                            task.run(EXPORT_ID);
                            return null;
                        })
                .when(runner)
                .generate(anyLong(), any());

        service.approve(EXPORT_ID, new ApprovalAction("同意（示例）"));

        // 审批人（全院范围）不放大导出内容：取数以申请人（USER_ID）冻结范围重建会话（生成成功即锁定）
        verify(store)
                .markReady(
                        eq(EXPORT_ID), eq(1L), anyString(), anyString(), any(byte[].class), any());
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("PATIENT_EXPORT_APPROVE", entry.getValue().action());
        assertEquals("{\"status\":\"PENDING_APPROVAL\"}", entry.getValue().beforeJson());
        assertEquals("{\"status\":\"GENERATING\"}", entry.getValue().afterJson());
    }

    @Test
    void rejectIsTerminalAndAudited() {
        login(adminPrincipal());
        stubJob("PENDING_APPROVAL", USER_ID);
        when(store.decide(
                        eq(EXPORT_ID),
                        eq("PENDING_APPROVAL"),
                        eq("REJECTED"),
                        eq(ADMIN_ID),
                        eq("nurse.demo"),
                        eq("材料不足（示例）")))
                .thenReturn(true);

        service.reject(EXPORT_ID, new ApprovalAction("材料不足（示例）"));

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("PATIENT_EXPORT_REJECT", entry.getValue().action());
        verify(runner, never()).generate(anyLong(), any());
    }

    // ---------------------------------------------------------------- 下载（限时 24 小时）

    @Test
    void downloadGuardsApplicantStateAndExpiry() {
        login(userPrincipal());
        stubJob("PENDING_APPROVAL", USER_ID);
        assertEquals(
                40901,
                assertThrows(ApiException.class, () -> service.download(EXPORT_ID)).code(),
                "未审批不能下载");

        stubJob("GENERATING", USER_ID);
        assertEquals(
                40901,
                assertThrows(ApiException.class, () -> service.download(EXPORT_ID)).code(),
                "生成中不能下载");

        Map<String, Object> expired = stubJob("READY", USER_ID);
        expired.put("expires_at", LocalDateTime.now().minusMinutes(1));
        when(store.job(EXPORT_ID)).thenReturn(expired);
        assertEquals(
                40901,
                assertThrows(ApiException.class, () -> service.download(EXPORT_ID)).code(),
                "超过 24 小时有效期");

        when(store.job(EXPORT_ID)).thenReturn(jobOf("READY", ADMIN_ID));
        assertEquals(
                40400,
                assertThrows(ApiException.class, () -> service.download(EXPORT_ID)).code(),
                "他人作业不暴露存在");

        Map<String, Object> ready = jobOf("READY", USER_ID);
        ready.put("expires_at", LocalDateTime.now().plusHours(23));
        ready.put("file_hash", "hash-it");
        ready.put("row_count", 3L);
        ready.put("file_name", "patient-export-it.csv");
        when(store.job(EXPORT_ID)).thenReturn(ready);
        when(jdbc.queryForObject(
                        eq("SELECT file_body FROM job_export WHERE id = ?"),
                        eq(byte[].class),
                        eq(EXPORT_ID)))
                .thenReturn("csv-it".getBytes(StandardCharsets.UTF_8));

        var file = service.download(EXPORT_ID);

        assertEquals("patient-export-it.csv", file.fileName());
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("DOWNLOAD", entry.getValue().accessMethod());
        assertEquals("hash-it", entry.getValue().fileHash());
        assertEquals(3L, entry.getValue().recordCount());
    }

    @Test
    void generationFailsClosedWithoutFrozenScope() {
        login(adminPrincipal());
        when(store.insertJob(
                        any(),
                        any(),
                        eq("PATIENT"),
                        eq("PATIENT"),
                        anyString(),
                        any(),
                        eq(ADMIN_ID),
                        eq("nurse.demo"),
                        eq(AuditExportStore.STATUS_GENERATING)))
                .thenReturn(EXPORT_ID);
        Map<String, Object> job = stubJob("GENERATING", ADMIN_ID);
        job.put("scope_json", null);
        doAnswer(
                        invocation -> {
                            AuditExportRunner.GenerationTask task = invocation.getArgument(1);
                            task.run(EXPORT_ID);
                            return null;
                        })
                .when(runner)
                .generate(anyLong(), any());

        service.submit(new PatientExportService.ExportRequest("PATIENT", "CODE", null, null, null));

        verify(store).markFailed(eq(EXPORT_ID), org.mockito.ArgumentMatchers.contains("导出上下文缺失"));
        verify(patientSearch, never()).exportRows(any(), anyInt());
    }

    // ---------------------------------------------------------------- 辅助

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private static PatientSearchService.PatientRowView row() {
        return new PatientSearchService.PatientRowView(
                "321",
                "张*（示例）",
                "MR**34",
                "LEG-IT-1",
                "M",
                66,
                "示例一病区",
                "演示医生",
                java.time.LocalDate.of(2026, 1, 12),
                java.time.LocalDate.of(2026, 1, 20),
                null,
                "ACTIVE",
                80,
                null);
    }

    private Map<String, Object> jobOf(String status, long applicantId) {
        Map<String, Object> job = new HashMap<>();
        job.put("id", EXPORT_ID);
        job.put("biz_type", "PATIENT");
        job.put("export_type", "PATIENT");
        job.put("status", status);
        job.put("applicant_id", applicantId);
        job.put("applicant_name", applicantId == ADMIN_ID ? "nurse.demo" : "doctor.demo");
        job.put(
                "condition_json",
                "{\"scope\":\"PATIENT\",\"valueMode\":\"LABEL\",\"fields\":[\"id\",\"name\",\"patientNo\","
                        + "\"legacyNo\",\"gender\",\"age\",\"wardName\",\"doctorName\",\"surgeryDate\","
                        + "\"dischargeDate\",\"nextVisitDate\",\"status\",\"baselineCompleteness\","
                        + "\"followupCompleteness\"],\"filters\":null,\"condition\":null}");
        job.put(
                "scope_json",
                "{\"tenantName\":\"虚构示例医院\",\"scope\":{\"all\":false,\"wardIds\":[3],"
                        + "\"deptIds\":[],\"self\":false,\"projectIds\":[]}}");
        return job;
    }

    /** 显式打桩（避免在 when(...) 内再触发 when 导致 UnfinishedStubbing）。 */
    private Map<String, Object> stubJob(String status, long applicantId) {
        Map<String, Object> job = jobOf(status, applicantId);
        when(store.job(EXPORT_ID)).thenReturn(job);
        return job;
    }

    /** 期望水印码：与生产同式 SHA-256 前 12 位（绑定作业 + 申请人 + 行数）。 */
    private String expectedCode(long rowCount) {
        return sha256Hex("PATIENT-EXPORT:" + EXPORT_ID + ":" + ADMIN_ID + ":" + rowCount)
                .substring(0, 12);
    }

    private static String sha256Hex(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void login(SessionPrincipal principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static SessionPrincipal adminPrincipal() {
        return principal(ADMIN_ID, "nurse.demo", "演示管理", List.of("TENANT_ADMIN"));
    }

    private static SessionPrincipal userPrincipal() {
        return principal(USER_ID, "doctor.demo", "演示医生", List.of("FOLLOWUP_DOCTOR"));
    }

    private static SessionPrincipal principal(
            long userId, String username, String displayName, List<String> roles) {
        return new SessionPrincipal(
                "token-it",
                "HOSPITAL",
                1L,
                "demoa",
                "虚构示例医院",
                userId,
                username,
                displayName,
                roles,
                List.of(),
                List.of("followup:patient:export"),
                List.of("followup"),
                1L,
                ScopeSnapshot.restricted(List.of(3L), List.of(), false),
                false,
                null);
    }
}
