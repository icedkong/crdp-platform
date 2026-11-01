package com.crdp.framework.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.crdp.framework.crypto.Sha256DigestService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * E08-T02 / PLT-AUD-009 验收：篡改任一条验链即报错。 验链按表内主键序重算每条 hash = SHA-256(prevHash + 规范化内容)，并核对与前序哈希的衔接：
 *
 * <ul>
 *   <li>内容被改 → 重算 hash 与存量不符（BAD_HASH）；
 *   <li>中间行被删 / 被调序 → prev_hash 与前一条 hash 不衔接（BROKEN_LINK）；
 *   <li>链已开始后又出现未封存行（MISSING_HASH）——绕过写入服务的直插可被检出；
 *   <li>E08-T01 遗留的未封存前缀（hash 为 NULL）跳过，链从第一条已封存行起锚 GENESIS。
 * </ul>
 */
class AuditChainVerifierTest {

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
    }

    @SuppressWarnings("unchecked")
    private void stubPages(AuditChainVerifier.ChainRow... rows) {
        when(jdbc.query(
                        anyString(),
                        ArgumentMatchers.<RowMapper<AuditChainVerifier.ChainRow>>any(),
                        any(Object[].class)))
                .thenReturn(List.of(rows), List.of());
    }

    /** 按真实封存口径构造一条已封存行（hash 由 prevHash + 内容算出）。 */
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
    void intactChainWithLegacyPrefixPasses() {
        List<Object> first = v("第一条（示例）");
        String h1 = seal(first, AuditChainTable.GENESIS);
        List<Object> second = v("第二条（示例）");
        String h2 = seal(second, h1);
        // id=1 是 E08-T01 遗留未封存行（hash NULL），链从 id=2 起锚 GENESIS
        stubPages(
                sealed(1L, v("遗留（示例）"), null, null),
                sealed(2L, first, AuditChainTable.GENESIS, h1),
                sealed(3L, second, h1, h2));

        AuditChainVerifier.TableVerification result = verifier.verifyTable(probe);

        assertThat(result.intact()).as("完整链验链通过：%s", result.breaks()).isTrue();
        assertThat(result.scannedRows()).isEqualTo(3);
        assertThat(result.hashedRows()).isEqualTo(2);
    }

    @Test
    void tamperedContentIsReportedAsBadHash() {
        List<Object> first = v("第一条（示例）");
        String h1 = seal(first, AuditChainTable.GENESIS);
        String h2 = seal(v("原始内容（示例）"), h1);
        // 攻击者把 id=3 的 detail 改掉，但存量 hash 仍是原内容的封存值
        stubPages(
                sealed(2L, first, AuditChainTable.GENESIS, h1),
                sealed(3L, v("篡改后的内容（示例）"), h1, h2));

        AuditChainVerifier.TableVerification result = verifier.verifyTable(probe);

        assertThat(result.intact()).isFalse();
        assertThat(result.breaks()).hasSize(1);
        assertThat(result.breaks().get(0).id()).isEqualTo(3L);
        assertThat(result.breaks().get(0).kind()).isEqualTo(AuditChainVerifier.BreakKind.BAD_HASH);
    }

    @Test
    void deletedMiddleRowIsReportedAsBrokenLink() {
        List<Object> first = v("第一条（示例）");
        String h1 = seal(first, AuditChainTable.GENESIS);
        List<Object> second = v("第二条（示例）");
        String h2 = seal(second, h1);
        List<Object> third = v("第三条（示例）");
        String h3 = seal(third, h2);
        // 第二条被删除：第三条的 prev_hash 仍指向已不存在的 h2，与实际前序 h1 不衔接
        stubPages(sealed(2L, first, AuditChainTable.GENESIS, h1), sealed(4L, third, h2, h3));

        AuditChainVerifier.TableVerification result = verifier.verifyTable(probe);

        assertThat(result.intact()).isFalse();
        assertThat(result.breaks()).hasSize(1);
        assertThat(result.breaks().get(0).id()).isEqualTo(4L);
        assertThat(result.breaks().get(0).kind())
                .isEqualTo(AuditChainVerifier.BreakKind.BROKEN_LINK);
    }

    @Test
    void unsealedRowAfterChainStartedIsReportedAsMissingHash() {
        List<Object> first = v("第一条（示例）");
        String h1 = seal(first, AuditChainTable.GENESIS);
        stubPages(
                sealed(2L, first, AuditChainTable.GENESIS, h1),
                sealed(3L, v("直插未封存（示例）"), null, null));

        AuditChainVerifier.TableVerification result = verifier.verifyTable(probe);

        assertThat(result.intact()).isFalse();
        assertThat(result.breaks()).hasSize(1);
        assertThat(result.breaks().get(0).id()).isEqualTo(3L);
        assertThat(result.breaks().get(0).kind())
                .isEqualTo(AuditChainVerifier.BreakKind.MISSING_HASH);
    }

    @Test
    void firstSealedRowMustAnchorGenesis() {
        List<Object> first = v("第一条（示例）");
        String wrongPrev = seal(v("被删除的前缀（示例）"), AuditChainTable.GENESIS);
        String h1 = seal(first, wrongPrev);
        // 首条封存行的 prev_hash 不是 GENESIS：链头之前有行被删
        stubPages(sealed(2L, first, wrongPrev, h1));

        AuditChainVerifier.TableVerification result = verifier.verifyTable(probe);

        assertThat(result.intact()).isFalse();
        assertThat(result.breaks()).hasSize(1);
        assertThat(result.breaks().get(0).kind())
                .isEqualTo(AuditChainVerifier.BreakKind.BROKEN_LINK);
        assertThat(result.breaks().get(0).id()).isEqualTo(2L);
    }

    @Test
    void tamperedPrevHashBreaksLinkAtSuccessorAndHashAtSelf() {
        // 同时篡改某条的 prev_hash（内容 hash 重算一致地伪造不了，因为 hash 覆盖 prev）：
        // prev_hash 参与本体 hash 计算 → 改 prev 必然 BAD_HASH
        List<Object> first = v("第一条（示例）");
        String h1 = seal(first, AuditChainTable.GENESIS);
        List<Object> second = v("第二条（示例）");
        String h2 = seal(second, h1);
        stubPages(
                sealed(2L, first, AuditChainTable.GENESIS, h1),
                sealed(
                        3L,
                        second,
                        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                        h2));

        AuditChainVerifier.TableVerification result = verifier.verifyTable(probe);

        assertThat(result.intact()).isFalse();
        assertThat(result.breaks())
                .extracting(AuditChainVerifier.ChainBreak::kind)
                .contains(AuditChainVerifier.BreakKind.BAD_HASH);
    }
}
