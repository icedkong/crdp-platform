package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.crdp.followup.importer.app.ImportFieldCatalogLoader;
import com.crdp.followup.importer.domain.ImportFieldCatalog;
import com.crdp.followup.importer.domain.WideLayoutAdapter;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * E23-T07 / A-PAT-018：医院 A 导入演练 · 技术执行与问题修复（tasks.csv 验收「演练耗时与回滚方案记录」的可执行形式）。
 *
 * <p>与既有两个演练用例的分工：{@code ImportScale5000IT}（E18-T07）管规模与「可回滚」， {@code
 * ImportRehearsalVerifyIT}（E23-T06）管数据核对（汇总数 + 抽样，不做回滚）；
 * 本用例补齐<strong>演练环的运维面</strong>——工程师在测试环境执行一次完整演练要走的全部技术路径：
 *
 * <ol>
 *   <li>主演练：5000 行 × 761 列宽表（医院样例形状，全虚构）上传 → 预检 0 错误 → 确认提交 DONE（25 批）；
 *   <li>「想整批重来」的第一道闸：提交进行中（IMPORTING）再上传 → 40901（同一租户只允许一个运行中作业， tech-plan §12；被拒上传的孤儿对象清理随 E23-T07
 *       修复并有单测）；
 *   <li>确认前放弃路径：小作业 PRECHECKED → 取消 CANCELLED（进行中不可打断，只能等终态后回滚）；
 *   <li>按批回滚：第 25 批（文件第 4801–5000 行）200 名患者「等于这批没导过」；
 *   <li>整作业回滚：剩余 24 批写入逆序删除 → 作业 ROLLED_BACK、演练队列「等于没导过」（A-PAT-018 流程 5 「可多次重新导入」的前提）；
 *   <li>重新演练：同一批病案号（源表前 250 行）重新上传 → 预检 → 提交 DONE——回滚后可完整重演；
 *   <li>清场：重新演练的作业也整作业回滚，演练环境按产品路径归零（不靠测试自清理）。
 * </ol>
 *
 * <p><strong>演练耗时记录</strong>（验收前半句）：每个阶段计时并在结尾输出「演练记录」表（阶段 / 结果 / 耗时 / 吞吐）， 供按 {@code
 * docs/ops/hospital-a-import-rollback.md} §6 的模板归档；A-NFR-001 不设性能指标， 只记录不做阈值断言。
 * 回滚耗时的库内证据（imp_batch.rolled_back_at、IMPORT_ROLLBACK 审计逐批在册）做断言—— 测试环境现场执行时可按手册 §5 的取证 SQL
 * 从库内重建各阶段耗时。
 *
 * <p>门控 {@code CRDP_IT_MYSQL_PORT}（真实 MySQL，与 ImportScale5000IT 同款，CI 全量恒跑）。 造数全部虚构（AGENTS.md 规则
 * 5：姓名 / 电话带「示例」与 IT- 标记）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportRehearsalOpsIT {

    private static final String PASSWORD = "Demo@2026";

    /** 造数与清理标记（全部虚构数据，规则 5）。 */
    private static final String MARKER = "IT-E23T07";

    /** 主演练文件（5000 行）与「确认前放弃」「重新演练」两台辅助作业的文件名（cleanState 按名清理）。 */
    private static final String FILE_MAIN = "演练Ops5000（示例）.xlsx";

    private static final String FILE_CANCEL = "演练Ops取消2（示例）.xlsx";

    private static final String FILE_REIMPORT = "演练Ops重灌250（示例）.xlsx";

    private static final List<String> FILE_NAMES = List.of(FILE_MAIN, FILE_CANCEL, FILE_REIMPORT);

    /** A-NFR-001 规模：5000 名历史患者（医院 A 演练量级）。 */
    private static final int PATIENTS = 5000;

    /** 重新演练规模：源表前 250 行（同一批病案号，验证回滚后可完整重演；200 名/批 → 2 批）。 */
    private static final int REIMPORT_PATIENTS = 250;

    /** 确认前放弃路径的小作业（2 行，预检秒级）。 */
    private static final int CANCEL_PATIENTS = 2;

    /** 医院样例宽表 10 组随访列（Q05）→ 9 + 212 + 10 × 54 = 761 列。 */
    private static final int FOLLOWUP_GROUPS = 10;

    /** 有数据的随访年度（第 1、2 年；第 3 年窗口已结束无数据 → HIST_MISSED，第 4 年窗口未结束不生成）。 */
    private static final List<Integer> DATA_YEARS = List.of(1, 2);

    /** 每名患者生成的随访记录数（2 个 HIST_IMPORT + 1 个 HIST_MISSED，ImportYearPlanner）。 */
    private static final int RECORDS_PER_PATIENT = 3;

    /** 演示租户（医院 A）与操作账号（TENANT_ADMIN：全部病区范围，冻结范围不跳行）。 */
    private static final String TENANT = "demoa";

    private static final long TENANT_ID = 1L;

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 预检 / 提交轮询上限（与 ImportScale5000IT 同款；A-NFR-001 不设性能指标）。 */
    private static final long PRECHECK_TIMEOUT_MS = 10 * 60_000L;

    private static final long COMMIT_TIMEOUT_MS = 15 * 60_000L;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ImportFieldCatalogLoader catalogLoader;
    @Autowired private ObjectStorage storage;

    /** 演练记录（阶段 / 结果 / 耗时 / 吞吐；测试结尾整表输出，供按手册模板归档）。 */
    private final List<String> drillRecord = new ArrayList<>();

    @BeforeEach
    @AfterAll
    void cleanState() {
        runInTenant(
                () -> {
                    // A 预置计划回到迁移态 auto_enroll=1（V61：历史导入自动入组）——其他 IT 会把它关成 0 且不恢复
                    jdbc.update(
                            "UPDATE fu_plan SET auto_enroll = 1 WHERE code = 'A_ANNUAL_FU_PLAN'");
                    // 作业、暂存行与对象存储文件（storage.delete 用上传时的相对键）
                    String names = String.join("', '", FILE_NAMES);
                    List<Map<String, Object>> jobs =
                            jdbc.queryForList(
                                    "SELECT id, storage_key AS storageKey FROM imp_job WHERE"
                                            + " file_name IN ('"
                                            + names
                                            + "')");
                    for (Map<String, Object> job : jobs) {
                        String key = (String) job.get("storageKey");
                        if (key != null && !key.isBlank()) {
                            storage.delete(key);
                        }
                    }
                    for (String table :
                            List.of("imp_batch_write", "imp_batch", "imp_issue", "imp_row")) {
                        jdbc.update(
                                "DELETE FROM "
                                        + table
                                        + " WHERE job_id IN (SELECT id FROM imp_job WHERE"
                                        + " file_name IN ('"
                                        + names
                                        + "'))");
                    }
                    // 审计只读（PLT-AUD-011）：自清理走维护开关（AuditTableMaintenance，E08-T02 逃生口）
                    com.crdp.server.AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE object_type = 'IMPORT_JOB' AND object_id IN"
                                    + " (SELECT CAST(id AS CHAR) FROM imp_job WHERE file_name IN"
                                    + " ('"
                                    + names
                                    + "'))");
                    jdbc.update("DELETE FROM imp_job WHERE file_name IN ('" + names + "')");
                    // 中断残留的患者按标记清理（子行在前；正常演练后为 0 行——本演练以产品回滚路径清场）
                    deleteCohortChildren();
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", MARKER + "-L%");
                });
    }

    private void deleteCohortChildren() {
        jdbc.update(
                "DELETE fh FROM fe_field_history fh JOIN fu_record fr ON fh.record_type ="
                        + " 'FU_RECORD' AND fh.record_id = fr.id JOIN pt_patient p ON"
                        + " fr.patient_id = p.id WHERE p.legacy_no LIKE ? AND fh.source = 'IMPORT'",
                MARKER + "-L%");
        jdbc.update(
                "DELETE fr FROM fu_record fr JOIN pt_patient p ON fr.patient_id = p.id"
                        + " WHERE p.legacy_no LIKE ?",
                MARKER + "-L%");
        jdbc.update(
                "DELETE c FROM pt_contact c JOIN pt_patient p ON c.patient_id = p.id"
                        + " WHERE p.legacy_no LIKE ?",
                MARKER + "-L%");
        jdbc.update(
                "DELETE sh FROM pt_status_history sh JOIN pt_patient p ON sh.patient_id = p.id"
                        + " WHERE p.legacy_no LIKE ?",
                MARKER + "-L%");
        jdbc.update(
                "DELETE ps FROM pt_patient_summary ps JOIN pt_patient p ON ps.patient_id = p.id"
                        + " WHERE p.legacy_no LIKE ?",
                MARKER + "-L%");
        jdbc.update(
                "DELETE bs FROM pt_baseline_section bs JOIN pt_patient p ON bs.patient_id = p.id"
                        + " WHERE p.legacy_no LIKE ?",
                MARKER + "-L%");
        jdbc.update(
                "DELETE e FROM fu_enrollment e JOIN pt_patient p ON e.patient_id = p.id"
                        + " WHERE p.legacy_no LIKE ?",
                MARKER + "-L%");
    }

    // ---------------------------------------------------------------- 技术执行演练（验收：演练耗时与回滚方案记录）

    /**
     * 演练环：主演练（5000 行）→ 提交中再上传 40901 → 取消路径 → 按批回滚 → 整作业回滚（等于没导过）→ 重新演练（250 行同病案号）→ 清场回滚 →
     * 输出演练记录（阶段耗时表）。
     */
    @Test
    void rehearsalOpsLoopRollbackAndReimport() throws IOException {
        WideLayout layout = wideLayout();
        assertEquals(761, layout.header().size(), "医院样例宽表 9 + 212 + 10 × 54 = 761 列");
        byte[] mainWorkbook = wideWorkbook(layout, 1, PATIENTS);
        byte[] cancelWorkbook = wideWorkbook(layout, 1, CANCEL_PATIENTS);
        byte[] reimportWorkbook = wideWorkbook(layout, 1, REIMPORT_PATIENTS);
        String token = hospitalToken();
        long drillStart = System.nanoTime();

        // ① 主演练 · 上传 + 预检（E23-T05 判据：PRECHECKED 且 0 错误）
        long t0 = System.nanoTime();
        long jobId = uploadJob(token, FILE_MAIN, mainWorkbook);
        Map<?, ?> prechecked = awaitStatus(jobId, token, PRECHECK_TIMEOUT_MS, "PRECHECKED");
        long precheckMs = elapsed(t0);
        assertEquals("WIDE", data(prechecked).get("layout"), "宽表版面识别（医院现有格式）");
        assertEquals(
                PATIENTS, ((Number) data(prechecked).get("stagedRows")).longValue(), "5000 行全部暂存");
        long errorCount = ((Number) data(prechecked).get("errorCount")).longValue();
        if (errorCount != 0) {
            System.out.printf("[E23-T07] 预检错误分布（定位用）：%s%n", issueHistogram(jobId));
        }
        assertEquals(0L, errorCount, "演练前提：预检 0 错误（E23-T05 判据）");
        record(
                "① 主演练 上传+预检（5000 行 × 761 列）",
                precheckMs,
                "%.0f 行/秒".formatted(PATIENTS * 1000.0 / precheckMs),
                "PRECHECKED，0 错误");

        // ② 主演练 · 确认提交（prepare 会话内即置 IMPORTING）→ 提交中再上传被 40901 拒（想整批重来的第一道闸）
        long t1 = System.nanoTime();
        Map<?, ?> confirmed =
                post(
                        "/api/hospital/followup/imports/" + jobId + "/confirm",
                        token,
                        Map.of("duplicateStrategy", "UPDATE"));
        assertEquals(0, code(confirmed), "确认提交受理失败：" + confirmed);
        assertEquals(
                "IMPORTING",
                data(view(jobId, token)).get("status"),
                "prepare 会话内即置 IMPORTING（40901 断言窗口的确定性前提）");
        Map<?, ?> rejectedUpload = uploadRaw(token, FILE_CANCEL, cancelWorkbook);
        assertEquals(
                40901,
                code(rejectedUpload),
                "提交进行中再上传应被拒（同一租户一个运行中作业）；被拒上传的对象清理见 ImportJobServiceTest（E23-T07 修复）");
        Map<?, ?> done = awaitStatus(jobId, token, COMMIT_TIMEOUT_MS, "DONE");
        long commitMs = elapsed(t1);
        Map<?, ?> stats = (Map<?, ?>) data(done).get("stats");
        assertEquals(25, ((Number) stats.get("batches")).intValue(), "5000 ÷ 200 = 25 批");
        assertEquals(25, ((Number) stats.get("batchesDone")).intValue(), "全部批次完成");
        assertEquals(0, ((List<?>) stats.get("failedBatches")).size(), "不应有失败批次：" + done);
        assertEquals(PATIENTS, ((Number) stats.get("createdPatients")).intValue(), "5000 名患者全部新建");
        assertEquals(
                PATIENTS * RECORDS_PER_PATIENT,
                ((Number) stats.get("recordsCreated")).intValue(),
                "每名患者 2 条历史导入 + 1 条历史未随访");
        record(
                "② 主演练 确认提交（25 批 × 200 名）",
                commitMs,
                "%.1f 名患者/秒".formatted(PATIENTS * 1000.0 / commitMs),
                "DONE，15000 条年度记录");

        // ③ 确认前放弃路径：小作业（DONE 作业不算运行中，可再传）→ PRECHECKED → 取消 CANCELLED
        long t2 = System.nanoTime();
        long cancelJobId = uploadJob(token, FILE_CANCEL, cancelWorkbook);
        awaitStatus(cancelJobId, token, PRECHECK_TIMEOUT_MS, "PRECHECKED");
        Map<?, ?> cancelled =
                post("/api/hospital/followup/imports/" + cancelJobId + "/cancel", token, Map.of());
        assertEquals(0, code(cancelled), "取消失败：" + cancelled);
        assertEquals("CANCELLED", data(cancelled).get("status"), "确认前放弃 → CANCELLED");
        record("③ 确认前放弃（2 行小作业：上传+预检+取消）", elapsed(t2), "—", "CANCELLED（不产生业务写数，无需回滚）");

        // ④ 按批回滚第 25 批（文件第 4801–5000 行）
        long t3 = System.nanoTime();
        Map<?, ?> batchRollback =
                post(
                        "/api/hospital/followup/imports/" + jobId + "/batches/25/rollback",
                        token,
                        Map.of());
        assertEquals(0, code(batchRollback), "按批回滚失败：" + batchRollback);
        assertEquals(
                200,
                ((Number) data(batchRollback).get("deletedPatients")).intValue(),
                "第 25 批新建的 200 名患者全部删除");
        long batchRollbackMs = elapsed(t3);
        runInTenant(
                () -> {
                    assertEquals(PATIENTS - 200, cohortCount(), "按批回滚后只剩前 24 批的 4800 名患者");
                    assertEquals(
                            0L,
                            cohortCount("legacy_no LIKE '" + MARKER + "-L4801'")
                                    + cohortCount("legacy_no LIKE '" + MARKER + "-L5000'"),
                            "第 25 批首尾两名患者已删除");
                    assertEquals(
                            1L,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM imp_batch b JOIN imp_job j ON"
                                                    + " b.job_id = j.id WHERE j.id = ? AND"
                                                    + " b.batch_no = 25 AND b.rolled_back_at IS"
                                                    + " NOT NULL",
                                            Long.class,
                                            jobId)
                                    .longValue(),
                            "批次 rolled_back_at 落库（回滚完成时刻的库内证据）");
                });
        assertEquals(
                "ROLLED_BACK",
                batchOf(data(view(jobId, token)), 25).get("status"),
                "第 25 批状态 ROLLED_BACK");
        record(
                "④ 按批回滚第 25 批（200 名）",
                batchRollbackMs,
                "%.0f 名患者/秒".formatted(200 * 1000.0 / batchRollbackMs),
                "4800 名留存，批次 ROLLED_BACK");

        // ⑤ 整作业回滚（剩余 24 批写入逆序删除 → 作业 ROLLED_BACK，「等于没导过」）
        long t4 = System.nanoTime();
        Map<?, ?> jobRollback =
                post("/api/hospital/followup/imports/" + jobId + "/rollback", token, Map.of());
        assertEquals(0, code(jobRollback), "整作业回滚失败：" + jobRollback);
        assertEquals(24, ((List<?>) jobRollback.get("data")).size(), "剩余 24 批各一条回滚结果");
        long jobRollbackMs = elapsed(t4);
        assertEquals(
                "ROLLED_BACK", data(view(jobId, token)).get("status"), "全部批次回滚后作业置 ROLLED_BACK");
        record(
                "⑤ 整作业回滚（剩余 24 批）",
                jobRollbackMs,
                "%.1f 名患者/秒".formatted(4800 * 1000.0 / jobRollbackMs),
                "作业 ROLLED_BACK");
        assertRehearsalCohortFullyRolledBack(jobId, token, 25, 25);

        // ⑥ 重新演练（A-PAT-018 流程 5「可多次重新导入」）：同一批病案号（源表前 250 行）重新上传 → DONE
        long t5 = System.nanoTime();
        long reimportJobId = uploadJob(token, FILE_REIMPORT, reimportWorkbook);
        awaitStatus(reimportJobId, token, PRECHECK_TIMEOUT_MS, "PRECHECKED");
        Map<?, ?> reconfirmed =
                post(
                        "/api/hospital/followup/imports/" + reimportJobId + "/confirm",
                        token,
                        Map.of("duplicateStrategy", "UPDATE"));
        assertEquals(0, code(reconfirmed), "重新演练确认失败：" + reconfirmed);
        Map<?, ?> redone = awaitStatus(reimportJobId, token, COMMIT_TIMEOUT_MS, "DONE");
        long reimportMs = elapsed(t5);
        Map<?, ?> reimportStats = (Map<?, ?>) data(redone).get("stats");
        assertEquals(
                2, ((Number) reimportStats.get("batches")).intValue(), "250 行 → 2 批（200 + 50）");
        assertEquals(
                REIMPORT_PATIENTS,
                ((Number) reimportStats.get("createdPatients")).intValue(),
                "回滚后同病案号重灌 = 全部重新新建");
        runInTenant(
                () ->
                        assertEquals(
                                REIMPORT_PATIENTS, cohortCount(), "重新演练后演练队列回到 250 名（回滚后可完整重演）"));
        record(
                "⑥ 重新演练（250 行同病案号：上传+预检+提交）",
                reimportMs,
                "%.1f 名患者/秒".formatted(REIMPORT_PATIENTS * 1000.0 / reimportMs),
                "DONE，2 批全部新建");

        // ⑦ 清场：重新演练的作业也整作业回滚，演练环境按产品路径归零
        long t6 = System.nanoTime();
        Map<?, ?> finalRollback =
                post(
                        "/api/hospital/followup/imports/" + reimportJobId + "/rollback",
                        token,
                        Map.of());
        assertEquals(0, code(finalRollback), "清场回滚失败：" + finalRollback);
        assertEquals(2, ((List<?>) finalRollback.get("data")).size(), "2 批各一条回滚结果");
        record("⑦ 清场（重新演练作业整作业回滚）", elapsed(t6), "—", "演练队列清零，环境归零");
        assertRehearsalCohortFullyRolledBack(reimportJobId, token, 2, 2);
        runInTenant(
                () -> {
                    assertEquals(0, cohortCount(), "清场后演练队列应为 0（产品回滚路径归零，不靠测试清理）");
                    assertEquals(
                            0,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_task t JOIN pt_patient p ON"
                                                    + " t.patient_id = p.id WHERE p.legacy_no LIKE ?",
                                            Integer.class,
                                            MARKER + "-L%")
                                    .intValue(),
                            "历史导入只入组不建任务（A-PAT-018 流程 6）");
                });

        // ⑧ 演练耗时记录（验收前半句）：阶段表输出，测试环境人工执行按手册 §6 模板归档
        long totalMs = elapsed(drillStart);
        record("端到端合计（含造数与各阶段断言核对）", totalMs, "—", "演练环闭合");
        printDrillRecord();
    }

    /**
     * 回滚后的「等于没导过」核对（E18-T05 语义）+ 回滚耗时的库内证据（rolled_back_at / IMPORT_ROLLBACK 审计）。
     *
     * @param expectedBatches 该作业总批数（主演练 25 / 重新演练 2）
     * @param expectedRollbackAudits 预期 IMPORT_ROLLBACK 审计条数（按批回滚过的作业 = 批数；只整作业回滚 = 批数）
     */
    private void assertRehearsalCohortFullyRolledBack(
            long jobId, String token, long expectedBatches, long expectedRollbackAudits) {
        runInTenant(
                () -> {
                    assertEquals(0, cohortCount(), "患者主档清零");
                    assertEquals(0, cohortRecordCount("HIST_IMPORT"), "历史导入记录清零");
                    assertEquals(0, cohortRecordCount("HIST_MISSED"), "历史未随访记录清零");
                    assertEquals(0, cohortEnrollmentCount(), "入组行清零");
                    assertEquals(0, cohortContactCount(), "联系方式版本清零");
                    assertEquals(0, cohortSectionCount(), "基线分节清零");
                    assertEquals(
                            0L,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM pt_status_history sh JOIN"
                                                    + " pt_patient p ON sh.patient_id = p.id WHERE"
                                                    + " p.legacy_no LIKE ?",
                                            Long.class,
                                            MARKER + "-L%")
                                    .longValue(),
                            "状态时间线随新建患者一起删");
                    assertEquals(
                            0L,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM pt_patient_summary ps JOIN"
                                                    + " pt_patient p ON ps.patient_id = p.id WHERE"
                                                    + " p.legacy_no LIKE ?",
                                            Long.class,
                                            MARKER + "-L%")
                                    .longValue(),
                            "患者摘要随新建患者一起删");
                    assertEquals(
                            0L,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM imp_batch_write bw JOIN"
                                                    + " imp_batch b ON bw.batch_id = b.id WHERE"
                                                    + " b.job_id = ?",
                                            Long.class,
                                            jobId)
                                    .longValue(),
                            "批次写数登记随回滚删除");
                    // 回滚耗时的库内证据：全部批次 rolled_back_at 在册 + 逐批 IMPORT_ROLLBACK 审计可重建回滚时间线
                    assertEquals(
                            expectedBatches,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM imp_batch WHERE job_id = ? AND"
                                                    + " rolled_back_at IS NOT NULL",
                                            Long.class,
                                            jobId)
                                    .longValue(),
                            expectedBatches + " 批 rolled_back_at 全部在册（回滚完成时刻可从库内取证）");
                    assertEquals(
                            1L,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'IMPORT_COMMIT' AND object_id = ?",
                                            Long.class,
                                            String.valueOf(jobId))
                                    .longValue(),
                            "作业级提交审计");
                    assertEquals(
                            expectedRollbackAudits,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'IMPORT_ROLLBACK' AND object_id = ?",
                                            Long.class,
                                            String.valueOf(jobId))
                                    .longValue(),
                            "回滚审计逐批在册（主演练 = 按批 1 条 + 整作业 24 条）");
                });
        Map<?, ?> finalView = data(view(jobId, token));
        assertEquals(
                expectedBatches,
                ((List<?>) finalView.get("batches"))
                        .stream()
                                .filter(
                                        batch ->
                                                "ROLLED_BACK"
                                                        .equals(((Map<?, ?>) batch).get("status")))
                                .count(),
                "全部 " + expectedBatches + " 批 ROLLED_BACK");
    }

    // ---------------------------------------------------------------- 演练耗时记录

    private static long elapsed(long fromNanos) {
        return (System.nanoTime() - fromNanos) / 1_000_000;
    }

    private void record(String phase, long ms, String throughput, String outcome) {
        drillRecord.add("| %s | %s | %s | %s |".formatted(phase, ms + " ms", throughput, outcome));
    }

    /** 输出演练记录表（与 docs/ops/hospital-a-import-rollback.md §6 模板同列：阶段 / 耗时 / 吞吐 / 结果）。 */
    private void printDrillRecord() {
        System.out.println("[E23-T07] ── 演练记录（自动化口径；测试环境人工执行按回滚手册 §6 模板归档）──");
        System.out.println("[E23-T07] | 阶段 | 耗时 | 吞吐 | 结果 |");
        for (String row : drillRecord) {
            System.out.println("[E23-T07] " + row);
        }
    }

    // ---------------------------------------------------------------- 造数（宽表，全部虚构；与
    // ImportScale5000IT 同款形态）

    /** 宽表表头 + 字段键 → 列位索引（前置 9 + 基线锚点字典序 + 组 × 54；真实字段目录，与运行时同源）。 */
    record WideLayout(
            List<String> header,
            Map<String, Integer> baselineColumns,
            Map<String, Integer> followupColumns) {}

    private WideLayout wideLayout() {
        return callInTenant(
                () -> {
                    ImportFieldCatalog catalog = catalogLoader.load();
                    List<String> header = new ArrayList<>(WideLayoutAdapter.FRONT_COLUMNS);
                    Map<String, Integer> baselineColumns = new LinkedHashMap<>();
                    for (ImportFieldCatalog.CatalogField field : catalog.baselineFields()) {
                        if (field.originalHeader() != null) {
                            baselineColumns.put(field.fieldKey(), header.size());
                            header.add(field.originalHeader());
                        }
                    }
                    Map<String, Integer> followupColumns = new LinkedHashMap<>();
                    for (int group = 1; group <= FOLLOWUP_GROUPS; group++) {
                        for (ImportFieldCatalog.CatalogField field : catalog.followupFields()) {
                            if (field.originalHeader() == null) {
                                continue;
                            }
                            followupColumns.putIfAbsent(field.fieldKey(), header.size());
                            header.add(field.originalHeader());
                        }
                    }
                    assertEquals(
                            212, baselineColumns.size(), "基线锚点 212 列（V38 口径，对不上说明字典变了，需同步本用例）");
                    assertEquals(54, followupColumns.size(), "随访锚点每组 54 列");
                    return new WideLayout(header, baselineColumns, followupColumns);
                });
    }

    /**
     * 流式生成 xlsx（单工作表 inlineStr；稀疏单元格逐行写 zip，内存只有当前行——E18-T02 口径）。 行号区间 [from, to]
     * 与病案号确定性映射：重新演练文件取主演练前 250 行 → 同一批病案号。
     */
    private static byte[] wideWorkbook(WideLayout layout, int from, int to) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypesXml());
            put(zip, "_rels/.rels", rootRelsXml());
            put(zip, "xl/workbook.xml", workbookXml());
            put(zip, "xl/_rels/workbook.xml.rels", workbookRelsXml());
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            StringBuilder out = new StringBuilder(1 << 16);
            out.append(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                            + "<worksheet"
                            + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                            + "><sheetData>");
            out.append("<row r=\"1\">");
            for (int column = 0; column < layout.header().size(); column++) {
                cell(out, 1, column, layout.header().get(column));
            }
            out.append("</row>");
            zip.write(out.toString().getBytes(StandardCharsets.UTF_8));
            for (int i = from; i <= to; i++) {
                out.setLength(0);
                writeSparseRow(out, i + 1, patientRow(i, layout));
                zip.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            zip.write("</sheetData></worksheet>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    private static void writeSparseRow(StringBuilder out, int rowNo, Map<Integer, String> cells) {
        out.append("<row r=\"").append(rowNo).append("\">");
        for (Map.Entry<Integer, String> entry : cells.entrySet()) {
            cell(out, rowNo, entry.getKey(), entry.getValue());
        }
        out.append("</row>");
    }

    /** 一名虚构演练患者（稀疏列位；前置列与基线列一致，避免 FRONT_MISMATCH 噪声）。 */
    private static Map<Integer, String> patientRow(int i, WideLayout layout) {
        Map<Integer, String> cells = new LinkedHashMap<>();
        String mrn = MARKER + "-M" + "%04d".formatted(i);
        String legacy = MARKER + "-L" + "%04d".formatted(i);
        String name = "演练" + "%04d".formatted(i) + "（示例）";
        String gender = i % 2 == 1 ? "男" : "女";
        String ward = i % 2 == 1 ? "心外一病区（示例）" : "心外二病区（示例）";
        LocalDate admission = dischargeDate().minusDays(14);
        cells.put(0, String.valueOf(i));
        cells.put(1, legacy);
        cells.put(2, mrn);
        cells.put(3, name);
        cells.put(4, gender);
        cells.put(5, "65");
        cells.put(6, ymd(admission));
        cells.put(7, "冠心病演练（示例）");
        put(cells, layout.baselineColumns(), "f001", ymd(dischargeDate().minusDays(10)));
        put(cells, layout.baselineColumns(), "f002", mrn);
        put(cells, layout.baselineColumns(), "f003", name);
        put(cells, layout.baselineColumns(), "f004", gender);
        put(cells, layout.baselineColumns(), "f005", "65");
        put(cells, layout.baselineColumns(), "f006", "冠心病演练（示例）");
        put(cells, layout.baselineColumns(), "f007", ward);
        put(cells, layout.baselineColumns(), "f009", "1390000" + "%04d".formatted(i));
        put(cells, layout.baselineColumns(), "f011", "演练手术备注（示例）");
        put(cells, layout.baselineColumns(), "f016", ymd(admission));
        put(cells, layout.baselineColumns(), "f017", ymd(dischargeDate()));
        put(cells, layout.baselineColumns(), "f191", "存活");
        int followupColumns =
                layout.header().size()
                        - WideLayoutAdapter.FRONT_COLUMNS.size()
                        - layout.baselineColumns().size();
        int groupWidth = followupColumns / FOLLOWUP_GROUPS;
        for (int year : DATA_YEARS) {
            int offset = (year - 1) * groupWidth;
            putAt(
                    cells,
                    layout.followupColumns().get("f214") + offset,
                    ymd(dischargeDate().plusYears(year)));
            putAt(cells, layout.followupColumns().get("f215") + offset, "否");
        }
        return cells;
    }

    private static void put(
            Map<Integer, String> cells, Map<String, Integer> columns, String key, String value) {
        Integer column = columns.get(key);
        assertNotNull(column, "字段目录缺少宽表锚点列：" + key);
        putAt(cells, column, value);
    }

    private static void putAt(Map<Integer, String> cells, int column, String value) {
        cells.put(column, value);
    }

    /** 出院 = 北京时间今天 − 4 年 + 30 天：第 1–3 年窗口已结束（第 1、2 年有数据、第 3 年 HIST_MISSED）、第 4 年未结束。 */
    private static LocalDate dischargeDate() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusYears(4).plusDays(30);
    }

    private static String ymd(LocalDate date) {
        return YYYYMMDD.format(date);
    }

    private static void cell(StringBuilder out, int rowNo, int column, String value) {
        out.append("<c r=\"")
                .append(columnLetters(column))
                .append(rowNo)
                .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                .append(escape(value))
                .append("</t></is></c>");
    }

    private static String columnLetters(int zeroBased) {
        int index = zeroBased + 1;
        StringBuilder letters = new StringBuilder(3);
        while (index > 0) {
            int digit = (index - 1) % 26;
            letters.insert(0, (char) ('A' + digit));
            index = (index - 1) / 26;
        }
        return letters.toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypesXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                <Default Extension="xml" ContentType="application/xml"/>\
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>\
                </Types>\
                """;
    }

    private static String rootRelsXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                </Relationships>\
                """;
    }

    private static String workbookXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
                <sheets><sheet name="患者数据" sheetId="1" r:id="rId1"/></sheets></workbook>\
                """;
    }

    private static String workbookRelsXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>\
                </Relationships>\
                """;
    }

    // ---------------------------------------------------------------- HTTP 与轮询

    private String hospitalToken() {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of(
                                "tenantCode",
                                TENANT,
                                "username",
                                "nurse.demo",
                                "password",
                                PASSWORD));
        assertEquals(0, code(response), "登录失败：" + response);
        return (String) data(response).get("token");
    }

    /** multipart 上传成功路径：断言 code=0 并返回 jobId。 */
    private long uploadJob(String token, String fileName, byte[] workbook) {
        Map<?, ?> uploaded = uploadRaw(token, fileName, workbook);
        assertEquals(0, code(uploaded), "上传失败：" + uploaded);
        return ((Number) data(uploaded).get("jobId")).longValue();
    }

    /** multipart 上传原始响应（不断言成功——40901 演练路径用）。 */
    private Map<?, ?> uploadRaw(String token, String fileName, byte[] workbook) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        ByteArrayResource file =
                new ByteArrayResource(workbook) {
                    @Override
                    public String getFilename() {
                        return fileName;
                    }
                };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file);
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/hospital/followup/imports",
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        Map.class);
        Map<?, ?> mapped = response.getBody();
        assertNotNull(mapped, "上传无响应");
        return mapped;
    }

    /** 轮询作业视图至期望终态（异常终态直接失败并带原因）。 */
    private Map<?, ?> awaitStatus(long jobId, String token, long timeoutMs, String expected) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Map<?, ?> last = null;
        while (System.currentTimeMillis() < deadline) {
            last = view(jobId, token);
            String status = (String) data(last).get("status");
            if (expected.equals(status)) {
                return last;
            }
            if ("PRECHECK_FAILED".equals(status)
                    || "CANCELLED".equals(status)
                    || "PARTIAL_FAILED".equals(status)
                    || "ROLLED_BACK".equals(status)) {
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(
                "作业未在 " + (timeoutMs / 1000) + " 秒内到达 " + expected + "：" + describe(last));
    }

    private static String describe(Map<?, ?> response) {
        if (response == null) {
            return "无响应";
        }
        Map<?, ?> view = data(response);
        return "status="
                + view.get("status")
                + "，failReason="
                + view.get("failReason")
                + "，layoutProblems="
                + view.get("layoutProblems")
                + "，stagedRows="
                + view.get("stagedRows")
                + "，stats="
                + view.get("stats");
    }

    private Map<?, ?> view(long jobId, String token) {
        Map<?, ?> response = get("/api/hospital/followup/imports/" + jobId, token);
        assertEquals(0, code(response), "查询作业失败：" + response);
        return response;
    }

    private static Map<?, ?> batchOf(Map<?, ?> view, int batchNo) {
        return ((List<?>) view.get("batches"))
                .stream()
                        .map(batch -> (Map<?, ?>) batch)
                        .filter(batch -> ((Number) batch.get("batchNo")).intValue() == batchNo)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("批次 " + batchNo + " 不存在"));
    }

    private Map<?, ?> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> exchange(
            HttpMethod method, String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return (Map<?, ?>)
                rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? Map.of() : (Map<?, ?>) data;
    }

    // ---------------------------------------------------------------- 库内计数（租户上下文）

    /** 作业问题分布（code × count，按量降序；失败诊断用，问题行不含敏感原值）。 */
    private String issueHistogram(long jobId) {
        return callInTenant(
                () ->
                        jdbc
                                .queryForList(
                                        "SELECT code, header, COUNT(*) AS total FROM imp_issue"
                                                + " WHERE job_id = ? GROUP BY code, header"
                                                + " ORDER BY total DESC",
                                        jobId)
                                .stream()
                                .map(
                                        row ->
                                                row.get("code")
                                                        + "「"
                                                        + row.get("header")
                                                        + "」×"
                                                        + row.get("total"))
                                .reduce((a, b) -> a + "，" + b)
                                .orElse("无"));
    }

    private int cohortCount() {
        return cohortCount("legacy_no LIKE '" + MARKER + "-L%'");
    }

    private int cohortCount(String where) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pt_patient WHERE " + where, Integer.class);
    }

    private int cohortRecordCount(String source) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM fu_record fr JOIN pt_patient p ON fr.patient_id = p.id"
                        + " WHERE p.legacy_no LIKE ? AND fr.source = ?",
                Integer.class,
                MARKER + "-L%",
                source);
    }

    private int cohortEnrollmentCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM fu_enrollment e JOIN pt_patient p ON e.patient_id = p.id"
                        + " WHERE p.legacy_no LIKE ?",
                Integer.class,
                MARKER + "-L%");
    }

    private int cohortContactCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM pt_contact c JOIN pt_patient p ON c.patient_id = p.id"
                        + " WHERE p.legacy_no LIKE ? AND c.source = 'IMPORT'",
                Integer.class,
                MARKER + "-L%");
    }

    private int cohortSectionCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM pt_baseline_section bs JOIN pt_patient p ON bs.patient_id ="
                        + " p.id WHERE p.legacy_no LIKE ?",
                Integer.class,
                MARKER + "-L%");
    }

    private void runInTenant(Runnable action) {
        TenantContext.runAsTenant(TENANT_ID, TENANT, action);
    }

    private <T> T callInTenant(Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_ID, TENANT, action::get);
    }
}
