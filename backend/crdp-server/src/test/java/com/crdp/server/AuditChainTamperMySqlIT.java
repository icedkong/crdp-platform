package com.crdp.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.crdp.framework.audit.AuditChainTable;
import com.crdp.framework.audit.AuditChainVerifier;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.Sha256DigestService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.platform.AuditChainVerificationService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * E08-T06 / PLT-AUD-008 验收「篡改检测用例通过」：真库端到端的篡改矩阵（完整应用 + 真实 MySQL/Redis）。
 *
 * <ol>
 *   <li>六表逐表篡改：租户三张（aud_log / aud_login_log / aud_data_access_log）与平台三张 （plt_audit_log / 平台
 *       aud_login_log / aud_platform_access_log）各写两条真实审计 → 断言封存衔接 → 维护开关下篡改后一条内容 → 验链在该行报
 *       BAD_HASH（各表真实写入路径的列对齐同时被覆盖）；
 *   <li>重封攻击：改内容并按算法重算本条 hash（本行自洽）→ 后继行 BROKEN_LINK 检出；
 *   <li>验链作业：篡改后跑 AuditChainVerificationService → job_tenant_run 记 FAILED、 aud_chain_check 记
 *       BROKEN（PLT-AUD-009 断链告警的作业面与记录面）。
 * </ol>
 *
 * <p>共享演示库上其他 IT 的自清理会在链上留洞（维护开关删除，属预期）， 涉及全表断点的断言一律按标记行过滤； 门控 CRDP_IT_MYSQL_PORT。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class AuditChainTamperMySqlIT {

    private static final String TENANT = "demoa";
    private static final long TENANT_ID = 1L;
    private static final String MODULE = "it-tamper";
    private static final String PROBE = "IT_TAMPER_PROBE";
    private static final String TENANT_LOGIN_USER = "it-tamper-user";
    private static final String PLATFORM_LOGIN_USER = "it-tamper-platform";
    private static final String TAMPERED = "篡改后的内容（示例）";

    /** 与 AuditChainVerificationService.JOB_CODE 一致（包私有不可见，此处按值引用）。 */
    private static final String CHAIN_VERIFY_JOB = "aud-chain-verify";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuditLogger auditLogger;
    @Autowired private AuditChainVerifier verifier;
    @Autowired private AuditChainVerificationService verificationService;

    private JdbcTemplate platform;
    private final Sha256DigestService digest = new Sha256DigestService();

    /** 本类跑验链作业前 aud_chain_check 的水位（AfterAll 只清自己写入的行）。 */
    private Long jobCheckBase;

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
        cleanMarkers();
    }

    @AfterAll
    void cleanUp() {
        if (platform == null) {
            return;
        }
        cleanMarkers();
        if (jobCheckBase != null) {
            // 验链结果表是普通表（只追加是写入侧约定，无触发器），清掉本类作业写入的行
            platform.update("DELETE FROM aud_chain_check WHERE id > ?", jobCheckBase);
        }
    }

    /** 自清理走维护开关（只读触发器放行；会在链上留洞，属预期，断言按标记行过滤）。 */
    private void cleanMarkers() {
        TenantContext.runAsTenant(
                TENANT_ID,
                TENANT,
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc, "DELETE FROM aud_log WHERE module = ?", MODULE);
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_login_log WHERE username = ?",
                            TENANT_LOGIN_USER);
                    AuditTableMaintenance.delete(
                            jdbc, "DELETE FROM aud_data_access_log WHERE module = ?", MODULE);
                });
        TenantContext.runAsPlatform(
                () -> {
                    AuditTableMaintenance.delete(
                            platform, "DELETE FROM plt_audit_log WHERE module = ?", MODULE);
                    AuditTableMaintenance.delete(
                            platform,
                            "DELETE FROM aud_login_log WHERE username = ?",
                            PLATFORM_LOGIN_USER);
                    AuditTableMaintenance.delete(
                            platform,
                            "DELETE FROM aud_platform_access_log WHERE module = ?",
                            MODULE);
                });
    }

    // ------------------------------------------------- 六表逐表篡改（验收核心：篡改任一条验链即报错）

    @Test
    void tenantOperationLogTamperIsDetected() {
        assertTenantTamperDetected(
                AuditChainTable.TENANT_OPERATION,
                "aud_log",
                "detail",
                "SELECT id, prev_hash, hash FROM aud_log WHERE module = ? AND action = ? ORDER BY id",
                new Object[] {MODULE, PROBE},
                () -> writeOperationMarkers(2));
    }

    @Test
    void tenantLoginLogTamperIsDetected() {
        assertTenantTamperDetected(
                AuditChainTable.TENANT_LOGIN,
                "aud_login_log",
                "message",
                "SELECT id, prev_hash, hash FROM aud_login_log WHERE username = ? ORDER BY id",
                new Object[] {TENANT_LOGIN_USER},
                this::writeTenantLoginMarkers);
    }

    @Test
    void tenantDataAccessLogTamperIsDetected() {
        assertTenantTamperDetected(
                AuditChainTable.TENANT_DATA_ACCESS,
                "aud_data_access_log",
                "description",
                "SELECT id, prev_hash, hash FROM aud_data_access_log WHERE module = ? ORDER BY id",
                new Object[] {MODULE},
                this::writeDataAccessMarkers);
    }

    @Test
    void platformOperationLogTamperIsDetected() {
        assertPlatformTamperDetected(
                AuditChainTable.PLATFORM_OPERATION,
                "plt_audit_log",
                "detail",
                "SELECT id, prev_hash, hash FROM plt_audit_log WHERE module = ? ORDER BY id",
                MODULE,
                this::writePlatformOperationMarkers);
    }

    @Test
    void platformLoginLogTamperIsDetected() {
        assertPlatformTamperDetected(
                AuditChainTable.PLATFORM_LOGIN,
                "aud_login_log",
                "message",
                "SELECT id, prev_hash, hash FROM aud_login_log WHERE username = ? ORDER BY id",
                PLATFORM_LOGIN_USER,
                this::writePlatformLoginMarkers);
    }

    @Test
    void platformAccessLogTamperIsDetected() {
        assertPlatformTamperDetected(
                AuditChainTable.PLATFORM_ACCESS,
                "aud_platform_access_log",
                "description",
                "SELECT id, prev_hash, hash FROM aud_platform_access_log WHERE module = ?"
                        + " ORDER BY id",
                MODULE,
                this::writePlatformAccessMarkers);
    }

    // ------------------------------------------------- 重封攻击（改内容并按算法重算本条 hash）

    @Test
    void resealedRowIsCaughtBySuccessorLink() {
        writeOperationMarkers(3);
        List<Map<String, Object>> rows = tenantOperationMarkerRows();
        long firstId = id(rows.get(0));
        long resealedId = id(rows.get(1));
        long successorId = id(rows.get(2));
        // 读回目标行全部参与哈希的列（与写入侧同一顺序、同一归一规则），改 detail 后按算法重算本条 hash
        Map<String, Object> row =
                TenantContext.callAsTenant(
                        TENANT_ID,
                        TENANT,
                        () -> jdbc.queryForMap("SELECT * FROM aud_log WHERE id = ?", resealedId));
        List<Object> values = new ArrayList<>();
        for (String column : AuditChainTable.TENANT_OPERATION.hashColumns()) {
            values.add(row.get(column));
        }
        String resealedDetail = "重封后的内容（示例）";
        values.set(
                AuditChainTable.TENANT_OPERATION.hashColumns().indexOf("detail"), resealedDetail);
        String prevHash = String.valueOf(row.get("prev_hash"));
        String resealedHash =
                digest.hashHex(AuditChainTable.TENANT_OPERATION.hashInput(prevHash, values));
        TenantContext.runAsTenant(
                TENANT_ID,
                TENANT,
                () ->
                        AuditTableMaintenance.update(
                                jdbc,
                                "UPDATE aud_log SET detail = ?, hash = ? WHERE id = ?",
                                resealedDetail,
                                resealedHash,
                                resealedId));

        AuditChainVerifier.TableVerification result =
                TenantContext.callAsTenant(
                        TENANT_ID,
                        TENANT,
                        () -> verifier.verifyTable(AuditChainTable.TENANT_OPERATION));

        // 本行重算自洽（不报 BAD_HASH），后继行 prev_hash 仍指向原 hash → BROKEN_LINK
        List<AuditChainVerifier.ChainBreak> inRange =
                result.breaks().stream()
                        .filter(brk -> brk.id() >= firstId && brk.id() <= successorId)
                        .toList();
        assertThat(inRange).as("标记段内只允许后继行一处断链：%s", result.breaks()).hasSize(1);
        assertThat(inRange.get(0).id()).isEqualTo(successorId);
        assertThat(inRange.get(0).kind()).isEqualTo(AuditChainVerifier.BreakKind.BROKEN_LINK);
    }

    // ------------------------------------------------- 验链作业（PLT-AUD-009：断链记录与作业 FAILED）

    @Test
    void tamperFailsVerificationJobAndRecordsBroken() {
        writeOperationMarkers(2);
        List<Map<String, Object>> rows = tenantOperationMarkerRows();
        tamperTenantOperationRow(id(rows.get(rows.size() - 1)));
        jobCheckBase =
                platform.queryForObject(
                        "SELECT COALESCE(MAX(id), 0) FROM aud_chain_check", Long.class);

        // 共享演示库上其他 IT 的自清理会让平台库自身带洞，runForDate 末尾的平台侧校验可能抛错；
        // 与本用例断言的租户侧机制无关，吞掉（定时入口同样是吞掉记日志的口径）
        try {
            verificationService.runForDate(LocalDate.now());
        } catch (IllegalStateException expectedPlatformSideHoles) {
            // 平台库历史洞导致的预期路径，见上注
        }

        // 该租户本趟作业 FAILED（断链告警的作业面）
        Map<String, Object> run =
                TenantContext.callAsTenant(
                        TENANT_ID,
                        TENANT,
                        () ->
                                jdbc.queryForMap(
                                        "SELECT status, error_text FROM job_tenant_run"
                                                + " WHERE job_code = ? AND run_date = ?",
                                        CHAIN_VERIFY_JOB,
                                        LocalDate.now()));
        assertThat(run.get("status")).isEqualTo("FAILED");
        assertThat(String.valueOf(run.get("error_text"))).contains("断链");

        // 验链结果落 aud_chain_check：篡改的表记 BROKEN（断链告警的记录面，/integrity 查询数据源）
        Map<String, Object> check =
                platform.queryForMap(
                        "SELECT status, break_count, hashed_rows FROM aud_chain_check"
                                + " WHERE tenant_id = ? AND table_name = 'aud_log' AND id > ?"
                                + " ORDER BY id DESC LIMIT 1",
                        TENANT_ID,
                        jobCheckBase);
        assertThat(check.get("status")).isEqualTo("BROKEN");
        assertThat(((Number) check.get("break_count")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) check.get("hashed_rows")).longValue()).isGreaterThanOrEqualTo(2);
    }

    // ---------------------------------------------------------------- 六表矩阵共用断言

    /** 写标记 → 已封存且衔接 → 维护开关篡改后一条内容 → 验链在该行报 BAD_HASH。 */
    private void assertTenantTamperDetected(
            AuditChainTable table,
            String tableName,
            String tamperColumn,
            String markerSql,
            Object[] markerArgs,
            Runnable writer) {
        writer.run();
        List<Map<String, Object>> rows =
                TenantContext.callAsTenant(
                        TENANT_ID, TENANT, () -> jdbc.queryForList(markerSql, markerArgs));
        assertSealedAndChained(rows, tableName);
        long tamperedId = id(rows.get(1));
        TenantContext.runAsTenant(
                TENANT_ID,
                TENANT,
                () ->
                        AuditTableMaintenance.update(
                                jdbc,
                                "UPDATE "
                                        + tableName
                                        + " SET "
                                        + tamperColumn
                                        + " = ? WHERE id = ?",
                                TAMPERED,
                                tamperedId));
        AuditChainVerifier.TableVerification result =
                TenantContext.callAsTenant(TENANT_ID, TENANT, () -> verifier.verifyTable(table));
        assertThat(result.breaks())
                .as("篡改 %s 第 %d 行 → 验链报错", tableName, tamperedId)
                .anySatisfy(
                        brk -> {
                            assertThat(brk.id()).isEqualTo(tamperedId);
                            assertThat(brk.kind()).isEqualTo(AuditChainVerifier.BreakKind.BAD_HASH);
                        });
    }

    /** 平台库三表的同构断言（写入自路由平台库，验链切平台上下文）。 */
    private void assertPlatformTamperDetected(
            AuditChainTable table,
            String tableName,
            String tamperColumn,
            String markerSql,
            Object markerArg,
            Runnable writer) {
        writer.run();
        List<Map<String, Object>> rows = platform.queryForList(markerSql, markerArg);
        assertSealedAndChained(rows, tableName);
        long tamperedId = id(rows.get(1));
        AuditTableMaintenance.update(
                platform,
                "UPDATE " + tableName + " SET " + tamperColumn + " = ? WHERE id = ?",
                TAMPERED,
                tamperedId);
        AuditChainVerifier.TableVerification result =
                TenantContext.callAsPlatform(() -> verifier.verifyTable(table));
        assertThat(result.breaks())
                .as("篡改平台库 %s 第 %d 行 → 验链报错", tableName, tamperedId)
                .anySatisfy(
                        brk -> {
                            assertThat(brk.id()).isEqualTo(tamperedId);
                            assertThat(brk.kind()).isEqualTo(AuditChainVerifier.BreakKind.BAD_HASH);
                        });
    }

    /** 两条标记均已封存（64 位哈希）且前后衔接（后一条 prev_hash = 前一条 hash）。 */
    private static void assertSealedAndChained(List<Map<String, Object>> rows, String tableName) {
        assertThat(rows).as("%s 标记行", tableName).hasSize(2);
        assertThat(rows.get(0).get("hash")).asString().hasSize(64);
        assertThat(rows.get(1).get("hash")).asString().hasSize(64);
        assertThat(rows.get(1).get("prev_hash"))
                .as("%s 链衔接", tableName)
                .isEqualTo(rows.get(0).get("hash"));
    }

    // ---------------------------------------------------------------- 写入与篡改工具

    private void writeOperationMarkers(int count) {
        TenantContext.runAsTenant(
                TENANT_ID,
                TENANT,
                () -> {
                    for (int i = 1; i <= count; i++) {
                        auditLogger.append(
                                AuditEntry.operation(MODULE, PROBE)
                                        .description("it-tamper 操作第 " + i + " 条（示例）")
                                        .objectType("it_tamper")
                                        .objectId(String.valueOf(i))
                                        .build());
                    }
                });
    }

    private void writeTenantLoginMarkers() {
        TenantContext.runAsTenant(
                TENANT_ID,
                TENANT,
                () -> {
                    for (int i = 1; i <= 2; i++) {
                        auditLogger.append(
                                AuditEntry.login(TENANT_LOGIN_USER)
                                        .description("it-tamper 登录第 " + i + " 条（示例）")
                                        .sessionId("it-tamper-" + i)
                                        .build());
                    }
                });
    }

    private void writeDataAccessMarkers() {
        TenantContext.runAsTenant(
                TENANT_ID,
                TENANT,
                () -> {
                    for (int i = 1; i <= 2; i++) {
                        auditLogger.append(
                                AuditEntry.dataAccess(MODULE, "VIEW_PLAIN")
                                        .description("it-tamper 数据访问第 " + i + " 条（示例）")
                                        .objectType("patient")
                                        .objectId("900" + i)
                                        .recordCount(1L)
                                        .build());
                    }
                });
    }

    private void writePlatformOperationMarkers() {
        TenantContext.runAsPlatform(
                () -> {
                    for (int i = 1; i <= 2; i++) {
                        auditLogger.append(
                                AuditEntry.operation(MODULE, PROBE)
                                        .tenantId(TENANT_ID)
                                        .description("it-tamper 平台操作第 " + i + " 条（示例）")
                                        .build());
                    }
                });
    }

    private void writePlatformLoginMarkers() {
        TenantContext.runAsPlatform(
                () -> {
                    for (int i = 1; i <= 2; i++) {
                        auditLogger.append(
                                AuditEntry.login(PLATFORM_LOGIN_USER)
                                        .description("it-tamper 平台登录第 " + i + " 条（示例）")
                                        .build());
                    }
                });
    }

    private void writePlatformAccessMarkers() {
        // 平台访问日志即使在租户上下文调用也自路由平台库（医院端不可见）
        TenantContext.runAsTenant(
                TENANT_ID,
                TENANT,
                () -> {
                    for (int i = 1; i <= 2; i++) {
                        auditLogger.append(
                                AuditEntry.platformAccess(TENANT_ID, MODULE, "PAGE_VIEW")
                                        .description("it-tamper 平台访问第 " + i + " 条（示例）")
                                        .viewSessionId("vs-it-tamper")
                                        .page("/it-tamper")
                                        .build());
                    }
                });
    }

    private void tamperTenantOperationRow(long tamperedId) {
        TenantContext.runAsTenant(
                TENANT_ID,
                TENANT,
                () ->
                        AuditTableMaintenance.update(
                                jdbc,
                                "UPDATE aud_log SET detail = ? WHERE id = ?",
                                TAMPERED,
                                tamperedId));
    }

    private List<Map<String, Object>> tenantOperationMarkerRows() {
        return TenantContext.callAsTenant(
                TENANT_ID,
                TENANT,
                () ->
                        jdbc.queryForList(
                                "SELECT id, prev_hash, hash FROM aud_log WHERE module = ? AND"
                                        + " action = ? ORDER BY id",
                                MODULE,
                                PROBE));
    }

    private static long id(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }
}
