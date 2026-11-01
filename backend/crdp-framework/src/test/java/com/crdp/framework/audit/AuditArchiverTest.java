package com.crdp.framework.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.crypto.Sha256DigestService;
import com.crdp.framework.storage.AuditArchiveStorage;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E08-T03 / PLT-AUD-010：超过在线保留期（1 年）的月分区导出压缩 JSONL + 清单，单次 PUT 写入审计归档 WORM 桶（对象级保留 = 归档日 + 15
 * 年），读回校验通过后才登记并删除在线分区（DROP PARTITION，不触发行级只读触发器）。
 *
 * <p>验收口径：归档对象锁定 15 年；在线保留 1 年。删除是不可逆动作，顺序必须严格「上传 → 读回校验 → 登记 → 删分区」，任何一步失败都不删。
 */
class AuditArchiverTest {

    /** 固定逻辑日：在线保留 12 个月 → cutoff = 2026-06-15，p202601（上界 2026-02-01）到期。 */
    private static final LocalDate TODAY = LocalDate.of(2027, 6, 15);

    private static final AuditArchiver.ArchivePolicy POLICY =
            new AuditArchiver.ArchivePolicy(12, 15, 3);

    private final Sha256DigestService digest = new Sha256DigestService();

    private final JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    private final InMemoryStore store = new InMemoryStore();

    private AuditArchiver archiver;

    /** 归档探针表：与生产登记表同构（列名随意，导出走 SELECT * 全列）。 */
    private final AuditChainTable probe = new AuditChainTable("aud_probe", "event_type", "detail");

    @BeforeEach
    void setUp() {
        archiver = new AuditArchiver(jdbc, store, digest);
        // 默认：无既有分区登记
        when(jdbc.queryForObject(contains("aud_archive"), eq(Long.class), anyString(), anyString()))
                .thenReturn(0L);
    }

    // ---------------------------------------------------------------- 到期分区导出与上传

    @Test
    void expiredPartitionIsExportedUploadedWithWormRetentionThenDropped() {
        stubPartitions("p202601", "p202606", "pmax");
        stubRows(List.of(row(5L, "a1（示例）", "h4", "h5"), row(9L, "a2（示例）", "h5", "h9")));

        List<AuditArchiver.PartitionOutcome> outcomes =
                archiver.archiveExpired(probe, "audit/tenant/demoa", POLICY, TODAY);

        assertThat(outcomes).hasSize(1);
        AuditArchiver.PartitionOutcome outcome = outcomes.get(0);
        assertThat(outcome.partition()).isEqualTo("p202601");
        assertThat(outcome.status()).isEqualTo(AuditArchiver.OutcomeStatus.ARCHIVED);

        // 对象级保留 = 归档日 + 15 年（WORM 锁定）
        LocalDate expectedRetention = TODAY.plusYears(15);
        assertThat(store.retentions.get("audit/tenant/demoa/aud_probe/2026-01.jsonl.gz"))
                .isEqualTo(expectedRetention);
        assertThat(store.retentions.get("audit/tenant/demoa/aud_probe/2026-01.manifest.json"))
                .isEqualTo(expectedRetention);

        // 数据对象：解压后逐行 JSON，行数与列值保真
        byte[] gz = store.objects.get("audit/tenant/demoa/aud_probe/2026-01.jsonl.gz");
        assertThat(gz).isNotNull();
        String jsonl = AuditArchiver.gunzipToString(gz);
        String[] lines = jsonl.split("\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).contains("\"id\":5").contains("\"detail\":\"a1（示例）\"");

        // 清单：行数、首尾序号、首尾链值、JSONL 摘要
        String manifest =
                new String(
                        store.objects.get("audit/tenant/demoa/aud_probe/2026-01.manifest.json"),
                        StandardCharsets.UTF_8);
        assertThat(manifest)
                .contains("\"table\":\"aud_probe\"")
                .contains("\"partition\":\"p202601\"")
                .contains("\"rowCount\":2")
                .contains("\"firstId\":5")
                .contains("\"lastId\":9")
                .contains("\"firstPrevHash\":\"h4\"")
                .contains("\"lastHash\":\"h9\"")
                .contains("\"sha256\":\"" + digest.hashHex(jsonl) + "\"");

        // 未到期分区不动
        verify(jdbc, never()).update(contains("DROP PARTITION p202606"));
    }

    @Test
    void registryInsertComesBeforeDropPartition() {
        stubPartitions("p202601");
        stubRows(List.of(row(5L, "a（示例）", null, "h5")));

        archiver.archiveExpired(probe, "audit/platform", POLICY, TODAY);

        var order = Mockito.inOrder(jdbc);
        // 先登记（aud_archive），后删分区（DROP PARTITION）——中途崩溃可凭登记续跑
        order.verify(jdbc).update(contains("INSERT INTO aud_archive"), any(Object[].class));
        order.verify(jdbc).update(contains("DROP PARTITION p202601"));
    }

    // ---------------------------------------------------------------- 失败安全：任何一步失败都不删

    @Test
    void uploadFailureKeepsPartitionOnline() {
        stubPartitions("p202601");
        stubRows(List.of(row(5L, "a（示例）", null, "h5")));
        store.failPuts = true;

        List<AuditArchiver.PartitionOutcome> outcomes =
                archiver.archiveExpired(probe, "audit/platform", POLICY, TODAY);

        assertThat(outcomes.get(0).status()).isEqualTo(AuditArchiver.OutcomeStatus.FAILED);
        verify(jdbc, never()).update(contains("DROP PARTITION"));
        verify(jdbc, never()).update(contains("INSERT INTO aud_archive"), any(Object[].class));
    }

    @Test
    void readbackMismatchKeepsPartitionOnline() {
        stubPartitions("p202601");
        stubRows(List.of(row(5L, "a（示例）", null, "h5")));
        store.corruptOnRead = true;

        List<AuditArchiver.PartitionOutcome> outcomes =
                archiver.archiveExpired(probe, "audit/platform", POLICY, TODAY);

        assertThat(outcomes.get(0).status()).isEqualTo(AuditArchiver.OutcomeStatus.FAILED);
        verify(jdbc, never()).update(contains("DROP PARTITION"));
    }

    @Test
    void conflictingExistingObjectIsNotDropped() {
        stubPartitions("p202601");
        stubRows(List.of(row(5L, "a（示例）", null, "h5")));
        // 桶里已有同 key 但内容不同的对象（WORM 不可覆盖）→ 冲突，转人工，不删
        store.objects.put(
                "audit/platform/aud_probe/2026-01.jsonl.gz", AuditArchiver.gzip("旧内容（示例）"));

        List<AuditArchiver.PartitionOutcome> outcomes =
                archiver.archiveExpired(probe, "audit/platform", POLICY, TODAY);

        assertThat(outcomes.get(0).status()).isEqualTo(AuditArchiver.OutcomeStatus.CONFLICT);
        verify(jdbc, never()).update(contains("DROP PARTITION"));
    }

    // ---------------------------------------------------------------- 幂等与续跑

    @Test
    void crashBetweenUploadAndRegistryResumesVerifyAndDrop() {
        stubPartitions("p202601");
        stubRows(List.of(row(5L, "a（示例）", null, "h5")));
        // 第一次运行完整成功（上传 + 登记 + 删分区）
        archiver.archiveExpired(probe, "audit/platform", POLICY, TODAY);
        assertThat(store.putCount).isEqualTo(2);
        // 第二次运行模拟崩溃后重跑：登记已存在（重置登记计数桩为 1），分区仍在（分区桩未变）
        when(jdbc.queryForObject(contains("aud_archive"), eq(Long.class), anyString(), anyString()))
                .thenReturn(1L);

        List<AuditArchiver.PartitionOutcome> outcomes =
                archiver.archiveExpired(probe, "audit/platform", POLICY, TODAY);

        assertThat(outcomes.get(0).status()).isEqualTo(AuditArchiver.OutcomeStatus.RESUMED);
        // 续跑不再上传（WORM 对象不可覆盖也不必重传），校验通过后直接删分区
        assertThat(store.putCount).isEqualTo(2);
        verify(jdbc, Mockito.times(2)).update(contains("DROP PARTITION p202601"));
    }

    @Test
    void emptyExpiredPartitionIsDroppedWithoutUpload() {
        stubPartitions("p202601");
        stubRows(List.of());

        List<AuditArchiver.PartitionOutcome> outcomes =
                archiver.archiveExpired(probe, "audit/platform", POLICY, TODAY);

        assertThat(outcomes.get(0).status()).isEqualTo(AuditArchiver.OutcomeStatus.EMPTY);
        assertThat(store.putCount).isZero();
        verify(jdbc).update(contains("DROP PARTITION p202601"));
        // 登记行数 0、对象 key 为空（可追溯该分区确实无数据）
        verify(jdbc).update(contains("INSERT INTO aud_archive"), any(Object[].class));
    }

    // ---------------------------------------------------------------- 在线保留边界

    @Test
    void partitionWithinOnlineRetentionIsKept() {
        // 今天 2027-06-15、在线 12 个月 → cutoff 2026-06-15；
        // p202606 上界 2026-07-01 > cutoff（未到期）；p202605 上界 2026-06-01 ≤ cutoff（到期）
        stubPartitions("p202605", "p202606");
        stubRows(List.of(row(1L, "a（示例）", null, "h1")));

        List<AuditArchiver.PartitionOutcome> outcomes =
                archiver.archiveExpired(probe, "audit/platform", POLICY, TODAY);

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).partition()).isEqualTo("p202605");
        verify(jdbc, never()).update(contains("DROP PARTITION p202606"));
    }

    // ---------------------------------------------------------------- 分区维护（预建未来月份）

    @Test
    void missingFutureMonthsAreReorganizedFromPmax() {
        // 已有到 2026-09；今天 2027-06-15、预建 3 个月 → 需补 2026-10 … 2027-09
        stubPartitions("p202601", "p202608", "p202609", "pmax");

        archiver.ensurePartitions(probe, TODAY, POLICY);

        org.mockito.ArgumentCaptor<String> ddl = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(ddl.capture());
        assertThat(ddl.getValue())
                .contains("REORGANIZE PARTITION pmax INTO")
                // 逐月连续、以 pmax 收尾；任意缺月都不应跳过
                .contains("p202610")
                .contains("p202709")
                .contains("pmax VALUES LESS THAN (MAXVALUE)")
                .doesNotContain("p202609 VALUES");
    }

    @Test
    void noMissingMonthsMeansNoReorganize() {
        stubPartitions("p202601", "p202606", "p202607", "p202608", "p202609", "pmax");

        archiver.ensurePartitions(probe, LocalDate.of(2026, 6, 15), POLICY);

        verify(jdbc, never()).update(contains("REORGANIZE"));
    }

    @Test
    void unknownPartitionNamesAreIgnored() {
        // 非本约定命名（pYYYYMM）的分区不参与到期判断，也不被删除
        stubPartitions("legacy_a", "p202601");

        List<AuditArchiver.PartitionOutcome> outcomes =
                archiver.archiveExpired(probe, "audit/platform", POLICY, TODAY);

        assertThat(outcomes)
                .extracting(AuditArchiver.PartitionOutcome::partition)
                .containsExactly("p202601");
    }

    // ---------------------------------------------------------------- 存储未配置：只维护分区，不归档

    @Test
    void disabledStoreMeansNoUploadAndNoDrop() {
        stubPartitions("p202601");
        stubRows(List.of(row(5L, "a（示例）", null, "h5")));
        AuditArchiver disabled = new AuditArchiver(jdbc, new DisabledStore(), digest);

        List<AuditArchiver.PartitionOutcome> outcomes =
                disabled.archiveExpired(probe, "audit/platform", POLICY, TODAY);

        assertThat(outcomes).isEmpty();
        verify(jdbc, never()).update(contains("DROP PARTITION"));
    }

    // ---------------------------------------------------------------- 工具与测试替身

    private void stubPartitions(String... names) {
        when(jdbc.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(new ArrayList<>(List.of(names)));
    }

    @SuppressWarnings("unchecked")
    private void stubRows(List<Map<String, Object>> rows) {
        // 导出走物理分区选取（SELECT * FROM t PARTITION (pYYYYMM) ORDER BY id），无参单语句
        when(jdbc.queryForList(anyString())).thenReturn((List<Map<String, Object>>) (List<?>) rows);
    }

    private static Map<String, Object> row(long id, String detail, String prevHash, String hash) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("event_type", "PROBE");
        row.put("detail", detail);
        row.put("prev_hash", prevHash);
        row.put("hash", hash);
        return row;
    }

    /** 内存版审计归档桶：记录每次 PUT 的保留期；可注入故障与读回损坏。 */
    static final class InMemoryStore implements AuditArchiveStorage {
        final Map<String, byte[]> objects = new java.util.HashMap<>();
        final Map<String, LocalDate> retentions = new java.util.HashMap<>();
        int putCount;
        boolean failPuts;
        boolean corruptOnRead;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void putWithRetention(
                String key, byte[] body, String contentType, LocalDate retainUntil) {
            if (failPuts) {
                throw new DataAccessResourceFailureException("归档桶暂不可用（示例）");
            }
            putCount++;
            objects.put(key, body);
            retentions.put(key, retainUntil);
        }

        @Override
        public byte[] get(String key) {
            byte[] body = objects.get(key);
            if (body == null) {
                return null;
            }
            return corruptOnRead ? new byte[] {1, 2, 3} : body;
        }
    }

    static final class DisabledStore implements AuditArchiveStorage {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void putWithRetention(
                String key, byte[] body, String contentType, LocalDate retainUntil) {
            throw new UnsupportedOperationException("审计归档桶未配置");
        }

        @Override
        public byte[] get(String key) {
            throw new UnsupportedOperationException("审计归档桶未配置");
        }
    }
}
