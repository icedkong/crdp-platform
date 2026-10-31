package com.crdp.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.crdp.framework.crypto.EnvelopeCryptoService;
import com.crdp.framework.crypto.LocalEnvelopeKeyProvider;
import com.crdp.framework.crypto.TenantDataKeyService;
import com.crdp.framework.tenant.TenantContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * E03-T04 对真 MySQL 的端到端验证（验收原文：按病案号、电话精确查找命中）。同一值的不同写法
 * 产生同一盲索引，等值查询全部命中；不同值不误命中。CRDP_IT_MYSQL_PORT=13306 时运行；自 E02-T09 起随 CI 的 MySQL 运行。
 */
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class BlindIndexMySqlIT {

    private static final String PURPOSE = "it-blind";

    private static JdbcTemplate demoa;
    private static EnvelopeCryptoService crypto;

    @BeforeAll
    static void setUp() {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        demoa =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/t_demoa?useSSL=false&allowPublicKeyRetrieval=true",
                                "crdp_app",
                                "DevOnly#app2026"));
        demoa.execute(
                "CREATE TABLE IF NOT EXISTS it_blind_demo ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + "mr_no VARCHAR(512) NOT NULL,"
                        + "mr_no_index VARCHAR(64) NOT NULL,"
                        + "phone_index VARCHAR(64) NOT NULL) ENGINE=InnoDB");
        crypto =
                new EnvelopeCryptoService(
                        new TenantDataKeyService(
                                new LocalEnvelopeKeyProvider("dev-only-not-a-production-key"),
                                demoa));
    }

    @AfterAll
    static void tearDown() {
        if (demoa != null) {
            demoa.execute("DROP TABLE IF EXISTS it_blind_demo");
            demoa.update("DELETE FROM crypto_data_key WHERE purpose = ?", PURPOSE);
        }
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void exactSearchHitsTheSameValueWrittenDifferently() {
        TenantContext.useTenant(301L, "demoa-it");
        demoa.update("DELETE FROM it_blind_demo");
        insert("MR-DEMO-001", "+8613800138000");
        insert("mr demo 001", "138 0013 8000");
        insert("ＭＲ－ＤＥＭＯ－００１", "138-0013-8000");

        Integer hitsByMrNo =
                demoa.queryForObject(
                        "SELECT COUNT(*) FROM it_blind_demo WHERE mr_no_index = ?",
                        Integer.class,
                        crypto.blindIndex(PURPOSE, " Mr-Demo-001 "));
        assertEquals(3, hitsByMrNo, "三种写法的病案号应被同一查询全部命中");

        Integer hitsByPhone =
                demoa.queryForObject(
                        "SELECT COUNT(*) FROM it_blind_demo WHERE phone_index = ?",
                        Integer.class,
                        crypto.blindIndex(PURPOSE, "13800138000"));
        assertEquals(3, hitsByPhone, "三种写法的电话应被同一查询全部命中");

        Integer misses =
                demoa.queryForObject(
                        "SELECT COUNT(*) FROM it_blind_demo WHERE phone_index = ?",
                        Integer.class,
                        crypto.blindIndex(PURPOSE, "13900139000"));
        assertEquals(0, misses, "不同值不得误命中");
    }

    private void insert(String mrNo, String phone) {
        demoa.update(
                "INSERT INTO it_blind_demo (mr_no, mr_no_index, phone_index) VALUES (?, ?, ?)",
                mrNo,
                crypto.blindIndex(PURPOSE, mrNo),
                crypto.blindIndex(PURPOSE, phone));
    }
}
