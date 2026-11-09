package com.crdp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crdp.framework.audit.AuditArchiver;
import com.crdp.framework.audit.AuditChainTable;
import com.crdp.framework.crypto.Sha256DigestService;
import com.crdp.framework.storage.AuditArchiveStorage;
import com.crdp.framework.storage.ObsAuditArchiveStorage;
import com.crdp.framework.storage.UnsupportedAuditArchiveStorage;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.platform.AuditArchiveService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E22-T08 / PLT-AUD-010 归档演练（完整应用 + 真实 MySQL/Redis）。验收原文：归档对象在保留期内不可删改。
 *
 * <p>与 {@link AuditArchiveMySqlIT}（E08-T03 归档全流程）互补：本演练用一个**执行 WORM 语义的本地桶替身** {@link
 * FileWormStore}（同键不同内容拒绝覆盖、保留期只能延长、无删除入口），在真实归档作业跑完后演练 「保留期内不可删改」的三层证据：
 *
 * <ol>
 *   <li><b>入口层</b>：端口与 OBS 适配器都没有删除 / 覆盖语义（反射断言），应用侧不存在删改归档对象的代码路径；
 *   <li><b>桶层（WORM）</b>：覆盖不同内容被拒、缩短保留被拒、延长保留允许——生产 OBS 合规模式由同一套语义兜底， 真实拒删行为按
 *       docs/ops/audit-worm-archive.md §4 用 obsutil 删除 / 覆盖尝试验证（预期失败）；
 *   <li><b>稽查层（可发现）</b>：绕过桶直接改文件（模拟拿到主机权限的攻击者）不被阻止，但对象摘要与 aud_archive.sha256 /
 *       清单立即对不上——删改不可伪造、可被发现（哈希链绑定见 ADR 0005）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT。自建自清（module=it-worm-drill），全部虚构数据。
 */
@org.springframework.test.context.ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class AuditWormDrillMySqlIT {

    private static final String TENANT = "demoa";
    private static final String MODULE = "it-worm-drill";

    /** 逻辑日：cutoff = 2026-06-15，p202601（含补录的 2025-06 行）到期归档。 */
    private static final LocalDate LOGICAL_DATE = LocalDate.of(2027, 6, 15);

    private static final LocalDate RETAIN_UNTIL = LocalDate.of(2042, 6, 15);

    /** 执行 WORM 语义的本地桶替身（对象落临时目录，保留期在内存登记）。 */
    static final FileWormStore wormStore = new FileWormStore();

    @TestConfiguration
    static class WormDrillStoreConfig {

        @Bean
        @Primary
        AuditArchiveStorage auditArchiveStorage() {
            return wormStore;
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuditArchiveService archiveService;
    private JdbcTemplate platform;
    private final Sha256DigestService digest = new Sha256DigestService();

    @BeforeAll
    void resetState() throws Exception {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        platform =
                new JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/crdp_platform?useSSL=false&allowPublicKeyRetrieval=true",
                                "crdp_app",
                                "DevOnly#app2026"));
        wormStore.reset();
        TenantContext.runAsTenant(
                1L,
                TENANT,
                () -> {
                    for (String table :
                            List.of("aud_log", "aud_login_log", "aud_data_access_log")) {
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM " + table + " WHERE module = ?", MODULE);
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_archive WHERE table_name = ? AND month_first_day"
                                        + " < '2026-07-01'",
                                table);
                        restoreMonthPartitions(table);
                    }
                });
        TenantContext.runAsPlatform(
                () -> {
                    for (String table :
                            List.of("plt_audit_log", "aud_login_log", "aud_platform_access_log")) {
                        AuditTableMaintenance.delete(
                                platform,
                                "DELETE FROM aud_archive WHERE table_name = ? AND"
                                        + " month_first_day < '2026-07-01'",
                                table);
                        restoreMonthPartitionsOn(platform, table);
                    }
                });
    }

    @AfterAll
    void cleanUp() {
        // 复用 @BeforeAll 的重置：删演练行与登记、恢复月分区（CI 全新库上是空操作）
        try {
            resetState();
        } catch (Exception ex) {
            throw new IllegalStateException("演练清理失败", ex);
        } finally {
            wormStore.cleanup();
        }
    }

    // ---------------------------------------------------------------- 验收：归档 + 不可删改矩阵

    @Test
    void archivedObjectsAreImmutableWithinRetention() {
        // ① 补录一条 2025-06-15 的已封存行（INSERT 不触发只读触发器），跑真实归档作业（04:10 同一入口）
        backdateSealedRow();
        archiveService.runForDate(LOGICAL_DATE);

        String dataKey = "audit/tenant/demoa/aud_log/2026-01.jsonl.gz";
        String manifestKey = "audit/tenant/demoa/aud_log/2026-01.manifest.json";
        TenantContext.runAsTenant(
                1L,
                TENANT,
                () -> {
                    Map<String, Object> registry =
                            jdbc.queryForMap(
                                    "SELECT * FROM aud_archive WHERE table_name = 'aud_log' AND"
                                            + " partition_name = 'p202601'");
                    assertThat(registry.get("row_count")).isEqualTo(1L);
                    assertThat((String) registry.get("object_key")).isEqualTo(dataKey);
                    assertThat(((java.sql.Date) registry.get("retention_until")).toLocalDate())
                            .as("对象级保留 = 归档日 + 15 年")
                            .isEqualTo(RETAIN_UNTIL);

                    // ② 桶层（WORM 语义）：对象存在、保留期钉到 2042-06-15（数据对象与清单同锁）
                    assertThat(wormStore.retentionOf(dataKey)).isEqualTo(RETAIN_UNTIL);
                    assertThat(wormStore.retentionOf(manifestKey)).isEqualTo(RETAIN_UNTIL);

                    // ③ 不可删改矩阵一：覆盖不同内容被拒（OBS 合规模式同语义）
                    byte[] original = wormStore.get(dataKey);
                    assertThatThrownBy(
                                    () ->
                                            wormStore.putWithRetention(
                                                    dataKey,
                                                    "篡改后的归档体（示例）".getBytes(),
                                                    "application/gzip",
                                                    RETAIN_UNTIL.plusYears(1)))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("WORM");

                    // ④ 不可删改矩阵二：缩短保留被拒（保留期只能延长）
                    assertThatThrownBy(
                                    () ->
                                            wormStore.putWithRetention(
                                                    dataKey,
                                                    original,
                                                    "application/gzip",
                                                    RETAIN_UNTIL.minusYears(1)))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("WORM");

                    // ⑤ 保留期延长允许（等保测评 / 合同加严口径）
                    wormStore.putWithRetention(
                            dataKey, original, "application/gzip", RETAIN_UNTIL.plusYears(1));
                    assertThat(wormStore.retentionOf(dataKey)).isEqualTo(RETAIN_UNTIL.plusYears(1));

                    // ⑥ 读回校验：对象摘要与登记一致（归档后按需恢复查询同口径）
                    assertThat(digest.hashHex(AuditArchiver.gunzipToString(wormStore.get(dataKey))))
                            .isEqualTo(registry.get("sha256"));

                    // ⑦ 稽查层：绕过桶直改文件不被阻止，但立即暴露——改坏的内容读不回（解压失败），
                    //    换成合法 gzip 的伪造内容则摘要与登记对不上（aud_archive 只追加，删改不可伪造）
                    wormStore.tamperOnDisk(dataKey, "被主机级攻击者改写（示例）".getBytes());
                    assertThatThrownBy(() -> AuditArchiver.gunzipToString(wormStore.get(dataKey)))
                            .isInstanceOf(java.io.UncheckedIOException.class);
                    wormStore.tamperOnDisk(dataKey, AuditArchiver.gzip("伪造的归档行（示例）"));
                    assertThat(digest.hashHex(AuditArchiver.gunzipToString(wormStore.get(dataKey))))
                            .isNotEqualTo(registry.get("sha256"))
                            .as("桶外删改逃不过登记摘要比对（aud_archive 只追加）");
                });
    }

    /** 入口层：端口与实现都没有删除 / 覆盖语义——应用侧不存在删改归档对象的代码路径（PLT-AUD-011 同口径）。 */
    @Test
    void archiveStoragePortHasNoDeleteOrOverwriteSemantics() {
        for (Class<?> type :
                List.of(
                        AuditArchiveStorage.class, ObsAuditArchiveStorage.class,
                        UnsupportedAuditArchiveStorage.class, FileWormStore.class)) {
            assertThat(
                            Arrays.stream(type.getDeclaredMethods())
                                    .map(java.lang.reflect.Method::getName)
                                    .filter(
                                            name ->
                                                    name.toLowerCase().contains("delete")
                                                            || name.toLowerCase().contains("remove")
                                                            || name.toLowerCase()
                                                                    .contains("overwrite")))
                    .as("%s 不应有删除 / 覆盖语义方法", type.getSimpleName())
                    .isEmpty();
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 直插一条 2025-06-15 的已封存行（哈希按 AuditChainTable 口径预计算，与 E08-T03 IT 同款做法）。 */
    private void backdateSealedRow() {
        TenantContext.runAsTenant(
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
                    String detail = "WORM 演练归档行（示例）";
                    List<Object> values =
                            Arrays.asList(
                                    "IT_WORM_DRILL",
                                    null,
                                    "IT_WORM_DRILL",
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
                            "IT_WORM_DRILL",
                            null,
                            "IT_WORM_DRILL",
                            null,
                            detail,
                            null,
                            MODULE,
                            "SUCCESS",
                            prev,
                            hash,
                            java.sql.Timestamp.valueOf("2025-06-15 10:00:00"));
                });
    }

    /** 恢复月分区到迁移形态（p202601 起逐月连续）；与 AuditArchiveMySqlIT 同款自清理逻辑。 */
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
        LocalDate lowestMonth =
                "pmax".equals(lowest) ? LocalDate.of(2028, 1, 1) : lowestBound.minusMonths(1);
        StringBuilder ddl =
                new StringBuilder("ALTER TABLE ")
                        .append(table)
                        .append(" REORGANIZE PARTITION ")
                        .append(lowest)
                        .append(" INTO (");
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
            ddl.append("PARTITION pmax VALUES LESS THAN (MAXVALUE))");
        } else {
            ddl.append("PARTITION ")
                    .append(lowest)
                    .append(" VALUES LESS THAN (TO_DAYS('")
                    .append(lowestBound)
                    .append("')))");
        }
        target.update(ddl.toString());
    }

    private static LocalDate partitionBoundOf(String partitionName) {
        int year = Integer.parseInt(partitionName.substring(1, 5));
        int month = Integer.parseInt(partitionName.substring(5, 7));
        return LocalDate.of(year, month, 1).plusMonths(1);
    }

    // ---------------------------------------------------------------- 本地 WORM 桶替身

    /**
     * 执行 WORM 语义的本地桶替身：对象按键落临时目录；同键不同内容拒绝覆盖、保留期只能延长、 无删除入口。 比真实 OBS
     * 更严（到期后也没有删除路径——到期后默认不自动删除，tech-plan §14）。 {@link #tamperOnDisk}
     * 是演练专用的「绕过桶直改文件」通道，模拟拿到主机权限的攻击者（真实 OBS 无此通道，由合规模式兜底）。
     */
    static final class FileWormStore implements AuditArchiveStorage {

        private final Map<String, LocalDate> retentions = new HashMap<>();
        private Path root;

        void reset() throws Exception {
            cleanup();
            root = Files.createTempDirectory("crdp-worm-drill");
        }

        void cleanup() {
            retentions.clear();
            if (root == null) {
                return;
            }
            try (var files = Files.walk(root)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (Exception ignored) {
                // 临时目录清理失败不影响断言
            }
            root = null;
        }

        LocalDate retentionOf(String key) {
            return retentions.get(key);
        }

        /** 演练通道：绕过桶 API 直改文件（模拟主机级攻击者）；get 读到的即被改内容。 */
        void tamperOnDisk(String key, byte[] body) {
            try {
                Files.write(objectPath(key), body);
            } catch (Exception ex) {
                throw new IllegalStateException("篡改通道写入失败", ex);
            }
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void putWithRetention(
                String key, byte[] body, String contentType, LocalDate retainUntil) {
            Path path = objectPath(key);
            if (Files.exists(path)) {
                byte[] existing = get(key);
                if (!Arrays.equals(existing, body)) {
                    throw new IllegalStateException("WORM：受保护对象不可覆盖（同键不同内容）key=" + key);
                }
                if (retainUntil.isBefore(retentions.get(key))) {
                    throw new IllegalStateException("WORM：保留期只能延长，不能缩短 key=" + key);
                }
                retentions.put(key, retainUntil); // 同内容延长保留：允许
                return;
            }
            try {
                Files.createDirectories(path.getParent());
                Files.write(path, body);
            } catch (Exception ex) {
                throw new IllegalStateException("本地 WORM 桶写入失败 key=" + key, ex);
            }
            retentions.put(key, retainUntil);
        }

        @Override
        public byte[] get(String key) {
            try {
                return Files.readAllBytes(objectPath(key));
            } catch (java.nio.file.NoSuchFileException nonexistent) {
                return null;
            } catch (Exception ex) {
                throw new IllegalStateException("本地 WORM 桶读取失败 key=" + key, ex);
            }
        }

        private Path objectPath(String key) {
            if (root == null) {
                throw new IllegalStateException("桶未初始化（先 reset()）");
            }
            return root.resolve(key);
        }
    }
}
