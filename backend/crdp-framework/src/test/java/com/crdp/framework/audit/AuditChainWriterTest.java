package com.crdp.framework.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.framework.crypto.Sha256DigestService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * E08-T02 / PLT-AUD-008：写入时封存。 每条审计 INSERT 在独立新事务里完成四步（同一连接）：
 *
 * <ol>
 *   <li>锁链头行（aud_chain_head SELECT ... FOR UPDATE）串行化并发写入，避免分叉；
 *   <li>取前序哈希 = 已封存行按主键倒序第一条的 hash（无则 GENESIS）；
 *   <li>计算本条 hash = SHA-256(prevHash + 规范化内容)，随行 INSERT；
 *   <li>推进链头（last_id / last_hash）后提交。
 * </ol>
 *
 * <p>插入语句契约：调用方 SQL 以 prev_hash、hash 结尾两列收尾（占位符比业务参数多 2），本组件补最后两个参数。
 */
class AuditChainWriterTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final List<String> sqls = new ArrayList<>();
    private final List<List<Object>> argRows = new ArrayList<>();
    private AuditChainWriter writer;

    private final AuditChainTable probe = new AuditChainTable("aud_probe", "a", "b");

    @BeforeEach
    void setUp() {
        // REQUIRES_NEW 事务模板只做直通（不验证传播行为——那是 Spring 的职责）
        PlatformTransactionManager passthrough = Mockito.mock(PlatformTransactionManager.class);
        when(passthrough.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        writer = new AuditChainWriter(jdbc, new Sha256DigestService(), passthrough);

        doAnswer(
                        invocation -> {
                            String sql = invocation.getArgument(0);
                            // 只捕获对审计日志表本体的 INSERT（链头推进语句单看）
                            if (!sql.contains("aud_chain_head")) {
                                sqls.add(sql);
                                List<Object> args = new ArrayList<>();
                                for (int i = 1; i < invocation.getArguments().length; i++) {
                                    args.add(invocation.getArgument(i));
                                }
                                argRows.add(args);
                            }
                            return 1;
                        })
                .when(jdbc)
                .update(anyString(), any(Object[].class));
    }

    private void stubChainReads(String lastHash, long newId) {
        when(jdbc.queryForObject(contains("FOR UPDATE"), eq(String.class), eq("aud_probe")))
                .thenReturn(null);
        when(jdbc.query(
                        contains("ORDER BY id DESC"),
                        ArgumentMatchers
                                .<org.springframework.jdbc.core.ResultSetExtractor<String>>any()))
                .thenReturn(lastHash);
        when(jdbc.queryForObject(contains("LAST_INSERT_ID()"), eq(Long.class))).thenReturn(newId);
    }

    @Test
    void firstAppendSealsWithGenesisPrevHash() {
        stubChainReads(null, 11L);

        writer.insert(
                probe,
                "INSERT INTO aud_probe (a, b, prev_hash, hash) VALUES (?, ?, ?, ?)",
                List.of("PROBE", 1L));

        assertThat(sqls).hasSize(1);
        List<Object> args = argRows.get(0);
        assertThat(args.subList(0, 2)).containsExactly("PROBE", 1L);
        assertThat(args.get(2)).isEqualTo(AuditChainTable.GENESIS);
        String expected =
                new Sha256DigestService()
                        .hashHex(probe.hashInput(AuditChainTable.GENESIS, List.of("PROBE", 1L)));
        assertThat(args.get(3)).isEqualTo(expected);
    }

    @Test
    void subsequentAppendChainsOntoLastSealedHash() {
        stubChainReads("f".repeat(64), 12L);

        writer.insert(
                probe,
                "INSERT INTO aud_probe (a, b, prev_hash, hash) VALUES (?, ?, ?, ?)",
                List.of("PROBE2", 2L));

        List<Object> args = argRows.get(0);
        assertThat(args.get(2)).isEqualTo("f".repeat(64));
        String expected =
                new Sha256DigestService()
                        .hashHex(probe.hashInput("f".repeat(64), List.of("PROBE2", 2L)));
        assertThat(args.get(3)).isEqualTo(expected);
    }

    @Test
    void headIsAdvancedWithNewIdAndHashInsideSameTransaction() {
        stubChainReads(null, 13L);
        String sql = "INSERT INTO aud_probe (a, b, prev_hash, hash) VALUES (?, ?, ?, ?)";

        long id = writer.insert(probe, sql, List.of("PROBE3", 3L));

        assertThat(id).isEqualTo(13L);
        Mockito.verify(jdbc)
                .update(
                        ArgumentMatchers.contains("aud_chain_head"),
                        ArgumentMatchers.eq(13L),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.eq("aud_probe"));
    }

    @Test
    void insertSqlMustEndWithPrevHashAndHashPlaceholders() {
        stubChainReads(null, 14L);
        // SQL 少了两个哈希占位符 → 属于调用方缺陷，快速失败
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                writer.insert(
                                        probe,
                                        "INSERT INTO aud_probe (a, b) VALUES (?, ?)",
                                        List.of("PROBE4", 4L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prev_hash");
    }
}
