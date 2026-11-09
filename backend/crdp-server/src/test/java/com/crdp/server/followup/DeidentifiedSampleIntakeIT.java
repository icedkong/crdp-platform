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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * E23-T05 / A-PAT-018 端到端（tasks.csv 验收原文「医院提供的样本经脱敏后校验通过」）： 医院宽表样本（真实数据永不进仓库，此处以虚构但同形态的「原始样本」代替）→
 * <strong>scripts/deidentify_sample.py 脱敏</strong>（工具子进程真跑，锚点来自 V38）→ 导入向导同链路上传 → 预检
 * <strong>PRECHECKED 且 errorCount = 0</strong>（校验通过；Q36 / Q37 脏值按口径进人工复核清单）。
 *
 * <p>只做预检不确认提交——正式导入演练是 E23-T06（数据核对）与 E23-T07（技术执行）； 同时断言预检不写业务表（tech-plan §12④「不写入数据」）。单元层同口径见
 * {@link DeidentifiedSampleValidateTest}； 运行手册 `docs/ops/hospital-a-sample-intake.md`。 门控
 * CRDP_IT_MYSQL_PORT（真实 MySQL，与 ImportScale5000IT 同款）；样本全部虚构（规则 5）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeidentifiedSampleIntakeIT {

    private static final String PASSWORD = "Demo@2026";

    /** 演示租户（医院 A）与向导操作账号（与 ImportScale5000IT 同款）。 */
    private static final String TENANT = "demoa";

    private static final long TENANT_ID = 1L;

    /** 医院样例宽表 10 组随访列（Q05）→ 9 + 212 + 10 × 54 = 761 列。 */
    private static final int FOLLOWUP_GROUPS = 10;

    private static final int PATIENTS = 3;

    private static final String RAW_FILE = "E23T05医院样本（示例）.xlsx";

    private static final String DEID_FILE = "E23T05脱敏样本（示例）.xlsx";

    /** 脱敏工具的固定 salt（同输入可复现映射；IT 断言与 salt 无关，固定值只为失败重跑可比较）。 */
    private static final String SALT = "it-e23t05";

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final long PRECHECK_TIMEOUT_MS = 5 * 60_000L;

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private ImportFieldCatalogLoader catalogLoader;

    @Autowired private ObjectStorage storage;

    @BeforeEach
    @AfterAll
    void cleanState() {
        runInTenant(
                () -> {
                    for (String fileName : List.of(RAW_FILE, DEID_FILE)) {
                        List<Map<String, Object>> jobs =
                                jdbc.queryForList(
                                        "SELECT id, storage_key AS storageKey FROM imp_job WHERE"
                                                + " file_name = ?",
                                        fileName);
                        for (Map<String, Object> job : jobs) {
                            String key = (String) job.get("storageKey");
                            if (key != null && !key.isBlank()) {
                                storage.delete(key);
                            }
                        }
                        jdbc.update(
                                "DELETE FROM imp_issue WHERE job_id IN (SELECT id FROM imp_job"
                                        + " WHERE file_name = '"
                                        + fileName
                                        + "')");
                        jdbc.update(
                                "DELETE FROM imp_row WHERE job_id IN (SELECT id FROM imp_job WHERE"
                                        + " file_name = '"
                                        + fileName
                                        + "')");
                        jdbc.update("DELETE FROM imp_job WHERE file_name = ?", fileName);
                    }
                    // 预检不写业务表：正常路径为 0 行，清理防御中断残留
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE 'L00000%'");
                });
    }

    /**
     * 全链路：生成「医院原始样本」（虚构标识、真实形态脏值）→ python 脱敏工具（子进程）→ 上传脱敏输出 → 预检 0 错误（校验通过）→ Q36 / Q37 警告在人工复核清单 →
     * 业务表无写入。
     */
    @Test
    void deidentifiedHospitalSamplePassesPrecheckWithZeroErrors() throws Exception {
        // ① 医院原始样本（仓库外临时目录；标识值虚构但形态真实：中文姓名、数字病案号、11 位电话）
        Path repo = repoRoot();
        WideLayout layout = wideLayout();
        assertEquals(761, layout.header().size(), "医院样例 9 + 212 + 10 × 54 = 761 列");
        Path raw = Files.createTempFile("e23t05-raw", ".xlsx");
        Path deid = Files.createTempFile("e23t05-deid", ".xlsx");
        raw.toFile().deleteOnExit();
        deid.toFile().deleteOnExit();
        Files.write(raw, hospitalRawWorkbook(layout));
        assertTrue(
                !raw.toAbsolutePath().normalize().startsWith(repo),
                "医院原始样本必须在仓库外（AGENTS §5.5：医院提供的样例不进仓库）");

        // ② 脱敏工具（真跑 scripts/deidentify_sample.py；含写后自检，非 0 退出即失败）
        Process tool =
                new ProcessBuilder(
                                "python3",
                                "scripts/deidentify_sample.py",
                                raw.toAbsolutePath().toString(),
                                "-o",
                                deid.toAbsolutePath().toString(),
                                "--salt",
                                SALT)
                        .directory(repo.toFile())
                        .redirectErrorStream(true)
                        .start();
        String toolOutput =
                new String(tool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, tool.waitFor(), "脱敏工具应成功退出：" + toolOutput);
        assertTrue(toolOutput.contains("脱敏完成"), "工具应报告完成：" + toolOutput);
        assertTrue(toolOutput.contains("mrn"), "工具应报告病案号列族替换计数：" + toolOutput);
        byte[] deidentified = Files.readAllBytes(deid);
        assertTrue(deidentified.length > 0, "脱敏输出非空");

        // ③ 上传脱敏样本（导入向导同一端点）→ 预检至 PRECHECKED
        String token = hospitalToken();
        Map<?, ?> uploaded = upload(token, deidentified, DEID_FILE);
        long jobId = ((Number) data(uploaded).get("jobId")).longValue();
        Map<?, ?> prechecked = awaitStatus(jobId, token, PRECHECK_TIMEOUT_MS, "PRECHECKED");
        assertEquals("WIDE", data(prechecked).get("layout"), "宽表版面识别（医院现有格式）");
        assertEquals(
                PATIENTS, ((Number) data(prechecked).get("stagedRows")).longValue(), "3 行全部暂存");
        assertEquals(
                0L,
                ((Number) data(prechecked).get("errorCount")).longValue(),
                "验收：医院提供的样本经脱敏后校验通过（0 错误；有 ERROR 确认提交被拦）");

        // ④ 警告 = 人工复核清单：Q36（是否类 0/1）/ Q37（编码+文字）按口径进清单；前置列与基线列一致（无 FRONT_MISMATCH）
        long warnCount = ((Number) data(prechecked).get("warnCount")).longValue();
        assertTrue(warnCount >= 2, "Q36 / Q37 至少两条复核警告：" + warnCount);
        Map<?, ?> issues = issues(jobId, token, "WARN");
        List<String> codes =
                ((List<?>) data(issues).get("items"))
                        .stream().map(item -> (String) ((Map<?, ?>) item).get("code")).toList();
        assertTrue(codes.contains("BOOLEAN_01"), "Q36 是否类「0/1」复核警告应在册：" + codes);
        assertTrue(codes.contains("OPTION_CODE_TEXT"), "Q37「编码+文字」复核警告应在册：" + codes);
        assertTrue(!codes.contains("FRONT_MISMATCH"), "脱敏工具保持前置列与基线列一致（同一原值 → 同一虚构值）：" + codes);
        Map<?, ?> errors = issues(jobId, token, "ERROR");
        assertEquals(0L, ((Number) data(errors).get("total")).longValue(), "无错误级问题");

        // ⑤ 预检不写业务表（tech-plan §12④）：3 行只是暂存，患者主档无该样本病案号
        runInTenant(
                () ->
                        assertEquals(
                                0L,
                                jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM pt_patient WHERE legacy_no"
                                                        + " LIKE 'L00000%'",
                                                Long.class)
                                        .longValue(),
                                "预检阶段不得写入患者主档（正式导入是 E23-T06/T07）"));
    }

    // ---------------------------------------------------------------- 原始样本造数（虚构标识、真实形态）

    /** 仓库根（surefire 的 user.dir 是模块目录，向上找到 scripts/deidentify_sample.py 为止）。 */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath().normalize();
        while (dir != null) {
            if (Files.exists(dir.resolve("scripts/deidentify_sample.py"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "从 " + Path.of("").toAbsolutePath() + " 向上找不到仓库根（scripts/deidentify_sample.py）");
    }

    /** 宽表表头 + 字段键 → 列位（前置 9 + 基线锚点字典序 + 组 × 54；真实字段目录，随访取第 1 组列位）。 */
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
                    for (ImportFieldCatalog.CatalogField field : catalog.followupFields()) {
                        if (field.originalHeader() != null) {
                            followupColumns.put(field.fieldKey(), header.size());
                            header.add(field.originalHeader());
                        }
                    }
                    int groupWidth = followupColumns.size();
                    for (int group = 1; group < FOLLOWUP_GROUPS; group++) {
                        catalog.followupFields().stream()
                                .filter(field -> field.originalHeader() != null)
                                .forEach(field -> header.add(field.originalHeader()));
                    }
                    assertEquals(54, groupWidth, "随访锚点列每组 54 列（含停用的 #217 死亡随访时间）");
                    return new WideLayout(header, baselineColumns, followupColumns);
                });
    }

    /**
     * 医院原始样本（3 名虚构患者，标识形态真实：中文姓名 / 数字病案号 / 11 位电话； 脏值形态：N/A、0/1、编码+文字、yyyymmdd 日期）。 inlineStr
     * 全文本单工作表（SimpleXlsx 形状，与 ImportScale5000IT 同款）。
     */
    private byte[] hospitalRawWorkbook(WideLayout layout) throws IOException {
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
                Map<Integer, String> row = rawRow(i, layout);
                final int rowNo = i + 1;
                final StringBuilder sink = out;
                out.append("<row r=\"").append(rowNo).append("\">");
                row.forEach((column, value) -> cell(sink, rowNo, column, value));
                out.append("</row>");
                zip.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            zip.write("</sheetData></worksheet>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    /** 一名「医院原始样本」患者（稀疏列位；标识值虚构但形态真实，全部示例标记）。 */
    private static Map<Integer, String> rawRow(int i, WideLayout layout) {
        Map<String, Integer> columns = layout.baselineColumns();
        Map<Integer, String> cells = new LinkedHashMap<>();
        LocalDate discharge = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusYears(4).plusDays(30);
        LocalDate admission = discharge.minusDays(14);
        LocalDate surgery = discharge.minusDays(10);
        String gender = i % 2 == 1 ? "男" : "女";
        // 前置列（医院原系统导出形态）
        cells.put(0, String.valueOf(i));
        cells.put(1, "P" + "%04d".formatted(i)); // 患者编号 → 原系统编号
        cells.put(2, "%07d".formatted(1000000 + i)); // 病案号（数字形态）
        cells.put(3, "测试患者" + "甲乙丙".charAt(i - 1) + "（示例）"); // 姓名
        cells.put(4, gender);
        cells.put(5, "66");
        cells.put(6, ymd(admission));
        cells.put(7, "冠心病");
        cells.put(8, "98%");
        // 基线必填 + 标识列 + 脏值形态
        put(cells, columns, "f001", ymd(surgery));
        put(cells, columns, "f002", "%07d".formatted(1000000 + i));
        put(cells, columns, "f003", "测试患者" + "甲乙丙".charAt(i - 1) + "（示例）");
        put(cells, columns, "f004", gender);
        put(cells, columns, "f005", "66");
        put(cells, columns, "f006", "冠心病");
        put(cells, columns, "f007", "心外一病区（示例）"); // 病区值不脱敏（非标识），走病区字典映射
        put(cells, columns, "f009", "137" + "%08d".formatted(i)); // 电话1（11 位形态）
        put(cells, columns, "f010", "N/A"); // N/A → 导入侧归一为空
        put(cells, columns, "f013", "170");
        put(cells, columns, "f037", "2二级"); // Q37 编码+文字
        put(cells, columns, "f016", ymd(admission));
        put(cells, columns, "f017", ymd(discharge));
        put(cells, columns, "f191", "存活");
        // 第 1 年随访（第 2–10 组全空）
        Map<String, Integer> followup = layout.followupColumns();
        put(cells, followup, "f214", ymd(discharge.plusYears(1))); // 随访日期
        put(cells, followup, "f215", "否"); // 是否死亡
        put(cells, followup, "f213", "1"); // 是否已加微信 = 1（Q36：0/1 按空值导入）
        put(cells, followup, "f248", "45"); // ef_射血分数（软范围 5–85 内）
        return cells;
    }

    private static void put(
            Map<Integer, String> cells, Map<String, Integer> columns, String key, String value) {
        Integer column = columns.get(key);
        assertNotNull(column, "字段目录缺少宽表锚点列：" + key);
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
    private Map<?, ?> upload(String token, byte[] workbook, String fileName) {
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
        assertEquals(0, code(mapped), "上传失败：" + mapped);
        return mapped;
    }

    /** 预检问题分页（level 过滤）。 */
    private Map<?, ?> issues(long jobId, String token, String level) {
        Map<?, ?> response =
                get("/api/hospital/followup/imports/" + jobId + "/issues?level=" + level, token);
        assertEquals(0, code(response), "问题分页查询失败：" + response);
        return response;
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
            if ("PRECHECK_FAILED".equals(status) || "CANCELLED".equals(status)) {
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
                + view.get("stagedRows");
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

    private void runInTenant(Runnable action) {
        TenantContext.runAsTenant(TENANT_ID, TENANT, action);
    }

    private <T> T callInTenant(Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_ID, TENANT, action::get);
    }
}
