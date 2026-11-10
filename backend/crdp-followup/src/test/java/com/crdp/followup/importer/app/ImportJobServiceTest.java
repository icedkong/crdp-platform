package com.crdp.followup.importer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.importer.app.ImportCommitService.BatchView;
import com.crdp.followup.importer.app.ImportCommitService.ConfirmOptions;
import com.crdp.followup.importer.app.ImportJobService.FileDownload;
import com.crdp.followup.importer.app.ImportJobService.IssuePage;
import com.crdp.followup.importer.app.ImportJobService.JobView;
import com.crdp.followup.importer.app.ImportJobService.UploadedJob;
import com.crdp.followup.importer.infra.mapper.ImportFeatureMapper;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.IssueRow;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.JobRow;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E18-T06 导入 HTTP 用例测试（tasks.csv 验收「上传、校验、提交三步可见进度」的服务面）： 上传（对象存储 + 建作业 + 异步派发预检 + 审计）、功能开关
 * wide_import 40301、模板类型校验、作业视图聚合（状态 / 计数 / 批次进度）、问题分页与级别校验、确认派发 execute、取消与重试门控。
 * 派发器用内联替身（同步执行便于断言异步体）；其余依赖 Mockito 桩。全部虚构数据。
 */
class ImportJobServiceTest {

    private static final long OPERATOR = 7L;

    private static final String OPERATOR_NAME = "doctor.demo";

    private static final long JOB_ID = 42L;

    private final ImportStagingMapper staging = mock(ImportStagingMapper.class);
    private final ImportFeatureMapper features = mock(ImportFeatureMapper.class);
    private final ImportFieldRuleLoader ruleLoader = mock(ImportFieldRuleLoader.class);
    private final ImportStagingService stagingService = mock(ImportStagingService.class);
    private final ImportValidationService validation = mock(ImportValidationService.class);
    private final ImportCommitService commit = mock(ImportCommitService.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final ObjectMapper json = new ObjectMapper();
    private final InlineRunner runner = new InlineRunner();

    private ImportJobService service;

    /** 内联派发替身：异步体同步跑（断言派发到了什么，而不是等线程池）。 */
    private static final class InlineRunner extends ImportDispatchRunner {

        @Override
        public void dispatch(long jobId, ImportTask task) {
            task.run(jobId);
        }
    }

    @BeforeEach
    void setUp() {
        service =
                new ImportJobService(
                        staging,
                        features,
                        ruleLoader,
                        stagingService,
                        validation,
                        commit,
                        storage,
                        audit,
                        json,
                        runner);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        when(features.findEnabled(anyString())).thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 上传与预检派发

    @Test
    void uploadStoresObjectCreatesJobAndDispatchesPrecheck() {
        when(stagingService.createJob(
                        any(ImportStagingService.NewImportFile.class),
                        eq(OPERATOR),
                        eq(OPERATOR_NAME)))
                .thenReturn(JOB_ID);
        byte[] body = "xlsx-bytes（示例）".getBytes();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        when(storage.put(keyCaptor.capture(), eq(body), anyString())).thenReturn("t/demoa/key");
        when(storage.get(anyString())).thenReturn(body);

        UploadedJob uploaded = service.upload("患者导入（示例）.xlsx", body);

        assertEquals(JOB_ID, uploaded.jobId());
        String key = keyCaptor.getValue();
        assertTrue(key.startsWith("imports/"), "对象键落在 imports/ 前缀下：" + key);
        assertTrue(key.endsWith(".xlsx"));
        verify(staging).updateStorageKey(JOB_ID, key);
        // 异步预检从对象存储读回同一字节并派发（OQ-101⑤）
        verify(stagingService).runPrecheck(eq(JOB_ID), any(InputStream.class));
        verify(audit).append(any());
    }

    @Test
    void uploadCleansStoredObjectWhenJobCreationRejected() {
        // E23-T07 演练发现：建作业被拒（40901 已有运行中作业是「想整批重来」时的常态操作）时，
        // 先写对象存储的原始文件无人清理，成为桶内孤儿——拒绝路径必须回收已写对象。
        when(storage.put(anyString(), any(), anyString()))
                .thenReturn("t/demoa/imports/imp-orph.xlsx");
        when(stagingService.createJob(
                        any(ImportStagingService.NewImportFile.class),
                        eq(OPERATOR),
                        eq(OPERATOR_NAME)))
                .thenThrow(new ApiException(409, 40901, "同一租户同一时间只允许一个导入作业，已有运行中的作业"));

        ApiException rejected =
                assertThrows(
                        ApiException.class, () -> service.upload("患者导入（示例）.xlsx", new byte[1]));

        assertEquals(40901, rejected.code(), "拒绝原因原样上抛");
        ArgumentCaptor<String> deleted = ArgumentCaptor.forClass(String.class);
        verify(storage).delete(deleted.capture());
        assertTrue(deleted.getValue().startsWith("imports/"), "清理的是本次刚写的对象键");
        verify(staging, never()).updateStorageKey(anyLong(), anyString());
        verify(stagingService, never()).runPrecheck(anyLong(), any(InputStream.class));
    }

    @Test
    void uploadRejectsBlankFileNameAndEmptyBody() {
        ApiException noName =
                assertThrows(ApiException.class, () -> service.upload(" ", new byte[1]));
        assertEquals(40001, noName.code());
        ApiException noBody =
                assertThrows(
                        ApiException.class, () -> service.upload("患者导入（示例）.xlsx", new byte[0]));
        assertEquals(40001, noBody.code());
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test
    void featureSwitchOffRejectsWith40301() {
        when(features.findEnabled(ImportJobService.FEATURE_WIDE_IMPORT)).thenReturn(false);
        ApiException error =
                assertThrows(
                        ApiException.class, () -> service.upload("患者导入（示例）.xlsx", new byte[1]));
        assertEquals(40301, error.code());
        assertThrows(ApiException.class, () -> service.view(JOB_ID));
        assertThrows(ApiException.class, () -> service.template("STANDARD"));
    }

    // ---------------------------------------------------------------- 模板

    @Test
    void templateDefaultsToStandardAndRejectsUnknownType() {
        when(ruleLoader.load()).thenReturn(rules());
        FileDownload standard = service.template(null);
        assertTrue(standard.fileName().contains("基线"));
        assertTrue(standard.body().length > 0);
        FileDownload wide = service.template("WIDE");
        assertTrue(wide.fileName().contains("宽表"));
        ApiException error = assertThrows(ApiException.class, () -> service.template("OTHER"));
        assertEquals(40001, error.code());
    }

    // ---------------------------------------------------------------- 作业视图与问题分页

    @Test
    void viewAggregatesStatusCountsAndBatchProgress() {
        when(staging.findJob(JOB_ID))
                .thenReturn(
                        new JobRow(
                                JOB_ID,
                                "患者导入（示例）.xlsx",
                                1024,
                                null,
                                "imports/202611/imp-x.xlsx",
                                "WIDE",
                                "IMPORTING",
                                "{\"duplicateStrategy\":\"UPDATE\",\"defaultWardId\":\"5\"}",
                                "{}",
                                120,
                                null,
                                null,
                                OPERATOR,
                                OPERATOR_NAME,
                                LocalDateTime.of(2026, 11, 1, 9, 0),
                                LocalDateTime.of(2026, 11, 1, 9, 5)));
        when(staging.countIssues(JOB_ID, "ERROR")).thenReturn(2L);
        when(staging.countIssues(JOB_ID, "WARN")).thenReturn(3L);
        when(commit.batches(JOB_ID))
                .thenReturn(
                        List.of(
                                new BatchView(1, "DONE", 200, 150, 30, 20, 210, null),
                                new BatchView(2, "FAILED", 200, 0, 0, 0, 0, "批次失败原因（示例）")));

        JobView view = service.view(JOB_ID);

        assertEquals("IMPORTING", view.status());
        assertEquals(120, view.stagedRows());
        assertEquals(2, view.errorCount());
        assertEquals(3, view.warnCount());
        assertEquals("UPDATE", view.duplicateStrategy());
        assertEquals(5L, view.defaultWardId());
        assertEquals(2, view.stats().batches());
        assertEquals(1, view.stats().batchesDone());
        assertEquals(150, view.stats().createdPatients());
        assertEquals(30, view.stats().updatedPatients());
        assertEquals(20, view.stats().skippedPatients());
        assertEquals(210, view.stats().recordsCreated());
        assertEquals(List.of(2), view.stats().failedBatches());
        assertEquals("2026-11-01 09:05", view.updatedAt());
    }

    @Test
    void missingJobIs40400() {
        when(staging.findJob(JOB_ID)).thenReturn(null);
        ApiException error = assertThrows(ApiException.class, () -> service.view(JOB_ID));
        assertEquals(40400, error.code());
    }

    @Test
    void issuesPageValidatesLevelAndPageNo() {
        when(staging.findJob(JOB_ID)).thenReturn(job("PRECHECKED"));
        ApiException badLevel =
                assertThrows(ApiException.class, () -> service.issues(JOB_ID, "FATAL", 1, 20));
        assertEquals(40001, badLevel.code());
        ApiException badPage =
                assertThrows(ApiException.class, () -> service.issues(JOB_ID, null, 0, 20));
        assertEquals(40001, badPage.code());
    }

    @Test
    void issuesPageMapsRowsAndTotals() {
        when(staging.findJob(JOB_ID)).thenReturn(job("PRECHECKED"));
        when(staging.countIssues(JOB_ID, "ERROR")).thenReturn(21L);
        when(staging.findIssues(eq(JOB_ID), eq("ERROR"), eq(20L), eq(20)))
                .thenReturn(
                        List.of(
                                new IssueRow(
                                        1,
                                        "患者数据",
                                        3,
                                        12,
                                        "M",
                                        "手术日期",
                                        "ERROR",
                                        "BAD_DATE",
                                        "不是有效日期（示例原因）")));

        IssuePage page = service.issues(JOB_ID, "ERROR", 2, 20);

        assertEquals(21, page.total());
        assertEquals(2, page.pageNo());
        assertEquals(20, page.pageSize());
        assertEquals(1, page.items().size());
        assertEquals("M", page.items().get(0).columnLetters());
        assertEquals("BAD_DATE", page.items().get(0).code());
    }

    // ---------------------------------------------------------------- 确认 / 取消 / 重试 / 报告

    @Test
    void confirmPreparesInSessionAndDispatchesExecute() {
        ConfirmOptions options = new ConfirmOptions("SKIP", 5L);
        when(commit.execute(JOB_ID))
                .thenReturn(
                        new com.crdp.followup.importer.app.ImportCommitService.CommitSummary(
                                JOB_ID, "DONE", 1, 1, 0, 0, 1, List.of()));

        UploadedJob confirmed = service.confirm(JOB_ID, options);

        assertEquals(JOB_ID, confirmed.jobId());
        verify(commit).prepare(JOB_ID, options);
        verify(commit).execute(JOB_ID);
    }

    @Test
    void confirmPropagatesPrepareGating() {
        doThrow(new ApiException(400, 40001, "校验报告存在错误问题（示例）"))
                .when(commit)
                .prepare(anyLong(), any());
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.confirm(JOB_ID, new ConfirmOptions("UPDATE", null)));
        assertEquals(40001, error.code());
        verify(commit, never()).execute(anyLong());
    }

    @Test
    void cancelOnlyBeforeConfirm() {
        // 第一次 requireJob 读到 PRECHECKED，取消后的 view 再读即 CANCELLED
        when(staging.findJob(JOB_ID)).thenReturn(job("PRECHECKED"), job("CANCELLED"));
        when(staging.findJob(43L)).thenReturn(job("IMPORTING"));
        when(staging.casStatus(JOB_ID, "PRECHECKED", "CANCELLED")).thenReturn(1);
        when(staging.countIssues(eq(JOB_ID), anyString())).thenReturn(0L);
        when(commit.batches(JOB_ID)).thenReturn(List.of());

        JobView cancelled = service.cancel(JOB_ID);
        assertEquals("CANCELLED", cancelled.status());
        verify(audit).append(any());

        ApiException running = assertThrows(ApiException.class, () -> service.cancel(43L));
        assertEquals(40901, running.code());
    }

    @Test
    void retryRequiresPartialFailedAndDispatchesRetryFailed() {
        when(staging.findJob(JOB_ID)).thenReturn(job("PARTIAL_FAILED"));
        when(commit.retryFailed(JOB_ID))
                .thenReturn(
                        new com.crdp.followup.importer.app.ImportCommitService.CommitSummary(
                                JOB_ID, "DONE", 1, 0, 0, 0, 0, List.of()));
        assertEquals(JOB_ID, service.retry(JOB_ID).jobId());
        verify(commit).retryFailed(JOB_ID);

        when(staging.findJob(44L)).thenReturn(job("DONE"));
        ApiException done = assertThrows(ApiException.class, () -> service.retry(44L));
        assertEquals(40901, done.code());
    }

    @Test
    void reportDownloadsAnnotatedReportWithAudit() {
        byte[] body = "report-xlsx（示例）".getBytes();
        when(validation.annotatedReport(JOB_ID)).thenReturn(body);

        FileDownload file = service.report(JOB_ID);

        assertNotNull(file.body());
        assertTrue(file.fileName().contains("校验报告"));
        verify(audit).append(any());
    }

    // ---------------------------------------------------------------- 夹具

    private static JobRow job(String status) {
        return new JobRow(
                JOB_ID,
                "患者导入（示例）.xlsx",
                1024,
                null,
                null,
                null,
                status,
                null,
                null,
                0,
                null,
                null,
                OPERATOR,
                OPERATOR_NAME,
                LocalDateTime.of(2026, 11, 1, 9, 0),
                LocalDateTime.of(2026, 11, 1, 9, 0));
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例租户）",
                OPERATOR,
                OPERATOR_NAME,
                "王医生（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of(),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                0L,
                null);
    }

    private static ImportFieldRuleLoader.ImportFieldRules rules() {
        return new ImportFieldRuleLoader.ImportFieldRules(
                List.of(
                        new com.crdp.followup.importer.domain.ImportFieldRule(
                                "f002",
                                "病案号",
                                "病案号",
                                "basic",
                                "encrypted",
                                true,
                                com.crdp.form.field.domain.FieldType.TEXT,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                true,
                                Map.of(),
                                null)),
                List.of(
                        new com.crdp.followup.importer.domain.ImportFieldRule(
                                "f214",
                                "随访日期",
                                "随访日期",
                                "fu_contact",
                                "json",
                                true,
                                com.crdp.form.field.domain.FieldType.DATE,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                false,
                                Map.of(),
                                null)),
                "f002",
                null,
                "baseline-form",
                "followup-form");
    }
}
