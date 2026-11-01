package com.crdp.framework.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.crdp.framework.crypto.Sha256DigestService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * E08-T06 / PLT-AUD-008 验收「篡改任一条验链即报错」——对抗了解算法与格式的攻击者的强化用例。 在 {@code AuditChainVerifierTest}
 * 三类基础断链（改内容 / 删行 / 直插 NULL 哈希行）之上补四类：
 *
 * <ul>
 *   <li>重封攻击：改内容并按算法重算本条 hash（本行自洽）→ 后继行 prev_hash 衔接不上（BROKEN_LINK），单条链伪造不成；
 *   <li>整体调序：两行连同 prev/hash 一起对调 → 至少一处衔接断裂（BROKEN_LINK）；
 *   <li>伪造直插：链开始后插入一条带编造 hash（非 NULL）的行 → 重算不符（BAD_HASH）；
 *   <li>分页边界：断点落在第二批（BATCH_SIZE=500 之后）也要检出——翻页游标不得漏行；跨批完整链照常通过。
 * </ul>
 */
class AuditChainTamperDetectionTest {

    private final Sha256DigestService digest = new Sha256DigestService();
    private final AuditChainTable probe =
            new AuditChainTable("aud_probe", "event_type", "actor_id", "detail");

    private final JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    private AuditChainVerifier verifier;

    /** 与 AuditChainVerifier.BATCH_SIZE 对齐（见分页边界用例注释）。 */
    private static final int BATCH_SIZE = 500;

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

    /** 逐批返回行（最后一批之后必须再返回空批，模拟翻页结束）。 */
    @SafeVarargs
    private void stubPages(List<AuditChainVerifier.ChainRow>... pages) {
        List<List<AuditChainVerifier.ChainRow>> batches = new ArrayList<>(List.of(pages));
        batches.add(List.of());
        when(jdbc.query(
                        anyString(),
                        ArgumentMatchers.<RowMapper<AuditChainVerifier.ChainRow>>any(),
                        any(Object[].class)))
                .thenAnswer(invocation -> batches.isEmpty() ? List.of() : batches.remove(0));
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
    void resealedMiddleRowIsCaughtBySuccessorBrokenLink() {
        List<Object> first = v("第一条（示例）");
        String h1 = seal(first, AuditChainTable.GENESIS);
        String h2 = seal(v("第二条原始内容（示例）"), h1);
        // 攻击者改掉第二条内容并按算法重算本条 hash：本行重算自洽（BAD_HASH 不报），
        // 但第三条的 prev_hash 仍指向原 h2，衔接断裂——重封伪造不了整条链
        List<Object> resealed = v("重封后的内容（示例）");
        String forged = seal(resealed, h1);
        List<Object> third = v("第三条（示例）");
        String h3 = seal(third, h2);
        stubPages(
                List.of(
                        sealed(2L, first, AuditChainTable.GENESIS, h1),
                        sealed(3L, resealed, h1, forged),
                        sealed(4L, third, h2, h3)));

        AuditChainVerifier.TableVerification result = verifier.verifyTable(probe);

        assertThat(result.intact()).as("重封后验链仍报错：%s", result.breaks()).isFalse();
        assertThat(result.breaks()).hasSize(1);
        assertThat(result.breaks().get(0).id()).isEqualTo(4L);
        assertThat(result.breaks().get(0).kind())
                .isEqualTo(AuditChainVerifier.BreakKind.BROKEN_LINK);
    }

    @Test
    void fullySwappedRowsAreStillDetected() {
        List<Object> first = v("第一条（示例）");
        String h1 = seal(first, AuditChainTable.GENESIS);
        List<Object> second = v("第二条（示例）");
        String h2 = seal(second, h1);
        List<Object> third = v("第三条（示例）");
        String h3 = seal(third, h2);
        // 两行连同 prev/hash 整体对调（各自重算仍自洽）：主键序下的衔接必然断裂
        stubPages(
                List.of(
                        sealed(2L, first, AuditChainTable.GENESIS, h1),
                        sealed(3L, third, h2, h3),
                        sealed(4L, second, h1, h2)));

        AuditChainVerifier.TableVerification result = verifier.verifyTable(probe);

        assertThat(result.intact()).isFalse();
        assertThat(result.breaks())
                .extracting(AuditChainVerifier.ChainBreak::kind)
                .containsOnly(AuditChainVerifier.BreakKind.BROKEN_LINK);
        assertThat(result.breaks())
                .extracting(AuditChainVerifier.ChainBreak::id)
                .containsExactlyInAnyOrder(3L, 4L);
    }

    @Test
    void forgedInsertWithMadeUpHashIsBadHash() {
        List<Object> first = v("第一条（示例）");
        String h1 = seal(first, AuditChainTable.GENESIS);
        // 直插行带了格式合法的编造 hash（非 NULL，MISSING_HASH 不适用）：重算不符即 BAD_HASH
        stubPages(
                List.of(
                        sealed(2L, first, AuditChainTable.GENESIS, h1),
                        sealed(3L, v("伪造直插行（示例）"), h1, "deadbeef".repeat(8))));

        AuditChainVerifier.TableVerification result = verifier.verifyTable(probe);

        assertThat(result.intact()).isFalse();
        assertThat(result.breaks()).hasSize(1);
        assertThat(result.breaks().get(0).id()).isEqualTo(3L);
        assertThat(result.breaks().get(0).kind()).isEqualTo(AuditChainVerifier.BreakKind.BAD_HASH);
    }

    @Test
    void tamperBeyondBatchBoundaryIsDetected() {
        // 前 500 行构成完整链（第一批），第 501 行（第二批）内容被改但存量 hash 未动
        List<AuditChainVerifier.ChainRow> batch1 = new ArrayList<>(BATCH_SIZE);
        String prev = AuditChainTable.GENESIS;
        for (int i = 1; i <= BATCH_SIZE; i++) {
            List<Object> values = v("第 " + i + " 条（示例）");
            String hash = seal(values, prev);
            batch1.add(sealed(i, values, prev, hash));
            prev = hash;
        }
        String storedTail = prev;
        List<Object> tamperedTail = v("第 501 条被篡改（示例）");
        List<AuditChainVerifier.ChainRow> batch2 =
                List.of(
                        sealed(
                                BATCH_SIZE + 1L,
                                tamperedTail,
                                storedTail,
                                seal(v("第 501 条原始（示例）"), storedTail)));
        stubPages(batch1, batch2);

        AuditChainVerifier.TableVerification result = verifier.verifyTable(probe);

        assertThat(result.intact()).as("跨批断点也要检出：%s", result.breaks()).isFalse();
        assertThat(result.scannedRows()).isEqualTo(BATCH_SIZE + 1L);
        assertThat(result.hashedRows()).isEqualTo(BATCH_SIZE + 1L);
        assertThat(result.breaks()).hasSize(1);
        assertThat(result.breaks().get(0).id()).isEqualTo(BATCH_SIZE + 1L);
        assertThat(result.breaks().get(0).kind()).isEqualTo(AuditChainVerifier.BreakKind.BAD_HASH);
    }

    @Test
    void intactChainSpanningBatchesVerifiesIntact() {
        // 对照组：501 行跨两批全部自洽 → 翻页游标不得把行漏掉或重复导致误报
        List<AuditChainVerifier.ChainRow> batch1 = new ArrayList<>(BATCH_SIZE);
        String prev = AuditChainTable.GENESIS;
        for (int i = 1; i <= BATCH_SIZE; i++) {
            List<Object> values = v("第 " + i + " 条（示例）");
            String hash = seal(values, prev);
            batch1.add(sealed(i, values, prev, hash));
            prev = hash;
        }
        List<Object> tailValues = v("第 501 条（示例）");
        List<AuditChainVerifier.ChainRow> batch2 =
                List.of(sealed(BATCH_SIZE + 1L, tailValues, prev, seal(tailValues, prev)));
        stubPages(batch1, batch2);

        AuditChainVerifier.TableVerification result = verifier.verifyTable(probe);

        assertThat(result.intact()).as("跨批完整链通过：%s", result.breaks()).isTrue();
        assertThat(result.scannedRows()).isEqualTo(BATCH_SIZE + 1L);
        assertThat(result.hashedRows()).isEqualTo(BATCH_SIZE + 1L);
    }
}
