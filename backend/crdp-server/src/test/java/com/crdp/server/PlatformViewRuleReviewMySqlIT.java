package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E20-T05 端到端（验收原文：高敏变更需第二人批准）。场景按序：
 *
 * <ol>
 *   <li>版本链（PLT-VIEW-015）：规则每次变更生成版本行（变更人、前后值、状态），普通变更提交即 APPLIED、版本号递增。
 *   <li>高敏变更（PLT-VIEW-014）：改明文 / 停用脱敏规则 / 新建即放宽 → 规则保持原状、版本行 PENDING_REVIEW； 同一规则并发再变更
 *       40901；发起人复核自己的变更 40901。
 *   <li>第二人批准：按版本快照写入规则行并生效（状态 / 版本 / 复核人落账），待复核清空；驳回则规则不动 （新建即待复核的行置停用）；写平台访问审计 VIEW_RULE_APPROVE
 *       / VIEW_RULE_REJECT。
 *   <li>回滚（PLT-VIEW-015「可回滚」）：以已生效版本发起一次新变更；收严回滚即时生效，放宽回滚同样待复核。
 *   <li>视角审计（PLT-VIEW-016）：会话台账列表 / 详情（仅平台侧权限 {@code platform:view:session:view}， 无权限
 *       40300）；平台访问日志按视角会话号钻取（aud_platform_access_log.view_session_id）。
 *   <li>权限边界：无 {@code platform:view:rule:review} 的角色复核 40300。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清（删除本 IT 建的规则、版本行、
 * 角色、账号与审计事件；种子默认规则只读不改动）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformViewRuleReviewMySqlIT {

    private static final String ADMIN_USERNAME = "platform.admin";

    private static final String ADMIN_PASSWORD = "Demo@2026";

    private static final String NEW_PASSWORD = "Rule#2026x";

    /** 本 IT 创建的规则 id、版本行、账号与角色 → 清理用。 */
    private static final List<Long> createdRuleIds = new ArrayList<>();

    private static final List<String> createdUsernames = new ArrayList<>();

    private static Long customRoleId;

    @Autowired private TestRestTemplate rest;

    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void cleanState() {
        deleteKeys("crdp:sess:*");
        deleteKeys("crdp:login:fail:*");
    }

    @AfterAll
    static void cleanUp() {
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate platform =
                new JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                base + "/crdp_platform" + params, "crdp_app", "DevOnly#app2026"));
        for (Long ruleId : createdRuleIds) {
            platform.update("DELETE FROM plt_view_rule_version WHERE rule_id = ?", ruleId);
            platform.update("DELETE FROM plt_view_rule_role WHERE rule_id = ?", ruleId);
            platform.update("DELETE FROM plt_view_rule WHERE id = ?", ruleId);
        }
        platform.update(
                "DELETE FROM plt_account_role WHERE account_id IN"
                        + " (SELECT id FROM plt_account WHERE username LIKE 'it.review.%')");
        platform.update(
                "DELETE FROM plt_password_history WHERE account_id IN"
                        + " (SELECT id FROM plt_account WHERE username LIKE 'it.review.%')");
        platform.update("DELETE FROM plt_account WHERE username LIKE 'it.review.%'");
        if (customRoleId != null) {
            platform.update("DELETE FROM plt_role_menu WHERE role_id = ?", customRoleId);
            platform.update("DELETE FROM plt_role WHERE id = ?", customRoleId);
        }
        platform.update("DELETE FROM plt_view_session WHERE session_no = 'IT-FAKE-SESSION-01'");
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM aud_platform_access_log WHERE action LIKE 'VIEW_RULE%'"
                        + " OR view_session_id = 'IT-FAKE-SESSION-01'");
    }

    // ---------------------------------------------------------------- 1. 版本链（PLT-VIEW-015）

    @Test
    @Order(1)
    void normalChangesApplyImmediatelyWithVersionChain() {
        String token = login();
        long ruleId = createMaskedRule(token);

        // v1（CREATE）：提交即 APPLIED，before 为空（系统初始默认口径）
        Map<String, Object> v1 = latestVersion(ruleId, token);
        assertEquals("CREATE", v1.get("changeType"));
        assertEquals(false, v1.get("sensitive"), "新建脱敏规则是收严，普通变更");
        assertEquals("APPLIED", v1.get("status"));
        assertNull(v1.get("beforeJson"), "新建无前值");
        assertNotNull(v1.get("appliedAt"), "普通变更提交即生效");
        assertEquals("platform.admin", v1.get("proposedByName"));
        assertNull(v1.get("reviewedByName"), "普通变更无复核人");

        // 改名称（中性变更）→ v2 APPLIED，行内容更新、版本 +1
        Map<?, ?> outcome =
                put("/api/platform/view-rules/" + ruleId, maskedRequest("IT 复核脱敏规则（示例）改名"), token);
        assertEquals(0, codeOf(outcome));
        assertEquals(false, dataOf(outcome).get("pendingReview"), "改名不属高敏");
        assertEquals(2, ruleOf(outcome).get("version"));
        Map<String, Object> v2 = latestVersion(ruleId, token);
        assertEquals("APPLIED", v2.get("status"));
        assertEquals(2, v2.get("version"));
        assertNotNull(v2.get("beforeJson"), "维护类变更带前值快照");
        assertTrue(String.valueOf(v2.get("beforeJson")).contains("\"visibility\":\"MASKED\""));
        assertTrue(String.valueOf(v2.get("afterJson")).contains("\"visibility\":\"MASKED\""));

        // 版本链：versions 全量、版本号倒序
        List<Map<String, Object>> chain = versions(ruleId, token);
        assertEquals(2, chain.size());
        assertEquals(2, chain.get(0).get("version"));
        assertEquals(1, chain.get(1).get("version"));

        // 写审计（审计中心「视角规则变更」页签口径）
        assertTrue(
                platformDirect()
                                .queryForObject(
                                        "SELECT COUNT(*) FROM aud_platform_access_log WHERE action ="
                                                + " 'VIEW_RULE_CREATE' AND object_id = '"
                                                + codeOf(ruleId)
                                                + "'",
                                        Integer.class)
                        >= 1,
                "新建写 VIEW_RULE_CREATE 审计");
    }

    // ---------------------------------------------------------------- 2. 高敏变更待复核（PLT-VIEW-014）

    @Test
    @Order(2)
    void sensitiveChangeKeepsRuleUntilApproved() {
        String token = login();
        long ruleId = firstCreatedRuleId();

        // 脱敏 → 明文（规格原文「将字段改为明文」）：高敏 → 规则保持原状
        Map<?, ?> outcome =
                put("/api/platform/view-rules/" + ruleId, plainRequest("IT 复核改明文（示例）"), token);
        assertEquals(0, codeOf(outcome));
        assertEquals(true, dataOf(outcome).get("pendingReview"), "改明文属高敏变更");
        Map<?, ?> rule = ruleOf(outcome);
        assertEquals("MASKED", rule.get("visibility"), "复核前规则保持原状（脱敏）");
        assertEquals(2, rule.get("version"), "复核前版本不动");
        assertEquals(true, rule.get("pendingChange"), "列表行带待复核标记");

        // 待复核清单可见（含前后值）
        Map<String, Object> pending = pendingOf(codeOf(ruleId), token);
        assertEquals("PENDING_REVIEW", pending.get("status"));
        assertEquals(true, pending.get("sensitive"));
        assertEquals(3, pending.get("version"), "版本号按版本链 MAX+1 分配");
        assertTrue(String.valueOf(pending.get("beforeJson")).contains("\"visibility\":\"MASKED\""));
        assertTrue(String.valueOf(pending.get("afterJson")).contains("\"visibility\":\"PLAIN\""));

        // 同一规则再变更 → 40901（先处理在途提案）
        Map<?, ?> again =
                put("/api/platform/view-rules/" + ruleId, plainRequest("IT 复核改明文再次（示例）"), token);
        assertEquals(40901, codeOf(again));

        // 发起人复核自己的变更 → 40901（验收原文：高敏变更需第二人批准）
        long versionId = ((Number) pending.get("id")).longValue();
        Map<?, ?> self =
                post(
                        Map.of("comment", "自审（示例）"),
                        "/api/platform/view-rules/reviews/" + versionId + "/approve",
                        token);
        assertEquals(40901, codeOf(self), "发起人不能复核自己的高敏变更");
    }

    // ---------------------------------------------------------------- 3. 第二人批准 / 驳回

    @Test
    @Order(3)
    void secondAdminApprovesAndRejects() {
        String admin = login();
        String second = secondAdminToken();

        // 批准 Order(2) 的改明文提案 → 按快照写入规则行
        long ruleId = firstCreatedRuleId();
        Map<String, Object> pending = pendingOf(codeOf(ruleId), admin);
        long versionId = ((Number) pending.get("id")).longValue();
        Map<?, ?> approved =
                post(
                        Map.of("comment", "同意排障需要（示例）"),
                        "/api/platform/view-rules/reviews/" + versionId + "/approve",
                        second);
        assertEquals(0, codeOf(approved), String.valueOf(approved.get("message")));
        Map<?, ?> rule = dataOf(approved);
        assertEquals("PLAIN", rule.get("visibility"), "批准后按快照生效（明文）");
        assertEquals(3, rule.get("version"), "行版本 = 版本行号");
        assertEquals(false, rule.get("pendingChange"), "待复核清空");

        // 版本行落复核人与意见
        Map<String, Object> v3 = versionById(ruleId, versionId, admin);
        assertEquals("APPLIED", v3.get("status"));
        assertEquals("it.review.admin", v3.get("reviewedByName"), "复核人 = 第二人");
        assertNotNull(v3.get("reviewedAt"));
        assertEquals("同意排障需要（示例）", v3.get("reviewComment"));

        // 审计：VIEW_RULE_APPROVE（视角规则变更页签）
        assertTrue(
                platformDirect()
                                .queryForObject(
                                        "SELECT COUNT(*) FROM aud_platform_access_log WHERE action ="
                                                + " 'VIEW_RULE_APPROVE' AND object_id = '"
                                                + codeOf(ruleId)
                                                + "'",
                                        Integer.class)
                        >= 1);

        // 驳回：再发一条高敏（取消只读），规则不动
        Map<?, ?> proposed =
                put("/api/platform/view-rules/" + ruleId, editableRequest("IT 复核可编辑（示例）"), admin);
        assertEquals(true, dataOf(proposed).get("pendingReview"), "取消只读属高敏");
        long rejectId = ((Number) pendingOf(codeOf(ruleId), admin).get("id")).longValue();
        Map<?, ?> rejected =
                post(
                        Map.of("comment", "不需要放开（示例）"),
                        "/api/platform/view-rules/reviews/" + rejectId + "/reject",
                        second);
        assertEquals(0, codeOf(rejected));
        assertEquals("PLAIN", dataOf(rejected).get("visibility"), "驳回后规则保持原状");
        assertEquals("READONLY", dataOf(rejected).get("operation"), "取消只读未生效");
        Map<String, Object> v4 = versionById(ruleId, rejectId, admin);
        assertEquals("REJECTED", v4.get("status"), "版本行终态 REJECTED");
        assertNull(v4.get("appliedAt"), "驳回的变更从未生效");

        // 已处理的版本再复核 → 40901
        Map<?, ?> replay =
                post(
                        Map.of("comment", "重复复核（示例）"),
                        "/api/platform/view-rules/reviews/" + rejectId + "/approve",
                        second);
        assertEquals(40901, codeOf(replay));

        // 新建即高敏（允许导出）：行 PENDING_REVIEW、不进解析；批准后 ACTIVE；驳回的对照在 Order(5)
        Map<?, ?> exportOutcome =
                post(exportRuleRequest("IT 复核允许导出（示例）"), "/api/platform/view-rules", admin);
        assertEquals(0, codeOf(exportOutcome));
        assertEquals(true, dataOf(exportOutcome).get("pendingReview"), "新建即允许导出属高敏");
        long exportRuleId = ((Number) ruleOf(exportOutcome).get("id")).longValue();
        assertEquals("PENDING_REVIEW", ruleOf(exportOutcome).get("status"), "新建即高敏：规则行待复核、不生效");
        long exportVersionId = ((Number) dataOf(exportOutcome).get("pendingVersionId")).longValue();
        Map<?, ?> exportApproved =
                post(
                        Map.of("comment", "同意（示例）"),
                        "/api/platform/view-rules/reviews/" + exportVersionId + "/approve",
                        second);
        assertEquals(0, codeOf(exportApproved));
        assertEquals("ACTIVE", dataOf(exportApproved).get("status"), "批准后生效");

        // 权限边界：无 platform:view:rule:review 的角色复核 40300
        String viewer = viewerToken();
        Map<?, ?> denied =
                post(
                        Map.of("comment", "越权（示例）"),
                        "/api/platform/view-rules/reviews/" + exportVersionId + "/reject",
                        viewer);
        assertEquals(40300, codeOf(denied), "无复核权限 40300");
    }

    // ---------------------------------------------------------------- 4. 停用脱敏规则属高敏 +
    // 回滚（PLT-VIEW-015）

    @Test
    @Order(4)
    void disableRestrictiveRuleNeedsReviewAndRollback() {
        String admin = login();
        String second = secondAdminToken();

        // 停用一条脱敏规则 = 对原覆盖目标放宽 → 高敏
        long maskedId = createMaskedRule2(admin);
        Map<?, ?> disable =
                put(
                        "/api/platform/view-rules/" + maskedId,
                        disabledMaskedRequest("IT 复核停用脱敏（示例）"),
                        admin);
        assertEquals(true, dataOf(disable).get("pendingReview"), "停用脱敏规则属高敏（等效取消限制）");
        assertEquals("MASKED", ruleOf(disable).get("visibility"), "行内容未动");

        // 批准后按快照置停用
        long versionId = ((Number) dataOf(disable).get("pendingVersionId")).longValue();
        Map<?, ?> approved =
                post(
                        Map.of("comment", "同意停用（示例）"),
                        "/api/platform/view-rules/reviews/" + versionId + "/approve",
                        second);
        assertEquals(0, codeOf(approved));
        assertEquals("DISABLED", dataOf(approved).get("status"), "批准停用生效");

        // 回滚（收严）：Order(1) 的规则当前 PLAIN v3 → 回滚到 v2（脱敏）即时生效
        long ruleId = firstCreatedRuleId();
        Map<?, ?> rollback =
                post(
                        Map.of("toVersion", 2),
                        "/api/platform/view-rules/" + ruleId + "/rollback",
                        admin);
        assertEquals(0, codeOf(rollback), String.valueOf(rollback.get("message")));
        assertEquals(false, dataOf(rollback).get("pendingReview"), "回滚到脱敏是收严，即时生效");
        assertEquals("MASKED", ruleOf(rollback).get("visibility"));
        Map<String, Object> rollbackVersion = latestVersion(ruleId, admin);
        assertEquals("ROLLBACK", rollbackVersion.get("changeType"));
        assertEquals("APPLIED", rollbackVersion.get("status"));
        assertTrue(
                String.valueOf(rollbackVersion.get("afterJson"))
                        .contains("\"visibility\":\"MASKED\""));

        // 回滚（放宽）：回滚到 v3（明文）→ 高敏待复核、规则保持脱敏
        Map<?, ?> relax =
                post(
                        Map.of("toVersion", 3),
                        "/api/platform/view-rules/" + ruleId + "/rollback",
                        admin);
        assertEquals(true, dataOf(relax).get("pendingReview"), "回滚到明文是放宽，同样待复核");
        assertEquals("MASKED", ruleOf(relax).get("visibility"), "复核前保持脱敏");

        // 回滚目标不存在 / 未生效过 → 40400
        Map<?, ?> unknown =
                post(
                        Map.of("toVersion", 99),
                        "/api/platform/view-rules/" + ruleId + "/rollback",
                        admin);
        assertEquals(40400, codeOf(unknown));
    }

    // ---------------------------------------------------------------- 5. 视角审计（PLT-VIEW-016，仅平台侧）

    @Test
    @Order(5)
    void sessionLedgerAndAuditDrillOnlyOnPlatformSide() {
        String admin = login();

        // 台账造一行（会话生命周期由 E20-T01 的 IT 覆盖；此处只测台账读取与钻取口径）
        platformDirect()
                .update(
                        "INSERT INTO plt_view_session (session_no, account_id, account_name,"
                                + " display_name, role_codes, tenant_id, purpose, note,"
                                + " started_at_epoch, rule_version, status, started_at, ends_at,"
                                + " end_at, end_method, page_count, query_count, denied_count)"
                                + " VALUES ('IT-FAKE-SESSION-01',"
                                + accountId(ADMIN_USERNAME)
                                + ", 'platform.admin', '平台管理员（示例）', 'PLATFORM_ADMIN',"
                                + tenantIdByCode("demoa")
                                + ", 'TROUBLESHOOT', 'IT 台账（示例）', 0, 'TEST', 'ENDED',"
                                + " NOW(3), NOW(3), NOW(3), 'LOGOUT', 2, 5, 1)");
        // 平台访问日志造一条带会话号的 VIEW_QUERY（未封存行是链前缀，验链口径允许）
        platformDirect()
                .update(
                        "INSERT INTO aud_platform_access_log (tenant_id, user_id, user_name,"
                                + " module, action, description, object_type, view_session_id,"
                                + " page, record_count, result)"
                                + " VALUES ("
                                + tenantIdByCode("demoa")
                                + ","
                                + accountId(ADMIN_USERNAME)
                                + ", 'platform.admin', 'followup', 'VIEW_QUERY',"
                                + " 'IT 钻取（示例）', 'patient', 'IT-FAKE-SESSION-01',"
                                + " '/api/hospital/followup/patients/search', 7, 'SUCCESS')");

        // 会话列表（platform:view:session:view）
        Map<?, ?> sessions =
                get("/api/platform/tenant-view/sessions?accountName=platform.admin", admin);
        assertEquals(0, codeOf(sessions));
        Map<?, ?> page = dataOf(sessions);
        assertTrue(((Number) page.get("total")).longValue() >= 1, "台账行可见");
        Map<?, ?> row = listOf(page.get("items")).get(0);
        assertEquals("IT-FAKE-SESSION-01", row.get("sessionNo"));
        assertEquals("TROUBLESHOOT", row.get("purpose"));
        assertEquals("ENDED", row.get("status"));
        assertEquals("LOGOUT", row.get("endMethod"));
        assertEquals(2, ((Number) row.get("pageCount")).intValue());
        assertEquals(5, ((Number) row.get("queryCount")).intValue());
        assertEquals(1, ((Number) row.get("deniedCount")).intValue());

        // 详情
        long sessionId = ((Number) row.get("id")).longValue();
        Map<?, ?> detail = get("/api/platform/tenant-view/sessions/" + sessionId, admin);
        assertEquals(0, codeOf(detail));
        assertEquals("IT 台账（示例）", dataOf(detail).get("note"));
        Map<?, ?> missing = get("/api/platform/tenant-view/sessions/999999999", admin);
        assertEquals(40400, codeOf(missing));

        // 按会话号钻取平台访问明细（viewSessionId 条件）；时间窗按库内 NOW() 构造
        // （本地 / CI 的 MySQL 容器时钟是 UTC 而 JDBC 按 Asia/Shanghai，OQ-56 ⑤ 口径）
        LocalDateTime dbNow = platformDirect().queryForObject("SELECT NOW(3)", LocalDateTime.class);
        Map<?, ?> drill =
                post(
                        Map.of(
                                "startTime",
                                dbNow.minusHours(1)
                                        .atOffset(java.time.ZoneOffset.of("+08:00"))
                                        .toString(),
                                "endTime",
                                dbNow.plusHours(1)
                                        .atOffset(java.time.ZoneOffset.of("+08:00"))
                                        .toString(),
                                "viewSessionId",
                                "IT-FAKE-SESSION-01",
                                "pageNo",
                                1,
                                "pageSize",
                                20),
                        "/api/platform/audit/tenant-view/search",
                        admin);
        assertEquals(0, codeOf(drill), String.valueOf(drill.get("message")));
        List<Map<String, Object>> drillRows = listOf(dataOf(drill).get("items"));
        assertEquals(1, drillRows.size(), "只取该会话的访问明细");
        assertEquals("VIEW_QUERY", drillRows.get(0).get("action"));
        assertEquals("patient", drillRows.get(0).get("objectType"), "查询明细带对象");
        assertEquals(7, ((Number) drillRows.get(0).get("recordCount")).intValue(), "查询明细带条数");

        // 无 platform:view:session:view 的角色 → 40300（仅平台侧可见，医院端本就无入口）
        String viewer = viewerToken();
        Map<?, ?> forbidden = get("/api/platform/tenant-view/sessions", viewer);
        assertEquals(40300, codeOf(forbidden));
    }

    // ---------------------------------------------------------------- 数据与工具

    private long firstCreatedRuleId() {
        return createdRuleIds.get(0);
    }

    private long createMaskedRule(String token) {
        Map<?, ?> outcome = post(maskedRequest("IT 复核脱敏规则（示例）"), "/api/platform/view-rules", token);
        assertEquals(0, codeOf(outcome), String.valueOf(outcome.get("message")));
        assertEquals(false, dataOf(outcome).get("pendingReview"), "新建脱敏规则即时生效");
        long id = ((Number) ruleOf(outcome).get("id")).longValue();
        createdRuleIds.add(id);
        return id;
    }

    private long createMaskedRule2(String token) {
        Map<?, ?> outcome =
                post(maskedRequest("IT 复核停用用脱敏规则（示例）"), "/api/platform/view-rules", token);
        assertEquals(0, codeOf(outcome));
        long id = ((Number) ruleOf(outcome).get("id")).longValue();
        createdRuleIds.add(id);
        return id;
    }

    private static Map<String, Object> maskedRequest(String name) {
        return baseRequest(name, "MASKED", "READONLY", false, "ACTIVE");
    }

    private static Map<String, Object> plainRequest(String name) {
        return baseRequest(name, "PLAIN", "READONLY", false, "ACTIVE");
    }

    private static Map<String, Object> editableRequest(String name) {
        return baseRequest(name, "PLAIN", "EDITABLE", false, "ACTIVE");
    }

    private static Map<String, Object> exportRuleRequest(String name) {
        return baseRequest(name, "PLAIN", "READONLY", true, "ACTIVE");
    }

    private static Map<String, Object> disabledMaskedRequest(String name) {
        return baseRequest(name, "MASKED", "READONLY", false, "DISABLED");
    }

    /** 脱敏规则的字段层请求（patient.idCard + idCard 脱敏码；Map.of 不允许 null，用 HashMap）。 */
    private static Map<String, Object> baseRequest(
            String name, String visibility, String operation, boolean allowExport, String status) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", name);
        body.put("layer", "FIELD");
        body.put("resource", "patient");
        body.put("field", "idCard");
        body.put("visibility", visibility);
        body.put("maskRule", "MASKED".equals(visibility) ? "idCard" : null);
        body.put("operation", operation);
        body.put("allowExport", allowExport);
        body.put("status", status);
        body.put("roleIds", List.of());
        return body;
    }

    private String codeOf(long ruleId) {
        return platformDirect()
                .queryForObject(
                        "SELECT code FROM plt_view_rule WHERE id = ?", String.class, ruleId);
    }

    private long ruleByCode(String code) {
        return platformDirect()
                .queryForObject("SELECT id FROM plt_view_rule WHERE code = ?", Long.class, code);
    }

    private Map<String, Object> latestVersion(long ruleId, String token) {
        return versions(ruleId, token).get(0);
    }

    private Map<String, Object> versionById(long ruleId, long versionId, String token) {
        return versions(ruleId, token).stream()
                .filter(row -> ((Number) row.get("id")).longValue() == versionId)
                .findFirst()
                .orElseThrow();
    }

    private List<Map<String, Object>> versions(long ruleId, String token) {
        Map<?, ?> response = get("/api/platform/view-rules/" + ruleId + "/versions", token);
        assertEquals(0, codeOf(response), String.valueOf(response.get("message")));
        return listOf(response.get("data"));
    }

    private Map<String, Object> pendingOf(String ruleCode, String token) {
        Map<?, ?> response = get("/api/platform/view-rules/reviews/pending", token);
        assertEquals(0, codeOf(response));
        return listOf(response.get("data")).stream()
                .filter(row -> ruleCode.equals(row.get("ruleCode")))
                .findFirst()
                .orElseThrow();
    }

    /** 第二名平台管理员（PLATFORM_ADMIN 角色）——高敏变更的复核人。 */
    private String secondAdminToken() {
        if (!createdUsernames.contains("it.review.admin")) {
            String admin = login();
            createAccount(admin, "it.review.admin", "复核管理员（示例）", roleIdByCode("PLATFORM_ADMIN"));
        }
        return loginWithForcedChange("it.review.admin");
    }

    /** 只读视角规则的角色（菜单 147，无 148 / 149）→ 复核 40300。 */
    private String viewerToken() {
        if (customRoleId == null) {
            String admin = login();
            Map<?, ?> role =
                    post(
                            Map.of(
                                    "name",
                                    "规则只读角色（示例）",
                                    "menuIds",
                                    List.of(menuIdOf("/platform/view-rule"))),
                            "/api/platform/roles",
                            admin);
            assertEquals(0, codeOf(role), String.valueOf(role.get("message")));
            customRoleId = ((Number) dataOf(role).get("id")).longValue();
            createAccount(admin, "it.review.viewer", "规则只读（示例）", customRoleId);
        }
        return loginWithForcedChange("it.review.viewer");
    }

    private long menuIdOf(String path) {
        return platformDirect()
                .queryForObject("SELECT id FROM plt_menu WHERE path = ?", Long.class, path);
    }

    private long accountId(String username) {
        return platformDirect()
                .queryForObject(
                        "SELECT id FROM plt_account WHERE username = ?", Long.class, username);
    }

    private long tenantIdByCode(String code) {
        return platformDirect()
                .queryForObject("SELECT id FROM plt_tenant WHERE code = ?", Long.class, code);
    }

    private long roleIdByCode(String code) {
        return platformDirect()
                .queryForObject("SELECT id FROM plt_role WHERE code = ?", Long.class, code);
    }

    private String login() {
        Map<?, ?> response =
                post(
                        Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD),
                        "/api/platform/auth/login",
                        null);
        assertEquals(0, codeOf(response), "登录成功：" + String.valueOf(response.get("message")));
        return (String) dataOf(response).get("token");
    }

    /** 首登强制改密的账号登录：先试改后的口令，不行再走首登改密（多次调用幂等）。 */
    private String loginWithForcedChange(String username) {
        String reused = tryLogin(username, NEW_PASSWORD + "B");
        if (reused != null) {
            return reused;
        }
        String token = tryLogin(username, NEW_PASSWORD + "A");
        assertNotNull(token, "首登成功：" + username);
        Map<?, ?> changed =
                post(
                        Map.of(
                                "oldPassword",
                                NEW_PASSWORD + "A",
                                "newPassword",
                                NEW_PASSWORD + "B"),
                        "/api/platform/auth/password",
                        token);
        assertEquals(0, codeOf(changed), "首次改密成功：" + String.valueOf(changed.get("message")));
        String relogin = tryLogin(username, NEW_PASSWORD + "B");
        assertNotNull(relogin, "改密后重新登录：" + username);
        return relogin;
    }

    private String tryLogin(String username, String password) {
        Map<?, ?> response =
                post(
                        Map.of("username", username, "password", password),
                        "/api/platform/auth/login",
                        null);
        return codeOf(response) == 0 ? (String) dataOf(response).get("token") : null;
    }

    private void createAccount(
            String adminToken, String username, String displayName, long roleId) {
        Map<?, ?> response =
                post(
                        Map.of(
                                "username",
                                username,
                                "displayName",
                                displayName,
                                "phone",
                                "139000076" + (10 + createdUsernames.size() % 80),
                                "password",
                                NEW_PASSWORD + "A",
                                "roleIds",
                                List.of(roleId)),
                        "/api/platform/accounts",
                        adminToken);
        assertEquals(0, codeOf(response), "建号成功：" + String.valueOf(response.get("message")));
        createdUsernames.add(username);
    }

    private Map<?, ?> post(Map<String, Object> body, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private Map<?, ?> put(String path, Map<String, Object> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<String, Object> dataOf(Map<?, ?> response) {
        return response.get("data") == null ? Map.of() : (Map<String, Object>) response.get("data");
    }

    /** 保存结果（SaveOutcome）内的规则行。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> ruleOf(Map<?, ?> outcome) {
        return (Map<String, Object>) dataOf(outcome).get("rule");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private JdbcTemplate platformDirect() {
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        return new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        base + "/crdp_platform" + params, "crdp_app", "DevOnly#app2026"));
    }

    private void deleteKeys(String pattern) {
        java.util.Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
