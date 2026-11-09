package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.importer.app.ImportFieldCatalogLoader;
import com.crdp.followup.importer.domain.ImportFieldCatalog;
import com.crdp.followup.importer.domain.WideLayoutAdapter;
import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.stats.app.NodeOccurrenceRefreshService;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * E19-T09 M3 全量回归（tasks.csv 验收「回归通过，可进入 UAT」；本行未关联需求编号，属 M3 回归收尾，同 E06-T12 / E15-T10 口径）。
 *
 * <p>M3 完成标准（tech-plan §16）：「报告单、对比标红、宽表导入演练（约 5000 名患者）、统计与首页、平台视角；UAT 通过、渗透高危清零、恢复演练达标」。
 * 其中可自动化的前五项逐项各一个用例，挂在同一名患者、同一条业务动线上（历史患者从报告单到平台可见的完整旅程）：
 *
 * <ol>
 *   <li><b>报告单</b>（E16）：登记历史患者（出院 = 今天−4 年+30 天，预置计划 Q35 自动入组 + 已过窗口补建补录任务）→ 上传心脏超声报告单（位置 =
 *       基线「超声检查」分类、报告时间 = 出院前 2 天）→ 映射预览出术前 / 出院前两条（A-ATT-002 时相）→ 确认「出院前最后一次」写入基线 f102 EF
 *       （A-ATT-013 映射写入留痕：fe_field_history source=REPORT、RPT_MAPPING_WRITE 审计）→ 患者内报告单清单可见、位置带「基线 ·
 *       分类」标签；
 *   <li><b>对比标红</b>（E17）：第 1 年随访补录（实际随访日期 = 第 1 年计划日）提交 EF 44 → 提交事务内重算落 highlight（tech-plan §11），
 *       「上一次」<b>回落到①映射写入的基线 f102=55</b>（A-FU-027 第 1 年与基线对比）→ 命中 ef_drop（下降 11 ≥ 10，Q41）；
 *   <li><b>宽表导入演练</b>（E18）：2 行 × 761 列虚构宽表（造数口径同 ImportScale5000IT）走导入向导同一条 HTTP 链路：multipart 上传 →
 *       预检 PRECHECKED 0 错误 → 确认提交 DONE（1 批、2 名新建、6 条记录：HIST_IMPORT×2 + HIST_MISSED×1，A-PAT-018）→
 *       自动入组预置计划（不建任务）；
 *   <li><b>统计与首页</b>（E19）：每日刷新事实表（E19-T01）→ 三名患者第 1–3 年事实行（动线患者已随访来自②的真实提交记录、导入患者来自③的
 *       HIST_IMPORT；HIST_MISSED 草稿不计分子，Q06）→ yearly_rate 卡（cohortYear = 出院年）逐格核对 → node_rate
 *       卡（预置计划）同一数字 （A-DASH-012 两卡关系）→ kpi 卡患者总数 = 患者列表总数（钻取一致，A 规格 §9）→ 医护负责患者数行（A-DASH-009：负责患者数
 *       / 随访率 1/3）；
 *   <li><b>平台视角</b>（E20）：平台管理员进入 demoa 视角 → 按姓名检索动线患者只回脱敏值（PLT-VIEW-010）、首页 kpi 卡读放行（view 动作） →
 *       删除患者 40303（PLT-VIEW-012 只读；代为操作不含删除）→ VIEW_QUERY / VIEW_DENIED 平台审计与台账计数递增。
 * </ol>
 *
 * <p>「UAT 通过、渗透高危清零、恢复演练达标」为人工 / 运维关卡（A UAT 与导入演练 W33–W35、渗透 E22-T09、恢复演练 E22-T07/T12），不在自动化回归范围。
 *
 * <p>与既有用例的分工：ReportMappingIT / ReportLocationIT 锁映射与位置的守卫矩阵，CompareHighlightIT 用 SQL 造基线锁重算与规则变更，
 * ImportScale5000IT 锁 5000 规模与回滚演练，Dashboard*IT / StatsQ38CaliberIT 各自锁卡片口径，PlatformTenantView*IT
 * 锁视角授权与执行点。 本套件的独有覆盖是<b>跨 Epic 串接</b>：报告单映射写入的基线超声（HTTP 正口）作为对比回落来源；HTTP 提交且命中标红的记录作为统计「已随访」来源；
 * 导入的历史随访记录进入事实表与两张随访率卡；同一患者最终以脱敏只读形态出现在平台视角——五个域在同一条动线上互为前提，任何一环断裂都会在后续用例暴露。
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis）。自建自清（legacy 前缀
 * IT-E19T09-、导入文件名「回归导入2患者（示例）.xlsx」、 责任医生 it-e19t09-doc、视角会话与 VIEW_ 平台审计）。全部虚构数据（AGENTS.md 规则
 * 5）。后台 outbox 分发 tick 停用（1 小时间隔，同 M2 回归口径）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.port=0", "crdp.outbox.dispatch.interval-ms=3600000"})
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class M3CoreRegressionIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String BASE = "/api/hospital/followup";

    /** 造数与清理标记（全部虚构数据，AGENTS.md 规则 5）。 */
    private static final String LEGACY = "IT-E19T09";

    private static final String NAME = "陈回归（示例）";

    private static final String IMPORT_FILE = "回归导入2患者（示例）.xlsx";

    private static final String DOCTOR_USERNAME = "it-e19t09-doc";

    /** 预置计划编码（V61：出院日期起算、每年一次、窗口 ±7 天、auto_enroll）。 */
    private static final String PRESET_PLAN = "A_ANNUAL_FU_PLAN";

    private static final String FORM = "A_ANNUAL_FU";

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 出院 = 北京时间今天−4 年 + 30 天：第 1–3 年窗口已结束（第 4 年窗口未开始不排事实行）。 */
    private static final LocalDate DISCHARGE =
            PlanDateCalculator.businessToday(Instant.now()).minusYears(4).plusDays(30);

    /** 事实表刷新统计日 = 业务今天（第 1–3 年窗口已结束、第 4 年未开启）。 */
    private static final LocalDate AS_OF = PlanDateCalculator.businessToday(Instant.now());

    /** 预检 / 提交轮询上限（2 行量级给足）。 */
    private static final long PRECHECK_TIMEOUT_MS = 2 * 60_000L;

    private static final long COMMIT_TIMEOUT_MS = 3 * 60_000L;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private NodeOccurrenceRefreshService refreshService;
    @Autowired private ImportFieldCatalogLoader catalogLoader;
    @Autowired private ObjectStorage storage;

    /** 动线状态：五项用例共用（患者、报告单、记录、导入作业随动线推进写入）。 */
    private long patientId;

    private long reportId;

    private long recordId;

    private long importJobId;

    private long itDoctorId;

    // ---------------------------------------------------------------- 标准①：报告单

    /** 登记 → 上传心脏超声报告单（基线超声位置）→ 确认「出院前最后一次」映射写入 f102 → 患者内清单可见。 */
    @Test
    @Order(1)
    void item1_echoReportMapsIntoBaselineForLaterCompare() {
        recycleLeftoverFromEarlierRun();
        String admin = hospitalToken("demoa", "nurse.demo");

        // 出院登记（HTTP，历史患者；预置计划 auto_enroll=1 → Q35 事务内自动入组 + 已过窗口补建补录任务）
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-M1");
        body.put("name", NAME);
        body.put("gender", "F");
        body.put("birthDate", "1955-03-08");
        body.put("wardId", defaultWardId());
        body.put("admissionDate", DISCHARGE.minusDays(14).toString());
        body.put("surgeryDate", DISCHARGE.minusDays(7).toString());
        body.put("dischargeDate", DISCHARGE.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-C1");
        Map<?, ?> created = post("/api/hospital/followup/patients", admin, body);
        assertEquals(0, codeOf(created), "出院登记失败：" + created);
        patientId = Long.parseLong((String) dataOf(created).get("id"));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fu_enrollment e JOIN fu_plan p ON p.id = e.plan_id"
                                + " WHERE e.patient_id = ? AND p.code = ?",
                        patientId,
                        PRESET_PLAN),
                "登记事务内自动加入预置计划（Q35）");
        assertEquals(
                3,
                count("SELECT COUNT(*) FROM fu_task WHERE patient_id = ?", patientId),
                "第 1–3 年窗口已过 → 入组即补建 3 条补录任务（Q44 passed_window_action=BACKFILL）");

        // 上传心脏超声报告单：位置 = 基线「超声检查」分类锚点、报告时间 = 出院前 2 天（时相边界内）
        String echoCategory = baselineEchoCategoryKey(admin);
        Map<?, ?> echoName = firstNameOf("ECHO", admin);
        reportId =
                createEchoReport(
                        admin,
                        str(echoName.get("id")),
                        echoCategory,
                        DISCHARGE.minusDays(2).toString(),
                        List.of(indicator("EF", "55"), indicator("MR_DEGREE", "中量")));

        // 映射预览（A-ATT-013）：基线位置出「术前 / 出院前」两条，用户选时相（A-ATT-002）
        Map<?, ?> preview =
                dataOf(post(BASE + "/reports/" + reportId + "/mapping/preview", admin, Map.of()));
        List<?> mappings = (List<?>) preview.get("mappings");
        assertEquals(2, mappings.size(), "位置含基线超声分类 → 术前 / 出院前两条映射");
        Map<?, ?> predischarge =
                mappings.stream()
                        .map(item -> (Map<?, ?>) item)
                        .filter(item -> "PREDISCHARGE_LAST".equals(item.get("phase")))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("缺少出院前最后一次映射：" + mappings));
        assertNull(predischarge.get("blockedReason"), "出院前报告不受时相边界拦截");
        Map<?, ?> candidate =
                ((List<?>) predischarge.get("candidates")).isEmpty()
                        ? null
                        : (Map<?, ?>) ((List<?>) predischarge.get("candidates")).get(0);
        assertNotNull(candidate, "基线候选（分节未建，targetVersion 0）");
        List<String> fieldKeys = new ArrayList<>();
        for (Object write : (List<?>) candidate.get("writes")) {
            Map<?, ?> row = (Map<?, ?>) write;
            if (row.get("error") == null) {
                fieldKeys.add(str(row.get("fieldKey")));
            }
        }
        assertTrue(fieldKeys.contains("f102"), "出院前 EF 写入 f102（V64 映射锚点）：" + fieldKeys);

        // 确认写入（分节不存在 → 建行写值，A-ATT-013）
        Map<String, Object> confirm = new HashMap<>();
        confirm.put("mappingId", str(predischarge.get("mappingId")));
        confirm.put("targetVersion", ((Number) candidate.get("targetVersion")).intValue());
        confirm.put("fieldKeys", fieldKeys);
        Map<?, ?> confirmed =
                post(BASE + "/reports/" + reportId + "/mapping/confirm", admin, confirm);
        assertEquals(0, codeOf(confirmed), "确认映射写入失败：" + confirmed);
        assertEquals(
                55,
                query(
                        "SELECT JSON_EXTRACT(data, '$.f102') FROM pt_baseline_section"
                                + " WHERE patient_id = ? AND category_key = ?",
                        Integer.class,
                        patientId,
                        echoCategory),
                "出院前 EF=55 写入基线超声分节（后续对比回落的来源）");
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM fe_field_history WHERE record_type ="
                                + " 'PT_BASELINE_SECTION' AND field_key = 'f102' AND source ="
                                + " 'REPORT' AND record_id IN (SELECT id FROM pt_baseline_section"
                                + " WHERE patient_id = ?)",
                        patientId),
                "映射写入留字段历史（来源 = 报告单）");
        assertTrue(
                count(
                                "SELECT COUNT(*) FROM aud_log WHERE action = 'RPT_MAPPING_WRITE'"
                                        + " AND object_type = 'REPORT' AND object_id = ?",
                                String.valueOf(reportId))
                        >= 1,
                "写 RPT_MAPPING_WRITE 审计");

        // 患者内报告单清单可见（A-ATT-010 / A-ATT-002：位置标签「基线 · 分类」）
        Map<?, ?> page = dataOf(get(BASE + "/patients/" + patientId + "/reports", admin));
        List<?> items = (List<?>) page.get("items");
        assertEquals(1, items.size(), "患者内清单共 1 份报告单");
        Map<?, ?> row = (Map<?, ?>) items.get(0);
        assertEquals(String.valueOf(reportId), str(row.get("id")));
        List<?> locations = (List<?>) row.get("locations");
        assertEquals(1, locations.size(), "上传时带了 1 个位置");
        assertNotNull(((Map<?, ?>) locations.get(0)).get("anchorLabel"), "位置标签带分类显示名");
    }

    // ---------------------------------------------------------------- 标准②：对比标红

    /** 第 1 年随访补录提交 EF 44 →「上一次」回落①映射写入的基线 55 → 命中 ef_drop 并持久化 highlight。 */
    @Test
    @Order(2)
    void item2_firstYearSubmitHighlightsAgainstMappedBaseline() {
        String doctor = hospitalToken("demoa", "doctor.demo");
        LocalDate firstYearPlanDate = DISCHARGE.plusYears(1);

        // 无锚点手工补录（A-FU-014）：实际随访日期 = 第 1 年计划日（窗口内）
        Map<?, ?> record =
                post(
                        BASE + "/patients/" + patientId + "/records",
                        doctor,
                        Map.of(
                                "source",
                                "BACKFILL",
                                "visitDate",
                                firstYearPlanDate.toString(),
                                "formCode",
                                FORM));
        assertEquals(0, codeOf(record), "创建补录记录失败：" + record);
        recordId = Long.parseLong(str(dataOf(record).get("recordId")));

        // 提交（必填与条件显示口径同 CompareSourceIT / CompareHighlightIT）：EF 44 vs 基线 55 → 下降 11
        Map<String, Object> data = new HashMap<>();
        data.put("f214", firstYearPlanDate.toString());
        data.put("f215", 0);
        data.put("f213", 1);
        data.put("f236", 1);
        data.put("f237", firstYearPlanDate.toString());
        data.put("f248", 44);
        Map<?, ?> submitted =
                post(
                        BASE + "/records/" + recordId + "/submit",
                        doctor,
                        Map.of("baseVersion", 0, "data", data));
        assertEquals(0, codeOf(submitted), "提交第 1 年随访失败：" + submitted);

        // 提交事务内重算落 fu_record.highlight（tech-plan §11）；previousValue = ①映射写入的基线值
        Map<String, Object> hit = firstHitOf(recordId);
        assertEquals("ef_drop", hit.get("ruleId"), "提交即命中 Q41 ef_drop");
        assertEquals("f248", hit.get("key"));
        assertEquals(55, ((Number) hit.get("previousValue")).intValue(), "回落基线 f102 = ①映射写入的 55");
        assertEquals("下降 11 个百分点", hit.get("changeText"), "变化量文案与前端同形");
    }

    // ---------------------------------------------------------------- 标准③：宽表导入演练

    /**
     * 2 行 × 761 列宽表（同 ImportScale5000IT 造数口径：9 前置 + 212 基线锚点 + 10 组 × 54 随访锚点，第 1、2 年有数据） → 上传 → 预检
     * 0 错误 → 确认提交 DONE → 患者建档、年度历史记录生成、自动入组预置计划（不建任务）。5000 规模与回滚演练由 ImportScale5000IT
     * 锁定；本用例锁「导入动线进入同一租户库、喂给④统计」的串接。
     */
    @Test
    @Order(3)
    void item3_wideImportRehearsalFeedsTheSameTenant() throws IOException {
        WideLayout layout = wideLayout();
        assertEquals(761, layout.header().size(), "医院样例宽表 9 + 212 + 10 × 54 = 761 列（Q05）");
        String token = hospitalToken("demoa", "nurse.demo");

        Map<?, ?> uploaded = upload(token, wideWorkbook(layout));
        importJobId = ((Number) dataOf(uploaded).get("jobId")).longValue();
        Map<?, ?> prechecked = awaitStatus(importJobId, token, PRECHECK_TIMEOUT_MS, "PRECHECKED");
        assertEquals("WIDE", dataOf(prechecked).get("layout"), "宽表版面识别");
        assertEquals(2L, ((Number) dataOf(prechecked).get("stagedRows")).longValue(), "2 行全部暂存");
        assertEquals(
                0L,
                ((Number) dataOf(prechecked).get("errorCount")).longValue(),
                "造数必须 0 错误（有 ERROR 确认提交被拦，E18-T05）");

        Map<?, ?> confirmed =
                post(
                        "/api/hospital/followup/imports/" + importJobId + "/confirm",
                        token,
                        Map.of("duplicateStrategy", "UPDATE"));
        assertEquals(0, codeOf(confirmed), "确认提交受理失败：" + confirmed);
        Map<?, ?> done = awaitStatus(importJobId, token, COMMIT_TIMEOUT_MS, "DONE");
        Map<?, ?> stats = (Map<?, ?>) dataOf(done).get("stats");
        assertEquals(1, ((Number) stats.get("batches")).intValue(), "2 行 = 1 批");
        assertEquals(1, ((Number) stats.get("batchesDone")).intValue(), "全部批次完成");
        assertEquals(2, ((Number) stats.get("createdPatients")).intValue(), "2 名患者全部新建");
        assertEquals(
                6,
                ((Number) stats.get("recordsCreated")).intValue(),
                "每名患者 2 条历史导入 + 1 条历史未随访 = 6 条记录");

        // 库内核对：入组预置计划（不建任务，A-PAT-018 流程 6）、HIST_IMPORT 实际随访日期在窗口内、HIST_MISSED 为草稿（Q06）
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM fu_enrollment e JOIN pt_patient p ON p.id ="
                                + " e.patient_id JOIN fu_plan pl ON pl.id = e.plan_id"
                                + " WHERE p.legacy_no LIKE ? AND pl.code = ?",
                        LEGACY + "-L%",
                        PRESET_PLAN),
                "导入患者全部自动入组预置计划");
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                + " WHERE p.legacy_no LIKE ?",
                        LEGACY + "-L%"),
                "历史导入不建任务（A-PAT-018 流程 6）");
        assertEquals(
                4,
                count(
                        "SELECT COUNT(*) FROM fu_record r JOIN pt_patient p ON p.id = r.patient_id"
                                + " WHERE p.legacy_no LIKE ? AND r.source = 'HIST_IMPORT'"
                                + " AND r.status = 'SUBMITTED'",
                        LEGACY + "-L%"),
                "第 1、2 年有数据 → 每名患者 2 条 HIST_IMPORT（SUBMITTED）");
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM fu_record r JOIN pt_patient p ON p.id = r.patient_id"
                                + " WHERE p.legacy_no LIKE ? AND r.source = 'HIST_MISSED'"
                                + " AND r.status = 'DRAFT'",
                        LEGACY + "-L%"),
                "第 3 年窗口已结束无数据 → HIST_MISSED 草稿（Q06 待补录）");
        assertTrue(
                count(
                                "SELECT COUNT(*) FROM fu_record r JOIN pt_patient p ON p.id ="
                                        + " r.patient_id WHERE p.legacy_no LIKE ?"
                                        + " AND r.source = 'HIST_IMPORT' AND r.visit_date BETWEEN ? AND ?",
                                LEGACY + "-L%",
                                java.sql.Date.valueOf(DISCHARGE.plusYears(1).minusDays(7)),
                                java.sql.Date.valueOf(DISCHARGE.plusYears(2).plusDays(7)))
                        >= 2,
                "历史导入记录的实际随访日期落在年度窗口内");
    }

    // ---------------------------------------------------------------- 标准④：统计与首页

    /**
     * 每日刷新（E19-T01）→ 三名患者第 1–3 年事实行 → yearly_rate（cohortYear = 出院年）与 node_rate（预置计划） 两卡同一数字 → kpi
     * 卡与患者列表钻取一致 → 医护负责患者数（A-DASH-009）。
     */
    @Test
    @Order(4)
    void item4_factsAndDashboardCardsReflectTheWholeJourney() {
        String admin = hospitalToken("demoa", "nurse.demo");

        // 动线患者挂 IT 责任医生（医护负责患者数行，A-DASH-009；导入患者无责任医生不出行）
        itDoctorId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_user (username, display_name, password_hash,"
                                            + " status) VALUES (?, ?, 'PENDING_DEMO_HASH',"
                                            + " 'ENABLED')",
                                    DOCTOR_USERNAME,
                                    "柳回归（示例）");
                            long doctorId =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_user WHERE username = ?",
                                            Long.class,
                                            DOCTOR_USERNAME);
                            jdbc.update(
                                    "UPDATE pt_patient SET responsible_user_id = ? WHERE id = ?",
                                    doctorId,
                                    patientId);
                            return doctorId;
                        });

        refreshService.refreshForDate(AS_OF);

        // 事实表：三名患者第 1–3 年各一行（动线患者的已随访来自②的真实提交记录，导入患者来自③的 HIST_IMPORT）
        assertEquals(
                3,
                count(
                        "SELECT COUNT(*) FROM st_node_occurrence o JOIN pt_patient p ON p.id ="
                                + " o.patient_id WHERE p.legacy_no LIKE ? AND o.occurrence = 1",
                        LEGACY + "-%"),
                "第 1 次一人一行（动线 + 2 名导入患者）");
        Map<String, Object> chainFact =
                queryMap(
                        "SELECT due, followed, in_window FROM st_node_occurrence"
                                + " WHERE patient_id = ? AND occurrence = 1",
                        patientId);
        assertEquals(1, ((Number) chainFact.get("due")).intValue(), "动线患者第 1 年应随访");
        assertEquals(
                1,
                ((Number) chainFact.get("followed")).intValue(),
                "已随访 = ②窗口内提交的随访记录（无锚点补录按最近计划日期归属，OQ-106②）");
        assertEquals(1, ((Number) chainFact.get("in_window")).intValue(), "窗口内完成");
        assertEquals(
                "SUCCESS",
                query(
                        "SELECT status FROM job_tenant_run WHERE job_code = 'st-node-refresh'"
                                + " AND run_date = ?",
                        String.class,
                        AS_OF),
                "每日刷新作业台账 SUCCESS");
        assertTrue(
                count("SELECT COUNT(*) FROM aud_log WHERE action = 'STATS_NODE_REFRESH'") >= 1,
                "刷新写汇总审计");

        // 每年随访率（A-DASH-006 / Q38）：cohortYear = 出院年只含本动线 3 名患者
        Map<?, ?> yearly =
                dataOf(get(cardPath("yearly_rate") + "cohortYear=" + DISCHARGE.getYear(), admin));
        assertEquals(AS_OF.toString(), String.valueOf(yearly.get("asOf")), "asOf = 刷新统计日");
        List<?> yearRows = (List<?>) yearly.get("rows");
        assertEquals(3, yearRows.size(), "第 1–3 年各一行（第 4 年窗口未结束不列）");
        assertRateRow(rowByLabel(yearRows, "第1年"), 3, 3, 3, 100.0d, "动线② + 导入2名全部完成");
        assertRateRow(rowByLabel(yearRows, "第2年"), 3, 2, 2, 66.7d, "只有导入患者第 2 年有 HIST_IMPORT");
        assertRateRow(rowByLabel(yearRows, "第3年"), 3, 0, 0, 0.0d, "动线补录任务未做、导入 HIST_MISSED 未补");

        // 节点随访率（A-DASH-012）：同一计划同一数字（预置计划为全部三名患者唯一入组）
        long presetPlanId = query("SELECT id FROM fu_plan WHERE code = ?", Long.class, PRESET_PLAN);
        Map<?, ?> node = dataOf(get(cardPath("node_rate") + "planId=" + presetPlanId, admin));
        assertEquals("冠心病术后年度随访", node.get("planName"), "预置计划（V61）");
        List<?> nodeRows = (List<?>) node.get("rows");
        assertEquals(3, nodeRows.size(), "年度循环节点第 1–3 次各一行");
        assertRateRow(rowByLabel(nodeRows, "第1年"), 3, 3, 3, 100.0d, "两卡同一筛选下数字一致");
        assertRateRow(rowByLabel(nodeRows, "第2年"), 3, 2, 2, 66.7d, "两卡同一筛选下数字一致");
        assertRateRow(rowByLabel(nodeRows, "第3年"), 3, 0, 0, 0.0d, "两卡同一筛选下数字一致");

        // 指标卡（A-DASH-001）：患者总数与患者列表条数一致（A 规格 §9 验收第一条，钻取一致）
        Map<?, ?> kpi = dataOf(get(cardPath("kpi"), admin));
        assertEquals("A", kpi.get("style"), "demoa 未开 implant → 医院 A 指标集");
        long patientTotal = metricValue(metricsByCode(kpi).get("total"));
        Map<?, ?> search = post(BASE + "/patients/search", admin, Map.of());
        assertEquals(0, codeOf(search), "患者列表检索失败：" + search);
        assertEquals(
                ((Number) dataOf(search).get("total")).longValue(),
                patientTotal,
                "患者总数 = 患者列表条数（含本动线 3 名虚构患者）");
        assertTrue(patientTotal >= 3, "至少含本动线 3 名患者");

        // 医护负责患者数（A-DASH-009 / E19-T07）：IT 责任医生一行——负责 1 名、随访率 1/3（②第 1 年计入）
        Map<?, ?> workload = dataOf(get("/api/hospital/followup/stats/doctor-workload?", admin));
        assertEquals(AS_OF.toString(), String.valueOf(workload.get("asOf")), "asOf = 刷新统计日");
        Map<?, ?> doctorRow =
                (Map<?, ?>)
                        ((List<?>) workload.get("rows"))
                                .stream()
                                        .map(row -> (Map<?, ?>) row)
                                        .filter(
                                                row ->
                                                        DOCTOR_USERNAME.equals(
                                                                        str(row.get("doctorId")))
                                                                || String.valueOf(itDoctorId)
                                                                        .equals(
                                                                                str(
                                                                                        row.get(
                                                                                                "doctorId"))))
                                        .findFirst()
                                        .orElseThrow(
                                                () ->
                                                        new AssertionError(
                                                                "责任医生行缺失：" + workload.get("rows")));
        assertEquals("柳回归（示例）", doctorRow.get("doctorName"));
        assertEquals(1L, num(doctorRow.get("totalPatients")), "负责患者数 = 动线患者 1 名");
        assertEquals(1L, num(doctorRow.get("activePatients")), "在随访 = ACTIVE");
        assertEquals(0L, num(doctorRow.get("monthDue")), "补录任务计划日期在往年 → 本月应随访 0");
        assertEquals(0L, num(doctorRow.get("overdue")), "无 OVERDUE 任务");
        assertEquals(3L, num(doctorRow.get("rateDueCount")), "随访率分母 = 第 1–3 年各 1");
        assertEquals(1L, num(doctorRow.get("rateFollowedCount")), "已随访 = ②第 1 年提交记录");
        assertEquals(33.3d, ((Number) doctorRow.get("followRatePct")).doubleValue(), 0.05d, "1/3");
    }

    // ---------------------------------------------------------------- 标准⑤：平台视角

    /** 进入 demoa 视角：检索动线患者只回脱敏值、kpi 卡读放行、删除 40303；VIEW_ 平台审计与台账计数。 */
    @Test
    @Order(5)
    void item5_platformViewSeesThePatientMaskedAndReadOnly() {
        deleteKeys("crdp:sess:*");
        deleteKeys("crdp:view-enter:*");
        JdbcTemplate platform = platformDirect();

        // 进入视角（E20-T01）：平台登录 → 建会话 → 签发进入凭据 → 换视角令牌
        String platformToken = loginToken("platform.admin", PASSWORD);
        long demoaId =
                platform.queryForObject(
                        "SELECT id FROM plt_tenant WHERE code = ?", Long.class, "demoa");
        Map<?, ?> entered =
                post(
                        "/api/platform/tenant-view/sessions",
                        platformToken,
                        Map.of(
                                "tenantId",
                                demoaId,
                                "purpose",
                                "TROUBLESHOOT",
                                "note",
                                "M3 回归（示例）"));
        assertEquals(0, codeOf(entered), "进入视角失败：" + entered);
        String sessionNo = (String) dataOf(entered).get("sessionNo");
        Map<?, ?> ticket =
                post(
                        "/api/platform/tenant-view/sessions/current/entry-ticket",
                        platformToken,
                        Map.of());
        assertEquals(0, codeOf(ticket), "签发进入凭据失败：" + ticket);
        Map<?, ?> exchanged =
                post(
                        "/api/hospital/view/enter",
                        null,
                        Map.of("ticket", dataOf(ticket).get("ticket")));
        assertEquals(0, codeOf(exchanged), "换取视角令牌失败：" + exchanged);
        String viewToken = (String) dataOf(exchanged).get("token");
        assertNotNull(viewToken);

        try {
            // 读放行 + 脱敏（PLT-VIEW-010）：按姓名检索动线患者，行内只回脱敏值
            Map<?, ?> page = post(BASE + "/patients/search", viewToken, Map.of("keyword", NAME));
            assertEquals(0, codeOf(page), "视角检索患者列表放行：" + page.get("message"));
            List<?> items = (List<?>) dataOf(page).get("items");
            assertEquals(1, items.size(), "按姓名精确命中动线患者");
            String maskedName = String.valueOf(((Map<?, ?>) items.get(0)).get("name"));
            assertTrue(
                    maskedName.startsWith("陈") && maskedName.length() < NAME.length(),
                    "行内姓名只含脱敏值（前端不接收原值）：" + maskedName);
            // view 动作读放行（E20-T03）：首页 kpi 卡在视角中可读、数字与医院侧同一张事实表
            long viewTotal =
                    metricValue(
                            metricsByCode(dataOf(get(cardPath("kpi"), viewToken))).get("total"));
            long hospitalTotal =
                    metricValue(
                            metricsByCode(
                                            dataOf(
                                                    get(
                                                            cardPath("kpi"),
                                                            hospitalToken("demoa", "nurse.demo"))))
                                    .get("total"));
            assertEquals(viewTotal, hospitalTotal, "视角与医院会话的患者总数同一口径");

            // 只读执行（PLT-VIEW-012）：明文查看专用接口对视角永远禁用（E20-T06 代为操作的 plain 动作永不放行）
            assertEquals(
                    40303,
                    codeOf(
                            post(
                                    BASE + "/patients/" + patientId + "/plaintext",
                                    viewToken,
                                    Map.of("field", "name"))),
                    "视角令牌查看患者姓名明文 40303");

            // 平台侧审计与台账：读放行 VIEW_QUERY、被拒 VIEW_DENIED，台账计数递增
            Integer queries =
                    platform.queryForObject(
                            "SELECT COUNT(*) FROM aud_platform_access_log"
                                    + " WHERE action = 'VIEW_QUERY' AND view_session_id = ?",
                            Integer.class,
                            sessionNo);
            assertTrue(queries != null && queries >= 2, "读放行写 VIEW_QUERY 审计：" + queries);
            Integer denied =
                    platform.queryForObject(
                            "SELECT COUNT(*) FROM aud_platform_access_log"
                                    + " WHERE action = 'VIEW_DENIED' AND view_session_id = ?",
                            Integer.class,
                            sessionNo);
            assertTrue(denied != null && denied >= 1, "被拒写 VIEW_DENIED 审计：" + denied);
            Map<String, Object> ledger =
                    platform.queryForMap(
                            "SELECT query_count, denied_count FROM plt_view_session"
                                    + " WHERE session_no = ?",
                            sessionNo);
            assertTrue(((Number) ledger.get("query_count")).longValue() >= 2, "台账查询计数递增");
            assertTrue(((Number) ledger.get("denied_count")).longValue() >= 1, "台账被拒计数递增");

            // 动线收尾核对：只读视角不产生任何业务写入，动线患者仍在
            assertEquals(
                    1,
                    count("SELECT COUNT(*) FROM pt_patient WHERE id = ?", patientId),
                    "动线患者未被删除（只读视角不产生业务写入）");
        } finally {
            // 退出视角（会话收尾；平台侧行与审计由 cleanUp 清理）
            delete("/api/hospital/view/session", viewToken);
        }
    }

    // ---------------------------------------------------------------- 自建自清

    /** 回收早前中断运行残留（本类标记前缀）。审计先行（object 子查询要用还没删的行）。 */
    private void recycleLeftoverFromEarlierRun() {
        runInTenant(
                "demoa",
                () -> {
                    // 预置计划回迁移态（V61：auto_enroll=1；其他 IT 按「单计划世界」关掉且不恢复）
                    jdbc.update("UPDATE fu_plan SET auto_enroll = 1 WHERE code = ?", PRESET_PLAN);
                    // 对比规则种子复位（V67/V70：ef_drop 阈值 10 启用；防其他用例改后未还原）
                    jdbc.update(
                            "UPDATE fu_compare_rule SET params ="
                                    + " JSON_OBJECT('direction', 'DROP', 'threshold', 10),"
                                    + " status = 'ENABLED', updated_by = NULL"
                                    + " WHERE form_code = 'A_ANNUAL_FU' AND rule_code = 'ef_drop'");
                    // 导入作业与对象存储文件
                    List<Map<String, Object>> jobs =
                            jdbc.queryForList(
                                    "SELECT id, storage_key AS storageKey FROM imp_job WHERE"
                                            + " file_name = ?",
                                    IMPORT_FILE);
                    for (Map<String, Object> job : jobs) {
                        String key = (String) job.get("storageKey");
                        if (key != null && !key.isBlank()) {
                            storage.delete(key);
                        }
                    }
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE (object_type = 'IMPORT_JOB'"
                                    + " AND object_id IN (SELECT CAST(id AS CHAR) FROM imp_job"
                                    + " WHERE file_name = '"
                                    + IMPORT_FILE
                                    + "'))"
                                    + " OR (action IN ('RPT_CREATE', 'RPT_MAPPING_WRITE')"
                                    + " AND object_type = 'REPORT' AND object_id IN"
                                    + " (SELECT CAST(id AS CHAR) FROM rpt_report WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE '"
                                    + LEGACY
                                    + "-%')))"
                                    + " OR (event_type = 'PATIENT_CREATE'"
                                    + " AND target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                    + " WHERE legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (event_type IN"
                                    + " ('RECORD_SUBMIT', 'RECORD_BACKFILL_CREATE', 'RECORD_UNPLANNED_CREATE')"
                                    + " AND target IN (SELECT CONCAT('FU_RECORD:', id) FROM fu_record"
                                    + " WHERE patient_id IN (SELECT id FROM pt_patient"
                                    + " WHERE legacy_no LIKE '"
                                    + LEGACY
                                    + "-%')))"
                                    + " OR (event_type IN"
                                    + " ('TASK_CREATE', 'TASK_CLAIM', 'TASK_START', 'TASK_PROGRESS',"
                                    + " 'TASK_COMPLETE', 'TASK_FOLLOWUP_CREATE')"
                                    + " AND target IN (SELECT CONCAT('TASK:', id) FROM fu_task"
                                    + " WHERE patient_id IN (SELECT id FROM pt_patient"
                                    + " WHERE legacy_no LIKE '"
                                    + LEGACY
                                    + "-%')))"
                                    + " OR action = 'STATS_NODE_REFRESH'");
                    jdbc.update(
                            "DELETE FROM imp_batch_write WHERE job_id IN"
                                    + " (SELECT id FROM imp_job WHERE file_name = ?)",
                            IMPORT_FILE);
                    jdbc.update(
                            "DELETE FROM imp_batch WHERE job_id IN"
                                    + " (SELECT id FROM imp_job WHERE file_name = ?)",
                            IMPORT_FILE);
                    jdbc.update(
                            "DELETE FROM imp_issue WHERE job_id IN"
                                    + " (SELECT id FROM imp_job WHERE file_name = ?)",
                            IMPORT_FILE);
                    jdbc.update(
                            "DELETE FROM imp_row WHERE job_id IN"
                                    + " (SELECT id FROM imp_job WHERE file_name = ?)",
                            IMPORT_FILE);
                    jdbc.update("DELETE FROM imp_job WHERE file_name = ?", IMPORT_FILE);
                    // 动线患者子行（导入患者与手工患者同前缀；子行在前）
                    jdbc.update(
                            "DELETE FROM sys_outbox WHERE JSON_EXTRACT(payload, '$.patientId') IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE FROM st_node_occurrence WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE fh FROM fe_field_history fh"
                                    + " LEFT JOIN fu_record fr ON fr.id = fh.record_id"
                                    + " AND fh.record_type = 'FU_RECORD'"
                                    + " LEFT JOIN pt_baseline_section s ON s.id = fh.record_id"
                                    + " AND fh.record_type = 'PT_BASELINE_SECTION'"
                                    + " WHERE (fh.record_type = 'FU_RECORD' AND fr.patient_id IN"
                                    + "   (SELECT id FROM pt_patient WHERE legacy_no LIKE ?))"
                                    + "    OR (fh.record_type = 'PT_BASELINE_SECTION'"
                                    + "    AND s.patient_id IN"
                                    + "   (SELECT id FROM pt_patient WHERE legacy_no LIKE ?))",
                            LEGACY + "-%",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE v FROM rpt_indicator_value v JOIN rpt_report r"
                                    + " ON r.id = v.report_id JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE l FROM rpt_report_link l JOIN pt_patient p"
                                    + " ON p.id = l.patient_id WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE r FROM rpt_report r JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM sys_file WHERE original_name LIKE ?", LEGACY + "-%");
                    jdbc.update(
                            "DELETE FROM fu_record WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE e FROM fu_enrollment e JOIN pt_patient p"
                                    + " ON p.id = e.patient_id WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE c FROM pt_contact c JOIN pt_patient p ON c.patient_id = p.id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE s FROM pt_baseline_section s JOIN pt_patient p"
                                    + " ON p.id = s.patient_id WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE ps FROM pt_patient_summary ps JOIN pt_patient p"
                                    + " ON ps.patient_id = p.id WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE sh FROM pt_status_history sh JOIN pt_patient p"
                                    + " ON sh.patient_id = p.id WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    jdbc.update("DELETE FROM sys_user WHERE username = ?", DOCTOR_USERNAME);
                    jdbc.update(
                            "DELETE FROM job_tenant_run WHERE job_code IN"
                                    + " ('st-node-refresh', 'fu-task-create')");
                });
        runInTenant(
                "demob",
                () ->
                        jdbc.update(
                                "DELETE FROM job_tenant_run WHERE job_code IN"
                                        + " ('st-node-refresh', 'fu-task-create')"));
        // 平台侧：视角会话与 VIEW_ 审计（platform.admin 本类动线会话）
        JdbcTemplate platform = platformDirect();
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM aud_platform_access_log WHERE view_session_id IN"
                        + " (SELECT session_no FROM plt_view_session WHERE account_name ="
                        + " 'platform.admin' AND note = 'M3 回归（示例）')");
        platform.update(
                "DELETE FROM plt_view_session WHERE account_name = 'platform.admin'"
                        + " AND note = 'M3 回归（示例）'");
    }

    @AfterAll
    void cleanUp() {
        recycleLeftoverFromEarlierRun();
    }

    // ---------------------------------------------------------------- 造数与工具（与各域 IT 同口径）

    /** 宽表表头 + 字段键 → 列位索引（前置 9 + 基线锚点字典序 + 10 组 × 54；真实字段目录，与运行时同源）。 */
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
                    for (int group = 1; group <= 10; group++) {
                        for (ImportFieldCatalog.CatalogField field : catalog.followupFields()) {
                            if (field.originalHeader() == null) {
                                continue;
                            }
                            followupColumns.putIfAbsent(field.fieldKey(), header.size());
                            header.add(field.originalHeader());
                        }
                    }
                    return new WideLayout(header, baselineColumns, followupColumns);
                });
    }

    record WideLayout(
            List<String> header,
            Map<String, Integer> baselineColumns,
            Map<String, Integer> followupColumns) {}

    /** 2 行虚构宽表（流式 inlineStr 单工作表，ImportScale5000IT 同形状）。 */
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
            for (int i = 1; i <= 2; i++) {
                out.setLength(0);
                writeSparseRow(out, i + 1, patientRow(i, layout));
                zip.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            zip.write("</sheetData></worksheet>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    /** 一名虚构导入患者（稀疏列位；坐标与 E18 宽表适配器同源——表头即锚点，值按显示名 / 原表写法）。 */
    private static Map<Integer, String> patientRow(int i, WideLayout layout) {
        Map<Integer, String> cells = new LinkedHashMap<>();
        String mrn = LEGACY + "-M" + "%04d".formatted(i + 1);
        String legacy = LEGACY + "-L" + "%04d".formatted(i);
        String name = "导入回归" + "%04d".formatted(i) + "（示例）";
        String gender = i % 2 == 1 ? "男" : "女";
        String ward = i % 2 == 1 ? "心外一病区（示例）" : "心外二病区（示例）";
        LocalDate admission = DISCHARGE.minusDays(14);
        cells.put(0, String.valueOf(i));
        cells.put(1, legacy);
        cells.put(2, mrn);
        cells.put(3, name);
        cells.put(4, gender);
        cells.put(5, "65");
        cells.put(6, ymd(admission));
        cells.put(7, "冠心病（示例）");
        put(cells, layout.baselineColumns(), "f001", ymd(DISCHARGE.minusDays(10)));
        put(cells, layout.baselineColumns(), "f002", mrn);
        put(cells, layout.baselineColumns(), "f003", name);
        put(cells, layout.baselineColumns(), "f004", gender);
        put(cells, layout.baselineColumns(), "f005", "65");
        put(cells, layout.baselineColumns(), "f006", "冠心病（示例）");
        put(cells, layout.baselineColumns(), "f007", ward);
        put(cells, layout.baselineColumns(), "f009", "1390000" + "%04d".formatted(i));
        put(cells, layout.baselineColumns(), "f011", "回归手术备注（示例）");
        put(cells, layout.baselineColumns(), "f016", ymd(admission));
        put(cells, layout.baselineColumns(), "f017", ymd(DISCHARGE));
        put(cells, layout.baselineColumns(), "f191", "存活");
        int followupColumns =
                layout.header().size()
                        - WideLayoutAdapter.FRONT_COLUMNS.size()
                        - layout.baselineColumns().size();
        int groupWidth = followupColumns / 10;
        for (int year : List.of(1, 2)) {
            int offset = (year - 1) * groupWidth;
            putAt(
                    cells,
                    layout.followupColumns().get("f214") + offset,
                    ymd(DISCHARGE.plusYears(year)));
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

    private static String ymd(LocalDate date) {
        return YYYYMMDD.format(date);
    }

    private static void writeSparseRow(StringBuilder out, int rowNo, Map<Integer, String> cells) {
        out.append("<row r=\"").append(rowNo).append("\">");
        for (Map.Entry<Integer, String> entry : cells.entrySet()) {
            cell(out, rowNo, entry.getKey(), entry.getValue());
        }
        out.append("</row>");
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

    // ---------------------------------------------------------------- 业务造数（HTTP 正口）

    /** ECHO 首个名称（经胸超声心动图，V64 绑定 18 项指标模板）。 */
    private Map<?, ?> firstNameOf(String typeCode, String token) {
        List<?> names =
                (List<?>) get(BASE + "/report-names?typeCode=" + typeCode, token).get("data");
        return (Map<?, ?>) names.get(0);
    }

    private long createEchoReport(
            String token,
            String nameId,
            String categoryKey,
            String reportDate,
            List<Map<String, Object>> indicators) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("typeCode", "ECHO");
        body.put("nameId", nameId);
        body.put("reportDate", reportDate);
        body.put("findings", "各腔室内径正常（示例）");
        Map<String, Object> location = new HashMap<>();
        location.put("linkType", "baseline");
        location.put("anchorType", "category");
        location.put("anchorKey", categoryKey);
        body.put("locations", List.of(location));
        body.put("indicators", indicators);
        Map<?, ?> created = post(BASE + "/reports", token, body);
        assertEquals(0, codeOf(created), "创建报告单失败：" + created);
        return Long.parseLong(str(dataOf(created).get("id")));
    }

    private static Map<String, Object> indicator(String itemCode, String result) {
        Map<String, Object> item = new HashMap<>();
        item.put("itemCode", itemCode);
        item.put("result", result);
        return item;
    }

    /** 基线「超声检查」分类锚点（位置树按显示名定位，V38 分类 4；CompareHighlightIT 的 echo 分节）。 */
    private String baselineEchoCategoryKey(String token) {
        List<?> roots =
                (List<?>)
                        get(BASE + "/patients/" + patientId + "/report-locations", token)
                                .get("data");
        Map<?, ?> baseline =
                roots.stream()
                        .map(node -> (Map<?, ?>) node)
                        .filter(node -> "baseline".equals(node.get("key")))
                        .findFirst()
                        .orElseThrow();
        return (String)
                ((List<?>) baseline.get("children"))
                        .stream()
                                .filter(node -> str(((Map<?, ?>) node).get("title")).contains("超声"))
                                .map(
                                        node ->
                                                ((Map<?, ?>) ((Map<?, ?>) node).get("value"))
                                                        .get("anchorKey"))
                                .findFirst()
                                .orElseThrow();
    }

    /** fu_record.highlight 第一条命中（MySQL JSON 列输出带空格，按字段解析；无命中 null）。 */
    private Map<String, Object> firstHitOf(long recordId) {
        String stored =
                query(
                        "SELECT CAST(highlight AS CHAR) FROM fu_record WHERE id = ?",
                        String.class,
                        recordId);
        assertNotNull(stored, "提交后 highlight 应有命中（ef_drop）");
        try {
            return JSON.readValue(stored, new TypeReference<List<Map<String, Object>>>() {}).get(0);
        } catch (Exception broken) {
            throw new IllegalStateException("highlight 解析失败：" + stored, broken);
        }
    }

    // ---------------------------------------------------------------- 断言辅助

    private static Map<?, ?> rowByLabel(List<?> rows, String label) {
        return rows.stream()
                .map(row -> (Map<?, ?>) row)
                .filter(row -> label.equals(row.get("label")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少行 " + label + "：" + rows));
    }

    private static void assertRateRow(
            Map<?, ?> row, long due, long followed, long inWindow, double ratePct, String why) {
        assertEquals(due, num(row.get("dueCount")), "应随访（" + why + "）");
        assertEquals(followed, num(row.get("followedCount")), "已随访（" + why + "）");
        assertEquals(inWindow, num(row.get("inWindowCount")), "窗口内完成（" + why + "）");
        assertEquals(
                ratePct,
                ((Number) row.get("followRatePct")).doubleValue(),
                0.05d,
                "随访率主指标（" + why + "）");
    }

    private static Map<String, Object> metricsByCode(Map<?, ?> kpi) {
        Map<String, Object> byCode = new HashMap<>();
        for (Object item : (List<?>) kpi.get("metrics")) {
            Map<?, ?> metric = (Map<?, ?>) item;
            byCode.put(String.valueOf(metric.get("code")), metric);
        }
        return byCode;
    }

    /** 指标取值（简单指标为数值、复杂指标为带 value 的对象，DashboardCardsIT 同口径）。 */
    private static long metricValue(Object metricOrValue) {
        Object value = metricOrValue instanceof Map<?, ?> map ? map.get("value") : metricOrValue;
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static String cardPath(String cardCode) {
        return "/api/hospital/followup/dashboard/cards/" + cardCode + "?";
    }

    // ---------------------------------------------------------------- 导入 HTTP 与轮询

    /** multipart 上传（字段 file；与导入向导同一端点）。 */
    private Map<?, ?> upload(String token, byte[] workbook) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        ByteArrayResource file =
                new ByteArrayResource(workbook) {
                    @Override
                    public String getFilename() {
                        return IMPORT_FILE;
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
        assertEquals(0, codeOf(mapped), "上传失败：" + mapped);
        return mapped;
    }

    /** 轮询作业视图至期望终态（失败终态直接抛出并带原因）。 */
    private Map<?, ?> awaitStatus(long jobId, String token, long timeoutMs, String expected) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Map<?, ?> last = null;
        while (System.currentTimeMillis() < deadline) {
            last = get("/api/hospital/followup/imports/" + jobId, token);
            String status = (String) dataOf(last).get("status");
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
                Thread.sleep(300);
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
        Map<?, ?> view = dataOf(response);
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

    // ---------------------------------------------------------------- HTTP 与上下文

    private void deleteKeys(String pattern) {
        var keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /** 平台库直连（视角会话与平台审计核对；IT 环境本机端口）。 */
    private JdbcTemplate platformDirect() {
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        return new JdbcTemplate(
                new DriverManagerDataSource(
                        base + "/crdp_platform" + params, "crdp_app", "DevOnly#app2026"));
    }

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
        assertEquals(0, codeOf(response), "医院登录失败：" + response);
        return (String) dataOf(response).get("token");
    }

    private String loginToken(String username, String password) {
        Map<?, ?> login =
                post(
                        "/api/platform/auth/login",
                        null,
                        Map.of("username", username, "password", password));
        assertEquals(0, codeOf(login), "平台登录失败：" + login.get("message"));
        return (String) dataOf(login).get("token");
    }

    private Map<?, ?> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    private Map<?, ?> delete(String path, String token) {
        return exchange(HttpMethod.DELETE, path, token, null);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> exchange(
            HttpMethod method, String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return (Map<?, ?>)
                rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private static Map<?, ?> dataOf(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static long num(Object value) {
        return ((Number) value).longValue();
    }

    private static String str(Object value) {
        return String.valueOf(value);
    }

    private long defaultWardId() {
        return query("SELECT MIN(id) FROM ward", Long.class);
    }

    private int count(String sql, Object... args) {
        return runInTenant("demoa", () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    /** 租户上下文内的单值查询（直接用 jdbc 须先挂租户，AGENTS §5.1）。 */
    private <T> T query(String sql, Class<T> type, Object... args) {
        return runInTenant("demoa", () -> jdbc.queryForObject(sql, type, args));
    }

    /** 租户上下文内的单行查询（列名 → 值）。 */
    private Map<String, Object> queryMap(String sql, Object... args) {
        return runInTenant("demoa", () -> jdbc.queryForMap(sql, args));
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant(1L, tenant, action::get);
    }

    private <T> T callInTenant(Supplier<T> action) {
        return TenantContext.callAsTenant(1L, "demoa", action::get);
    }
}
