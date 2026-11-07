package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * E18-T07 / A-NFR-001：5000 名患者规模压测与回滚演练（tasks.csv 验收「5000 名虚构患者导入成功、可回滚」）。
 *
 * <p>规模口径（A-NFR-001「约 5000 名历史患者（医院已确认）」+ Q05 医院现有 Excel 为宽表、样例 10 组共 761 列）： 生成 5000 行 × 761 列（9
 * 前置 + 212 基线锚点 + 10 组 × 54 随访锚点）的虚构宽表——表头从真实字段字典（租户库）加载， 每行必填字段（姓名 / 病案号 / 性别 / 病区 / 三个日期 /
 * 出院状态）齐备、病区值可映射（E18-T04），第 1、2 年有随访数据， 全部虚构（AGENTS.md 规则 5：姓名 / 电话带「示例」或 IT 标记，无真实患者数据）。
 *
 * <p>演练链路（与导入向导完全一致的 HTTP 路径，E18-T06）：multipart 上传 → 轮询预检（PRECHECKED、errorCount = 0）→ 确认提交（UPDATE
 * 策略）→ 轮询批次至 DONE（25 批 × 200 名患者，A-PAT-018 / tech-plan §12⑥）→ 库内核对规模正确性 →
 * <strong>回滚演练</strong>：先按批回滚最后一批（200 名患者「等于这批没导过」）， 再整作业回滚（剩余 24 批 + 作业置 ROLLED_BACK，E18-T05
 * 写入逆序删除）， 核对患者主档 / 随访记录 / 入组 / 联系方式 / 基线分节 / 状态时间线 / 摘要全部清零——「可回滚」验收。
 *
 * <p>A-NFR-001 明确「不设专项性能指标」，因此压测只记录各阶段耗时与吞吐（日志输出）， 不做阈值断言；断言全部针对正确性与可回滚性。门控 CRDP_IT_MYSQL_PORT（真实
 * MySQL，与 RecordDraftIT 同款）。文件生成用测试内流式 xlsx 写入器（SimpleXlsx 的 inlineStr 单工作表形状，稀疏单元格逐行进
 * zip——E18-T02「大文件不占满内存」口径，避免 5000 × 761 全量驻留字符串）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportScale5000IT {

    private static final String PASSWORD = "Demo@2026";

    /** 造数与清理标记（全部虚构数据，规则 5）。 */
    private static final String MARKER = "IT-E18T07";

    private static final String FILE_NAME = "压测5000患者（示例）.xlsx";

    /** A-NFR-001 规模：5000 名历史患者。 */
    private static final int PATIENTS = 5000;

    /** 医院样例宽表 10 组随访列（Q05：样例为 10 组、组数不限）→ 9 + 212 + 10 × 54 = 761 列。 */
    private static final int FOLLOWUP_GROUPS = 10;

    /** 有数据的随访年度（第 1、2 年；第 3 年窗口已结束无数据 → HIST_MISSED，第 4 年窗口未结束不生成）。 */
    private static final List<Integer> DATA_YEARS = List.of(1, 2);

    /** 每名患者生成的随访记录数（2 个 HIST_IMPORT + 1 个 HIST_MISSED，ImportYearPlanner）。 */
    private static final int RECORDS_PER_PATIENT = 3;

    /** 演示租户（医院 A）与操作账号（TENANT_ADMIN：全部病区范围，冻结范围不跳行）。 */
    private static final String TENANT = "demoa";

    private static final long TENANT_ID = 1L;

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 预检 / 提交轮询上限（压测量级给足：预检 10 分钟、提交 15 分钟；A-NFR-001 不设性能指标）。 */
    private static final long PRECHECK_TIMEOUT_MS = 10 * 60_000L;

    private static final long COMMIT_TIMEOUT_MS = 15 * 60_000L;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ImportFieldCatalogLoader catalogLoader;
    @Autowired private ObjectStorage storage;

    @BeforeEach
    @AfterAll
    void cleanState() {
        runInTenant(
                () -> {
                    // A 预置计划回到迁移态 auto_enroll=1（V61 规格：出院登记 / 历史导入自动入组）——
                    // 其他 IT（BackfillTimelineIT 等）按「单计划世界」把它关成 0 且不恢复，
                    // 不重置的话本演练入组与年度排期锚点全部为空（导入只生成 HIST_IMPORT）。
                    jdbc.update(
                            "UPDATE fu_plan SET auto_enroll = 1 WHERE code = 'A_ANNUAL_FU_PLAN'");
                    // 作业与对象存储文件（storage.delete 用上传时的相对键）
                    List<Map<String, Object>> jobs =
                            jdbc.queryForList(
                                    "SELECT id, storage_key AS storageKey FROM imp_job WHERE"
                                            + " file_name = ?",
                                    FILE_NAME);
                    for (Map<String, Object> job : jobs) {
                        String key = (String) job.get("storageKey");
                        if (key != null && !key.isBlank()) {
                            storage.delete(key);
                        }
                    }
                    deleteIn(
                            "imp_batch_write",
                            "job_id IN (SELECT id FROM imp_job WHERE file_name = '"
                                    + FILE_NAME
                                    + "')");
                    deleteIn(
                            "imp_batch",
                            "job_id IN (SELECT id FROM imp_job WHERE file_name = '"
                                    + FILE_NAME
                                    + "')");
                    deleteIn(
                            "imp_issue",
                            "job_id IN (SELECT id FROM imp_job WHERE file_name = '"
                                    + FILE_NAME
                                    + "')");
                    deleteIn(
                            "imp_row",
                            "job_id IN (SELECT id FROM imp_job WHERE file_name = '"
                                    + FILE_NAME
                                    + "')");
                    // 审计只读（PLT-AUD-011）：自清理走维护开关（AuditTableMaintenance，E08-T02 逃生口）
                    com.crdp.server.AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE object_type = 'IMPORT_JOB' AND object_id IN"
                                    + " (SELECT CAST(id AS CHAR) FROM imp_job WHERE file_name = '"
                                    + FILE_NAME
                                    + "')");
                    jdbc.update("DELETE FROM imp_job WHERE file_name = ?", FILE_NAME);
                    // 中断残留的患者按标记清理（子行在前；正常演练后为 0 行）
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

    private void deleteIn(String table, String where) {
        jdbc.update("DELETE FROM " + table + " WHERE " + where);
    }

    // ---------------------------------------------------------------- 压测 + 回滚演练

    /**
     * 全链路：5000 行宽表上传 → 预检 0 错误 → 提交 DONE（25 批全成、5000 名新建、15000 条年度记录）→ 库内规模核对 → 按批回滚 * 最后一批 → 整作业回滚
     * → 「等于没导过」核对 + 提交 / 回滚审计存在。
     */
    @Test
    void imports5000FictitiousPatientsThenRollsBackCleanly() throws IOException {
        WideLayout layout = wideLayout();
        assertEquals(761, layout.header().size(), "医院样例宽表 9 + 212 + 10 × 54 = 761 列");
        byte[] workbook = wideWorkbook(layout);
        System.out.printf(
                "[E18-T07] 造数完成：%d 行 × %d 列，xlsx %d 字节%n",
                PATIENTS, layout.header().size(), workbook.length);
        String token = hospitalToken();

        // ① 上传（multipart，向导同链路）→ 预检轮询至 PRECHECKED
        long t0 = System.nanoTime();
        Map<?, ?> uploaded = upload(token, workbook);
        long jobId = ((Number) data(uploaded).get("jobId")).longValue();
        Map<?, ?> prechecked = awaitStatus(jobId, token, PRECHECK_TIMEOUT_MS, "PRECHECKED");
        long precheckMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals("WIDE", data(prechecked).get("layout"), "宽表版面识别");
        assertEquals(
                PATIENTS, ((Number) data(prechecked).get("stagedRows")).longValue(), "5000 行全部暂存");
        assertEquals(
                0L,
                ((Number) data(prechecked).get("errorCount")).longValue(),
                "造数必须 0 错误（有 ERROR 确认提交被拦，E18-T05）");
        long warnCount = ((Number) data(prechecked).get("warnCount")).longValue();
        System.out.printf(
                "[E18-T07] 预检完成：%d ms（%.1f 行/秒），warn=%d%n",
                precheckMs, PATIENTS * 1000.0 / precheckMs, warnCount);

        // ② 确认提交（UPDATE 策略；全部为新病案号，无重复）→ 轮询批次至 DONE
        long t1 = System.nanoTime();
        Map<?, ?> confirmed =
                post(
                        "/api/hospital/followup/imports/" + jobId + "/confirm",
                        token,
                        Map.of("duplicateStrategy", "UPDATE"));
        assertEquals(0, code(confirmed), "确认提交受理失败：" + confirmed);
        Map<?, ?> done = awaitStatus(jobId, token, COMMIT_TIMEOUT_MS, "DONE");
        long commitMs = (System.nanoTime() - t1) / 1_000_000;
        Map<?, ?> stats = (Map<?, ?>) data(done).get("stats");
        System.out.printf(
                "[E18-T07] 提交完成：%d ms（%.1f 名患者/秒），stats=%s%n",
                commitMs, PATIENTS * 1000.0 / commitMs, stats);

        // ③ 库内规模核对（真实 MySQL，逐表计数 + 抽查一名患者）——先逐来源断言再对总账
        assertCohortAtScale(PATIENTS);
        assertEquals(25, ((Number) stats.get("batches")).intValue(), "5000 ÷ 200 = 25 批");
        assertEquals(25, ((Number) stats.get("batchesDone")).intValue(), "全部批次完成");
        assertEquals(0, ((List<?>) stats.get("failedBatches")).size(), "不应有失败批次：" + done);
        assertEquals(PATIENTS, ((Number) stats.get("createdPatients")).intValue(), "5000 名患者全部新建");
        assertEquals(0, ((Number) stats.get("updatedPatients")).intValue(), "无既有患者");
        assertEquals(0, ((Number) stats.get("skippedPatients")).intValue(), "无跳过行");
        assertEquals(
                PATIENTS * RECORDS_PER_PATIENT,
                ((Number) stats.get("recordsCreated")).intValue(),
                "每名患者 2 条历史导入 + 1 条历史未随访 = 15000 条记录");

        // ④ 回滚演练 · 先按批回滚最后一批（第 25 批 = 文件第 4801–5000 行）
        long t2 = System.nanoTime();
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
        runInTenant(
                () -> {
                    assertEquals(PATIENTS - 200, cohortCount(), "按批回滚后只剩前 24 批的 4800 名患者");
                    assertEquals(
                            0L,
                            cohortCount("legacy_no LIKE '" + MARKER + "-L4801'")
                                    + cohortCount("legacy_no LIKE '" + MARKER + "-L5000'"),
                            "第 25 批首尾两名患者已删除");
                });
        Map<?, ?> afterBatch = view(jobId, token);
        assertEquals(
                "ROLLED_BACK", batchOf(data(afterBatch), 25).get("status"), "第 25 批状态 ROLLED_BACK");

        // ⑤ 回滚演练 · 整作业回滚（剩余 24 批逐批删除 → 作业 ROLLED_BACK）
        Map<?, ?> jobRollback =
                post("/api/hospital/followup/imports/" + jobId + "/rollback", token, Map.of());
        assertEquals(0, code(jobRollback), "整作业回滚失败：" + jobRollback);
        assertEquals(24, ((List<?>) jobRollback.get("data")).size(), "剩余 24 批各一条回滚结果");
        long rollbackMs = (System.nanoTime() - t2) / 1_000_000;
        assertEquals(
                "ROLLED_BACK", data(view(jobId, token)).get("status"), "全部批次回滚后作业置 ROLLED_BACK");
        System.out.printf("[E18-T07] 回滚演练完成（含按批）：共 %d ms%n", rollbackMs);

        // ⑥ 「等于没导过」：患者与全部子行清零 + 提交 / 回滚审计在册
        assertCohortFullyRolledBack(jobId, token);
    }

    /** 提交完成后的规模核对：六张业务表计数 + 一名患者的实体列 / 状态时间线 / 摘要抽查。 */
    private void assertCohortAtScale(int expectedPatients) {
        runInTenant(
                () -> {
                    assertEquals(
                            expectedPatients,
                            cohortCount(),
                            "患者主档（legacy_no 标记）应为 " + expectedPatients);
                    assertEquals(
                            expectedPatients * DATA_YEARS.size(),
                            cohortRecordCount("HIST_IMPORT"),
                            "有数据年度 HIST_IMPORT（SUBMITTED、实际随访日期）");
                    assertEquals(
                            expectedPatients,
                            cohortRecordCount("HIST_MISSED"),
                            "第 3 年窗口已结束无数据 → HIST_MISSED（DRAFT 待补录，Q06）");
                    assertEquals(
                            expectedPatients,
                            cohortEnrollmentCount(),
                            "全部加入 auto_enroll 预置计划（不建任务，A-PAT-018 流程 6）");
                    assertEquals(
                            expectedPatients,
                            cohortContactCount(),
                            "每名患者一条电话1 联系方式版本（source=IMPORT）");
                    assertEquals(
                            expectedPatients * 2,
                            cohortSectionCount(),
                            "每名患者两条基线分节（case：诊断信息 / basic：手术备注，storage=json）");
                    // 抽查第 1 名患者：实体列（性别 / 病区 / 日期）+ 状态时间线 + 摘要
                    Map<String, Object> patient =
                            jdbc.queryForMap(
                                    "SELECT gender, ward_id AS wardId, surgery_date AS"
                                            + " surgeryDate, admission_date AS admissionDate,"
                                            + " discharge_date AS dischargeDate FROM pt_patient"
                                            + " WHERE legacy_no = ?",
                                    MARKER + "-L0001");
                    assertEquals("M", patient.get("gender"), "性别按显示名男 → M");
                    assertEquals(
                            wardIdOf("W-A-CVS1"),
                            ((Number) patient.get("wardId")).longValue(),
                            "病区值「心外一病区（示例）」映射到字典病区（E18-T04）");
                    assertEquals(
                            dischargeDate(),
                            ((java.sql.Date) patient.get("dischargeDate")).toLocalDate(),
                            "出院日期（起算日期快照，A-PAT-018）");
                    assertEquals(
                            1L,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM pt_status_history sh JOIN"
                                                    + " pt_patient p ON sh.patient_id = p.id WHERE"
                                                    + " p.legacy_no = ?",
                                            Long.class,
                                            MARKER + "-L0001")
                                    .longValue(),
                            "登记状态时间线随导入写入");
                    assertEquals(
                            1L,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM pt_patient_summary ps JOIN"
                                                    + " pt_patient p ON ps.patient_id = p.id WHERE"
                                                    + " p.legacy_no = ?",
                                            Long.class,
                                            MARKER + "-L0001")
                                    .longValue(),
                            "患者摘要随导入写入");
                });
    }

    /** 回滚后的「等于没导过」核对：六张表清零 + 批次全 ROLLED_BACK + IMPORT_COMMIT / IMPORT_ROLLBACK 审计。 */
    private void assertCohortFullyRolledBack(long jobId, String token) {
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
                    assertEquals(
                            1L,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'IMPORT_COMMIT' AND object_id = ?",
                                            Long.class,
                                            String.valueOf(jobId))
                                    .longValue(),
                            "作业级提交审计");
                    assertTrue(
                            jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                            + " 'IMPORT_ROLLBACK' AND object_id = ?",
                                                    Long.class,
                                                    String.valueOf(jobId))
                                            .longValue()
                                    >= 25L,
                            "回滚审计 ≥ 25 条（25 批逐批 + 演练路径）");
                });
        Map<?, ?> finalView = data(view(jobId, token));
        assertEquals(
                25,
                ((List<?>) finalView.get("batches"))
                        .stream()
                                .filter(
                                        batch ->
                                                "ROLLED_BACK"
                                                        .equals(((Map<?, ?>) batch).get("status")))
                                .count(),
                "全部 25 批 ROLLED_BACK");
    }

    // ---------------------------------------------------------------- 造数（宽表 5000 行，全部虚构）

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
                            // 组内列位按第 1 组记录；后续组 = 同一列位 + (group−1) × 每组锚点数
                            followupColumns.putIfAbsent(field.fieldKey(), header.size());
                            header.add(field.originalHeader());
                        }
                    }
                    return new WideLayout(header, baselineColumns, followupColumns);
                });
    }

    /**
     * 流式生成 xlsx（SimpleXlsx 同款单工作表 inlineStr 形状；稀疏单元格逐行写 zip， 内存只有当前行——5000 × 761 全量驻留会到数百 MB
     * 字符串，E18-T02「大文件不占满内存」同口径）。
     */
    private static byte[] wideWorkbook(WideLayout layout) throws IOException {
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
            for (int i = 1; i <= PATIENTS; i++) {
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

    /** 一名虚构患者（稀疏列位；坐标与 E18 宽表适配器同源——表头即锚点，值按显示名 / 原表写法）。 */
    private static Map<Integer, String> patientRow(int i, WideLayout layout) {
        Map<Integer, String> cells = new LinkedHashMap<>();
        String mrn = MARKER + "-M" + "%04d".formatted(i);
        String legacy = MARKER + "-L" + "%04d".formatted(i);
        String name = "压测" + "%04d".formatted(i) + "（示例）";
        String gender = i % 2 == 1 ? "男" : "女";
        String ward = i % 2 == 1 ? "心外一病区（示例）" : "心外二病区（示例）";
        LocalDate admission = dischargeDate().minusDays(14);
        // 前置列（与基线列一致，避免前置 / 基线比对警告；序号与数据完整度列不参与比对）
        cells.put(0, String.valueOf(i));
        cells.put(1, legacy);
        cells.put(2, mrn);
        cells.put(3, name);
        cells.put(4, gender);
        cells.put(5, "65");
        cells.put(6, ymd(admission));
        cells.put(7, "冠心病（示例）");
        // 基线锚点列：必填齐备（f001/f002/f003/f004/f007/f016/f017/f191）+ 电话1（→ 联系方式版本）+ 手术备注（→ basic 分节）
        put(cells, layout.baselineColumns(), "f001", ymd(dischargeDate().minusDays(10)));
        put(cells, layout.baselineColumns(), "f002", mrn);
        put(cells, layout.baselineColumns(), "f003", name);
        put(cells, layout.baselineColumns(), "f004", gender);
        put(cells, layout.baselineColumns(), "f005", "65");
        put(cells, layout.baselineColumns(), "f006", "冠心病（示例）");
        put(cells, layout.baselineColumns(), "f007", ward);
        put(cells, layout.baselineColumns(), "f009", "1390000" + "%04d".formatted(i));
        put(cells, layout.baselineColumns(), "f011", "压测手术备注（示例）");
        put(cells, layout.baselineColumns(), "f016", ymd(admission));
        put(cells, layout.baselineColumns(), "f017", ymd(dischargeDate()));
        put(cells, layout.baselineColumns(), "f191", "存活");
        // 随访组：第 1、2 年有数据（随访日期 + 是否死亡=否），其余组全空（宽表全空组跳过）
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

    private static LocalDate dischargeDate() {
        // 出院 = 今天 − 4 年 + 30 天：第 1–3 年窗口已结束（第 1、2 年有数据，第 3 年 HIST_MISSED）、
        // 第 4 年窗口未结束不生成记录 → 每名患者恰好 3 条年度记录。
        return LocalDate.now().minusYears(4).plusDays(30);
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
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
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
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                </Relationships>\
                """;
    }

    private static String workbookXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
                <sheets><sheet name="患者数据" sheetId="1" r:id="rId1"/></sheets></workbook>\
                """;
    }

    private static String workbookRelsXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
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

    /** multipart 上传（字段 file；与导入向导同一端点）。 */
    private Map<?, ?> upload(String token, byte[] workbook) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        ByteArrayResource file =
                new ByteArrayResource(workbook) {
                    @Override
                    public String getFilename() {
                        return FILE_NAME;
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
        assertEquals(0, code(mapped), "上传失败：" + mapped);
        return mapped;
    }

    /** 轮询作业视图至期望终态（PRECHECK_FAILED / PARTIAL_FAILED / 超时直接失败并带原因）。 */
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

    private long wardIdOf(String code) {
        return jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code);
    }

    private void runInTenant(Runnable action) {
        TenantContext.runAsTenant(TENANT_ID, TENANT, action);
    }

    private <T> T callInTenant(Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_ID, TENANT, action::get);
    }
}
