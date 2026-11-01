package com.crdp.framework.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

import com.crdp.framework.crypto.Sha256DigestService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * E08-T03 / PLT-AUD-008+010：归档删除在线分区后，哈希链跨归档段仍可验。
 *
 * <p>归档作业按月删分区（DROP PARTITION），在线行之间出现「归档洞」： 洞后首行的 prev_hash 指向已归档行的链尾哈希（登记在
 * aud_archive.last_hash，对象在 WORM 桶锁定 15 年）。验链遇到衔接不上时先查登记： prev_hash 与某归档段链尾一致（且该段 last_id 在当前行之前）→
 * 判定为合法桥接，不断链； 对不上（登记也没有）→ 仍按 BROKEN_LINK 报断链（真删行 / 篡改依旧可检出）。
 */
class AuditChainArchiveBridgeTest {

    private final Sha256DigestService digest = new Sha256DigestService();
    private final AuditChainTable probe =
            new AuditChainTable("aud_probe", "event_type", "actor_id", "detail");

    private final JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    private AuditChainVerifier verifier;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        verifier = new AuditChainVerifier(jdbc, digest);
        when(jdbc.query(
                        anyString(),
                        ArgumentMatchers.<RowMapper<AuditChainVerifier.ChainRow>>any(),
                        any(Object[].class)))
                .thenReturn(List.of());
        // 默认：无归档登记（aud_archive 空表）
        when(jdbc.queryForList(contains("aud_archive"), any(Object[].class))).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private void stubPages(AuditChainVerifier.ChainRow... rows) {
        when(jdbc.query(
                        anyString(),
                        ArgumentMatchers.<RowMapper<AuditChainVerifier.ChainRow>>any(),
                        any(Object[].class)))
                .thenReturn(List.of(rows), List.of());
    }

    private void stubArchivedTails(Map<Long, String> lastIdToHash) {
        List<Map<String, Object>> registry = new java.util.ArrayList<>();
        lastIdToHash.forEach(
                (lastId, lastHash) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("last_id", lastId);
                    row.put("last_hash", lastHash);
                    registry.add(row);
                });
        when(jdbc.queryForList(contains("aud_archive"), any(Object[].class)))
                .thenReturn((List<Map<String, Object>>) (List<?>) registry);
    }

    private AuditChainVerifier.ChainRow sealed(
            long id, List<Object> values, String prevHash, String storedHash) {
        return new AuditChainVerifier.ChainRow(id, values, prevHash, storedHash);
    }

    private String seal(List<Object> values, String prevHash) {
        return digest.hashHex(probe.hashInput(prevHash, values));
    }

    private List<Object> v(String detail) {
        return List.of("PROBE", 7L, detail);
    }

    @Test
    void bridgedLinkOverArchivedPartitionIsIntact() {
        // p202601（id 2…9）已归档删除；在线首行 id=10 的 prev_hash 指向归档段链尾 h9
        List<Object> online = v("归档后的在线行（示例）");
        String h10 = seal(online, "h9");
        stubPages(sealed(10L, online, "h9", h10));
        stubArchivedTails(Map.of(9L, "h9"));

        AuditChainVerifier.TableVerification result = verifier.verifyTable(probe);

        assertThat(result.intact()).as("跨归档段桥接验链通过：%s", result.breaks()).isTrue();
        assertThat(result.hashedRows()).isEqualTo(1);
    }

    @Test
    void unregisteredGapStillBreaks() {
        // 在线行 prev_hash 指向的哈希既不来自前一在线行、也不在归档登记里 → 真断链（删行 / 篡改可检出）
        List<Object> online = v("断链行（示例）");
        String h10 = seal(online, "deadbeef");
        stubPages(sealed(10L, online, "deadbeef", h10));
        stubArchivedTails(Map.of(9L, "h9"));

        AuditChainVerifier.TableVerification result = verifier.verifyTable(probe);

        assertThat(result.intact()).isFalse();
        assertThat(result.breaks()).hasSize(1);
        assertThat(result.breaks().get(0).kind())
                .isEqualTo(AuditChainVerifier.BreakKind.BROKEN_LINK);
        assertThat(result.breaks().get(0).id()).isEqualTo(10L);
    }

    @Test
    void archivedTailAfterCurrentRowDoesNotBridge() {
        // 登记段的 last_id 不小于当前行 id：不算「洞在前」，仍是断链（防拿未来归档段掩护调序）
        List<Object> online = v("洞在后（示例）");
        String h5 = seal(online, "h4");
        List<Object> next = v("次行（示例）");
        String h6 = seal(next, "other");
        stubPages(sealed(5L, online, "h4", h5), sealed(6L, next, "other", h6));
        stubArchivedTails(Map.of(20L, "other"));

        AuditChainVerifier.TableVerification result = verifier.verifyTable(probe);

        assertThat(result.intact()).isFalse();
        assertThat(result.breaks())
                .anySatisfy(
                        brk -> {
                            assertThat(brk.id()).isEqualTo(6L);
                            assertThat(brk.kind())
                                    .isEqualTo(AuditChainVerifier.BreakKind.BROKEN_LINK);
                        });
    }

    @Test
    void missingRegistryTableDegradesToPlainVerification() {
        // aud_archive 尚不存在的库（迁移前的老环境）：桥接查询失败不炸验链，按无归档处理
        List<Object> online = v("在线行（示例）");
        String h10 = seal(online, "h9");
        stubPages(sealed(10L, online, "h9", h10));
        when(jdbc.queryForList(contains("aud_archive"), any(Object[].class)))
                .thenThrow(
                        new BadSqlGrammarException(
                                "query", "SELECT last_id", new java.sql.SQLException("表不存在")));

        AuditChainVerifier.TableVerification result = verifier.verifyTable(probe);

        assertThat(result.intact()).isFalse();
        assertThat(result.breaks()).hasSize(1);
        assertThat(result.breaks().get(0).kind())
                .isEqualTo(AuditChainVerifier.BreakKind.BROKEN_LINK);
    }
}
