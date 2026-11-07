package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * E16-T12 端到端（任务验收「10 类资料与查看器用例通过」）：A-ATT-003 / C-SCAN-002 的 10 类预置资料逐类归档
 * 并在患者内清单（左栏导航数据源，A-PAT-020）与全院检索可见；A-ATT-005 / C-SCAN-015 查看器格式矩阵——报告单附件 挂台账后经 preview-url
 * 签发与公开取流口可看（图片 / PDF / DOCX 页内预览，MP3 原生播放，XLSX / AMR 只下载）； A-ATT-021
 * 读路径口径——旧模板版本的位置锚点在退回作业（E16-T15）落地前保持可见，不因版本不匹配丢位置。
 *
 * <p>与既有用例的分工：ReportConfigIT 锁配置（10 类清单），ReportIT 锁创建校验与审计，FileServiceAcceptanceIT 锁 MP3 全链路与伪装拒绝；本
 * IT 把「10 类 × 查看器格式矩阵 × 位置可见性」串成一条动线（每个格式至少落到一类资料上）。 全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportViewerIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String BASE = "/api/hospital/followup";
    private static final String UPLOAD = "/api/hospital/files";
    private static final String LEGACY = "IT-E16T12";
    private static final String OTHER_NAME = LEGACY + " 外院资料（示例）";

    /** 10 类预置（A-ATT-003 / V62）× 查看器格式：格式矩阵覆盖 png / jpg / pdf / docx / mp3 / xlsx。 */
    private static final List<String[]> TYPES_WITH_FORMAT =
            List.of(
                    new String[] {"ECHO", "心脏超声", "pdf"},
                    new String[] {"ANGIO", "冠脉造影", "jpg"},
                    new String[] {"ECG", "心电图", "png"},
                    new String[] {"CHEST_IMAGING", "胸片 / CT", "png"},
                    new String[] {"LAB", "检验", "png"},
                    new String[] {"DISCHARGE_SUMMARY", "出院小结", "docx"},
                    new String[] {"OPERATION_RECORD", "手术记录", "pdf"},
                    new String[] {"CONSENT", "知情同意书", "jpg"},
                    new String[] {"CALL", "通话记录", "mp3"},
                    new String[] {"OTHER", "其他", "xlsx"});

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanState() {
        for (String tenant : new String[] {"demoa"}) {
            runInTenant(
                    tenant,
                    () -> {
                        jdbc.update(
                                "DELETE v FROM rpt_indicator_value v"
                                        + " JOIN rpt_report r ON r.id = v.report_id"
                                        + " JOIN pt_patient p ON p.id = r.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE f FROM rpt_report_file f"
                                        + " JOIN rpt_report r ON r.id = f.report_id"
                                        + " JOIN pt_patient p ON p.id = r.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE l FROM rpt_report_link l"
                                        + " JOIN pt_patient p ON p.id = l.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE r FROM rpt_report r"
                                        + " JOIN pt_patient p ON p.id = r.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE FROM fu_record WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                                LEGACY + "-%");
                        // FILE_PREVIEW 审计按 object_id 关联本用例上传的文件（须在删台账前清）
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE action = 'FILE_PREVIEW'"
                                        + " AND object_id IN (SELECT id FROM sys_file WHERE"
                                        + " original_name LIKE ?)",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE FROM sys_file WHERE original_name LIKE ?", LEGACY + "-%");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                        jdbc.update("DELETE FROM rpt_name WHERE name = ?", OTHER_NAME);
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE action IN ('RPT_CREATE',"
                                        + " 'RPT_NAME_CREATE')");
                        // 全院检索写 BATCH_QUERY 数据访问审计
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_data_access_log WHERE object_type = 'REPORT'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：10 类资料用例通过

    /**
     * 10 类预置逐类归档：每类上传对应格式文件、以预置名称创建（「其他」无预置名称，先经配置口建名）， 患者内清单 10 份（typeName 与位置标签齐全，左栏导航 scope
     * 过滤按位置侧给出 10 / 0），全院检索逐类命中 且 firstFile 带出查看器所需格式（E16-T06 从列表缩略图 / 类型图标打开首附件）。
     */
    @Test
    void tenPresetTypesEachArchiveListAndSearch() throws IOException {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient("demoa", admin);
        String categoryKey = firstBaselineCategoryKey(admin, patientId);
        Map<String, String> nameByType = nameOfEachType(admin);

        Map<String, Map<?, ?>> createdByType = new LinkedHashMap<>();
        int day = 0;
        for (String[] spec : TYPES_WITH_FORMAT) {
            String typeCode = spec[0];
            String format = spec[2];
            long fileId =
                    upload(admin, LEGACY + "-" + typeCode + "（示例）." + format, fileBytesOf(format));
            Map<String, Object> body = new HashMap<>();
            body.put("patientId", patientId);
            body.put("typeCode", typeCode);
            body.put("nameId", nameByType.get(typeCode));
            body.put("reportDate", LocalDate.of(2026, 9, 1).plusDays(day++).toString());
            body.put(
                    "locations",
                    List.of(
                            Map.of(
                                    "linkType",
                                    "baseline",
                                    "anchorType",
                                    "category",
                                    "anchorKey",
                                    categoryKey)));
            body.put("fileIds", List.of(String.valueOf(fileId)));
            Map<?, ?> created = post(BASE + "/reports", admin, body);
            assertEquals(0, code(created), typeCode + " 创建失败：" + created);
            createdByType.put(typeCode, data(created));
        }
        assertEquals(10, createdByType.size(), "10 类各一份");

        // 患者内清单（报告单页签与左栏导航数据源）：10 份、类型名与位置标签齐全
        Map<?, ?> page = data(get(BASE + "/patients/" + patientId + "/reports", admin));
        List<?> items = (List<?>) page.get("items");
        assertEquals(10, items.size(), "10 类资料各一份");
        List<String> typeNames =
                items.stream()
                        .map(node -> str(((Map<?, ?>) node).get("typeName")))
                        .sorted()
                        .toList();
        assertEquals(
                TYPES_WITH_FORMAT.stream().map(spec -> spec[1]).sorted().toList(),
                typeNames,
                "类型名与 A 规格预置资料类型表一致");
        for (Object node : items) {
            Map<?, ?> row = (Map<?, ?>) node;
            assertEquals(1L, ((Number) row.get("fileCount")).longValue());
            List<?> locations = (List<?>) row.get("locations");
            assertEquals(1, locations.size(), "位置至少 1 个（A-ATT-002）");
            Map<?, ?> tag = (Map<?, ?>) locations.get(0);
            assertNotNull(tag.get("anchorLabel"), "位置标签带分类显示名：" + row.get("typeName"));
            assertNotNull(
                    ((Number) tag.get("templateVersion")).intValue() > 0
                            ? tag.get("templateVersion")
                            : null,
                    "位置带锚点所属模板版本（A-ATT-021 按版本保存）");
        }

        // 左栏导航 scope 口径（A-PAT-020 / E16-T14 消费）：baseline 只留含基线位置的报告单
        assertEquals(
                10,
                ((List<?>)
                                data(get(
                                                BASE
                                                        + "/patients/"
                                                        + patientId
                                                        + "/reports?scope=baseline",
                                                admin))
                                        .get("items"))
                        .size(),
                "全部含基线分类位置");
        assertEquals(
                0,
                ((List<?>)
                                data(get(
                                                BASE
                                                        + "/patients/"
                                                        + patientId
                                                        + "/reports?scope=followup",
                                                admin))
                                        .get("items"))
                        .size(),
                "没有随访记录位置的报告单不进随访页签左栏");

        // 全院检索逐类命中，行带 firstFile（查看器从列表打开首附件的格式来源）
        for (String[] spec : TYPES_WITH_FORMAT) {
            String typeCode = spec[0];
            Map<?, ?> result =
                    data(post(BASE + "/reports/search", admin, Map.of("typeCode", typeCode)));
            List<?> rows = (List<?>) result.get("items");
            assertEquals(1, rows.size(), typeCode + " 检索应命中 1 份：" + rows.size());
            Map<?, ?> row = (Map<?, ?>) rows.get(0);
            assertEquals(typeCode, row.get("typeCode"));
            Map<?, ?> firstFile = (Map<?, ?>) row.get("firstFile");
            assertNotNull(firstFile, "行带 firstFile（E16-T06 全院列表打开查看器）");
            assertEquals(spec[2], firstFile.get("format"), typeCode + " 首附件格式");
        }
        assertEquals(
                10,
                ((List<?>) data(post(BASE + "/reports/search", admin, Map.of())).get("types"))
                        .size(),
                "检索响应的类型候选 = 10 类启用类型");
    }

    // ---------------------------------------------------------------- 验收：查看器用例通过（格式矩阵）

    /**
     * 查看器格式矩阵（A-ATT-005）：一份报告单挂 7 种格式附件（png / jpg / pdf / docx / xlsx / mp3 / amr）， preview-url
     * 逐个签发——图片 / PDF / DOCX / MP3 可内联（页内预览与原生播放），XLSX / AMR 只下载
     * （inlinePlayable=false）；图片经公开取流口回流原字节（inline、no-store）；每次签发写 FILE_PREVIEW 审计。
     */
    @Test
    void viewerFormatMatrixAcrossPreviewChain() throws IOException {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient("demoa", admin);
        String categoryKey = firstBaselineCategoryKey(admin, patientId);
        String otherNameId = ensureOtherName(admin);

        List<String> formats = List.of("png", "jpg", "pdf", "docx", "xlsx", "mp3", "amr");
        Map<String, byte[]> bytesByFormat = new LinkedHashMap<>();
        Map<String, Long> fileIdByFormat = new LinkedHashMap<>();
        List<String> fileIds = new ArrayList<>();
        for (String format : formats) {
            byte[] body = fileBytesOf(format);
            bytesByFormat.put(format, body);
            long fileId = upload(admin, LEGACY + "-查看器-" + format + "（示例）." + format, body);
            fileIdByFormat.put(format, fileId);
            fileIds.add(String.valueOf(fileId));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("typeCode", "OTHER");
        body.put("nameId", otherNameId);
        body.put("reportDate", "2026-09-20");
        body.put(
                "locations",
                List.of(
                        Map.of(
                                "linkType",
                                "baseline",
                                "anchorType",
                                "category",
                                "anchorKey",
                                categoryKey)));
        body.put("fileIds", fileIds);
        Map<?, ?> created = post(BASE + "/reports", admin, body);
        assertEquals(0, code(created), "多格式报告单创建失败：" + created);
        assertEquals(7, ((Number) data(created).get("fileCount")).intValue());

        runInTenant(
                "demoa",
                () ->
                        assertEquals(
                                7L,
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_file WHERE status = 'ATTACHED'"
                                                + " AND id IN ("
                                                + String.join(
                                                        ",",
                                                        fileIds.stream().map(f -> "?").toList())
                                                + ")",
                                        Long.class,
                                        fileIds.toArray()),
                                "7 个附件全部挂上报告单（置 ATTACHED）"));

        Map<String, Object> playableByFormat =
                Map.of(
                        "png", Boolean.TRUE,
                        "jpg", Boolean.TRUE,
                        "pdf", Boolean.TRUE,
                        "docx", Boolean.TRUE,
                        "mp3", Boolean.TRUE,
                        "xlsx", Boolean.FALSE,
                        "amr", Boolean.FALSE);
        for (String format : formats) {
            Map<?, ?> grant =
                    data(
                            get(
                                    "/api/hospital/files/"
                                            + fileIdByFormat.get(format)
                                            + "/preview-url",
                                    admin));
            assertEquals(format, grant.get("format"), "签发回带真实格式：" + format);
            assertEquals(
                    playableByFormat.get(format),
                    grant.get("inlinePlayable"),
                    format + " 的查看器口径（A-ATT-005：XLSX / AMR 只下载）");
            assertTrue(
                    ((Number) grant.get("expiresAtEpochSecond")).longValue()
                            > System.currentTimeMillis() / 1000,
                    format + " 链接未过期");
            assertTrue(
                    str(grant.get("url")).startsWith("/api/hospital/files/preview/"),
                    "短时链接指向公开取流口");
        }

        // 图片取流：inline 回流原字节、不缓存（全屏查看器 <img> 的真实链路）
        Map<?, ?> pngGrant =
                data(
                        get(
                                "/api/hospital/files/" + fileIdByFormat.get("png") + "/preview-url",
                                admin));
        ResponseEntity<byte[]> streamed = rest.getForEntity(str(pngGrant.get("url")), byte[].class);
        assertEquals(200, streamed.getStatusCode().value());
        assertEquals(MediaType.parseMediaType("image/png"), streamed.getHeaders().getContentType());
        assertTrue(
                streamed.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("inline"),
                "预览取流 inline 展示");
        assertTrue(
                streamed.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL).contains("no-store"),
                "预览不缓存");
        assertTrue(
                streamed.getBody() != null
                        && streamed.getBody().length == bytesByFormat.get("png").length,
                "取流字节与上传一致");

        runInTenant(
                "demoa",
                () -> {
                    String placeholders = String.join(",", fileIds.stream().map(f -> "?").toList());
                    long previewAudits =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_log WHERE action = 'FILE_PREVIEW'"
                                            + " AND object_id IN ("
                                            + placeholders
                                            + ")",
                                    Long.class,
                                    fileIds.toArray());
                    assertEquals(
                            formats.size() + 1L,
                            previewAudits,
                            "每次签发写一条 FILE_PREVIEW 审计（含 png 二次签发）");
                });
    }

    // ---------------------------------------------------------------- 位置可见性（退回作业 E16-T15 的读路径前提）

    /**
     * A-ATT-021 读路径：位置按模板版本保存，但展示 / 检索不因锚点版本旧于当前版本而丢位置—— 退回作业（E16-T15）落地前，旧版本锚点行保持可见且不标
     * adjusted；本用例锁定该前提， 防止「提前按版本过滤」把历史位置静默吞掉。
     */
    @Test
    void staleVersionAnchorStaysVisibleUntilFallbackJob() {
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient("demoa", admin);
        long recordId = backfillRecord(admin, patientId, "2026-06-10");
        Map<?, ?> recordNode = firstRecordNode(admin, patientId);
        Map<?, ?> firstSection = (Map<?, ?>) ((List<?>) recordNode.get("children")).get(0);
        String sectionKey = str(((Map<?, ?>) firstSection.get("value")).get("anchorKey"));

        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("typeCode", "ECG");
        body.put("nameId", firstNameId(admin, "ECG"));
        body.put("reportDate", "2026-09-15");
        List<Map<String, Object>> locations = new ArrayList<>();
        Map<String, Object> section = new HashMap<>();
        section.put("linkType", "followup_record");
        section.put("linkRecordId", String.valueOf(recordId));
        section.put("anchorType", "section");
        section.put("anchorKey", sectionKey);
        locations.add(section);
        body.put("locations", locations);
        Map<?, ?> created = post(BASE + "/reports", admin, body);
        assertEquals(0, code(created), "创建失败：" + created);
        long reportId = Long.parseLong(str(data(created).get("id")));

        // 模拟模板改版：锚点行版本被甩在旧版本（退回作业 E16-T15 未跑）
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE rpt_report_link SET template_version = template_version +"
                                        + " 100 WHERE report_id = ?",
                                reportId));

        Map<?, ?> page = data(get(BASE + "/patients/" + patientId + "/reports", admin));
        List<?> items = (List<?>) page.get("items");
        assertEquals(1, items.size(), "旧版本锚点的报告单仍可见");
        Map<?, ?> tag = (Map<?, ?>) ((List<?>) ((Map<?, ?>) items.get(0)).get("locations")).get(0);
        assertNotNull(tag.get("anchorLabel"), "旧版本锚点仍带分组显示名");
        assertFalse(Boolean.TRUE.equals(tag.get("adjusted")), "退回作业（E16-T15）落地前不标「原位置已调整」");

        // 检索按位置（含随访记录）不受旧版本锚点影响
        Map<?, ?> result =
                data(post(BASE + "/reports/search", admin, Map.of("locationScope", "FOLLOWUP")));
        assertTrue(
                ((List<?>) result.get("items"))
                        .stream()
                                .anyMatch(
                                        node ->
                                                String.valueOf(((Map<?, ?>) node).get("id"))
                                                        .equals(String.valueOf(reportId))),
                "位置检索仍命中该报告单");
    }

    // ---------------------------------------------------------------- 造数与工具（口径同 ReportIT /
    // FileServiceAcceptanceIT）

    /** 「其他」无预置名称（V62 注释），经配置口新建后返回 id（租户管理员有 followup:scan:config）。 */
    private String ensureOtherName(String token) {
        Map<?, ?> created =
                post(
                        BASE + "/report-config/names",
                        token,
                        Map.of("typeCode", "OTHER", "name", OTHER_NAME, "sortNo", 10));
        assertEquals(0, code(created), "「其他」类型建名失败：" + created);
        return str(data(created).get("id"));
    }

    /** 每类取预置名称（V62；OTHER 无预置名称，走配置口新建）。 */
    private Map<String, String> nameOfEachType(String token) {
        Map<String, String> byType = new LinkedHashMap<>();
        for (String[] spec : TYPES_WITH_FORMAT) {
            if ("OTHER".equals(spec[0])) {
                byType.put(spec[0], ensureOtherName(token));
            } else {
                byType.put(spec[0], firstNameId(token, spec[0]));
            }
        }
        return byType;
    }

    private String firstNameId(String token, String typeCode) {
        List<?> names =
                (List<?>) get(BASE + "/report-names?typeCode=" + typeCode, token).get("data");
        assertFalse(names.isEmpty(), typeCode + " 应有预置名称");
        return str(((Map<?, ?>) names.get(0)).get("id"));
    }

    /** 位置树基线根第一个分类的 anchorKey（A_BASELINE 当前发布版分类，子节点全部可选）。 */
    private String firstBaselineCategoryKey(String token, long patientId) {
        List<?> roots =
                (List<?>)
                        get(BASE + "/patients/" + patientId + "/report-locations", token)
                                .get("data");
        Map<?, ?> baselineRoot =
                roots.stream()
                        .map(node -> (Map<?, ?>) node)
                        .filter(node -> "baseline".equals(node.get("key")))
                        .findFirst()
                        .orElseThrow();
        Map<?, ?> first = (Map<?, ?>) ((List<?>) baselineRoot.get("children")).get(0);
        return str(((Map<?, ?>) first.get("value")).get("anchorKey"));
    }

    /** 位置树随访记录根的第一条记录节点（整条 + 分组 value）。 */
    private Map<?, ?> firstRecordNode(String token, long patientId) {
        List<?> roots =
                (List<?>)
                        get(BASE + "/patients/" + patientId + "/report-locations", token)
                                .get("data");
        Map<?, ?> followupRoot =
                roots.stream()
                        .map(node -> (Map<?, ?>) node)
                        .filter(node -> "followup".equals(node.get("key")))
                        .findFirst()
                        .orElseThrow();
        return (Map<?, ?>) ((List<?>) followupRoot.get("children")).get(0);
    }

    private long registeredPatient(String tenant, String token) {
        Map<String, Object> body = new HashMap<>();
        String legacyNo = LEGACY + "-" + (System.nanoTime() % 100000);
        body.put("medicalRecordNo", legacyNo);
        body.put("name", "周报告（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", defaultWardId(tenant));
        body.put("admissionDate", LocalDate.now().minusDays(23).toString());
        body.put("surgeryDate", LocalDate.now().minusDays(22).toString());
        body.put("dischargeDate", LocalDate.now().minusDays(20).toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", legacyNo);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记患者失败：" + created);
        return Long.parseLong(str(data(created).get("id")));
    }

    /** 历史补录建一条 fu_record（E15-T07 接口；未入组患者须显式指定 formCode），返回 recordId。 */
    private long backfillRecord(String token, long patientId, String visitDate) {
        Map<?, ?> created =
                post(
                        BASE + "/patients/" + patientId + "/records",
                        token,
                        Map.of(
                                "source",
                                "BACKFILL",
                                "visitDate",
                                visitDate,
                                "formCode",
                                "A_ANNUAL_FU"));
        assertEquals(0, code(created), "历史补录失败：" + created);
        return Long.parseLong(str(data(created).get("recordId")));
    }

    /** 各格式的最小合法样例（能过 Tika 真实类型与图片解码；口径同 FileTypeDetectorTest / ContactLogIT）。 */
    private static byte[] fileBytesOf(String format) throws IOException {
        return switch (format) {
            case "png" -> png();
            case "jpg" -> jpeg();
            case "pdf" -> pdf();
            case "docx" ->
                    ooxml(
                            "/word/document.xml",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml");
            case "xlsx" ->
                    ooxml(
                            "/xl/workbook.xml",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml");
            case "mp3" -> mp3(128);
            case "amr" -> "#!AMR\n".getBytes(StandardCharsets.US_ASCII);
            default -> throw new IllegalArgumentException("未覆盖格式：" + format);
        };
    }

    private long upload(String token, String filename, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(
                "file",
                new ByteArrayResource(body) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                });
        @SuppressWarnings("unchecked")
        Map<?, ?> response =
                (Map<?, ?>)
                        rest.postForEntity(UPLOAD, new HttpEntity<>(parts, headers), Map.class)
                                .getBody();
        assertNotNull(response, "上传响应体不应为空");
        assertEquals(0, code(response), "上传失败：" + response);
        return Long.parseLong(str(data(response).get("fileId")));
    }

    /** 合法 1×1 PNG（与 ReportIT 同一张）。 */
    private static byte[] png() {
        return Base64.getDecoder()
                .decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
                                + "AAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    }

    /** 真实可解码 1×1 JPEG（ImageIO 生成——上传口会解码生成缩略图，只有 JFIF 头的样例过不了）。 */
    private static byte[] jpeg() throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /** 最小 PDF（Tika 认 %PDF- 魔数；非图片不进缩略图解码）。 */
    private static byte[] pdf() {
        return ("%PDF-1.7\n%"
                        + "\u00e2\u00e3\u00cf\u00d3\n1 0 obj\n<< /Type /Catalog >>\nendobj\n"
                        + "trailer\n<< /Root 1 0 R >>\n%%EOF")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 最小 OOXML 包（[Content_Types].xml + _rels/.rels + 主部件；口径同 FileTypeDetectorTest）。 */
    private static byte[] ooxml(String mainPart, String mainContentType) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(
                    ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                                    + "<Override PartName=\""
                                    + mainPart
                                    + "\" ContentType=\""
                                    + mainContentType
                                    + "\"/></Types>")
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write(
                    ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\""
                                    + mainPart.substring(1)
                                    + "\"/></Relationships>")
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(mainPart));
            zip.write("<?xml version=\"1.0\"?><root/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    /** ID3v2 头 + 帧填充（Tika 按 ID3 魔数识别 audio/mpeg）。 */
    private static byte[] mp3(int padding) {
        byte[] header = new byte[] {'I', 'D', '3', 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        byte[] payload = new byte[header.length + padding];
        System.arraycopy(header, 0, payload, 0, header.length);
        for (int i = header.length; i < payload.length; i++) {
            payload[i] = 0x55;
        }
        return payload;
    }

    private long defaultWardId(String tenant) {
        return runInTenant(
                tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    private String hospitalToken(String tenant) {
        return hospitalToken(tenant, "nurse.demo");
    }

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
        assertEquals(0, code(response));
        return (String) data(response).get("token");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    private Map<?, ?> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
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

    private static int code(Map<?, ?> response) {
        Object code = response == null ? null : response.get("code");
        return code == null ? -1 : Integer.parseInt(code.toString());
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantIdOf(tenant), tenant, action::get);
    }

    private long tenantIdOf(String tenant) {
        return 1L;
    }
}
