package com.crdp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crdp.framework.audit.AuditChainTable;
import com.crdp.framework.audit.AuditChainVerifier;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * E08-T02 / PLT-AUD-008、009、011 端到端（完整应用 + 真实 MySQL/Redis）。验收原文：篡改任一条验链即报错。
 *
 * <ol>
 *   <li>封存（008）：每条写入带 prev_hash / hash，前后衔接成链（租户库与平台库各验）；
 *   <li>验链（009）：内容篡改 → BAD_HASH；中间行删除 → BROKEN_LINK；
 *   <li>只读（011）：不开维护开关时 UPDATE / DELETE 在数据库层失败（触发器 45000）。
 * </ol>
 *
 * <p>断言按本测试的标记行（module='it-chain'）过滤：共享演示库上其他 IT 的自清理会留下链空洞（维护开关删除，属预期）， 全表「零断点」不可作为断言。 门控
 * CRDP_IT_MYSQL_PORT。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class AuditChainMySqlIT {

    private static final String TENANT = "demoa";
    private static final String MODULE = "it-chain";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuditLogger auditLogger;
    @Autowired private AuditChainVerifier verifier;

    private JdbcTemplate platform;

    @BeforeEach
    void connectPlatformAndCleanMarkers() {
        if (platform == null) {
            String port = System.getenv("CRDP_IT_MYSQL_PORT");
            platform =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    "jdbc:mysql://127.0.0.1:"
                                            + port
                                            + "/crdp_platform?useSSL=false&allowPublicKeyRetrieval=true",
                                    "crdp_app",
                                    "DevOnly#app2026"));
        }
        // 自清理走维护开关（只读触发器放行；会在链上留洞，属预期，断言按标记行过滤）
        TenantContext.runAsTenant(
                1L,
                TENANT,
                () ->
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM aud_log WHERE module = ?", MODULE));
        TenantContext.runAsPlatform(
                () -> {
                    AuditTableMaintenance.delete(
                            platform, "DELETE FROM plt_audit_log WHERE detail LIKE ?", "it-chain%");
                    AuditTableMaintenance.delete(
                            platform,
                            "DELETE FROM aud_platform_access_log WHERE module = ?",
                            MODULE);
                });
    }

    // ---------------------------------------------------------------- 封存（PLT-AUD-008）

    @Test
    void tenantAppendsAreSealedAndChained() {
        writeTenantMarkers(3);
        List<Map<String, Object>> rows = tenantMarkerRows();

        assertThat(rows).hasSize(3);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("hash")).asString().hasSize(64);
        }
        // 前后衔接：后一条 prev_hash = 前一条 hash；首条衔接当时链尾（可能非 GENESIS，共享库）
        assertThat(rows.get(1).get("prev_hash")).isEqualTo(rows.get(0).get("hash"));
        assertThat(rows.get(2).get("prev_hash")).isEqualTo(rows.get(1).get("hash"));
    }

    @Test
    void platformAppendsAreSealedAndChained() {
        TenantContext.runAsPlatform(
                () -> {
                    auditLogger.append(
                            AuditEntry.operation("platform", "IT_CHAIN_PROBE_1")
                                    .tenantId(1L)
                                    .description("it-chain 平台操作一（示例）")
                                    .build());
                    auditLogger.append(
                            AuditEntry.operation("platform", "IT_CHAIN_PROBE_2")
                                    .tenantId(1L)
                                    .description("it-chain 平台操作二（示例）")
                                    .build());
                });
        List<Map<String, Object>> rows = platformMarkerRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get("prev_hash"))
                .as("平台库 plt_audit_log 链衔接")
                .isEqualTo(rows.get(0).get("hash"));
        assertThat(rows.get(0).get("hash")).asString().hasSize(64);
    }

    // ---------------------------------------------------------------- 验链（PLT-AUD-009，验收核心）

    @Test
    void freshMarkerSegmentVerifiesIntact() {
        writeTenantMarkers(2);
        List<Map<String, Object>> rows = tenantMarkerRows();
        long firstMarkerId = ((Number) rows.get(0).get("id")).longValue();

        AuditChainVerifier.TableVerification result =
                TenantContext.callAsTenant(
                        1L, TENANT, () -> verifier.verifyTable(AuditChainTable.TENANT_OPERATION));

        assertThat(result.breaks())
                .as("标记段自身无断点（更早的洞来自其他 IT 的自清理，不在断言范围）")
                .allSatisfy(brk -> assertThat(brk.id()).isLessThan(firstMarkerId));
    }

    @Test
    void tamperedContentIsReportedAsBadHash() {
        writeTenantMarkers(2);
        List<Map<String, Object>> rows = tenantMarkerRows();
        long tamperedId = ((Number) rows.get(1).get("id")).longValue();
        // 篡改内容：维护开关下改 detail（真实攻击对应绕过应用的直改库）
        TenantContext.runAsTenant(
                1L,
                TENANT,
                () ->
                        AuditTableMaintenance.update(
                                jdbc,
                                "UPDATE aud_log SET detail = ? WHERE id = ?",
                                "篡改后的内容（示例）",
                                tamperedId));

        AuditChainVerifier.TableVerification result =
                TenantContext.callAsTenant(
                        1L, TENANT, () -> verifier.verifyTable(AuditChainTable.TENANT_OPERATION));

        assertThat(result.intact()).as("篡改任一条验链即报错").isFalse();
        assertThat(result.breaks())
                .anySatisfy(
                        brk -> {
                            assertThat(brk.id()).isEqualTo(tamperedId);
                            assertThat(brk.kind()).isEqualTo(AuditChainVerifier.BreakKind.BAD_HASH);
                        });
    }

    @Test
    void deletedMiddleRowIsReportedAsBrokenLink() {
        writeTenantMarkers(3);
        List<Map<String, Object>> rows = tenantMarkerRows();
        long deletedId = ((Number) rows.get(1).get("id")).longValue();
        long successorId = ((Number) rows.get(2).get("id")).longValue();
        TenantContext.runAsTenant(
                1L,
                TENANT,
                () ->
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM aud_log WHERE id = ?", deletedId));

        AuditChainVerifier.TableVerification result =
                TenantContext.callAsTenant(
                        1L, TENANT, () -> verifier.verifyTable(AuditChainTable.TENANT_OPERATION));

        assertThat(result.intact()).isFalse();
        assertThat(result.breaks())
                .anySatisfy(
                        brk -> {
                            assertThat(brk.id()).isEqualTo(successorId);
                            assertThat(brk.kind())
                                    .isEqualTo(AuditChainVerifier.BreakKind.BROKEN_LINK);
                        });
    }

    // ---------------------------------------------------------------- 只读（PLT-AUD-011）

    @Test
    void plainUpdateAndDeleteAreRejectedAtDatabaseLevel() {
        writeTenantMarkers(1);
        List<Map<String, Object>> rows = tenantMarkerRows();
        long markerId = ((Number) rows.get(0).get("id")).longValue();

        // 不开维护开关（普通应用连接）：UPDATE / DELETE 都在数据库层被触发器拒绝
        TenantContext.runAsTenant(
                1L,
                TENANT,
                () -> {
                    assertThatThrownBy(
                                    () ->
                                            jdbc.update(
                                                    "UPDATE aud_log SET detail = ? WHERE id = ?",
                                                    "绕过应用直改（示例）",
                                                    markerId))
                            .isInstanceOf(DataAccessException.class)
                            .hasMessageContaining("PLT-AUD-011");
                    assertThatThrownBy(
                                    () -> jdbc.update("DELETE FROM aud_log WHERE id = ?", markerId))
                            .isInstanceOf(DataAccessException.class)
                            .hasMessageContaining("PLT-AUD-011");
                });
        // 平台库同样拒绝（触发器只在命中行时触发，先落一条平台访问记录再改它）
        TenantContext.runAsPlatform(
                () ->
                        auditLogger.append(
                                AuditEntry.platformAccess(1L, MODULE, "PAGE_VIEW")
                                        .description("it-chain 平台访问（示例）")
                                        .viewSessionId("vs-it-chain")
                                        .page("/it-chain")
                                        .build()));
        Long accessId =
                platform.queryForObject(
                        "SELECT id FROM aud_platform_access_log WHERE view_session_id ="
                                + " 'vs-it-chain' ORDER BY id DESC LIMIT 1",
                        Long.class);
        assertThatThrownBy(
                        () ->
                                platform.update(
                                        "UPDATE aud_platform_access_log SET page = ? WHERE id = ?",
                                        "/tampered",
                                        accessId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("PLT-AUD-011");
    }

    // ---------------------------------------------------------------- 工具

    private void writeTenantMarkers(int count) {
        TenantContext.runAsTenant(
                1L,
                TENANT,
                () -> {
                    for (int i = 1; i <= count; i++) {
                        auditLogger.append(
                                AuditEntry.operation(MODULE, "IT_CHAIN_PROBE")
                                        .description("it-chain 第 " + i + " 条（示例）")
                                        .objectType("it_chain")
                                        .objectId(String.valueOf(i))
                                        .build());
                    }
                });
    }

    private List<Map<String, Object>> tenantMarkerRows() {
        return TenantContext.callAsTenant(
                1L,
                TENANT,
                () ->
                        jdbc.queryForList(
                                "SELECT id, prev_hash, hash FROM aud_log WHERE module = ?"
                                        + " ORDER BY id",
                                MODULE));
    }

    private List<Map<String, Object>> platformMarkerRows() {
        return platform.queryForList(
                "SELECT id, prev_hash, hash FROM plt_audit_log WHERE detail LIKE ? ORDER BY id",
                "it-chain%");
    }
}
