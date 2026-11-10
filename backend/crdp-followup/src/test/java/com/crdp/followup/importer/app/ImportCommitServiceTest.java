package com.crdp.followup.importer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.importer.app.ImportCommitService.BatchView;
import com.crdp.followup.importer.app.ImportCommitService.CommitSummary;
import com.crdp.followup.importer.app.ImportCommitService.ConfirmOptions;
import com.crdp.followup.importer.app.ImportFieldRuleLoader.ImportFieldRules;
import com.crdp.followup.importer.app.ImportRowPayloads.BaselineRow;
import com.crdp.followup.importer.app.ImportRowPayloads.CellValue;
import com.crdp.followup.importer.app.ImportRowPayloads.FollowupGroup;
import com.crdp.followup.importer.app.ImportRowPayloads.FollowupRow;
import com.crdp.followup.importer.app.ImportRowPayloads.WideRow;
import com.crdp.followup.importer.domain.ImportFieldRule;
import com.crdp.followup.importer.domain.ImportWardDirectory;
import com.crdp.followup.importer.domain.ImportWardDirectory.WardEntry;
import com.crdp.followup.importer.infra.mapper.ImportCommitMapper;
import com.crdp.followup.importer.infra.mapper.ImportCommitMapper.BatchInsert;
import com.crdp.followup.importer.infra.mapper.ImportCommitMapper.ImportedRecordInsert;
import com.crdp.followup.importer.infra.mapper.ImportCommitMapper.WriteInsert;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.IssueRow;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.JobInsert;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.JobRow;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.RowInsert;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.StagedRow;
import com.crdp.followup.patient.app.PatientFollowupStatusService;
import com.crdp.followup.patient.app.PatientRegistrationGateway;
import com.crdp.followup.patient.app.PatientStatusHistoryService;
import com.crdp.followup.patient.app.PatientSummaryService;
import com.crdp.followup.patient.infra.mapper.BaselineSectionInsert;
import com.crdp.followup.patient.infra.mapper.BaselineSectionMapper;
import com.crdp.followup.patient.infra.mapper.ContactVersionInsert;
import com.crdp.followup.patient.infra.mapper.PatientContactMapper;
import com.crdp.followup.patient.infra.mapper.PatientInsert;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.plan.infra.mapper.PlanMapper.NodeRow;
import com.crdp.followup.plan.infra.mapper.PlanMapper.PlanRow;
import com.crdp.form.field.domain.FieldType;
import com.crdp.form.history.app.FieldHistoryService;
import com.crdp.form.record.app.FormService;
import com.crdp.form.rule.app.FormDerivedEngine;
import com.crdp.form.rule.app.FormProgressEngine.CategoryProgress;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E18-T05 提交服务测试（tasks.csv 验收「按年度节点生成历史记录，可按批回滚」）：
 *
 * <ul>
 *   <li>门控：非 PRECHECKED 40901、有 ERROR 问题 40001、策略 / 兜底病区非法 40001；
 *   <li>宽表提交：患者主档（实体列 + 密文 / 盲索引）、基线分节（按分类）、入组（不建任务，E14-T04 口径）、年度记录 ——有数据年度
 *       HIST_IMPORT（SUBMITTED、实际随访日期、节点锚点与排期），窗口已结束无数据年度 HIST_MISSED（DRAFT，Q06），窗口未结束不生成；
 *   <li>分批：每 200 名患者一批、每批一个事务、失败批次隔离（PARTIAL_FAILED）与单独重试（DONE）；
 *   <li>重复策略：SKIP 跳过；UPDATE 更新实体列、合并分节并留字段历史（来源=导入）；
 *   <li>回滚：按批删写数（新建患者连主档，更新患者保留主档只删导入子行，OQ-104）；
 *   <li>范围与兜底：冻结病区范围外跳过、病区空用兜底病区 / 无兜底跳过；
 *   <li>死亡：随访记录确认死亡 → 患者状态 DEAD、死亡年度之后不再标历史未随访。
 * </ul>
 *
 * <p>数据库交互用假 / 桩 mapper（暂存与提交 mapper 为内存假实现，患者 / 分节 / 联系方式 insert 用 Mockito 桩捕获参数并回填 id）；目录 / 加密 /
 * 跨子域服务全部 Mockito 桩。全部虚构数据（手机号夹具为 138-0013 虚构测试号段）。
 */
class ImportCommitServiceTest {

    private static final long OPERATOR = 7L;

    private static final String OPERATOR_NAME = "导入账号（示例）";

    private static final long PLAN_ID = 11L;

    private static final long NODE_ID = 2L;

    private static final long ENROLLMENT_ID = 77L;

    private static final long WARD_ID = 5L;

    private static final LocalDate TODAY = LocalDate.now();

    private final FakeStagingMapper staging = new FakeStagingMapper();
    private final FakeCommitMapper commit = new FakeCommitMapper(staging);
    private final ObjectMapper json = new ObjectMapper();

    private final CryptoService crypto = mock(CryptoService.class);
    private final ImportFieldRuleLoader ruleLoader = mock(ImportFieldRuleLoader.class);
    private final ImportWardDirectoryLoader wardDirectoryLoader =
            mock(ImportWardDirectoryLoader.class);
    private final PlanMapper planMapper = mock(PlanMapper.class);
    private final PlanEnrollmentService enrollments = mock(PlanEnrollmentService.class);
    private final PatientRegistrationGateway gateway = mock(PatientRegistrationGateway.class);
    private final PatientMapper patientMapper = mock(PatientMapper.class);
    private final BaselineSectionMapper sectionMapper = mock(BaselineSectionMapper.class);
    private final PatientContactMapper contactMapper = mock(PatientContactMapper.class);
    private final PatientStatusHistoryService statusHistory =
            mock(PatientStatusHistoryService.class);
    private final PatientSummaryService summary = mock(PatientSummaryService.class);
    private final PatientFollowupStatusService patientStatus =
            mock(PatientFollowupStatusService.class);
    private final FormService formService = mock(FormService.class);
    private final FieldHistoryService fieldHistory = mock(FieldHistoryService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final List<PatientInsert> insertedPatients = new ArrayList<>();

    private final java.util.concurrent.atomic.AtomicBoolean failNextInsert =
            new java.util.concurrent.atomic.AtomicBoolean();

    private final List<BaselineSectionInsert> insertedSections = new ArrayList<>();

    private final List<ContactVersionInsert> insertedContacts = new ArrayList<>();

    private ImportCommitService service;

    @BeforeEach
    void setUp() {
        login(true);
        when(crypto.encrypt(anyString(), anyString()))
                .thenAnswer(inv -> "enc:" + inv.getArgument(1));
        when(crypto.decrypt(anyString()))
                .thenAnswer(inv -> inv.getArgument(0, String.class).substring(4));
        when(crypto.blindIndex(anyString(), anyString()))
                .thenAnswer(inv -> "blind(" + inv.getArgument(1) + ")");
        when(ruleLoader.load()).thenReturn(rules());
        when(wardDirectoryLoader.load())
                .thenReturn(
                        new ImportWardDirectory(
                                List.of(
                                        ward(WARD_ID, "W01", "心外一病区（示例）"),
                                        ward(6L, "W02", "心外二病区（示例）"))));
        when(planMapper.findAutoEnrollPlans()).thenReturn(List.of(plan()));
        when(planMapper.findById(PLAN_ID)).thenReturn(plan());
        when(planMapper.findNodesByPlanId(PLAN_ID)).thenReturn(nodes());
        when(enrollments.enrollForImport(anyLong(), anyLong(), any(), any()))
                .thenReturn(ENROLLMENT_ID);
        when(gateway.liveIdByMrnBlind(anyString())).thenReturn(null);
        when(formService.publishedVersion(anyString())).thenReturn(3);
        when(formService.progress(anyString(), anyInt(), any(), any()))
                .thenAnswer(
                        inv ->
                                new FormService.ProgressView(
                                        inv.getArgument(0),
                                        3,
                                        List.of(new CategoryProgress("case", "病历（示例）", 1, 2))));
        when(patientMapper.insertPatient(any()))
                .thenAnswer(
                        inv -> {
                            if (failNextInsert.compareAndSet(true, false)) {
                                throw new IllegalStateException("写入失败（示例）");
                            }
                            PatientInsert row = inv.getArgument(0);
                            row.setId(insertedPatients.size() + 1L);
                            insertedPatients.add(row);
                            return 1;
                        });
        when(sectionMapper.insertSection(any()))
                .thenAnswer(
                        inv -> {
                            BaselineSectionInsert row = inv.getArgument(0);
                            row.setId(insertedSections.size() + 1L);
                            insertedSections.add(row);
                            return 1;
                        });
        when(contactMapper.insertVersion(any()))
                .thenAnswer(
                        inv -> {
                            ContactVersionInsert row = inv.getArgument(0);
                            row.setId(insertedContacts.size() + 1L);
                            insertedContacts.add(row);
                            return 1;
                        });
        service = service();
    }

    private ImportCommitService service() {
        return new ImportCommitService(
                staging,
                commit,
                ruleLoader,
                wardDirectoryLoader,
                crypto,
                json,
                new TransactionTemplate(new PassThroughTransactionManager()),
                planMapper,
                enrollments,
                gateway,
                patientMapper,
                sectionMapper,
                contactMapper,
                statusHistory,
                summary,
                patientStatus,
                formService,
                new FormDerivedEngine(),
                fieldHistory,
                audit);
    }

    private static void login(boolean allScope) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal(allScope), null, List.of()));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 门控

    @Test
    void confirmRejectsJobNotPrechecked() {
        long jobId = newJob("UPLOADED");
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.confirm(jobId, new ConfirmOptions("UPDATE", null)));
        assertEquals(40901, error.code());
    }

    @Test
    void confirmRejectsWhenErrorIssuesExist() {
        long jobId = newJob("PRECHECKED");
        staging.errorIssues = 3;
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.confirm(jobId, new ConfirmOptions("UPDATE", null)));
        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("3"));
    }

    @Test
    void confirmRejectsInvalidStrategyAndInvalidDefaultWard() {
        long jobId = newJob("PRECHECKED");
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> service.confirm(jobId, new ConfirmOptions("OVERWRITE", null)))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> service.confirm(jobId, new ConfirmOptions("UPDATE", 99L)))
                        .code(),
                "兜底病区不存在 / 停用 → 40001");
    }

    // ---------------------------------------------------------------- 宽表提交（年度记录生成）

    @Test
    void wideCommitGeneratesYearRecordsEnrollmentAndBaseline() throws Exception {
        long jobId = newJob("PRECHECKED");
        LocalDate discharge = TODAY.minusYears(3).minusDays(30);
        // 第 1 年有数据（随访日期 + EF）、第 2 年全空、第 3 年窗口已结束无数据、第 4 年窗口未结束
        stageWidePatient(
                jobId,
                "MRN001（示例）",
                "张三（示例）",
                discharge,
                Map.of(1, followupGroup(Map.of("f214", "2025-03-18", "f257", "55"))),
                "心外一病区（示例）",
                "13800138000");
        CommitSummary result = service.confirm(jobId, new ConfirmOptions("UPDATE", null));

        assertEquals("DONE", result.status());
        assertEquals(1, result.createdPatients());
        assertEquals(0, result.skippedPatients());
        assertEquals("DONE", staging.job(jobId).status());

        // 患者主档：实体列 + 密文 / 盲索引 + 原系统编号
        PatientInsert patient = insertedPatients.get(0);
        assertEquals("enc:MRN001（示例）", patient.getMrnCipher());
        assertTrue(patient.getNameBlind().startsWith("blind(张三"), patient.getNameBlind());
        assertEquals("M", patient.getGender());
        assertEquals(WARD_ID, patient.getWardId());
        assertEquals(discharge, patient.getDischargeDate());
        assertEquals(discharge.minusDays(10), patient.getSurgeryDate());
        assertEquals("P001（示例）", patient.getLegacyNo());

        // 入组（不建任务）+ 状态时间线 / 摘要（Q35 登记同款卫星行）
        verify(enrollments)
                .enrollForImport(eq(patient.getId()), eq(PLAN_ID), eq(discharge), eq(OPERATOR));
        verify(statusHistory).recordRegistration(eq(patient.getId()), eq(discharge), eq(OPERATOR));
        verify(summary).recompute(eq(patient.getId()), eq(OPERATOR));

        // 年度记录：第 1 年历史导入（SUBMITTED、实际随访日期、节点锚点与排期）、第 2 / 3 年历史未随访（DRAFT，Q06）、
        // 第 4 年窗口未结束不生成（当前窗口由每日调度建任务，A-FU-001）
        List<ImportedRecordInsert> records = commit.records;
        assertEquals(3, records.size());
        ImportedRecordInsert year1 = find(records, 1);
        assertEquals("HIST_IMPORT", year1.getSource());
        assertEquals("SUBMITTED", year1.getStatus());
        assertEquals(LocalDate.parse("2025-03-18"), year1.getVisitDate());
        assertEquals(ENROLLMENT_ID, year1.getEnrollmentId());
        assertEquals(NODE_ID, year1.getNodeId());
        assertEquals(1, year1.getOccurrence());
        assertEquals(1, year1.getFollowupYear());
        assertEquals(discharge.plusYears(1), year1.getPlanDate());
        JsonNode data = json.readTree(year1.getData());
        assertEquals(55.0, data.get("f257").asDouble());
        ImportedRecordInsert year2 = find(records, 2);
        assertEquals("HIST_MISSED", year2.getSource());
        assertEquals("DRAFT", year2.getStatus());
        assertNull(year2.getVisitDate());
        assertEquals("{}", year2.getData());
        assertEquals(3, find(records, 3).getOccurrence());
        assertTrue(records.stream().noneMatch(record -> record.getOccurrence() == 4), "窗口未结束不生成");

        // 基线分节：json 字段按分类落 pt_baseline_section（column / encrypted 不进分节）
        assertEquals(1, insertedSections.size());
        assertEquals("case", insertedSections.get(0).getCategoryKey());
        assertEquals(3, insertedSections.get(0).getTemplateVersion());
        assertEquals(1, insertedSections.get(0).getVersion(), "正式保存口径 version 1");

        // 联系方式：电话1 → 版本表（source=IMPORT，E13-T03 版本语义；生效日期非空——
        // E18-T07 压测演练发现真库 NOT NULL 列漏设会整批失败，此处钉住回归）
        assertEquals(1, insertedContacts.size());
        assertEquals("PHONE_1", insertedContacts.get(0).getSlot());
        assertEquals("IMPORT", insertedContacts.get(0).getSource());
        assertEquals("enc:13800138000", insertedContacts.get(0).getPhoneCipher());
        assertEquals(
                LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")),
                insertedContacts.get(0).getEffectiveDate(),
                "生效日期默认导入当天（与 PatientContactService.addVersion 缺省同口径；"
                        + "业务日期按北京时间，CI 的 UTC 运行器上 LocalDate.now() 会差一天）");
    }

    @Test
    void deadPatientMarkedDeadAndNoMissedYearsAfterDeath() {
        long jobId = newJob("PRECHECKED");
        LocalDate discharge = TODAY.minusYears(3).minusDays(30);
        stageWidePatient(
                jobId,
                "MRN002（示例）",
                "李四（示例）",
                discharge,
                Map.of(
                        1, followupGroup(Map.of("f214", "2025-03-18", "f215", "否", "f257", "55")),
                        2, followupGroup(Map.of("f214", "2026-03-18", "f215", "是"))),
                "心外一病区（示例）",
                null);
        CommitSummary result = service.confirm(jobId, new ConfirmOptions("UPDATE", null));
        assertEquals(1, result.createdPatients());
        // 死亡 → 患者状态 DEAD（A 规格 §5），死亡年度（第 2 年）之后不再标历史未随访
        verify(patientStatus)
                .transition(
                        anyLong(),
                        eq("DEAD"),
                        anyString(),
                        eq("SYSTEM"),
                        eq("ACTIVE"),
                        eq(OPERATOR));
        assertEquals(2, commit.records.size());
        assertTrue(
                commit.records.stream()
                        .allMatch(record -> "HIST_IMPORT".equals(record.getSource())));
    }

    // ---------------------------------------------------------------- 分批与失败隔离

    @Test
    void commitsInBatchesOfTwoHundredPatients() {
        long jobId = newJob("PRECHECKED");
        for (int i = 0; i < 401; i++) {
            stageWidePatient(
                    jobId,
                    "MRN" + i + "（示例）",
                    "患者" + i + "（示例）",
                    TODAY.minusYears(2),
                    Map.of(),
                    "心外一病区（示例）",
                    null);
        }
        CommitSummary result = service.confirm(jobId, new ConfirmOptions("SKIP", null));
        assertEquals("DONE", result.status());
        assertEquals(3, result.batches());
        assertEquals(401, result.createdPatients());
        List<BatchView> batches = service.batches(jobId);
        assertEquals(List.of(200, 200, 1), batches.stream().map(BatchView::patientCount).toList());
        assertEquals(3, batches.stream().filter(batch -> "DONE".equals(batch.status())).count());
        assertEquals(3, commit.runningMarks, "每批一次 RUNNING（批事务外标记）");
    }

    @Test
    void failedBatchIsIsolatedAndRetryable() {
        long jobId = newJob("PRECHECKED");
        stageWidePatient(
                jobId, "MRN-A（示例）", "甲（示例）", TODAY.minusYears(2), Map.of(), "心外一病区（示例）", null);
        for (int i = 0; i < 200; i++) {
            stageWidePatient(
                    jobId,
                    "MRN" + i + "（示例）",
                    "患者" + i + "（示例）",
                    TODAY.minusYears(2),
                    Map.of(),
                    "心外一病区（示例）",
                    null);
        }
        stageWidePatient(
                jobId, "MRN-Z（示例）", "乙（示例）", TODAY.minusYears(2), Map.of(), "心外一病区（示例）", null);
        failNextInsert.set(true); // 首名患者写入失败一次 → 第 1 批失败、第 2 / 3 批照常
        CommitSummary first = service.confirm(jobId, new ConfirmOptions("SKIP", null));
        assertEquals("PARTIAL_FAILED", first.status());
        assertEquals(List.of(1), first.failedBatches(), "第 1 批失败（首患者写入异常）");
        assertEquals(2, first.createdPatients(), "第 2 批（余下 2 名患者）照常完成");
        assertTrue(
                service.batches(jobId).stream().anyMatch(batch -> "FAILED".equals(batch.status())),
                "失败批落 FAILED 并带原因");

        CommitSummary retried = service.retryFailed(jobId);
        assertEquals("DONE", retried.status());
        assertEquals(List.of(), retried.failedBatches());
        assertEquals(202, retried.createdPatients());
        assertTrue(
                service.batches(jobId).stream().allMatch(batch -> "DONE".equals(batch.status())));
    }

    @Test
    void retryRejectsJobWithoutFailedBatches() {
        long jobId = newJob("DONE");
        assertEquals(
                40901, assertThrows(ApiException.class, () -> service.retryFailed(jobId)).code());
    }

    // ---------------------------------------------------------------- 重复策略

    @Test
    void duplicateStrategySkipSkipsExistingPatient() {
        long jobId = newJob("PRECHECKED");
        when(gateway.liveIdByMrnBlind(anyString())).thenReturn(9L);
        stageWidePatient(
                jobId,
                "MRN009（示例）",
                "王五（示例）",
                TODAY.minusYears(2),
                Map.of(1, followupGroup(Map.of("f214", "2025-03-18"))),
                "心外一病区（示例）",
                null);
        CommitSummary result = service.confirm(jobId, new ConfirmOptions("SKIP", null));
        assertEquals(1, result.skippedPatients());
        assertEquals(0, result.createdPatients());
        assertTrue(insertedPatients.isEmpty(), "SKIP：既有患者不动（A-PAT-018）");
        assertTrue(commit.records.isEmpty());
    }

    @Test
    void duplicateStrategyUpdateWritesRecordsWithoutNewPatient() {
        long jobId = newJob("PRECHECKED");
        when(gateway.liveIdByMrnBlind(anyString())).thenReturn(9L);
        commit.patientEntity =
                new ImportCommitMapper.PatientEntityRow(
                        "F",
                        LocalDate.parse("1970-01-01"),
                        WARD_ID,
                        null,
                        null,
                        null,
                        null,
                        "ACTIVE");
        commit.sectionsOf.put(
                9L,
                List.of(
                        new ImportCommitMapper.SectionRow(
                                501L, "case", 3, "{\"f200\":\"旧备注（示例）\"}", null, 1)));
        stageWidePatient(
                jobId,
                "MRN009（示例）",
                "王五（示例）",
                TODAY.minusYears(2).minusDays(30),
                Map.of(1, followupGroup(Map.of("f214", "2025-03-18", "f257", "55"))),
                "心外一病区（示例）",
                null);
        CommitSummary result = service.confirm(jobId, new ConfirmOptions("UPDATE", null));
        assertEquals(1, result.updatedPatients());
        assertTrue(insertedPatients.isEmpty(), "UPDATE：不新建患者");
        assertEquals(List.of(9L), commit.updatedPatients, "实体列更新（性别 F→M）");
        assertTrue(insertedSections.isEmpty(), "既有分类走合并更新，不建新分节");
        assertEquals(1, commit.updatedSections.size(), "分节数据合并更新");
        verify(fieldHistory)
                .logChanges(
                        eq(FieldHistoryService.PT_BASELINE_SECTION),
                        eq(501L),
                        anyString(),
                        anyInt(),
                        any(),
                        any(),
                        any(),
                        anyString());
        assertEquals(2, commit.records.size(), "年度记录照常生成（第 1 年导入 + 第 2 年历史未随访）");
        assertTrue(commit.writes.stream().noneMatch(WriteInsert::createdFlag));
    }

    // ---------------------------------------------------------------- 回滚

    @Test
    void rollbackBatchRemovesCreatedPatientAndAllWrites() {
        long jobId = newJob("PRECHECKED");
        LocalDate discharge = TODAY.minusYears(2).minusDays(30);
        stageWidePatient(
                jobId,
                "MRN001（示例）",
                "张三（示例）",
                discharge,
                Map.of(1, followupGroup(Map.of("f214", "2025-03-18"))),
                "心外一病区（示例）",
                "13800138000");
        service.confirm(jobId, new ConfirmOptions("UPDATE", null));
        long patientId = insertedPatients.get(0).getId();
        assertTrue(commit.deletedPatients.isEmpty(), "回滚前主档在");

        var view = service.rollbackBatch(jobId, 1);
        assertEquals(1, view.deletedPatients());
        assertEquals(2, view.deletedRecords(), "第 1 年历史导入 + 第 2 年历史未随访");
        assertEquals(1, view.deletedEnrollments());
        assertEquals(1, view.deletedSections());
        assertEquals(1, view.deletedContacts());
        assertEquals(List.of(patientId), commit.deletedPatients, "新建患者连主档删除（这批没导过）");
        assertEquals(2, commit.deletedRecords.size());
        assertTrue(commit.statusHistoryDeleted.contains(patientId), "状态时间线随主档删除");
        assertTrue(commit.summaryDeleted.contains(patientId), "当前状态摘要随主档删除");
        assertEquals("ROLLED_BACK", service.batches(jobId).get(0).status());
        assertTrue(commit.writesOf(1L).isEmpty(), "写数清单回滚后清除");
        assertEquals(
                40901,
                assertThrows(ApiException.class, () -> service.rollbackBatch(jobId, 1)).code(),
                "重复回滚 → 40901");
    }

    @Test
    void rollbackJobMarksJobRolledBackAndKeepsUpdatedPatients() {
        long jobId = newJob("PRECHECKED");
        when(gateway.liveIdByMrnBlind(anyString())).thenReturn(9L);
        commit.patientEntity =
                new ImportCommitMapper.PatientEntityRow(
                        "F", null, WARD_ID, null, null, null, null, "ACTIVE");
        stageWidePatient(
                jobId,
                "MRN009（示例）",
                "王五（示例）",
                TODAY.minusYears(2).minusDays(30),
                Map.of(1, followupGroup(Map.of("f214", "2025-03-18"))),
                "心外一病区（示例）",
                null);
        service.confirm(jobId, new ConfirmOptions("UPDATE", null));
        List<com.crdp.followup.importer.app.ImportCommitService.RollbackView> views =
                service.rollbackJob(jobId);
        assertEquals(1, views.size());
        assertTrue(commit.deletedPatients.isEmpty(), "更新患者主档保留（OQ-104）");
        assertEquals("ROLLED_BACK", staging.job(jobId).status());
    }

    // ---------------------------------------------------------------- 范围与兜底病区

    @Test
    void outOfScopeWardIsSkippedAndBlankWardUsesDefault() {
        login(false); // 冻结范围只有心外一（5）
        long jobId = newJob("PRECHECKED");
        // 病区值解析到心外二（id=6）→ 越范围跳过（只能导入本人可见病区，tech-plan §12①）
        stageWidePatient(
                jobId, "MRN-A（示例）", "甲（示例）", TODAY.minusYears(2), Map.of(), "心外二病区（示例）", null);
        // 病区空 + 确认兜底病区 5 → 使用兜底（E18-T04 向导兜底接线点）
        stageWidePatient(jobId, "MRN-B（示例）", "乙（示例）", TODAY.minusYears(2), Map.of(), "", null);
        CommitSummary result = service.confirm(jobId, new ConfirmOptions("UPDATE", WARD_ID));
        assertEquals(1, result.skippedPatients());
        assertEquals(1, result.createdPatients());
        assertEquals(WARD_ID, insertedPatients.get(0).getWardId());
    }

    @Test
    void blankWardWithoutDefaultIsSkipped() {
        long jobId = newJob("PRECHECKED");
        stageWidePatient(jobId, "MRN-C（示例）", "丙（示例）", TODAY.minusYears(2), Map.of(), "", null);
        CommitSummary result = service.confirm(jobId, new ConfirmOptions("UPDATE", null));
        assertEquals(1, result.skippedPatients());
        assertTrue(insertedPatients.isEmpty());
    }

    // ---------------------------------------------------------------- 长表

    @Test
    void longLayoutBaselineAndFollowupRowsGenerateSameRecords() {
        long jobId = newJob("PRECHECKED");
        LocalDate discharge = TODAY.minusYears(2).minusDays(30);
        String blind = stageLongPatient(jobId, "MRN-L（示例）", "赵六（示例）", discharge);
        stageLongFollowup(jobId, blind, 1, Map.of("f214", "2025-03-18", "f257", "55"));
        CommitSummary result = service.confirm(jobId, new ConfirmOptions("UPDATE", null));
        assertEquals(1, result.createdPatients());
        assertEquals("F", insertedPatients.get(0).getGender());
        assertEquals(2, commit.records.size(), "第 1 年历史导入 + 第 2 年历史未随访");
        assertEquals(
                "HIST_IMPORT",
                commit.records.stream()
                        .filter(record -> record.getOccurrence() == 1)
                        .findFirst()
                        .orElseThrow()
                        .getSource());
    }

    // ---------------------------------------------------------------- 夹具

    private static ImportWardDirectory.WardEntry ward(long id, String code, String name) {
        return new WardEntry(id, code, name, "ENABLED", List.of());
    }

    private static PlanRow plan() {
        return new PlanRow(
                PLAN_ID,
                "A_ANNUAL_FU_PLAN",
                "冠心病术后年度随访（示例）",
                1,
                "PUBLISHED",
                "DISCHARGE_DATE",
                "ONGOING",
                null,
                true,
                null,
                0,
                null,
                null);
    }

    private static List<NodeRow> nodes() {
        return List.of(
                new NodeRow(
                        1L,
                        null,
                        "SUBPLAN",
                        "年度随访（示例）",
                        "CYCLE",
                        null,
                        1,
                        "YEAR",
                        "PLAN_START",
                        0,
                        "YEAR",
                        0,
                        "DAY",
                        0,
                        "DAY",
                        0,
                        "DAY",
                        null,
                        null,
                        null,
                        null,
                        null,
                        10),
                new NodeRow(
                        NODE_ID,
                        1L,
                        "NODE",
                        "电话随访（示例）",
                        null,
                        null,
                        null,
                        null,
                        "PLAN_START",
                        1,
                        "YEAR",
                        7,
                        "DAY",
                        7,
                        "DAY",
                        7,
                        "DAY",
                        "FORM",
                        "DOCTOR",
                        "A_ANNUAL_FU",
                        "BACKFILL",
                        null,
                        10));
    }

    /** 与提交服务字段定位同一套字段的规则（值全部虚构；storage / category 是 E18-T05 提交的分流依据）。 */
    private static ImportFieldRules rules() {
        return new ImportFieldRules(
                List.of(
                        rule("f001", "手术日期", FieldType.DATE, "column", "basic"),
                        rule("f002", "病案号", FieldType.TEXT, "encrypted", "basic"),
                        rule("f003", "姓名", FieldType.TEXT, "encrypted", "basic"),
                        optionRule(
                                "f004",
                                "性别",
                                FieldType.SELECT,
                                "column",
                                "basic",
                                Map.of(1, "男", 2, "女")),
                        rule("f007", "病区/中心", FieldType.SELECT, "column", "basic"),
                        rule("f016", "入院日期", FieldType.DATE, "column", "basic"),
                        rule("f017", "出院日期", FieldType.DATE, "column", "basic"),
                        rule("f267", "出生日期", FieldType.DATE, "column", "basic"),
                        rule("f100", "电话1", FieldType.TEXT, "encrypted", "basic"),
                        rule("f200", "手术备注", FieldType.TEXT, "json", "case")),
                List.of(
                        rule("f214", "随访日期", FieldType.DATE, "json", "fu_contact"),
                        optionRule(
                                "f215",
                                "是否死亡",
                                FieldType.RADIO,
                                "json",
                                "fu_contact",
                                Map.of(0, "否", 1, "是")),
                        rule("f257", "EF", FieldType.NUMBER, "json", "fu_contact")),
                "f002",
                "f007",
                "A_BASELINE",
                "A_ANNUAL_FU");
    }

    private static ImportFieldRule rule(
            String key, String label, FieldType type, String storage, String category) {
        return new ImportFieldRule(
                key, label, label, category, storage, true, type, null, null, null, null, null,
                null, false, false, Map.of(), null);
    }

    private static ImportFieldRule optionRule(
            String key,
            String label,
            FieldType type,
            String storage,
            String category,
            Map<Integer, String> options) {
        return new ImportFieldRule(
                key, label, label, category, storage, true, type, null, null, null, null, null,
                null, false, false, options, null);
    }

    private static SessionPrincipal principal(boolean allScope) {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例租户）",
                OPERATOR,
                "doctor.demo",
                "王医生（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of(),
                List.of("followup"),
                1L,
                allScope
                        ? ScopeSnapshot.allowAll()
                        : new ScopeSnapshot(false, List.of(WARD_ID), List.of(), false, List.of()),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                0L,
                null);
    }

    private long newJob(String status) {
        JobInsert row = new JobInsert("患者导入（示例）.xlsx", 10, null, OPERATOR, OPERATOR_NAME);
        staging.insertJob(row);
        if (!"UPLOADED".equals(status)) {
            staging.casStatus(row.getId(), "UPLOADED", status);
        }
        return row.getId();
    }

    private static CellValue cell(String value, int rowNo, int colNo) {
        return new CellValue(value, rowNo, colNo);
    }

    private static Map<String, CellValue> followupGroup(Map<String, String> values) {
        Map<String, CellValue> cells = new LinkedHashMap<>();
        values.forEach((key, value) -> cells.put(key, cell(value, 2, 10)));
        return cells;
    }

    /** 暂存一个宽表患者行（随访组按年展开；全空组由提交侧跳过，A-PAT-018「全空的组跳过」）。 */
    private void stageWidePatient(
            long jobId,
            String mrn,
            String name,
            LocalDate discharge,
            Map<Integer, Map<String, CellValue>> followups,
            String wardValue,
            String phone) {
        Map<String, CellValue> baseline = new LinkedHashMap<>();
        baseline.put("f001", cell(discharge.minusDays(10).toString(), 2, 9));
        baseline.put("f002", cell(mrn, 2, 10));
        baseline.put("f003", cell(name, 2, 11));
        baseline.put("f004", cell("男", 2, 12));
        baseline.put("f016", cell(discharge.minusDays(20).toString(), 2, 13));
        baseline.put("f017", cell(discharge.toString(), 2, 14));
        baseline.put("f200", cell("备注（示例）", 2, 15));
        if (phone != null) {
            baseline.put("f100", cell(phone, 2, 16));
        }
        baseline.put("f007", cell(wardValue, 2, 17));
        List<FollowupGroup> groups = new ArrayList<>();
        int maxYear = followups.keySet().stream().max(Integer::compareTo).orElse(0);
        for (int year = 1; year <= Math.max(maxYear, 3); year++) {
            groups.add(
                    new FollowupGroup(
                            year,
                            year,
                            String.valueOf(year),
                            followups.getOrDefault(year, Map.of())));
        }
        WideRow payload =
                new WideRow(mrn, cell(mrn, 2, 10), "P001（示例）", Map.of(), baseline, groups);
        staging.stageRow(jobId, "患者数据", 2, "PATIENT", "blind(" + mrn + ")", payload);
    }

    private String stageLongPatient(long jobId, String mrn, String name, LocalDate discharge) {
        Map<String, CellValue> baseline = new LinkedHashMap<>();
        baseline.put("f002", cell(mrn, 2, 1));
        baseline.put("f003", cell(name, 2, 2));
        baseline.put("f004", cell("女", 2, 3));
        baseline.put("f017", cell(discharge.toString(), 2, 4));
        baseline.put("f007", cell("心外一病区（示例）", 2, 5));
        String blind = "blind(" + mrn + ")";
        staging.stageRow(
                jobId,
                "基线",
                2,
                "BASELINE",
                blind,
                new BaselineRow(mrn, cell(mrn, 2, 1), "P001（示例）", baseline));
        return blind;
    }

    private void stageLongFollowup(long jobId, String blind, int year, Map<String, String> values) {
        Map<String, CellValue> cells = new LinkedHashMap<>();
        values.forEach((key, value) -> cells.put(key, cell(value, 2, 3)));
        String mrn = blind.substring("blind(".length(), blind.length() - 1);
        staging.stageRow(
                jobId,
                "随访记录",
                2 + year,
                "FOLLOWUP",
                blind,
                new FollowupRow(
                        mrn,
                        cell(mrn, 2 + year, 1),
                        year,
                        String.valueOf(year),
                        cell(String.valueOf(year), 2 + year, 2),
                        cells));
    }

    private static ImportedRecordInsert find(List<ImportedRecordInsert> records, int year) {
        return records.stream()
                .filter(record -> record.getOccurrence() == year)
                .findFirst()
                .orElseThrow();
    }

    /** 事务直通（批事务边界由服务保证，测试里直接执行回调）。 */
    private static final class PassThroughTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {}

        @Override
        public void rollback(TransactionStatus status) {}
    }

    /** 假暂存 mapper（提交侧用到 findJob / casStatus / countIssues；stageRow 为测试注入缝）。 */
    private static final class FakeStagingMapper implements ImportStagingMapper {
        private final Map<Long, MutableJob> jobs = new LinkedHashMap<>();
        private final List<StagedRow> rows = new ArrayList<>();
        private long errorIssues;
        private long sequence = 100;

        private static final class MutableJob {
            long id;
            String status;
            String optionsJson;
            String scopeJson;
        }

        JobRow job(long id) {
            MutableJob job = jobs.get(id);
            return new JobRow(
                    job.id,
                    "患者导入（示例）.xlsx",
                    10,
                    null,
                    null,
                    null,
                    job.status,
                    job.optionsJson,
                    job.scopeJson,
                    rows.size(),
                    null,
                    null,
                    OPERATOR,
                    OPERATOR_NAME,
                    null,
                    null);
        }

        void stageRow(
                long jobId, String sheet, int rowNo, String rowType, String blind, Object payload) {
            try {
                rows.add(
                        new StagedRow(
                                ++sequence,
                                sheet,
                                rowNo,
                                rowType,
                                blind,
                                "enc:" + new ObjectMapper().writeValueAsString(payload)));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public int insertJob(JobInsert row) {
            MutableJob job = new MutableJob();
            job.id = jobs.size() + 1;
            job.status = "UPLOADED";
            row.setId(job.id);
            jobs.put(job.id, job);
            return 1;
        }

        @Override
        public long countActive(List<String> statuses) {
            return 0;
        }

        @Override
        public JobRow findJob(long id) {
            return jobs.containsKey(id) ? job(id) : null;
        }

        @Override
        public int casStatus(long id, String from, String to) {
            MutableJob job = jobs.get(id);
            if (job == null || !from.equals(job.status)) {
                return 0;
            }
            job.status = to;
            return 1;
        }

        @Override
        public int addStagedRows(long id, long delta) {
            return 1;
        }

        @Override
        public int finishPrecheck(long id, String layout, long stagedRows) {
            return 1;
        }

        @Override
        public int failPrecheck(long id, String layout, String problems, String reason) {
            return 1;
        }

        @Override
        public int insertRows(List<RowInsert> batch) {
            return batch.size();
        }

        @Override
        public int deleteRows(long jobId) {
            return 1;
        }

        @Override
        public List<StagedRow> findRows(long jobId, long afterId, int limit) {
            return rows.stream()
                    .filter(row -> row.id() > afterId)
                    .sorted(java.util.Comparator.comparingLong(StagedRow::id))
                    .limit(limit)
                    .toList();
        }

        @Override
        public int insertIssues(List<ImportStagingMapper.IssueInsert> batch) {
            return batch.size();
        }

        @Override
        public int deleteIssues(long jobId) {
            return 1;
        }

        /** E18-T06 问题分页与存储键回填（向导页）：假实现只服务既有用例，不命中断言。 */
        @Override
        public List<IssueRow> findIssues(long jobId, String level, long offset, int limit) {
            return List.of();
        }

        @Override
        public int updateStorageKey(long id, String storageKey) {
            return 0;
        }

        @Override
        public long countIssues(long jobId, String level) {
            return "ERROR".equals(level) ? errorIssues : 0;
        }
    }

    /** 假提交 mapper（批次 / 写数 / 记录 / 更新 / 回滚删除全内存；saveCommitOptions 回写暂存作业）。 */
    private static final class FakeCommitMapper implements ImportCommitMapper {
        private final FakeStagingMapper staging;
        private final List<BatchInsert> batches = new ArrayList<>();
        private final Map<Long, String> batchStatus = new LinkedHashMap<>();
        private final Map<Long, int[]> batchCounts = new LinkedHashMap<>();
        private final Map<Long, String> batchFailReason = new LinkedHashMap<>();
        private final List<WriteInsert> writes = new ArrayList<>();
        private final List<ImportedRecordInsert> records = new ArrayList<>();
        private final List<Long> deletedRecords = new ArrayList<>();
        private final List<Long> deletedEnrollments = new ArrayList<>();
        private final List<Long> deletedSections = new ArrayList<>();
        private final List<Long> deletedPatients = new ArrayList<>();
        private final List<Long> statusHistoryDeleted = new ArrayList<>();
        private final List<Long> summaryDeleted = new ArrayList<>();
        private final List<Long> updatedPatients = new ArrayList<>();
        private final Map<Long, List<SectionRow>> sectionsOf = new LinkedHashMap<>();
        private final Map<Long, SectionRow> updatedSections = new LinkedHashMap<>();
        private PatientEntityRow patientEntity;
        private int runningMarks;

        private FakeCommitMapper(FakeStagingMapper staging) {
            this.staging = staging;
        }

        List<WriteInsert> writesOf(long batchId) {
            return writes.stream().filter(write -> write.batchId() == batchId).toList();
        }

        @Override
        public int saveCommitOptions(long id, String optionsJson, String scopeJson) {
            FakeStagingMapper.MutableJob job = staging.jobs.get(id);
            job.optionsJson = optionsJson;
            job.scopeJson = scopeJson;
            return 1;
        }

        @Override
        public List<PatientRowRef> findPatientRowRefs(long jobId) {
            return staging.rows.stream()
                    .filter(
                            row ->
                                    ("PATIENT".equals(row.rowType())
                                                    || "BASELINE".equals(row.rowType()))
                                            && row.mrnBlind() != null)
                    .map(row -> new PatientRowRef(row.id(), row.mrnBlind()))
                    .toList();
        }

        @Override
        public List<StagedRow> findPatientRows(long jobId, long afterId, long upToId, int limit) {
            return staging.rows.stream()
                    .filter(
                            row ->
                                    ("PATIENT".equals(row.rowType())
                                                    || "BASELINE".equals(row.rowType()))
                                            && row.id() > afterId
                                            && row.id() <= upToId)
                    .sorted(java.util.Comparator.comparingLong(StagedRow::id))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<StagedRow> findFollowupRows(
                long jobId, List<String> blinds, long afterId, int limit) {
            return staging.rows.stream()
                    .filter(
                            row ->
                                    "FOLLOWUP".equals(row.rowType())
                                            && row.id() > afterId
                                            && blinds.contains(row.mrnBlind()))
                    .sorted(java.util.Comparator.comparingLong(StagedRow::id))
                    .limit(limit)
                    .toList();
        }

        @Override
        public int insertBatch(BatchInsert row) {
            row.setId(batches.size() + 1L);
            batches.add(row);
            batchStatus.put(row.getId(), "PENDING");
            return 1;
        }

        @Override
        public List<BatchRow> findBatches(long jobId) {
            List<BatchRow> result = new ArrayList<>();
            for (BatchInsert batch : batches) {
                if (batch.getJobId() != jobId) {
                    continue;
                }
                int[] counts = batchCounts.getOrDefault(batch.getId(), new int[4]);
                result.add(
                        new BatchRow(
                                batch.getId(),
                                jobId,
                                batch.getBatchNo(),
                                batch.getFirstPatientRowId(),
                                batch.getLastPatientRowId(),
                                batch.getPatientCount(),
                                batchStatus.get(batch.getId()),
                                counts[0],
                                counts[1],
                                counts[2],
                                counts[3],
                                batchFailReason.get(batch.getId()),
                                null));
            }
            return result;
        }

        @Override
        public BatchRow findBatch(long jobId, int batchNo) {
            return findBatches(jobId).stream()
                    .filter(batch -> batch.batchNo() == batchNo)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public int markBatchRunning(long id) {
            String status = batchStatus.get(id);
            if (!"PENDING".equals(status) && !"FAILED".equals(status)) {
                return 0;
            }
            batchStatus.put(id, "RUNNING");
            runningMarks++;
            return 1;
        }

        @Override
        public int markBatchDone(long id, int created, int updated, int skipped, int recordsCount) {
            batchStatus.put(id, "DONE");
            batchCounts.put(id, new int[] {created, updated, skipped, recordsCount});
            return 1;
        }

        @Override
        public int markBatchFailed(long id, String failReason) {
            batchStatus.put(id, "FAILED");
            batchFailReason.put(id, failReason);
            return 1;
        }

        @Override
        public int markBatchRolledBack(long id) {
            batchStatus.put(id, "ROLLED_BACK");
            return 1;
        }

        @Override
        public int insertWrites(List<WriteInsert> rows) {
            writes.addAll(rows);
            return rows.size();
        }

        @Override
        public List<WriteRow> findWrites(long batchId) {
            long sequence = 0;
            List<WriteRow> result = new ArrayList<>();
            for (WriteInsert write : writesOf(batchId)) {
                result.add(
                        new WriteRow(
                                ++sequence,
                                write.batchId(),
                                write.jobId(),
                                write.entityType(),
                                write.entityId(),
                                write.createdFlag()));
            }
            return result;
        }

        @Override
        public int deleteWrites(long batchId) {
            writes.removeIf(write -> write.batchId() == batchId);
            return 1;
        }

        @Override
        public PatientEntityRow findPatientEntity(long id) {
            return patientEntity;
        }

        @Override
        public List<SectionRow> findSections(long patientId) {
            return sectionsOf.getOrDefault(patientId, List.of());
        }

        @Override
        public int updateSectionData(long id, String data, String dataMeta, long updatedBy) {
            updatedSections.put(id, new SectionRow(id, "case", 3, data, dataMeta, 2));
            return 1;
        }

        @Override
        public ContactRow findCurrentContact(long patientId, String slot) {
            return null;
        }

        @Override
        public int updatePatientFromImport(
                long id,
                String gender,
                LocalDate birthDate,
                Long wardId,
                LocalDate surgeryDate,
                LocalDate admissionDate,
                LocalDate dischargeDate,
                String legacyNo,
                long updatedBy) {
            updatedPatients.add(id);
            return 1;
        }

        @Override
        public ImportedRecordRow findImportedYearRecord(long patientId, int year) {
            return null;
        }

        @Override
        public int insertImportedRecord(ImportedRecordInsert row) {
            row.setId(records.size() + 1L);
            records.add(row);
            return 1;
        }

        @Override
        public int updateImportedRecord(
                long id,
                String source,
                String status,
                LocalDate visitDate,
                String data,
                String dataMeta,
                long updatedBy) {
            return 1;
        }

        @Override
        public int deleteImportedRecord(long id) {
            deletedRecords.add(id);
            return 1;
        }

        @Override
        public int deleteImportedContact(long id) {
            return 1;
        }

        @Override
        public int deleteEnrollment(long id) {
            deletedEnrollments.add(id);
            return 1;
        }

        @Override
        public int deleteBaselineSection(long id) {
            deletedSections.add(id);
            return 1;
        }

        @Override
        public int deleteCreatedPatient(long id) {
            deletedPatients.add(id);
            return 1;
        }

        @Override
        public int deleteStatusHistoryOf(long patientId) {
            statusHistoryDeleted.add(patientId);
            return 1;
        }

        @Override
        public int deleteSummaryOf(long patientId) {
            summaryDeleted.add(patientId);
            return 1;
        }
    }
}
