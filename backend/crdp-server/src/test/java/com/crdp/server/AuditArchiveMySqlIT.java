package com.crdp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crdp.framework.audit.AuditArchiver;
import com.crdp.framework.audit.AuditChainTable;
import com.crdp.framework.audit.AuditChainVerifier;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.Sha256DigestService;
import com.crdp.framework.storage.AuditArchiveStorage;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.platform.AuditArchiveService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * E08-T03 / PLT-AUD-010 端到端（完整应用 + 真实 MySQL/Redis）。验收原文：归档对象锁定 15 年，在线保留 1 年。
 *
 * <ol>
 *   <li>按月分区：迁移 V18 / V27 后六张审计表 RANGE(TO_DAYS(created_at)) 月分区 + pmax，只读触发器（PLT-AUD-011）存活；
 *   <li>归档：逻辑日 2027-06-15（在线保留 12 个月 → cutoff 2026-06-15）跑作业，p202601（含补录的 2025-06 封存行）导出 JSONL.gz +
 *       清清单、单次 PUT 写入 WORM 桶（对象级保留 = 2027-06-15 + 15 年 = 2042-06-15）、读回校验、 登记 aud_archive 后 DROP
 *       PARTITION；空到期分区（p202602…p202605）只登记行数 0 并删除；
 *   <li>验链桥接：分区删除后在线链出现归档洞，AuditChainVerifier 凭登记桥接，洞后行不报断链；
 *   <li>分区维护：缺月时 REORGANIZE pmax 预建未来月份。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT。自建自清（module=it-archive；@AfterAll 恢复月分区并清登记 / 对象），全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class AuditArchiveMySqlIT {

    private static final String TENANT = "demoa";
    private static final String MODULE = "it-archive";

    /** 逻辑日：cutoff = 2026-06-15，p202601…p202605 到期，p202606 起保留在线。 */
    private static final LocalDate LOGICAL_DATE = LocalDate.of(2027, 6, 15);

    private static final LocalDate RETAIN_UNTIL = LocalDate.of(2042, 6, 15);

    /** 测试替身：内存 WORM 桶（记录每次 PUT 的对象级保留日）。 */
    static final InMemoryWormStore wormStore = new InMemoryWormStore();

    @TestConfiguration
    static class FakeArchiveStoreConfig {

        @Bean
        @Primary
        AuditArchiveStorage auditArchiveStorage() {
            return wormStore;
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuditLogger auditLogger;
    @Autowired private AuditChainVerifier verifier;
    @Autowired private AuditArchiver archiver;
    @Autowired private AuditArchiveService archiveService;

    private JdbcTemplate platform;
    private final Sha256DigestService digest = new Sha256DigestService();

    @BeforeAll
    void connectPlatformAndResetState() {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        platform =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/crdp_platform?useSSL=false&allowPublicKeyRetrieval=true",
                                "crdp_app",
                                "DevOnly#app2026"));
        // 上一轮运行可能留下：已删的月分区、归档登记、内存桶对象。重置到迁移初始形态（CI 全新库上是空操作）。
        resetMonthlyState();
    }

    /** 自清理：删登记（维护开关）、删补录行、把月分区恢复到迁移初始形态（p202601…p202606）、清内存桶。 */
    @AfterAll
    void cleanUp() {
        if (platform == null) {
            return;
        }
        resetMonthlyState();
    }

    private void resetMonthlyState() {
        for (String table : List.of("aud_log", "aud_login_log", "aud_data_access_log")) {
            TenantContext.runAsTenant(
                    1L,
                    TENANT,
                    () -> {
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM " + table + " WHERE module = ?", MODULE);
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_archive WHERE table_name = ? AND month_first_day"
                                        + " < '2026-07-01'",
                                table);
                        restoreMonthPartitions(table);
                    });
        }
        for (String table : List.of("plt_audit_log", "aud_login_log", "aud_platform_access_log")) {
            TenantContext.runAsPlatform(
                    () -> {
                        AuditTableMaintenance.delete(
                                platform,
                                "DELETE FROM aud_archive WHERE table_name = ? AND"
                                        + " month_first_day < '2026-07-01'",
                                table);
                        restoreMonthPartitionsOn(platform, table);
                    });
        }
        wormStore.objects.clear();
        wormStore.retentions.clear();
    }

    // ---------------------------------------------------------------- 分区与只读（迁移落地）

    @Test
    void auditTablesAreMonthlyPartitionedWithTriggersAlive() {
        List<String> partitions = tenantPartitions("aud_log");
        assertThat(partitions).contains("p202601", "p202611", "pmax");

        // 只读触发器在分区改建后仍生效（PLT-AUD-011）
        TenantContext.runAsTenant(
                1L,
                TENANT,
                () -> {
                    auditLogger.append(
                            AuditEntry.operation(MODULE, "IT_ARCHIVE_PROBE")
                                    .description("it-archive 只读探针（示例）")
                                    .build());
                    Long id =
                            jdbc.queryForObject(
                                    "SELECT id FROM aud_log WHERE module = ? ORDER BY id DESC LIMIT 1",
                                    Long.class,
                                    MODULE);
                    assertThatThrownBy(
                                    () ->
                                            jdbc.update(
                                                    "UPDATE aud_log SET detail = ? WHERE id = ?",
                                                    "绕过分区直改（示例）",
                                                    id))
                            .isInstanceOf(DataAccessException.class)
                            .hasMessageContaining("PLT-AUD-011");
                });
    }

    // ---------------------------------------------------------------- 归档全流程（验收核心）

    @Test
    void expiredPartitionArchivedToWormLocked15YearsAndChainBridges() {
        // ① 补录两条 2025-06 的已封存行（直插含 created_at；INSERT 不触发只读触发器）
        String hash1 = backdateSealedRow("it-archive 归档前旧行一（示例）");
        String hash2 = backdateSealedRow("it-archive 归档前旧行二（示例）");
        long firstBackdatedId = firstBackdatedId(hash1);
        // ② 洞后在线行：当前时间经审计写入服务封存，prev_hash 指向旧行二（归档后成为跨洞衔接）
        TenantContext.runAsTenant(
                1L,
                TENANT,
                () ->
                        auditLogger.append(
                                AuditEntry.operation(MODULE, "IT_ARCHIVE_PROBE")
                                        .description("it-archive 归档后在线行（示例）")
                                        .build()));
        long onlineMarkerId =
                TenantContext.callAsTenant(
                        1L,
                        TENANT,
                        () ->
                                requireLong(
                                        "SELECT id FROM aud_log WHERE module = ? AND created_at >="
                                                + " '2026-07-01' ORDER BY id DESC LIMIT 1",
                                        MODULE));

        // ③ 跑归档作业（逻辑日 2027-06-15）
        archiveService.runForDate(LOGICAL_DATE);

        TenantContext.runAsTenant(
                1L,
                TENANT,
                () -> {
                    // 在线保留：旧行随分区删除，不再可查；保留期内的行仍在
                    assertThat(
                                    requireLong(
                                            "SELECT COUNT(*) FROM aud_log WHERE module = ? AND"
                                                    + " created_at < '2026-07-01'",
                                            MODULE))
                            .isZero();
                    assertThat(
                                    requireLong(
                                            "SELECT COUNT(*) FROM aud_log WHERE id = ?",
                                            onlineMarkerId))
                            .isEqualTo(1);

                    // 登记：行数、链尾、对象键、保留期 15 年
                    Map<String, Object> registry =
                            jdbc.queryForMap(
                                    "SELECT * FROM aud_archive WHERE table_name = 'aud_log' AND"
                                            + " partition_name = 'p202601'");
                    assertThat(registry.get("row_count")).isEqualTo(2L);
                    assertThat(registry.get("last_hash")).isEqualTo(hash2);
                    assertThat((String) registry.get("object_key"))
                            .isEqualTo("audit/tenant/demoa/aud_log/2026-01.jsonl.gz");
                    assertThat(((java.sql.Date) registry.get("retention_until")).toLocalDate())
                            .as("归档对象锁定 15 年（归档日 + 15 年）")
                            .isEqualTo(RETAIN_UNTIL);

                    // 空到期分区：行数 0 登记 + 删除
                    assertThat(
                                    requireLong(
                                            "SELECT COUNT(*) FROM aud_archive WHERE table_name ="
                                                    + " 'aud_log' AND partition_name = 'p202602' AND"
                                                    + " row_count = 0",
                                            new Object[0]))
                            .isEqualTo(1);
                    assertThat(tenantPartitions("aud_log"))
                            .doesNotContain("p202601", "p202602", "p202605")
                            .contains("p202606", "pmax");
                });

        // WORM 桶：单次 PUT 的数据对象与清单，对象级保留 = 归档日 + 15 年
        String dataKey = "audit/tenant/demoa/aud_log/2026-01.jsonl.gz";
        String manifestKey = "audit/tenant/demoa/aud_log/2026-01.manifest.json";
        assertThat(wormStore.objects).containsKeys(dataKey, manifestKey);
        assertThat(wormStore.retentions.get(dataKey)).isEqualTo(RETAIN_UNTIL);
        assertThat(wormStore.retentions.get(manifestKey)).isEqualTo(RETAIN_UNTIL);
        String jsonl = AuditArchiver.gunzipToString(wormStore.objects.get(dataKey));
        assertThat(jsonl.split("\n"))
                .hasSize(2)
                .anySatisfy(line -> assertThat(line).contains("2025-06-15").contains("归档前旧行一"));
        String manifest = new String(wormStore.objects.get(manifestKey), StandardCharsets.UTF_8);
        assertThat(manifest)
                .contains("\"rowCount\":2")
                .contains("\"lastHash\":\"" + hash2 + "\"")
                .contains("\"sha256\":\"" + digest.hashHex(jsonl) + "\"");

        // ④ 验链桥接：跨归档洞衔接不断链（其他 IT 自清理留下的更早断点不在断言范围）
        AuditChainVerifier.TableVerification after =
                TenantContext.callAsTenant(
                        1L, TENANT, () -> verifier.verifyTable(AuditChainTable.TENANT_OPERATION));
        long bridgeFloor = firstBackdatedId;
        assertThat(after.breaks())
                .as("归档删除在线分区后，洞后行不报断链：%s", after.breaks())
                .allSatisfy(brk -> assertThat(brk.id()).isLessThan(bridgeFloor));

        // ⑤ 平台库同步归档（空到期分区）：登记与删除同样生效
        assertThat(
                        platform.queryForObject(
                                "SELECT COUNT(*) FROM aud_archive WHERE table_name ="
                                        + " 'plt_audit_log' AND partition_name = 'p202601' AND"
                                        + " row_count = 0",
                                Long.class))
                .isEqualTo(1);

        // ⑥ 幂等重跑：分区已删、对象已锁，不再产生动作也不报错
        archiveService.runForDate(LOGICAL_DATE);
    }

    // ---------------------------------------------------------------- 分区维护

    @Test
    void missingFutureMonthsArePrecreated() {
        // 2028-03 视角：静态分区只到 2027-12，应从 2028-01 预建到 2028-06
        TenantContext.runAsTenant(
                1L,
                TENANT,
                () ->
                        archiver.ensurePartitions(
                                AuditChainTable.TENANT_OPERATION,
                                LocalDate.of(2028, 3, 15),
                                new AuditArchiver.ArchivePolicy(12, 15, 3)));

        assertThat(tenantPartitions("aud_log")).contains("p202801", "p202806", "pmax");
    }

    // ---------------------------------------------------------------- 工具

    /** 直插一条 2025-06-15 的已封存行（哈希按 AuditChainTable 口径预计算），返回本行哈希。 */
    private String backdateSealedRow(String detail) {
        return TenantContext.callAsTenant(
                1L,
                TENANT,
                () -> {
                    String prev =
                            jdbc.query(
                                    "SELECT hash FROM aud_log WHERE hash IS NOT NULL ORDER BY id"
                                            + " DESC LIMIT 1",
                                    rs -> rs.next() ? rs.getString(1) : null);
                    if (prev == null) {
                        prev = AuditChainTable.GENESIS;
                    }
                    // 与 TENANT_OPERATION 登记列一一对应（其余列留空，规范化口径按空串）
                    List<Object> values =
                            java.util.Arrays.asList(
                                    "IT_ARCHIVE_PROBE",
                                    null,
                                    "IT_ARCHIVE_PROBE",
                                    null,
                                    detail,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    MODULE,
                                    null,
                                    null,
                                    "SUCCESS",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null);
                    String hash =
                            digest.hashHex(
                                    AuditChainTable.TENANT_OPERATION.hashInput(prev, values));
                    jdbc.update(
                            """
                            INSERT INTO aud_log
                              (event_type, actor_id, action, target, detail, trace_id, module,
                               result, prev_hash, hash, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                            "IT_ARCHIVE_PROBE",
                            null,
                            "IT_ARCHIVE_PROBE",
                            null,
                            detail,
                            null,
                            MODULE,
                            "SUCCESS",
                            prev,
                            hash,
                            java.sql.Timestamp.valueOf("2025-06-15 10:00:00"));
                    return hash;
                });
    }

    /** 由旧行一的哈希定位链洞之前的行（须在归档删除分区前取，断言用：更早断点可能来自其他 IT 自清理）。 */
    private long firstBackdatedId(String firstHash) {
        return TenantContext.callAsTenant(
                1L,
                TENANT,
                () -> requireLong("SELECT MIN(id) FROM aud_log WHERE hash = ?", firstHash));
    }

    private List<String> tenantPartitions(String table) {
        return TenantContext.callAsTenant(
                1L,
                TENANT,
                () ->
                        jdbc.queryForList(
                                "SELECT PARTITION_NAME FROM information_schema.PARTITIONS WHERE"
                                        + " TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND"
                                        + " PARTITION_NAME IS NOT NULL",
                                String.class,
                                table));
    }

    private long requireLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    /** 恢复月分区到迁移形态（p202601 起逐月连续）：本 IT 删除了到期分区，补回供重跑。 */
    private void restoreMonthPartitions(String table) {
        restoreMonthPartitionsOn(jdbc, table);
    }

    private void restoreMonthPartitionsOn(JdbcTemplate target, String table) {
        List<String> existing =
                target.queryForList(
                        "SELECT PARTITION_NAME FROM information_schema.PARTITIONS WHERE"
                                + " TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND"
                                + " PARTITION_NAME IS NOT NULL ORDER BY PARTITION_ORDINAL_POSITION",
                        String.class,
                        table);
        if (existing.contains("p202601")) {
            return;
        }
        String lowest =
                existing.stream()
                        .filter(name -> name.matches("p\\d{6}"))
                        .findFirst()
                        .orElse("pmax");
        LocalDate lowestBound =
                "pmax".equals(lowest) ? LocalDate.of(2028, 1, 1) : partitionBoundOf(lowest);
        // pmax-only 兜底路径按静态初始集补到 2027-12；正常路径补到最低分区前一月（最低分区本体原样重建）
        LocalDate lowestMonth =
                "pmax".equals(lowest) ? LocalDate.of(2028, 1, 1) : lowestBound.minusMonths(1);
        StringBuilder ddl =
                new StringBuilder("ALTER TABLE ")
                        .append(table)
                        .append(" REORGANIZE PARTITION ")
                        .append(lowest)
                        .append(" INTO (");
        // 补 p202601 起到「最低分区所在月份之前」的缺口；最低分区本体最后原样重建（避免重名）
        LocalDate month = LocalDate.of(2026, 1, 1);
        while (month.isBefore(lowestMonth)) {
            ddl.append("PARTITION p")
                    .append(month.getYear())
                    .append(String.format("%02d", month.getMonthValue()))
                    .append(" VALUES LESS THAN (TO_DAYS('")
                    .append(month.plusMonths(1))
                    .append("')), ");
            month = month.plusMonths(1);
        }
        if ("pmax".equals(lowest)) {
            // 源分区就是 pmax：目标集须以新 pmax 收尾（覆盖 MAXVALUE 段）
            ddl.append("PARTITION pmax VALUES LESS THAN (MAXVALUE))");
        } else {
            // 源分区是月分区：目标集只重排该月的区间，不得再定义 pmax（原 pmax 仍在，重名即冲突）
            ddl.append("PARTITION ")
                    .append(lowest)
                    .append(" VALUES LESS THAN (TO_DAYS('")
                    .append(lowestBound)
                    .append("')))");
        }
        target.update(ddl.toString());
    }

    /** pYYYYMM 的上界（次月首日）；pmax 无上界抛出（本方法不用于 pmax）。 */
    private static LocalDate partitionBoundOf(String partitionName) {
        if (!partitionName.matches("p\\d{6}")) {
            throw new IllegalArgumentException("非月分区名：" + partitionName);
        }
        int year = Integer.parseInt(partitionName.substring(1, 5));
        int month = Integer.parseInt(partitionName.substring(5, 7));
        return LocalDate.of(year, month, 1).plusMonths(1);
    }

    /** 内存版审计归档桶（本 IT 替换 Unsupported 实现）。 */
    static final class InMemoryWormStore implements AuditArchiveStorage {

        final Map<String, byte[]> objects = new java.util.HashMap<>();
        final Map<String, LocalDate> retentions = new java.util.HashMap<>();

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void putWithRetention(
                String key, byte[] body, String contentType, LocalDate retainUntil) {
            objects.put(key, body);
            retentions.put(key, retainUntil);
        }

        @Override
        public byte[] get(String key) {
            return objects.get(key);
        }
    }
}
