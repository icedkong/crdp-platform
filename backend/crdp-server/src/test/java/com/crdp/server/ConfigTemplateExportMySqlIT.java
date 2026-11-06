package com.crdp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * E12-T01 端到端（验收原文：导出包只含配置，不含患者数据）。在演示租户 demoa（种子已开通 followup、预置 A 字段字典）里
 * 塞入带标记的患者行与术者字典项，从平台端导出配置模板，断言：
 *
 * <ol>
 *   <li>包只含注册表（ConfigTableRegistry）标注的配置表节，不含任何患者 / 业务数据表；
 *   <li>患者标记值（病案号密文、姓名密文）不出现在包 JSON 里；
 *   <li>STRUCTURE_ONLY（病区）只带计数不带行；术者字典取值被行排除谓词剔除；
 *   <li>包体 gzip 存 plt_config_template，package_hash 与解压后 JSON 的 SHA-256 一致；
 *   <li>导出与下载写平台审计；权限（platform:tenant:view / create）与 40400 / 40001 负向。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清（模板行、审计、demoa 里插入的
 * 患者与术者字典项、无权限账号与角色）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigTemplateExportMySqlIT {

    private static final String DEMO_PASSWORD = "Demo@2026";
    private static final long TENANT_A = 1L; // demoa
    private static final String MARKER_MRN = "ITCFG-MRN-2026（示例）";
    private static final String MARKER_NAME = "配置导出验证患者（示例）";
    private static final String SURGEON_ITEM = "ITCFG-SURGEON-1";

    private static JdbcTemplate platform;
    private static JdbcTemplate demoa;
    private static Long templateId;
    private static boolean viewerCreated;
    private static Long patientId;

    @Autowired private TestRestTemplate rest;

    private static JdbcTemplate connect() {
        if (platform == null) {
            String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
            String params = "?useSSL=false&allowPublicKeyRetrieval=true";
            platform =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    base + "/crdp_platform" + params,
                                    "crdp_app",
                                    "DevOnly#app2026"));
            demoa =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    base + "/t_demoa" + params, "crdp_app", "DevOnly#app2026"));
        }
        return platform;
    }

    @AfterAll
    static void cleanUp() {
        if (platform == null) {
            return;
        }
        platform.update("DELETE FROM plt_config_template");
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM plt_audit_log WHERE event_type IN"
                        + " ('CONFIG_TEMPLATE_EXPORT', 'CONFIG_TEMPLATE_DOWNLOAD')");
        if (patientId != null) {
            demoa.update("DELETE FROM pt_patient WHERE id = ?", patientId);
        }
        demoa.update(
                "DELETE FROM cfg_dict_item WHERE dict_code = 'surgeon' AND item_code = ?",
                SURGEON_ITEM);
        if (viewerCreated) {
            platform.update(
                    "DELETE ar FROM plt_account_role ar JOIN plt_account a ON a.id = ar.account_id"
                            + " WHERE a.username = 'platform.config.viewer.it'");
            platform.update("DELETE FROM plt_account WHERE username = 'platform.config.viewer.it'");
            platform.update(
                    "DELETE rm FROM plt_role_menu rm JOIN plt_role r ON r.id = rm.role_id"
                            + " WHERE r.code = 'PLATFORM_CONFIG_VIEWER_IT'");
            platform.update("DELETE FROM plt_role WHERE code = 'PLATFORM_CONFIG_VIEWER_IT'");
        }
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @Order(1)
    void exportPackageContainsOnlyConfig() throws Exception {
        connect();
        // 源租户里放带标记的患者行与术者字典项：导出包若读了它们，标记就会出现在包 JSON 里
        Long wardId = demoa.queryForObject("SELECT id FROM ward ORDER BY id LIMIT 1", Long.class);
        demoa.update(
                "INSERT INTO pt_patient (mrn_cipher, mrn_blind, name_cipher, name_blind,"
                        + " gender, ward_id, followup_status) VALUES (?, ?, ?, ?, 'M', ?, 'ACTIVE')",
                MARKER_MRN,
                "itcfg-blind-mrn",
                MARKER_NAME,
                "itcfg-blind-name",
                wardId);
        patientId =
                demoa.queryForObject(
                        "SELECT id FROM pt_patient WHERE mrn_blind = 'itcfg-blind-mrn'",
                        Long.class);
        demoa.update(
                "INSERT INTO cfg_dict_item (dict_code, item_code, label, sort_no, source, status)"
                        + " VALUES ('surgeon', ?, '配置导出术者（示例）', 10, 'TENANT', 'ENABLED')",
                SURGEON_ITEM);

        String token = loginPlatform("platform.admin");
        Map<?, ?> view =
                request(
                        HttpMethod.POST,
                        "/api/platform/config-templates",
                        token,
                        Map.of("tenantId", TENANT_A, "name", "IT 配置模板（示例）"));
        templateId = ((Number) view.get("id")).longValue();
        int version = ((Number) view.get("version")).intValue();
        assertThat(version).isGreaterThanOrEqualTo(1);
        assertThat(String.valueOf(view.get("tenantCode"))).isEqualTo("demoa");
        assertThat(((Number) view.get("tableCount")).intValue()).isGreaterThan(10);

        // 下载原始 JSON：配置表在，患者与标记不在
        HttpHeaders plain = new HttpHeaders();
        plain.setBearerAuth(token);
        ResponseEntity<String> raw =
                rest.exchange(
                        URI.create("/api/platform/config-templates/" + templateId + "/package"),
                        HttpMethod.GET,
                        new HttpEntity<>(plain),
                        String.class);
        assertThat(raw.getStatusCode().is2xxSuccessful()).isTrue();
        String body = raw.getBody();
        assertThat(body).isNotBlank();
        assertThat(body).contains("\"fe_field\"");
        assertThat(body).contains("\"fu_plan\"");
        assertThat(body).doesNotContain("pt_patient");
        assertThat(body).doesNotContain("fu_task");
        assertThat(body).doesNotContain("fu_record");
        assertThat(body).doesNotContain("sys_user\"");
        assertThat(body).doesNotContain(MARKER_MRN);
        assertThat(body).doesNotContain(MARKER_NAME);
        assertThat(body).doesNotContain(SURGEON_ITEM);

        // 包结构断言：注册表全集、STRUCTURE_ONLY 不带行、术者取值被剔除
        Map<?, ?> pkg =
                request(
                        HttpMethod.GET,
                        "/api/platform/config-templates/" + templateId + "/package",
                        token,
                        null);
        List<Map<?, ?>> tables = (List<Map<?, ?>>) pkg.get("tables");
        assertThat(tables)
                .extracting(t -> String.valueOf(t.get("table")))
                .containsExactlyElementsOf(
                        com.crdp.platform.configtemplate.ConfigTableRegistry.tables());
        Map<?, ?> ward =
                tables.stream()
                        .filter(t -> "ward".equals(t.get("table")))
                        .findFirst()
                        .orElseThrow();
        assertThat(String.valueOf(ward.get("mode"))).isEqualTo("STRUCTURE_ONLY");
        assertThat((List<?>) ward.get("rows")).isEmpty();
        assertThat(((Number) ward.get("sourceRowCount")).longValue()).isGreaterThanOrEqualTo(0);
        Map<?, ?> items =
                tables.stream()
                        .filter(t -> "cfg_dict_item".equals(t.get("table")))
                        .findFirst()
                        .orElseThrow();
        assertThat((List<?>) items.get("rows"))
                .noneMatch(r -> String.valueOf(((Map<?, ?>) r).get("dict_code")).equals("surgeon"));
        Map<?, ?> fields =
                tables.stream()
                        .filter(t -> "fe_field".equals(t.get("table")))
                        .findFirst()
                        .orElseThrow();
        // demoa 预置 A 字段字典（V38，266 字段）
        assertThat(((Number) fields.get("rowCount")).longValue()).isGreaterThanOrEqualTo(200);

        // 存储：包体 gzip 存库，hash 与解压 JSON 的 SHA-256 一致
        Map<String, Object> row =
                platform.queryForMap(
                        "SELECT version, format_version, package_hash, package_body, table_count,"
                                + " row_count FROM plt_config_template WHERE id = ?",
                        templateId);
        assertThat(((Number) row.get("version")).intValue()).isEqualTo(version);
        assertThat(((Number) row.get("format_version")).intValue()).isEqualTo(1);
        byte[] bodyBytes = (byte[]) row.get("package_body");
        String jsonText;
        try (GZIPInputStream gzip =
                new GZIPInputStream(new java.io.ByteArrayInputStream(bodyBytes))) {
            jsonText = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(jsonText).contains("\"smoking_history\"").doesNotContain(MARKER_MRN);
        assertThat(sha256Hex(jsonText)).isEqualTo(String.valueOf(row.get("package_hash")));
        // row_count = 各节入包行数合计（> 0：字段字典等配置在包里）
        assertThat(((Number) row.get("row_count")).longValue()).isGreaterThan(0);

        // 审计：导出一条 OPERATION；下载一条 OPERATION
        assertThat(
                        platform.queryForObject(
                                "SELECT COUNT(*) FROM plt_audit_log WHERE event_type ="
                                        + " 'CONFIG_TEMPLATE_EXPORT' AND object_id = ?",
                                Integer.class,
                                String.valueOf(templateId)))
                .isGreaterThan(0);
        assertThat(
                        platform.queryForObject(
                                "SELECT COUNT(*) FROM plt_audit_log WHERE event_type ="
                                        + " 'CONFIG_TEMPLATE_DOWNLOAD' AND object_id = ?",
                                Integer.class,
                                String.valueOf(templateId)))
                .isGreaterThan(0);
    }

    @Test
    @Order(2)
    void detailListsTableMetaWithoutRows() {
        connect();
        String token = loginPlatform("platform.admin");
        Map<?, ?> detail =
                request(
                        HttpMethod.GET,
                        "/api/platform/config-templates/" + templateId,
                        token,
                        null);
        assertThat(String.valueOf(((Map<?, ?>) detail.get("template")).get("tenantCode")))
                .isEqualTo("demoa");
        List<Map<?, ?>> tables = (List<Map<?, ?>>) detail.get("tables");
        assertThat(tables).isNotEmpty();
        assertThat(tables).allSatisfy(t -> assertThat((List<?>) t.get("rows")).isNull());

        Map<?, ?> page =
                request(
                        HttpMethod.GET,
                        "/api/platform/config-templates?pageNo=1&pageSize=10",
                        token,
                        null);
        assertThat(((Number) page.get("total")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat((List<?>) page.get("items")).isNotEmpty();
    }

    @Test
    @Order(3)
    void registryEndpointExposesCopyModes() {
        connect();
        String token = loginPlatform("platform.admin");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        Map<?, ?> payload =
                (Map<?, ?>)
                        rest.exchange(
                                        URI.create("/api/platform/config-templates/registry"),
                                        HttpMethod.GET,
                                        new HttpEntity<>(headers),
                                        Map.class)
                                .getBody();
        assertThat(payload).isNotNull();
        assertThat(((Number) payload.get("code")).intValue()).isZero();
        List<Map<?, ?>> registry = (List<Map<?, ?>>) payload.get("data");
        assertThat(registry)
                .extracting(e -> String.valueOf(e.get("table")))
                .contains("fe_field", "fu_plan", "cfg_dict_item", "ward");
        assertThat(registry)
                .anySatisfy(
                        e -> {
                            assertThat(e.get("table")).isEqualTo("ward");
                            assertThat(String.valueOf(e.get("mode"))).isEqualTo("STRUCTURE_ONLY");
                        });
    }

    @Test
    @Order(4)
    void negativeCases() {
        connect();
        String token = loginPlatform("platform.admin");
        // 未知租户 40400
        assertThat(
                        codeOf(
                                HttpMethod.POST,
                                "/api/platform/config-templates",
                                token,
                                Map.of("tenantId", 999_999L)))
                .isEqualTo(40400);
        // 名称超长 40001
        assertThat(
                        codeOf(
                                HttpMethod.POST,
                                "/api/platform/config-templates",
                                token,
                                Map.of("tenantId", TENANT_A, "name", "名".repeat(129))))
                .isEqualTo(40001);
        // 缺 tenantId 40001
        assertThat(
                        codeOf(
                                HttpMethod.POST,
                                "/api/platform/config-templates",
                                token,
                                Map.of("name", "缺租户（示例）")))
                .isEqualTo(40001);
    }

    @Test
    @Order(5)
    void permissionlessAccountGets40300() {
        connect();
        createViewer();
        String token = loginPlatform("platform.config.viewer.it");
        // 无 platform:tenant:create / view：导出与列表均 40300
        assertThat(
                        codeOf(
                                HttpMethod.POST,
                                "/api/platform/config-templates",
                                token,
                                Map.of("tenantId", TENANT_A)))
                .isEqualTo(40300);
        assertThat(codeOf(HttpMethod.GET, "/api/platform/config-templates", token, null))
                .isEqualTo(40300);
    }

    private int codeOf(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<?> entity =
                body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        Map<?, ?> payload =
                (Map<?, ?>) rest.exchange(URI.create(path), method, entity, Map.class).getBody();
        assertThat(payload).isNotNull();
        return Integer.parseInt(String.valueOf(payload.get("code")));
    }

    // ---------------------------------------------------------------- 基础设施

    @SuppressWarnings("unchecked")
    private Map<?, ?> request(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<?> entity =
                body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        Map<?, ?> payload =
                (Map<?, ?>) rest.exchange(URI.create(path), method, entity, Map.class).getBody();
        assertThat(payload).isNotNull();
        // 统一响应：code=0 才返回 data；业务失败（40300 / 40400 / 40001 等）断言非 0 并返回 null
        Number code = (Number) payload.get("code");
        assertThat(code).isNotNull();
        if (code.intValue() != 0) {
            return null;
        }
        return (Map<?, ?>) payload.get("data");
    }

    private String loginPlatform(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> payload =
                (Map<?, ?>)
                        rest.postForEntity(
                                        "/api/platform/auth/login",
                                        new HttpEntity<>(
                                                Map.of(
                                                        "username",
                                                        username,
                                                        "password",
                                                        DEMO_PASSWORD),
                                                headers),
                                        Map.class)
                                .getBody();
        assertThat(payload).isNotNull();
        assertThat(((Number) payload.get("code")).intValue()).isZero();
        return String.valueOf(((Map<?, ?>) payload.get("data")).get("token"));
    }

    private void createViewer() {
        if (viewerCreated) {
            return;
        }
        String passwordHash =
                new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(
                                16, 32, 1, 65536, 3)
                        .encode(DEMO_PASSWORD);
        // 无任何菜单授权的角色：不持有 platform:tenant:view / create
        platform.update(
                "INSERT INTO plt_role (code, name, built_in, status)"
                        + " VALUES ('PLATFORM_CONFIG_VIEWER_IT', '配置模板无权限（IT）', 0, 'ENABLED')");
        platform.update(
                "INSERT INTO plt_account (username, display_name, password_hash, status)"
                        + " VALUES ('platform.config.viewer.it', '配置无权限（IT 示例）', ?, 'ENABLED')",
                passwordHash);
        platform.update(
                "INSERT INTO plt_account_role (account_id, role_id)"
                        + " SELECT a.id, r.id FROM plt_account a, plt_role r"
                        + " WHERE a.username = 'platform.config.viewer.it'"
                        + " AND r.code = 'PLATFORM_CONFIG_VIEWER_IT'");
        viewerCreated = true;
    }

    private static String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
