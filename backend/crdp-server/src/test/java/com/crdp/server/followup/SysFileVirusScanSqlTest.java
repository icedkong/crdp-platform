package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * E22-T15 / 等保自查 D-01 整改核对（单元）：sys_file 病毒扫描列迁移 V139 与整改口径一致。
 *
 * <p>锁定口径：
 *
 * <ol>
 *   <li>只新增列与索引（ALTER TABLE ... ADD），不改不删既有列（AGENTS §5.6 Flyway 只新增）；
 *   <li>virus_scan_status 默认 UNSCANNED——存量行（扫描启用前的历史文件）天然进入补扫面， 不需要回填脚本；
 *   <li>状态枚举与代码一致（UNSCANNED / CLEAN / INFECTED / HASH_MISMATCH，SysFileMapper 守卫 WHERE
 *       virus_scan_status = 'UNSCANNED' 幂等）；
 *   <li>补扫索引（virus_scan_status, id）在位（补扫批查询与待处置清单的面）。
 * </ol>
 */
class SysFileVirusScanSqlTest {

    private static String migration;

    @BeforeAll
    static void read() {
        migration = read("db/tenant/followup/V139__sys_file_virus_scan.sql");
    }

    /** 只做加法：ADD COLUMN × 2 + ADD KEY，无 DROP / MODIFY / CHANGE（不改旧脚本语义）。 */
    @Test
    void appendOnlyAlter() {
        assertTrue(migration.contains("ALTER TABLE sys_file"), "是 sys_file 的 ALTER");
        assertTrue(migration.contains("ADD COLUMN virus_scan_status"), "新增 virus_scan_status 列");
        assertTrue(migration.contains("ADD COLUMN virus_scanned_at"), "新增 virus_scanned_at 列");
        assertTrue(migration.contains("ADD KEY idx_sys_file_virus_scan"), "补扫索引在位");
        assertFalse(containsAnyWord("DROP", "MODIFY", "CHANGE"), "不改不删既有列");
    }

    /** 存量行默认 UNSCANNED（扫描启用前的历史文件直接进入补扫面，无需回填）。 */
    @Test
    void existingRowsDefaultToUnscanned() {
        assertTrue(
                migration.contains("NOT NULL DEFAULT 'UNSCANNED'"),
                "virus_scan_status 默认 UNSCANNED");
    }

    /** 注释登记状态机与四态枚举（与 VirusScanRetroService / SysFileMapper 守卫一致）。 */
    @Test
    void statusMachineDocumented() {
        for (String state : new String[] {"UNSCANNED", "CLEAN", "INFECTED", "HASH_MISMATCH"}) {
            assertTrue(migration.contains(state), "枚举 " + state + " 在注释中登记");
        }
        assertTrue(migration.contains("E22-T15"), "脚本头注明任务来源（E22-T15 / D-01 整改）");
    }

    private static boolean containsAnyWord(String... words) {
        for (String word : words) {
            if (migration.matches("(?s).*\\b" + word + "\\b.*")) {
                return true;
            }
        }
        return false;
    }

    private static String read(String path) {
        try (InputStream in =
                SysFileVirusScanSqlTest.class.getClassLoader().getResourceAsStream(path)) {
            assertTrue(in != null, "迁移脚本在位：" + path);
            if (in == null) {
                throw new IllegalStateException("迁移脚本缺失：" + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
