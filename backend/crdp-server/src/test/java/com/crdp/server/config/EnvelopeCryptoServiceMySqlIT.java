package com.crdp.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.crypto.EnvelopeCryptoService;
import com.crdp.framework.crypto.LocalEnvelopeKeyProvider;
import com.crdp.framework.crypto.TenantDataKeyService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * E03-T02 对真 MySQL 的端到端验证：数据密钥真实落库（仅包装形态）、按租户独立、密文可往返。 需要本地依赖（docker compose 的
 * MySQL）时运行：CRDP_IT_MYSQL_PORT=13306；自 E02-T09 起 CI 注入该端口。 表结构来自租户库迁移 V4；本测试自清理，不留数据。
 */
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class EnvelopeCryptoServiceMySqlIT {

    private static final String PURPOSE = "it-field";

    private final JdbcTemplate demoa = jdbc("t_demoa");
    private final JdbcTemplate demob = jdbc("t_demob");

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        demoa.update("DELETE FROM crypto_data_key WHERE purpose = ?", PURPOSE);
        demob.update("DELETE FROM crypto_data_key WHERE purpose = ?", PURPOSE);
    }

    @Test
    void storesOnlyWrappedKeysPerTenantAndRoundTrips() {
        demoa.update("DELETE FROM crypto_data_key WHERE purpose = ?", PURPOSE);
        EnvelopeCryptoService cryptoOfA =
                new EnvelopeCryptoService(
                        new TenantDataKeyService(
                                new LocalEnvelopeKeyProvider("dev-only-not-a-production-key"),
                                demoa));

        TenantContext.useTenant(101L, "demoa-it");
        String ciphertext = cryptoOfA.encrypt(PURPOSE, "虚构姓名-甲");

        String wrapped =
                demoa.queryForObject(
                        "SELECT wrapped_key FROM crypto_data_key WHERE purpose = ? AND key_version = 1",
                        String.class,
                        PURPOSE);
        assertTrue(
                wrapped.startsWith("v1.AES-256-GCM.1.keywrap."), "库里只能有包装后的密钥（信封外层），实际：" + wrapped);
        assertEquals("虚构姓名-甲", cryptoOfA.decrypt(ciphertext));

        cryptoOfA.encrypt(PURPOSE, "第二次");
        Integer versions =
                demoa.queryForObject(
                        "SELECT COUNT(*) FROM crypto_data_key WHERE purpose = ?",
                        Integer.class,
                        PURPOSE);
        assertEquals(1, versions, "同一用途不会重复建版本");
    }

    @Test
    void eachTenantHasItsOwnKeyAndCannotDecryptTheOther() {
        demoa.update("DELETE FROM crypto_data_key WHERE purpose = ?", PURPOSE);
        demob.update("DELETE FROM crypto_data_key WHERE purpose = ?", PURPOSE);
        EnvelopeCryptoService cryptoOfA =
                new EnvelopeCryptoService(
                        new TenantDataKeyService(
                                new LocalEnvelopeKeyProvider("dev-only-not-a-production-key"),
                                demoa));
        EnvelopeCryptoService cryptoOfB =
                new EnvelopeCryptoService(
                        new TenantDataKeyService(
                                new LocalEnvelopeKeyProvider("dev-only-not-a-production-key"),
                                demob));

        TenantContext.useTenant(101L, "demoa-it");
        String ciphertextOfA = cryptoOfA.encrypt(PURPOSE, "甲院数据");
        TenantContext.useTenant(102L, "demob-it");
        String ciphertextOfB = cryptoOfB.encrypt(PURPOSE, "甲院数据");

        assertTrue(!ciphertextOfA.equals(ciphertextOfB), "两院密钥独立，相同明文密文不同");
        assertEquals("甲院数据", cryptoOfB.decrypt(ciphertextOfB));
        ApiException ex = assertThrows(ApiException.class, () -> cryptoOfB.decrypt(ciphertextOfA));
        assertEquals(40002, ex.code(), "乙院解不开甲院的密文");
    }

    private static JdbcTemplate jdbc(String database) {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        String url =
                "jdbc:mysql://127.0.0.1:"
                        + port
                        + "/"
                        + database
                        + "?useSSL=false&allowPublicKeyRetrieval=true";
        return new JdbcTemplate(new DriverManagerDataSource(url, "crdp_app", "DevOnly#app2026"));
    }
}
