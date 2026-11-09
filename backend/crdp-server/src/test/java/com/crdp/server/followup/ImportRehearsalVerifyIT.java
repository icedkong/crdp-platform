package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.importer.app.ImportFieldCatalogLoader;
import com.crdp.followup.importer.domain.ImportFieldCatalog;
import com.crdp.followup.importer.domain.WideLayoutAdapter;
import com.crdp.followup.patient.app.PatientRegistrationService;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
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
 * E23-T06 / A-PAT-018 + A-NFR-001：A 导入演练 · 数据核对（tasks.csv 验收原文「抽样核对与汇总数核对一致」，约 5000 名患者、测试环境）。
 *
 * <p>与 E18-T07 的 {@link ImportScale5000IT}（规模 + 回滚演练）互补：本用例是<strong>数据核对演练</strong>——核对基准不是硬编码常量，
 * 而是<strong>源表台账</strong>（生成宽表时同步记录每行的期望值），按「汇总数核对」与「抽样核对」两个口径对账：
 *
 * <ul>
 *   <li><strong>汇总数核对（四路一致）</strong>：源表台账汇总 = 作业 stats（向导结果页）= 库内计数（患者 / 年度记录按来源 / 入组 / 联系方式 / 基线分节
 *       / 任务数=0，A-PAT-018 流程 6 历史导入不建任务）= 患者列表 API 汇总（按病区 + 出院日期筛选， UAT J-2 的「比对患者数 /
 *       任务数汇总」）；预检警告清单同样与台账对账（Q36 / Q37 各自的单元格计数）。
 *   <li><strong>抽样核对（源表行 ↔ 库内患者逐字段）</strong>：随机抽 20 名（固定种子，可复现）+ 批次边界行（1 / 200 / 201 / 2500 / 4800
 *       / 4801 / 5000）+ 脏值行（Q36 / Q37 / 双电话首个）——每名核 对标识列（病案号 / 姓名解密 + 盲索引）、原系统编号保留、 性别 /
 *       病区映射、三个实体日期、基线分节 JSON（含 Q37 编码导入 + 文字进备注）、联系方式版本、 每个年度的随访记录（来源 / 状态 / 实际随访日期 / 表单值；Q36
 *       按空值导入、窗口已结束无数据年度为 HIST_MISSED 草稿、未结束年度不生成）。
 * </ul>
 *
 * <p>演练数据全部虚构（AGENTS.md 规则 5：姓名 / 电话带「示例 / 演练」标记与 IT 前缀）；真实医院样本的演练由 人在测试环境按
 * `docs/ops/hospital-a-import-rehearsal.md` 执行（E23-T05 脱敏链路），本用例是其可常跑的自动化形态。 门控
 * CRDP_IT_MYSQL_PORT（真实 MySQL，与 ImportScale5000IT 同款）；回滚 / 耗时记录属 E23-T07。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportRehearsalVerifyIT {

    private static final String PASSWORD = "Demo@2026";

    /** 造数与清理标记（全部虚构数据，规则 5）。 */
    private static final String MARKER = "IT-E23T06";

    private static final String FILE_NAME = "演练5000核对（示例）.xlsx";

    /** A-NFR-001：约 5000 名历史患者（演练口径与 ImportScale5000IT 同为整 5000 行）。 */
    private static final int PATIENTS = 5000;

    /** 医院样例宽表 10 组随访列（Q05）→ 9 + 212 + 10 × 54 = 761 列。 */
    private static final int FOLLOWUP_GROUPS = 10;

    /** 抽样核对的随机样本数（UAT J-2「随机抽 20 名患者」；固定种子可复现）。 */
    private static final int RANDOM_SAMPLE = 20;

    /** 演示租户（医院 A）与操作账号（TENANT_ADMIN：全部病区范围，导入与列表检索同口径）。 */
    private static final String TENANT = "demoa";

    private static final long TENANT_ID = 1L;

    private static final String WARD_1 = "W-A-CVS1";

    private static final String WARD_2 = "W-A-CVS2";

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final long PRECHECK_TIMEOUT_MS = 10 * 60_000L;

    private static final long COMMIT_TIMEOUT_MS = 15 * 60_000L;

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private ImportFieldCatalogLoader catalogLoader;

    @Autowired private CryptoService crypto;

    @Autowired private ObjectStorage storage;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    @AfterAll
    void cleanState() {
        runInTenant(
                () -> {
                    // A 预置计划回迁移态 auto_enroll=1（V61：出院登记 / 历史导入自动入组）——其他 IT 会把它关成 0
                    // 且不恢复，不重置则本演练入组与年度排期锚点全部为空（与 ImportScale5000IT 同款防御）。
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

    // ---------------------------------------------------------------- 演练 + 双口径核对

    /**
     * 全链路：5000 行宽表（台账造数）上传 → 预检 0 错误 → 确认提交 DONE → 汇总数核对（台账 = 作业 stats = 库内 = 列表 API；预检警告与台账对账）→
     * 抽样核对（随机 20 + 边界 + 脏值行，源表行 ↔ 库内逐字段）。
     */
    @Test
    void rehearsal5000PatientsVerifiesBySampleAndBySummary() throws IOException {
        WideLayout layout = wideLayout();
        assertEquals(761, layout.header().size(), "医院样例宽表 9 + 212 + 10 × 54 = 761 列");
        List<SourceRow> ledger = ledger();
        byte[] workbook = wideWorkbook(layout, ledger);
        System.out.printf(
                "[E23-T06] 演练数据就绪：%d 行 × %d 列（台账汇总：患者 %d、HIST_IMPORT %d、HIST_MISSED %d、任务 %d）%n",
                PATIENTS,
                layout.header().size(),
                PATIENTS,
                ledger.stream().mapToInt(row -> row.dataYears().size()).sum(),
                missedExpected(ledger),
                0);
        String token = hospitalToken();

        // ① 上传（导入向导同链路）→ 预检 PRECHECKED 且 0 错误（演练前提：校验通过，E23-T05 判据）
        long t0 = System.nanoTime();
        Map<?, ?> uploaded = upload(token, workbook);
        long jobId = ((Number) data(uploaded).get("jobId")).longValue();
        Map<?, ?> prechecked = awaitStatus(jobId, token, PRECHECK_TIMEOUT_MS, "PRECHECKED");
        long precheckMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals("WIDE", data(prechecked).get("layout"), "宽表版面识别（医院现有格式）");
        assertEquals(
                PATIENTS, ((Number) data(prechecked).get("stagedRows")).longValue(), "5000 行全部暂存");
        long errorCount = ((Number) data(prechecked).get("errorCount")).longValue();
        if (errorCount != 0) { // 失败诊断：演练造数必须 0 错误，打出错误分布定位造数问题
            System.out.printf("[E23-T06] 预检错误分布（定位用）：%s%n", issueHistogram(jobId));
        }
        assertEquals(0L, errorCount, "预检 0 错误（演练前提：校验通过，E23-T05 判据）");
        System.out.printf(
                "[E23-T06] 预检完成：%d ms（%.1f 行/秒），warn=%d%n",
                precheckMs,
                PATIENTS * 1000.0 / precheckMs,
                ((Number) data(prechecked).get("warnCount")).longValue());

        // ② 确认提交（UPDATE 策略；全部新病案号无重复）→ DONE
        long t1 = System.nanoTime();
        Map<?, ?> confirmed =
                post(
                        "/api/hospital/followup/imports/" + jobId + "/confirm",
                        token,
                        Map.of("duplicateStrategy", "UPDATE"));
        assertEquals(0, code(confirmed), "确认提交受理失败：" + confirmed);
        Map<?, ?> done = awaitStatus(jobId, token, COMMIT_TIMEOUT_MS, "DONE");
        long commitMs = (System.nanoTime() - t1) / 1_000_000;
        System.out.printf(
                "[E23-T06] 提交完成：%d ms（%.1f 名患者/秒）%n", commitMs, PATIENTS * 1000.0 / commitMs);

        // ③ 汇总数核对：四路一致（台账 → 作业 stats → 库内 → 列表 API）
        verifyBySummary(jobId, ledger, token);

        // ④ 抽样核对：随机 20 + 批次边界 + 脏值行（Q36 / Q37 / 双电话），源表行 ↔ 库内逐字段
        int sampled = verifyBySample(ledger, token);
        System.out.printf("[E23-T06] 数据核对演练通过：汇总数核对四路一致，抽样核对 %d 名逐字段一致%n", sampled);
    }

    // ---------------------------------------------------------------- 汇总数核对（四路一致）

    private void verifyBySummary(long jobId, List<SourceRow> ledger, String token) {
        Map<String, Long> expected = expectedTotals(ledger);
        // ③-a 作业 stats（向导结果页口径）
        Map<?, ?> stats = (Map<?, ?>) data(view(jobId, token)).get("stats");
        assertEquals(25, ((Number) stats.get("batches")).intValue(), "5000 ÷ 200 = 25 批");
        assertEquals(25, ((Number) stats.get("batchesDone")).intValue(), "全部批次完成");
        assertEquals(0, ((List<?>) stats.get("failedBatches")).size(), "不应有失败批次");
        assertEquals(
                PATIENTS,
                ((Number) stats.get("createdPatients")).intValue(),
                "作业 stats：新建患者数 = 台账患者数");
        assertEquals(0, ((Number) stats.get("updatedPatients")).intValue(), "无既有患者");
        assertEquals(0, ((Number) stats.get("skippedPatients")).intValue(), "无跳过行");
        assertEquals(
                expected.get("recordsTotal").intValue(),
                ((Number) stats.get("recordsCreated")).intValue(),
                "作业 stats：年度记录数 = 台账（有数据年度 + 窗口已结束无数据年度）");

        // ③-b 预检警告清单与台账对账（Q36 / Q37 是人工复核清单的输入，计数必须与源表脏单元格一致）
        runInTenant(
                () -> {
                    Map<String, Long> warnByCode = new LinkedHashMap<>();
                    jdbc.queryForList(
                                    "SELECT code, COUNT(*) AS total FROM imp_issue WHERE job_id = ?"
                                            + " AND level = 'WARN' GROUP BY code",
                                    jobId)
                            .forEach(
                                    row ->
                                            warnByCode.put(
                                                    (String) row.get("code"),
                                                    ((Number) row.get("total")).longValue()));
                    assertEquals(
                            expected.get("q36Cells").longValue(),
                            warnByCode.getOrDefault("BOOLEAN_01", 0L).longValue(),
                            "Q36 复核警告数 = 源表「0/1」单元格数（BOOLEAN_01）");
                    assertEquals(
                            expected.get("q37Cells").longValue(),
                            warnByCode.getOrDefault("OPTION_CODE_TEXT", 0L).longValue(),
                            "Q37 复核警告数 = 源表「编码+文字」单元格数（OPTION_CODE_TEXT）");
                    assertEquals(
                            0L,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM imp_issue WHERE job_id = ? AND"
                                                    + " level = 'ERROR'",
                                            Long.class,
                                            jobId)
                                    .longValue(),
                            "预检错误为 0（演练前提）");

                    // ③-c 库内计数（真实 MySQL，按台账标记圈定演练队列）
                    assertEquals(
                            PATIENTS,
                            cohortCount("legacy_no LIKE '" + MARKER + "-L%'"),
                            "库内：患者主档数 = 台账患者数");
                    for (int year = 1; year <= 3; year++) {
                        assertEquals(
                                expected.get("histImportY" + year).longValue(),
                                cohortRecordCount("HIST_IMPORT", year),
                                "库内：第 " + year + " 年 HIST_IMPORT 记录数 = 台账");
                    }
                    assertEquals(
                            expected.get("histMissed").longValue(),
                            cohortRecordCount("HIST_MISSED", null),
                            "库内：历史未随访记录数 = 台账（窗口已结束无数据年度，Q06）");
                    assertEquals(
                            PATIENTS,
                            cohortEnrollmentCount(),
                            "库内：入组数 = 台账（auto_enroll 预置计划，不建任务）");
                    assertEquals(
                            expected.get("phone1").longValue(),
                            cohortContactCount("PHONE_1"),
                            "库内：电话1 联系方式版本数 = 台账");
                    assertEquals(
                            expected.get("phone2").longValue(),
                            cohortContactCount("PHONE_2"),
                            "库内：电话2 联系方式版本数 = 台账");
                    assertEquals(
                            expected.get("sections").longValue(),
                            cohortSectionCount(),
                            "库内：基线分节数 = 台账（按 json 字段实际落到几节）");
                    assertEquals(
                            0L,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_task t JOIN pt_patient p ON"
                                                    + " t.patient_id = p.id WHERE p.legacy_no"
                                                    + " LIKE ?",
                                            Long.class,
                                            MARKER + "-L%")
                                    .longValue(),
                            "库内：任务数 = 0（A-PAT-018 流程 6：历史导入只入组不建任务）");
                });

        // ③-d 患者列表 API 汇总（用户在向导 / 列表页看到的口径：按病区 + 出院日期筛选）
        LocalDate discharge = ledger.get(0).discharge();
        long[] wards = callInTenant(() -> new long[] {wardIdOf(WARD_1), wardIdOf(WARD_2)});
        long ward1 = wards[0];
        long ward2 = wards[1];
        assertEquals(
                expected.get("ward1").longValue(),
                searchTotal(
                        token,
                        Map.of(
                                "wardIds",
                                List.of(ward1),
                                "dischargeFrom",
                                discharge.toString(),
                                "dischargeTo",
                                discharge.toString())),
                "列表 API：心外一病区 + 演练出院日期的患者数 = 台账");
        assertEquals(
                expected.get("ward2").longValue(),
                searchTotal(
                        token,
                        Map.of(
                                "wardIds",
                                List.of(ward2),
                                "dischargeFrom",
                                discharge.toString(),
                                "dischargeTo",
                                discharge.toString())),
                "列表 API：心外二病区 + 演练出院日期的患者数 = 台账");
        assertEquals(
                PATIENTS,
                searchTotal(
                        token,
                        Map.of(
                                "wardIds",
                                List.of(ward1, ward2),
                                "dischargeFrom",
                                discharge.toString(),
                                "dischargeTo",
                                discharge.toString())),
                "列表 API：演练队列总患者数 = 台账");
    }

    // ---------------------------------------------------------------- 抽样核对（源表行 ↔ 库内逐字段）

    /** 抽样集合：随机 20（固定种子）+ 批次边界 + 脏值首行；返回抽样数。 */
    private int verifyBySample(List<SourceRow> ledger, String token) {
        TreeSet<Integer> rows = new TreeSet<>();
        Random random = new Random(20260706L);
        while (rows.size() < RANDOM_SAMPLE) {
            rows.add(1 + random.nextInt(PATIENTS));
        }
        // 批次边界（200 名一批，E18-T05）：各批首尾行 + 首尾批交界；脏值首行（Q36 / Q37 / 双电话）
        rows.addAll(List.of(1, 200, 201, 2500, 4800, 4801, 5000, 3, 4, 7, 11));
        Map<Integer, SourceRow> byRow = new LinkedHashMap<>();
        for (SourceRow row : ledger) {
            byRow.put(row.rowNo(), row);
        }
        for (Integer rowNo : rows) {
            verifySampledPatient(byRow.get(rowNo), token);
        }
        return rows.size();
    }

    /** 一名抽样患者的逐字段核对（标识列解密、实体列、分节 JSON、联系方式、年度记录、Q36 / Q37 口径）。 */
    private void verifySampledPatient(SourceRow row, String token) {
        String where = "抽样核对（源表行 " + row.rowNo() + " / " + row.legacyNo() + "）：";
        // 病案号是唯一检索键；列表 API 盲索引精确命中且仅 1 行（用户侧抽查入口）
        Map<?, ?> page = search(token, Map.of("keyword", row.mrn(), "pageNo", 1, "pageSize", 20));
        assertEquals(1L, ((Number) data(page).get("total")).longValue(), where + "病案号检索应唯一命中");
        Map<?, ?> hit = (Map<?, ?>) ((List<?>) data(page).get("items")).get(0);
        assertEquals(row.legacyNo(), hit.get("legacyNo"), where + "列表行原系统编号");
        long patientId = Long.parseLong((String) hit.get("id"));

        runInTenant(
                () -> {
                    Map<String, Object> patient =
                            jdbc.queryForMap(
                                    "SELECT mrn_cipher AS mrnCipher, mrn_blind AS mrnBlind,"
                                            + " name_cipher AS nameCipher, name_blind AS nameBlind,"
                                            + " gender, ward_id AS wardId, surgery_date AS"
                                            + " surgeryDate, admission_date AS admissionDate,"
                                            + " discharge_date AS dischargeDate, legacy_no AS"
                                            + " legacyNo FROM pt_patient WHERE id = ?",
                                    patientId);
                    assertEquals(
                            row.mrn(),
                            crypto.decrypt((String) patient.get("mrnCipher")),
                            where + "病案号解密值");
                    assertEquals(
                            crypto.blindIndex(PatientRegistrationService.PURPOSE_MRN, row.mrn()),
                            patient.get("mrnBlind"),
                            where + "病案号盲索引");
                    assertEquals(
                            row.name(),
                            crypto.decrypt((String) patient.get("nameCipher")),
                            where + "姓名解密值");
                    assertEquals(
                            crypto.blindIndex(PatientRegistrationService.PURPOSE_NAME, row.name()),
                            patient.get("nameBlind"),
                            where + "姓名盲索引");
                    assertEquals(row.genderCode(), patient.get("gender"), where + "性别（显示名 → M/F）");
                    assertEquals(
                            wardIdOf(row.wardCode()),
                            ((Number) patient.get("wardId")).longValue(),
                            where + "病区映射");
                    assertEquals(
                            row.surgery(),
                            ((java.sql.Date) patient.get("surgeryDate")).toLocalDate(),
                            where + "手术日期（yyyymmdd 转换）");
                    assertEquals(
                            row.admission(),
                            ((java.sql.Date) patient.get("admissionDate")).toLocalDate(),
                            where + "入院日期");
                    assertEquals(
                            row.discharge(),
                            ((java.sql.Date) patient.get("dischargeDate")).toLocalDate(),
                            where + "出院日期（起算日期快照）");
                    assertEquals(
                            row.legacyNo(), patient.get("legacyNo"), where + "原系统编号保留（前置列患者编号）");

                    // 基线分节 JSON：合并各节后按字段核对（Q37：编码导入 + 文字进备注）
                    Map<String, Object> merged = new LinkedHashMap<>();
                    Map<String, Object> mergedMeta = new LinkedHashMap<>();
                    jdbc.queryForList(
                                    "SELECT category_key, data, data_meta FROM"
                                            + " pt_baseline_section WHERE patient_id = ?",
                                    patientId)
                            .forEach(
                                    section -> {
                                        merged.putAll(readJson((String) section.get("data")));
                                        mergedMeta.putAll(
                                                readJson((String) section.get("data_meta")));
                                    });
                    assertEquals(row.diagnosis(), merged.get("f006"), where + "诊断信息（分节 JSON）");
                    assertEquals(row.surgeryNote(), merged.get("f011"), where + "手术备注（分节 JSON）");
                    assertEquals(
                            row.height(),
                            ((Number) merged.get("f013")).doubleValue(),
                            where + "身高cm（数值转换）");
                    if (row.ccsText() != null) {
                        assertEquals(
                                row.ccsCode(),
                                ((Number) merged.get("f037")).intValue(),
                                where + "Q37「编码+文字」取编码导入");
                        Object meta = mergedMeta.get("f037");
                        assertTrue(meta instanceof Map, where + "Q37 备注元数据应在册（data_meta.f037）");
                        assertEquals(
                                row.ccsText(),
                                ((Map<?, ?>) meta).get("note"),
                                where + "Q37 文字部分存入该字段备注（data_meta）");
                    }

                    // 联系方式版本（电话1 / 电话2，加密 + source=IMPORT）
                    assertContact(patientId, "PHONE_1", row.phone1(), where);
                    if (row.phone2() != null) {
                        assertContact(patientId, "PHONE_2", row.phone2(), where);
                    } else {
                        assertEquals(
                                0L,
                                contactCount(patientId, "PHONE_2"),
                                where + "源表电话2 为空 → 不生成电话2 版本");
                    }

                    // 年度记录：有数据年度 HIST_IMPORT（SUBMITTED、实际随访日期、表单值）；
                    // 窗口已结束无数据年度 HIST_MISSED（DRAFT）；窗口未结束年度不生成（A-PAT-018 流程 6）
                    for (int year = 1; year <= 3; year++) {
                        Map<String, Object> record =
                                jdbc.queryForMap(
                                        "SELECT source, status, visit_date AS visitDate, data FROM"
                                                + " fu_record WHERE patient_id = ? AND followup_year"
                                                + " = ?",
                                        patientId,
                                        year);
                        if (row.dataYears().contains(year)) {
                            assertEquals(
                                    "HIST_IMPORT",
                                    record.get("source"),
                                    where + "第 " + year + " 年来源");
                            assertEquals(
                                    "SUBMITTED",
                                    record.get("status"),
                                    where + "第 " + year + " 年状态");
                            assertEquals(
                                    row.visitDate(year),
                                    ((java.sql.Date) record.get("visitDate")).toLocalDate(),
                                    where + "第 " + year + " 年实际随访日期（不受 ±7 天窗口限制）");
                            Map<String, Object> values = readJson((String) record.get("data"));
                            assertEquals(
                                    row.visitDate(year).toString(),
                                    values.get("f214"),
                                    where + "第 " + year + " 年随访日期字段");
                            assertEquals(
                                    0,
                                    ((Number) values.get("f215")).intValue(),
                                    where + "第 " + year + " 年是否死亡=否（编码 0）");
                            assertEquals(
                                    row.ef(year),
                                    ((Number) values.get("f248")).doubleValue(),
                                    where + "第 " + year + " 年 EF 射血分数");
                            assertFalse(
                                    values.containsKey("f213"),
                                    where + "Q36：是否已加微信填「1」按空值导入（不进表单值）");
                        } else {
                            assertEquals(
                                    "HIST_MISSED",
                                    record.get("source"),
                                    where + "第 " + year + " 年无数据 → 历史未随访");
                            assertEquals(
                                    "DRAFT", record.get("status"), where + "历史未随访为草稿（可补录，Q06）");
                            assertNull(record.get("visitDate"), where + "历史未随访无实际随访日期");
                        }
                    }
                    assertEquals(
                            0L,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_record WHERE patient_id = ? AND"
                                                    + " followup_year >= 4",
                                            Long.class,
                                            patientId)
                                    .longValue(),
                            where + "窗口未结束年度不预先生成记录（第 4 年起）");
                });
    }

    private void assertContact(long patientId, String slot, String phone, String where) {
        // 当前版本 = 槽位内最大 version（pt_contact 无 current_flag 列，V39）
        List<Map<String, Object>> versions =
                jdbc.queryForList(
                        "SELECT phone_cipher AS phoneCipher, source, version FROM pt_contact WHERE"
                                + " patient_id = ? AND slot = ? ORDER BY version DESC",
                        patientId,
                        slot);
        assertEquals(1, versions.size(), where + slot + " 应恰有一个导入版本");
        Map<String, Object> contact = versions.get(0);
        assertEquals(
                phone, crypto.decrypt((String) contact.get("phoneCipher")), where + slot + " 解密值");
        assertEquals("IMPORT", contact.get("source"), where + slot + " 来源=IMPORT");
        assertEquals(1, ((Number) contact.get("version")).intValue(), where + slot + " 版本号 1");
    }

    private long contactCount(long patientId, String slot) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM pt_contact WHERE patient_id = ? AND slot = ?",
                Long.class,
                patientId,
                slot);
    }

    // ---------------------------------------------------------------- 源表台账（生成即记录期望值）

    /**
     * 一名演练患者的源表行（台账 = 抽样核对与汇总核对的基准）。 脏值分布（按行号取模，确定性可复现）： i%4==3 → Q36（最后数据年度的「是否已加微信」填 1）； i%7==0 →
     * Q37（ccs 填「2二级」）； i%11==0 → 电话2 非空。
     */
    record SourceRow(
            int rowNo,
            String mrn,
            String legacyNo,
            String name,
            String genderDisplay,
            String genderCode,
            String wardName,
            String wardCode,
            LocalDate admission,
            LocalDate surgery,
            LocalDate discharge,
            String phone1,
            String phone2,
            double height,
            Integer ccsCode,
            String ccsText,
            String diagnosis,
            String surgeryNote,
            List<Integer> dataYears) {

        private LocalDate visitDate(int year) {
            // 实际随访日期：周年 ± 2 天内的确定偏移（历史导入不检查窗口，A-PAT-018「按实际随访日期记录」）
            return discharge.plusYears(year).plusDays(((rowNo % 5) - 2));
        }

        private double ef(int year) {
            // EF 随年度变化：抽样核对能发现年度之间的串行错位
            return 40 + ((rowNo + year) % 21);
        }
    }

    private List<SourceRow> ledger() {
        LocalDate discharge =
                LocalDate.now(ZoneId.of("Asia/Shanghai"))
                        .minusYears(4)
                        .plusDays(30); // 第 1–3 年窗口已结束、第 4 年未结束
        List<SourceRow> rows = new ArrayList<>(PATIENTS);
        for (int i = 1; i <= PATIENTS; i++) {
            boolean odd = i % 2 == 1;
            List<Integer> dataYears =
                    switch (i % 4) {
                        case 0 -> List.of(1, 2, 3);
                        case 1, 3 -> List.of(1, 2);
                        default -> List.of(1);
                    };
            boolean q37 = i % 7 == 0;
            rows.add(
                    new SourceRow(
                            i,
                            MARKER + "-M" + "%04d".formatted(i),
                            MARKER + "-L" + "%04d".formatted(i),
                            "演练" + "%04d".formatted(i) + "（示例）",
                            odd ? "男" : "女",
                            odd ? "M" : "F",
                            odd ? "心外一病区（示例）" : "心外二病区（示例）",
                            odd ? WARD_1 : WARD_2,
                            discharge.minusDays(14),
                            discharge.minusDays(10),
                            discharge,
                            "1390000" + "%04d".formatted(i),
                            i % 11 == 0 ? "1380000" + "%04d".formatted(i) : null,
                            150 + (i % 41),
                            q37 ? 2 : null,
                            q37 ? "二级" : null,
                            "冠心病演练（示例）",
                            "演练手术备注" + "%04d".formatted(i) + "（示例）",
                            dataYears));
        }
        return rows;
    }

    /** 台账汇总（汇总数核对的期望值；全部由源表推导，不硬编码）。 */
    private Map<String, Long> expectedTotals(List<SourceRow> ledger) {
        Map<String, Long> totals = new LinkedHashMap<>();
        long histY1 = 0;
        long histY2 = 0;
        long histY3 = 0;
        long missed = 0;
        long phone2 = 0;
        long sections = 0;
        long q36 = 0;
        long q37 = 0;
        for (SourceRow row : ledger) {
            for (int year = 1; year <= 3; year++) {
                if (row.dataYears().contains(year)) {
                    if (year == 1) histY1++;
                    if (year == 2) histY2++;
                    if (year == 3) histY3++;
                } else {
                    missed++;
                }
            }
            if (row.phone2() != null) {
                phone2++;
            }
            if (row.ccsCode() != null) {
                q37++;
            }
            if (row.rowNo() % 4 == 3) {
                q36++;
            }
            // json 存储字段落几节：basic（f005/f006/f011 恒有）+ case（f013 恒有，f037 可选）
            sections += 2;
        }
        totals.put("recordsTotal", histY1 + histY2 + histY3 + missed);
        totals.put("histImportY1", histY1);
        totals.put("histImportY2", histY2);
        totals.put("histImportY3", histY3);
        totals.put("histMissed", missed);
        totals.put("phone1", (long) PATIENTS);
        totals.put("phone2", phone2);
        totals.put("sections", sections);
        totals.put("q36Cells", q36);
        totals.put("q37Cells", q37);
        totals.put("ward1", ledger.stream().filter(row -> WARD_1.equals(row.wardCode())).count());
        totals.put("ward2", ledger.stream().filter(row -> WARD_2.equals(row.wardCode())).count());
        return totals;
    }

    private long missedExpected(List<SourceRow> ledger) {
        return expectedTotals(ledger).get("histMissed");
    }

    // ---------------------------------------------------------------- 宽表造数（稀疏单元格流式 xlsx）

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

    /** 流式生成 xlsx（单工作表 inlineStr；稀疏单元格逐行写 zip，内存只有当前行）。 */
    private static byte[] wideWorkbook(WideLayout layout, List<SourceRow> ledger)
            throws IOException {
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
            for (SourceRow row : ledger) {
                out.setLength(0);
                out.append("<row r=\"").append(row.rowNo() + 1).append("\">");
                Map<Integer, String> cells = rowCells(row, layout);
                // 护栏：行内单元格必须列位升序（Excel 真实形态；乱序会被流式解析器追加到行尾）
                int lastColumn = -1;
                for (Integer column : cells.keySet()) {
                    assertTrue(column > lastColumn, "演练宽表行内单元格应按列位升序（行 " + row.rowNo() + "）");
                    lastColumn = column;
                }
                cells.forEach((column, value) -> cell(out, row.rowNo() + 1, column, value));
                out.append("</row>");
                zip.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            zip.write("</sheetData></worksheet>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    /** 一名演练患者的稀疏单元格（前置列与基线列保持一致，避免 FRONT_MISMATCH 噪声干扰演练对账）。 */
    private static Map<Integer, String> rowCells(SourceRow row, WideLayout layout) {
        Map<Integer, String> cells = new LinkedHashMap<>();
        Map<String, Integer> baseline = layout.baselineColumns();
        // 前置列（医院原系统导出形态）
        cells.put(0, String.valueOf(row.rowNo()));
        cells.put(1, row.legacyNo());
        cells.put(2, row.mrn());
        cells.put(3, row.name());
        cells.put(4, row.genderDisplay());
        cells.put(5, "65");
        cells.put(6, ymd(row.admission()));
        cells.put(7, row.diagnosis());
        cells.put(8, "98%");
        // 基线锚点列（必填齐备 + json 字段 + 加密电话；Q37 行 ccs 填「编码+文字」）。
        // 注意按列位升序 put：流式解析器按 Excel 真实形态假设行内单元格升序（乱序会被追加到行尾）。
        put(cells, baseline, "f001", ymd(row.surgery()));
        put(cells, baseline, "f002", row.mrn());
        put(cells, baseline, "f003", row.name());
        put(cells, baseline, "f004", row.genderDisplay());
        put(cells, baseline, "f005", "65");
        put(cells, baseline, "f006", row.diagnosis());
        put(cells, baseline, "f007", row.wardName());
        put(cells, baseline, "f009", row.phone1());
        if (row.phone2() != null) {
            put(cells, baseline, "f010", row.phone2());
        }
        put(cells, baseline, "f011", row.surgeryNote());
        put(cells, baseline, "f013", String.valueOf((int) row.height()));
        put(cells, baseline, "f016", ymd(row.admission()));
        put(cells, baseline, "f017", ymd(row.discharge()));
        if (row.ccsCode() != null) {
            put(cells, baseline, "f037", row.ccsCode() + row.ccsText()); // Q37「2二级」
        }
        put(cells, baseline, "f191", "存活");
        // 随访组：台账年度有数据（随访日期 + 是否死亡=否 + EF）；Q36 行在最后一个数据年度填「是否已加微信=1」
        int groupWidth =
                layout.header().size()
                        - WideLayoutAdapter.FRONT_COLUMNS.size()
                        - layout.baselineColumns().size();
        groupWidth /= FOLLOWUP_GROUPS;
        for (int year : row.dataYears()) {
            int shift = (year - 1) * groupWidth;
            // 组内同样按列位升序（f213 < f214 < f215 < f248，字典序即锚点序）
            if (row.rowNo() % 4 == 3 && year == row.dataYears().get(row.dataYears().size() - 1)) {
                putAt(cells, layout.followupColumns().get("f213") + shift, "1"); // Q36
            }
            putAt(cells, layout.followupColumns().get("f214") + shift, ymd(row.visitDate(year)));
            putAt(cells, layout.followupColumns().get("f215") + shift, "否");
            putAt(
                    cells,
                    layout.followupColumns().get("f248") + shift,
                    String.valueOf((int) row.ef(year)));
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

    private static String ymd(LocalDate date) {
        return YYYYMMDD.format(date);
    }

    // ---------------------------------------------------------------- xlsx 与 HTTP 基件（与
    // ImportScale5000IT 同款）

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

    /** 患者列表检索（POST /search；返回 data 页对象）。 */
    private Map<?, ?> search(String token, Map<String, Object> body) {
        Map<?, ?> response = post("/api/hospital/followup/patients/search", token, body);
        assertEquals(0, code(response), "患者列表检索失败：" + response);
        return response;
    }

    /** 列表检索的筛选汇总（filters 嵌套在 SearchBody.filters，docs/api/README.md §2.4）。 */
    private long searchTotal(String token, Map<String, Object> filters) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pageNo", 1);
        body.put("pageSize", 1);
        body.put("filters", filters);
        return ((Number) data(search(token, body)).get("total")).longValue();
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String text) {
        try {
            return json.readValue(text == null || text.isBlank() ? "{}" : text, Map.class);
        } catch (IOException failure) {
            throw new IllegalStateException("核对用 JSON 解析失败：" + text, failure);
        }
    }

    // ---------------------------------------------------------------- 库内计数（租户上下文）

    /** 作业问题分布（code × count，按量降序；失败诊断用，问题行不含敏感原值）。 */
    private String issueHistogram(long jobId) {
        return callInTenant(
                () -> {
                    String counts =
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
                                    .orElse("无");
                    String samples =
                            jdbc
                                    .queryForList(
                                            "SELECT level, code, row_no AS rowNo, col_letters AS"
                                                    + " col, header FROM imp_issue WHERE job_id = ?"
                                                    + " AND level = 'ERROR' ORDER BY row_no LIMIT 3",
                                            jobId)
                                    .stream()
                                    .map(
                                            row ->
                                                    row.get("code")
                                                            + "@行"
                                                            + row.get("rowNo")
                                                            + "列"
                                                            + row.get("col")
                                                            + "「"
                                                            + row.get("header")
                                                            + "」")
                                    .reduce((a, b) -> a + "；" + b)
                                    .orElse("无");
                    return counts + "；ERROR 样例：" + samples;
                });
    }

    private int cohortCount(String where) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pt_patient WHERE " + where, Integer.class);
    }

    private int cohortRecordCount(String source, Integer year) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM fu_record fr JOIN pt_patient p ON fr.patient_id = p.id"
                        + " WHERE p.legacy_no LIKE ? AND fr.source = ?"
                        + (year == null ? "" : " AND fr.followup_year = " + year),
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

    private int cohortContactCount(String slot) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM pt_contact c JOIN pt_patient p ON c.patient_id = p.id"
                        + " WHERE p.legacy_no LIKE ? AND c.slot = ? AND c.source = 'IMPORT'",
                Integer.class,
                MARKER + "-L%",
                slot);
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
