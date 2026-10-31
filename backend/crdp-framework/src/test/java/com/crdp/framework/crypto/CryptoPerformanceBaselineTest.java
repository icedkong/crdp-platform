package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.framework.tenant.TenantContext;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E03-T06 性能基线：单条敏感字段加解密 / 盲索引的耗时。
 *
 * <p>这不是精确基准测试，做两件事：
 *
 * <ol>
 *   <li><b>记录基线</b>：对三个代表值（姓名、电话、病案号，均为虚构示例数据）各测 {@value #WARMUP} 次预热 + {@value #MEASURED} 次计量，把
 *       mean / p50 / p99 / max 打印进 surefire 输出，作为后续优化与回退比较的参照（首次基线数字见下方与 PR 描述）。
 *   <li><b>防回退</b>：阈值按<b>最慢的运行环境</b>（CI 共享 runner）留约 5 倍余量，只有明显的算法级回退（例如误把字段加密换成每次派生密钥的
 *       口令哈希、每条记录重复加解密多次）才会失败；它不是精确的性能回归检测器。
 * </ol>
 *
 * <p>度量范围是<b>密码算法路径</b>：AES-256-GCM 加解密、HMAC-SHA256 盲索引、密文格式的编解码与密钥的内存缓存 命中。当前实现每次 encrypt
 * 还会查一次租户库的当前密钥版本（SELECT MAX(key_version)，见 {@link
 * TenantDataKeyService#currentVersion}），本测试用桩代替了这次库访问；线上单条耗时还要加上同机房 MySQL
 * 的一次往返，那部分不属于算法基线。密钥明文只在内存缓存中，与生产语义一致（ADR 0004）。
 *
 * <p>首次基线（5,000 次/值）：
 *
 * <ul>
 *   <li>本地（2026-11-01，macOS arm64，Homebrew OpenJDK 21.0.13）：encrypt mean 6–22µs、p99 7–80µs；decrypt
 *       mean 3–6µs、p99 2.5–22µs；blindIndex mean 6–17µs、p99 28–58µs。
 *   <li>CI（2026-11-01，GitHub Actions ubuntu-latest，Temurin 21）：encrypt mean 观测到 187µs——共享 x86_64
 *       runner 比本地 arm64 慢约一个数量级，因此<b>阈值必须按 CI 硬件定</b>：mean ≤ {@value #MEAN_LIMIT_MICROS}µs（约 5
 *       倍余量）、p99 ≤ {@value #P99_LIMIT_MICROS}µs。
 * </ul>
 *
 * <p>单次 max 偶发到毫秒级（GC / JIT / 线程调度抖动），因此不作为阈值；p99 与 mean 才是稳定信号。计量前先对全部 值×操作的组合统一预热（{@link
 * #warmUpAllOperations()}），避免 JIT 分层编译成本落在第一个被计量的值上。
 */
class CryptoPerformanceBaselineTest {

    private static final int WARMUP = 2_000;
    private static final int MEASURED = 5_000;

    /**
     * 阈值：单条加密 / 解密平均耗时上限（微秒）。按最慢运行环境（CI 共享 runner，encrypt mean 实测 187µs）的约 5
     * 倍取整：只拦截明显回退（每次派生密钥的口令哈希 ≥ 毫秒级、重复加解密），CI 硬件波动不误报。
     */
    private static final long MEAN_LIMIT_MICROS = 1_000;

    /** 阈值：单条操作 p99 耗时上限（微秒），为 mean 上限的 5 倍。 */
    private static final long P99_LIMIT_MICROS = 5_000;

    private static final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private static final EnvelopeCryptoService crypto =
            new EnvelopeCryptoService(
                    new TenantDataKeyService(
                            new LocalEnvelopeKeyProvider("dev-only-not-a-production-key"),
                            jdbcTemplate));

    /** 代表值：姓名、电话、病案号（全部为虚构示例数据，不含真实患者信息）。 */
    private static final String[] REPRESENTATIVE_VALUES = {"张三（示例）", "13800138000", "MR-DEMO-0001"};

    @BeforeAll
    static void enterTenantWithDataKey() {
        TenantContext.useTenant(1L, "perf");
        // 版本查询与首次取键走桩：密钥解开一次后进内存缓存，度量的是算法路径
        LocalEnvelopeKeyProvider provider =
                new LocalEnvelopeKeyProvider("dev-only-not-a-production-key");
        String wrappedFieldKey = provider.generateDataKey().wrappedKey();
        String wrappedBlindKey = provider.generateDataKey().wrappedKey();
        when(jdbcTemplate.queryForObject(
                        contains("MAX(key_version)"), eq(Integer.class), eq("field")))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(
                        contains("MAX(key_version)"), eq(Integer.class), eq("blind")))
                .thenReturn(1);
        when(jdbcTemplate.queryForList(
                        contains("SELECT wrapped_key"), eq(String.class), eq("field"), eq(1)))
                .thenAnswer(invocation -> List.of(wrappedFieldKey));
        when(jdbcTemplate.queryForList(
                        contains("SELECT wrapped_key"), eq(String.class), eq("blind"), eq(1)))
                .thenAnswer(invocation -> List.of(wrappedBlindKey));
    }

    @AfterAll
    static void leaveTenant() {
        TenantContext.clear();
    }

    @Test
    void singleRecordEncryptDecryptAndBlindIndexStayWithinTheBaseline() {
        warmUpAllOperations();
        for (String value : REPRESENTATIVE_VALUES) {
            Stats encrypt = encryptStats(value);
            Stats blindIndex = blindIndexStats(value);
            Stats decrypt = decryptStats(value);

            System.out.printf(
                    "[E03-T06 性能基线] 值=%-12s encrypt   mean=%7.1fµs p50=%7.1fµs p99=%7.1fµs max=%7.1fµs（%d 次）%n",
                    value, encrypt.mean, encrypt.p50, encrypt.p99, encrypt.max, MEASURED);
            System.out.printf(
                    "[E03-T06 性能基线] 值=%-12s decrypt   mean=%7.1fµs p50=%7.1fµs p99=%7.1fµs max=%7.1fµs（%d 次）%n",
                    value, decrypt.mean, decrypt.p50, decrypt.p99, decrypt.max, MEASURED);
            System.out.printf(
                    "[E03-T06 性能基线] 值=%-12s blindIndex mean=%7.1fµs p50=%7.1fµs p99=%7.1fµs max=%7.1fµs（%d 次）%n",
                    value,
                    blindIndex.mean,
                    blindIndex.p50,
                    blindIndex.p99,
                    blindIndex.max,
                    MEASURED);

            assertTrue(
                    encrypt.mean <= MEAN_LIMIT_MICROS,
                    "单条加密平均耗时 " + encrypt.mean + "µs 超过基线上限 " + MEAN_LIMIT_MICROS + "µs");
            assertTrue(
                    encrypt.p99 <= P99_LIMIT_MICROS,
                    "单条加密 p99 耗时 " + encrypt.p99 + "µs 超过基线上限 " + P99_LIMIT_MICROS + "µs");
            assertTrue(
                    decrypt.mean <= MEAN_LIMIT_MICROS,
                    "单条解密平均耗时 " + decrypt.mean + "µs 超过基线上限 " + MEAN_LIMIT_MICROS + "µs");
            assertTrue(
                    decrypt.p99 <= P99_LIMIT_MICROS,
                    "单条解密 p99 耗时 " + decrypt.p99 + "µs 超过基线上限 " + P99_LIMIT_MICROS + "µs");
            assertTrue(
                    blindIndex.mean <= MEAN_LIMIT_MICROS,
                    "单条盲索引平均耗时 " + blindIndex.mean + "µs 超过基线上限 " + MEAN_LIMIT_MICROS + "µs");
        }
    }

    /** 计时的同时校验语义，避免度量了空转。 */
    @Test
    void measuredOperationsAreRealWork() {
        for (String value : REPRESENTATIVE_VALUES) {
            String ciphertext = crypto.encrypt("field", value);
            assertEquals(value, crypto.decrypt(ciphertext));
            assertEquals(crypto.blindIndex("blind", value), crypto.blindIndex("blind", value));
        }
    }

    /**
     * 计量前的统一暖机：对全部 值 × 操作 的组合各跑 {@value #WARMUP} 轮，让 JIT 分层编译在任何一个组合被计量之前完成， 避免第一个被计量的值独自吸收编译成本（CI
     * 上正是它把 encrypt mean 推到 187µs）。
     */
    private static void warmUpAllOperations() {
        for (int i = 0; i < WARMUP; i++) {
            for (String value : REPRESENTATIVE_VALUES) {
                String ciphertext = crypto.encrypt("field", value);
                crypto.decrypt(ciphertext);
                crypto.blindIndex("blind", value);
            }
        }
    }

    private Stats encryptStats(String value) {
        for (int i = 0; i < WARMUP; i++) {
            crypto.encrypt("field", value);
        }
        return measure(() -> crypto.encrypt("field", value));
    }

    private Stats decryptStats(String value) {
        String[] ciphertexts = new String[MEASURED];
        for (int i = 0; i < MEASURED; i++) {
            ciphertexts[i] = crypto.encrypt("field", value);
        }
        for (int i = 0; i < WARMUP; i++) {
            crypto.decrypt(ciphertexts[i % MEASURED]);
        }
        // 逐条解密预先加密好的密文，避免把加密时间混进解密基线
        int[] cursor = {0};
        Stats stats = measureNoReturn(() -> crypto.decrypt(ciphertexts[cursor[0]++ % MEASURED]));
        assertEquals(value, crypto.decrypt(ciphertexts[0]), "计时的解密必须是真实解密（抽样校验解回原文）");
        return stats;
    }

    private Stats blindIndexStats(String value) {
        for (int i = 0; i < WARMUP; i++) {
            crypto.blindIndex("blind", value);
        }
        return measure(() -> crypto.blindIndex("blind", value));
    }

    private Stats measure(java.util.function.Supplier<String> operation) {
        long[] nanos = new long[MEASURED];
        for (int i = 0; i < MEASURED; i++) {
            long start = System.nanoTime();
            operation.get();
            nanos[i] = System.nanoTime() - start;
        }
        return Stats.of(nanos);
    }

    private Stats measureNoReturn(Runnable operation) {
        long[] nanos = new long[MEASURED];
        for (int i = 0; i < MEASURED; i++) {
            long start = System.nanoTime();
            operation.run();
            nanos[i] = System.nanoTime() - start;
        }
        return Stats.of(nanos);
    }

    /** 单微秒精度的分位数统计。 */
    private record Stats(double mean, double p50, double p99, double max) {

        static Stats of(long[] nanos) {
            long[] sorted = nanos.clone();
            Arrays.sort(sorted);
            double total = 0;
            for (long nano : nanos) {
                total += nano;
            }
            return new Stats(
                    total / nanos.length / 1_000.0,
                    sorted[(int) (nanos.length * 0.50)] / 1_000.0,
                    sorted[Math.min(nanos.length - 1, (int) (nanos.length * 0.99))] / 1_000.0,
                    sorted[nanos.length - 1] / 1_000.0);
        }
    }
}
