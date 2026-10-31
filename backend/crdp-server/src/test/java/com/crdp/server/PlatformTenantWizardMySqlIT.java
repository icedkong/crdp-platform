package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * E02-T07 端到端（验收：向导走完即触发开通）：平台端登录 → POST /api/platform/tenants → 开通编排同步完成（五步 SUCCESS，租户
 * TRIAL、库已建）。门控 CRDP_IT_MYSQL_PORT（需 docker compose 的 MySQL 与 Redis）；CI 自动跳过；自建自清。完整租户 API（列表/维护）是
 * E05-T01。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class PlatformTenantWizardMySqlIT {

    private static JdbcTemplate root;
    private static JdbcTemplate platform;
    private static String createdCode;

    @Autowired private TestRestTemplate rest;

    @AfterAll
    static void cleanUp() {
        if (createdCode != null) {
            root.execute("DROP DATABASE IF EXISTS t_" + createdCode);
            root.execute("DROP USER IF EXISTS `t_" + createdCode + "`@'%'");
            List<Long> ids =
                    platform.queryForList(
                            "SELECT id FROM plt_tenant WHERE code = ?", Long.class, createdCode);
            for (Long id : ids) {
                platform.update("DELETE FROM plt_provision_step WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_resource WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant WHERE id = ?", id);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void wizardSubmissionTriggersOnboardingEndToEnd() {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        root =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/?useSSL=false&allowPublicKeyRetrieval=true",
                                "root",
                                "DevOnly#root2026"));
        platform =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/crdp_platform?useSSL=false&allowPublicKeyRetrieval=true",
                                "crdp_app",
                                "DevOnly#app2026"));

        // 未登录拒绝
        HttpHeaders anonymous = new HttpHeaders();
        anonymous.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> rejected =
                rest.postForEntity(
                                "/api/platform/tenants",
                                new HttpEntity<>(Map.of("name", "虚构示例医院戊"), anonymous),
                                Map.class)
                        .getBody();
        assertEquals(40100, code(rejected), "无令牌不得创建租户");

        // 平台登录（V4 菜单授予 platform:tenant:create）
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> login =
                rest.postForEntity(
                                "/api/platform/auth/login",
                                new HttpEntity<>(
                                        Map.of(
                                                "username",
                                                "platform.admin",
                                                "password",
                                                "Demo@2026"),
                                        loginHeaders),
                                Map.class)
                        .getBody();
        String token = ((Map<?, ?>) login.get("data")).get("token").toString();

        // 向导提交：不填编码（后端生成），带首个管理员
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        Map<?, ?> response =
                rest.postForEntity(
                                "/api/platform/tenants",
                                new HttpEntity<>(
                                        Map.of(
                                                "name", "虚构示例医院戊",
                                                "adminUsername", "admin",
                                                "adminDisplayName", "戊院管理员",
                                                "adminInitialPassword", "Init@2026#"),
                                        headers),
                                Map.class)
                        .getBody();

        assertEquals(0, code(response), () -> String.valueOf(response));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        createdCode = data.get("code").toString();
        assertTrue(createdCode.matches("^t[a-z0-9]{7}$"), "未填编码时应自动生成：" + createdCode);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) data.get("steps");
        assertEquals(5, steps.size());
        assertTrue(
                steps.stream().allMatch(step -> "SUCCESS".equals(step.get("status"))),
                () -> steps.toString());

        assertEquals(
                "TRIAL",
                platform.queryForObject(
                        "SELECT status FROM plt_tenant WHERE code = ?", String.class, createdCode));
        assertEquals(
                1,
                root.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                        Integer.class,
                        "t_" + createdCode),
                "向导走完即开通：租户库已建");
        assertNotNull(
                root.queryForObject(
                        "SELECT id FROM t_" + createdCode + ".sys_user WHERE username = 'admin'",
                        Long.class),
                "首个管理员已创建");
    }

    @Test
    @SuppressWarnings("unchecked")
    void provisionRetryAndRecycleWorkEndToEnd() {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        root =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/?useSSL=false&allowPublicKeyRetrieval=true",
                                "root",
                                "DevOnly#root2026"));
        platform =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/crdp_platform?useSSL=false&allowPublicKeyRetrieval=true",
                                "crdp_app",
                                "DevOnly#app2026"));

        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> login =
                rest.postForEntity(
                                "/api/platform/auth/login",
                                new HttpEntity<>(
                                        Map.of(
                                                "username",
                                                "platform.admin",
                                                "password",
                                                "Demo@2026"),
                                        loginHeaders),
                                Map.class)
                        .getBody();
        String token = ((Map<?, ?>) login.get("data")).get("token").toString();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        // 成功开通一个租户
        Map<?, ?> created =
                rest.postForEntity(
                                "/api/platform/tenants",
                                new HttpEntity<>(
                                        Map.of(
                                                "name", "虚构示例医院己",
                                                "adminUsername", "admin",
                                                "adminInitialPassword", "Init@2026#"),
                                        headers),
                                Map.class)
                        .getBody();
        long tenantId = ((Number) ((Map<?, ?>) created.get("data")).get("tenantId")).longValue();
        String code = ((Map<?, ?>) created.get("data")).get("code").toString();
        createdCode = code;

        // 进度页数据：TRIAL + 五步 SUCCESS（admin 已建成）
        Map<?, ?> view =
                rest.exchange(
                                "/api/platform/tenants/" + tenantId + "/provision",
                                HttpMethod.GET,
                                new HttpEntity<>(headers),
                                Map.class)
                        .getBody();
        Map<?, ?> viewData = (Map<?, ?>) view.get("data");
        assertEquals("TRIAL", viewData.get("status"));
        assertEquals(5, ((List<?>) viewData.get("steps")).size());

        // 重试：幂等重跑，仍是五步 SUCCESS（admin 已存在不受影响）
        Map<?, ?> retried =
                rest.exchange(
                                "/api/platform/tenants/" + tenantId + "/retry",
                                HttpMethod.POST,
                                new HttpEntity<>(headers),
                                Map.class)
                        .getBody();
        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) ((Map<?, ?>) retried.get("data")).get("steps");
        assertEquals(5, steps.size());
        assertTrue(
                steps.stream().noneMatch(step -> "FAILED".equals(step.get("status"))),
                () -> steps.toString());
        assertTrue(
                steps.stream()
                        .anyMatch(
                                step ->
                                        "SKIPPED".equals(step.get("status"))
                                                && "admin".equals(step.get("step"))),
                "重试不重复建管理员，admin 步骤为 SKIPPED");

        // TRIAL 状态不得回收
        Map<?, ?> recycleRejected =
                rest.exchange(
                                "/api/platform/tenants/" + tenantId,
                                HttpMethod.DELETE,
                                new HttpEntity<>(headers),
                                Map.class)
                        .getBody();
        assertEquals(40001, code(recycleRejected), "已开通完成的租户不得回收");

        // 模拟"开通失败中"（回置 PROVISIONING）后回收：库与登记全部清除
        platform.update("UPDATE plt_tenant SET status = 'PROVISIONING' WHERE id = ?", tenantId);
        Map<?, ?> recycled =
                rest.exchange(
                                "/api/platform/tenants/" + tenantId,
                                HttpMethod.DELETE,
                                new HttpEntity<>(headers),
                                Map.class)
                        .getBody();
        assertEquals(0, code(recycled));
        assertEquals(
                0,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_tenant WHERE id = ?", Integer.class, tenantId),
                "回收后平台登记清除");
        assertEquals(
                0,
                root.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                        Integer.class,
                        "t_" + code),
                "回收后租户库已删除");
        createdCode = null;
    }

    private static int code(Map<?, ?> response) {
        Object code = response == null ? null : response.get("code");
        return code == null ? -1 : Integer.parseInt(code.toString());
    }
}
