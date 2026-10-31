package com.crdp.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.Ciphertext;
import com.crdp.framework.crypto.CryptoReencryptionJob;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.crypto.EncryptedColumn;
import com.crdp.framework.crypto.EnvelopeCryptoService;
import com.crdp.framework.crypto.KeyRotationService;
import com.crdp.framework.crypto.LocalEnvelopeKeyProvider;
import com.crdp.framework.crypto.TenantDataKeyService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * E03-T05 对真 MySQL 的端到端验收：轮换后旧密文可读（旧版本密钥保留）、新数据用新版本；重加密作业迁移存量密文与盲索引、 中断后可断点续跑且不重复已完成行。需要本地依赖（docker
 * compose 的 MySQL）时运行：CRDP_IT_MYSQL_PORT=13306； CI 无数据库自动跳过。表结构来自租户库迁移 V4 / V5；本测试自清理，不留数据，明文全部虚构。
 */
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class CryptoRotationMySqlIT {

    private static final String FIELD_PURPOSE = "it-e03t05-field";
    private static final String INDEX_PURPOSE = "it-e03t05-idx";
    private static final String TABLE = "it_patient_e03t05";
    private static final List<String> PLAINTEXTS =
            List.of("张医生（示例）", "李医生（示例）", "王医生（示例）", "赵医生（示例）", "陈医生（示例）");

    private final JdbcTemplate jdbc = jdbc();
    private final LocalEnvelopeKeyProvider provider =
            new LocalEnvelopeKeyProvider("dev-only-not-a-production-key");
    private final TenantDataKeyService dataKeys = new TenantDataKeyService(provider, jdbc);
    private final CountingCryptoService crypto =
            new CountingCryptoService(new EnvelopeCryptoService(dataKeys));
    private final KeyRotationService rotation =
            new KeyRotationService(dataKeys, mock(AuditLogger.class));
    private final EncryptedColumn column =
            new EncryptedColumn(
                    TABLE, "id", "name_cipher", FIELD_PURPOSE, "name_bi", INDEX_PURPOSE);

    @BeforeAll
    static void createTables() {
        JdbcTemplate jdbc = jdbc();
        // 直接执行迁移文件内容（改成 IF NOT EXISTS），保证测试表结构与迁移一致
        jdbc.execute(migration("V4__crypto_data_key.sql"));
        jdbc.execute(migration("V5__crypto_reencrypt_progress.sql"));
    }

    @BeforeEach
    void setUp() {
        TenantContext.useTenant(101L, "demoa-it");
        jdbc.update("DROP TABLE IF EXISTS " + TABLE);
        jdbc.execute(
                "CREATE TABLE "
                        + TABLE
                        + " (id BIGINT NOT NULL AUTO_INCREMENT, name_cipher VARCHAR(512) NOT NULL,"
                        + " name_bi VARCHAR(128) NOT NULL, PRIMARY KEY (id)) ENGINE=InnoDB");
        cleanKeys();
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        jdbc.update("DROP TABLE IF EXISTS " + TABLE);
        jdbc.update("DELETE FROM crypto_reencrypt_progress WHERE table_name = ?", TABLE);
        cleanKeys();
    }

    @Test
    void rotatedKeyStillDecryptsOldCiphertextAndNewWritesUseNewVersion() {
        String beforeRotation = crypto.encrypt(FIELD_PURPOSE, "张医生（示例）");

        int newVersion = rotation.rotate(FIELD_PURPOSE);

        assertEquals(2, newVersion);
        assertEquals("张医生（示例）", crypto.decrypt(beforeRotation), "轮换后旧密文仍可读");
        assertTrue(
                crypto.encrypt(FIELD_PURPOSE, "李医生（示例）").startsWith("v1.AES-256-GCM.2."),
                "新写入应使用新版本");
        Integer versions =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crypto_data_key WHERE purpose = ?",
                        Integer.class,
                        FIELD_PURPOSE);
        assertEquals(2, versions, "新旧两个版本都保留在库里");
    }

    @Test
    void reencryptionMigratesAllRowsAndRecomputesBlindIndex() {
        List<Long> ids = insertRows();
        String firstRowV1Cipher = cipherOf(ids.get(0));
        assertEquals(2, rotation.rotate(FIELD_PURPOSE));

        job(2).run(null);

        for (int i = 0; i < ids.size(); i++) {
            String stored = cipherOf(ids.get(i));
            assertEquals(2, Ciphertext.parse(stored).keyVersion(), "第 " + i + " 行应迁移到版本 2");
            assertEquals(PLAINTEXTS.get(i), crypto.decrypt(stored));
            assertEquals(
                    crypto.blindIndex(INDEX_PURPOSE, PLAINTEXTS.get(i)),
                    biOf(ids.get(i)),
                    "重写密文时应同步重算盲索引");
        }
        assertEquals("DONE", progressStatus());
        assertEquals("张医生（示例）", crypto.decrypt(firstRowV1Cipher), "旧版本密钥保留，旧密文仍可读");
    }

    @Test
    void interruptedPassResumesFromCheckpointWithoutRepeatingRows() {
        List<Long> ids = insertRows();
        List<String> v1Ciphers = ids.stream().map(this::cipherOf).toList();
        rotation.rotate(FIELD_PURPOSE);
        // 第 3 行损坏：第一趟迁移完前两行后在这里失败
        jdbc.update("UPDATE " + TABLE + " SET name_cipher = 'broken' WHERE id = ?", ids.get(2));
        crypto.resetCount();
        assertThrows(ApiException.class, () -> job(2).run(null));

        assertEquals("RUNNING", progressStatus());
        assertEquals(ids.get(1), progressLastId(), "游标应停在最后一个成功批次");
        assertEquals(2, crypto.encryptions(), "第一趟只重加密了前两行");

        // 人工修复损坏行（恢复为原来的版本 1 密文），重跑从断点继续
        jdbc.update(
                "UPDATE " + TABLE + " SET name_cipher = ? WHERE id = ?",
                v1Ciphers.get(2),
                ids.get(2));
        job(2).run(null);

        for (int i = 0; i < ids.size(); i++) {
            assertEquals(2, Ciphertext.parse(cipherOf(ids.get(i))).keyVersion());
            assertEquals(PLAINTEXTS.get(i), crypto.decrypt(cipherOf(ids.get(i))));
        }
        assertEquals(5, crypto.encryptions(), "续跑只补做剩余三行，不重复前两行");
        assertEquals("DONE", progressStatus());
    }

    @Test
    void blindIndexOnlyRotationRefreshesAllIndexes() {
        List<Long> ids = insertRows();
        String biBefore = biOf(ids.get(0));
        rotation.rotate(INDEX_PURPOSE);

        job(2).run(null);

        for (int i = 0; i < ids.size(); i++) {
            assertEquals(
                    crypto.blindIndex(INDEX_PURPOSE, PLAINTEXTS.get(i)),
                    biOf(ids.get(i)),
                    "盲索引密钥轮换后应全量重算");
            assertEquals(PLAINTEXTS.get(i), crypto.decrypt(cipherOf(ids.get(i))), "明文不变");
        }
        assertNotEquals(biBefore, biOf(ids.get(0)), "盲索引值应随密钥版本变化");
    }

    @Test
    void secondRotationStartsAFreshPass() {
        List<Long> ids = insertRows();
        rotation.rotate(FIELD_PURPOSE);
        job(2).run(null);
        assertEquals("DONE", progressStatus());

        rotation.rotate(FIELD_PURPOSE);
        job(2).run(null);

        for (Long id : ids) {
            assertEquals(3, Ciphertext.parse(cipherOf(id)).keyVersion(), "再次轮换后应重新迁移到版本 3");
        }
    }

    private CryptoReencryptionJob job(int batchSize) {
        return new CryptoReencryptionJob(crypto, dataKeys, jdbc, List.of(column), batchSize);
    }

    private List<Long> insertRows() {
        for (String plaintext : PLAINTEXTS) {
            jdbc.update(
                    "INSERT INTO " + TABLE + " (name_cipher, name_bi) VALUES (?, ?)",
                    crypto.encrypt(FIELD_PURPOSE, plaintext),
                    crypto.blindIndex(INDEX_PURPOSE, plaintext));
        }
        // 不用 LAST_INSERT_ID：JdbcTemplate 每条语句可能拿不同连接，按插入顺序读回自增主键
        return jdbc.queryForList("SELECT id FROM " + TABLE + " ORDER BY id", Long.class);
    }

    private String cipherOf(long id) {
        return jdbc.queryForObject(
                "SELECT name_cipher FROM " + TABLE + " WHERE id = ?", String.class, id);
    }

    private String biOf(long id) {
        return jdbc.queryForObject(
                "SELECT name_bi FROM " + TABLE + " WHERE id = ?", String.class, id);
    }

    private String progressStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM crypto_reencrypt_progress WHERE table_name = ?",
                String.class,
                TABLE);
    }

    private Long progressLastId() {
        return jdbc.queryForObject(
                "SELECT last_id FROM crypto_reencrypt_progress WHERE table_name = ?",
                Long.class,
                TABLE);
    }

    private void cleanKeys() {
        jdbc.update(
                "DELETE FROM crypto_data_key WHERE purpose IN (?, ?)",
                FIELD_PURPOSE,
                INDEX_PURPOSE);
    }

    private static String migration(String fileName) {
        try {
            String ddl = Files.readString(Path.of("src/main/resources/db/tenant/base/" + fileName));
            return ddl.replaceFirst("CREATE TABLE", "CREATE TABLE IF NOT EXISTS");
        } catch (IOException ex) {
            throw new IllegalStateException("读不到迁移文件 " + fileName, ex);
        }
    }

    private static JdbcTemplate jdbc() {
        String url =
                "jdbc:mysql://127.0.0.1:"
                        + System.getenv("CRDP_IT_MYSQL_PORT")
                        + "/t_demoa?useSSL=false&allowPublicKeyRetrieval=true";
        return new JdbcTemplate(new DriverManagerDataSource(url, "crdp_app", "DevOnly#app2026"));
    }

    /** 统计重加密次数，验证断点续跑不会重复已完成行。 */
    private static final class CountingCryptoService implements CryptoService {

        private final CryptoService delegate;
        private final AtomicInteger encryptCount = new AtomicInteger();

        CountingCryptoService(CryptoService delegate) {
            this.delegate = delegate;
        }

        int encryptions() {
            return encryptCount.get();
        }

        void resetCount() {
            encryptCount.set(0);
        }

        @Override
        public String encrypt(String purpose, String plaintext) {
            encryptCount.incrementAndGet();
            return delegate.encrypt(purpose, plaintext);
        }

        @Override
        public String decrypt(String ciphertext) {
            return delegate.decrypt(ciphertext);
        }

        @Override
        public String blindIndex(String purpose, String value) {
            return delegate.blindIndex(purpose, value);
        }
    }
}
