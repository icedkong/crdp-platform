package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/** E03-T06：密码服务按剖面组装——local 主密钥、kms 占位、未知模式拒绝；重加密作业聚合各模块登记的加密列。 */
class CryptoConfigurationTest {

    private final CryptoConfiguration configuration = new CryptoConfiguration();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);

    @AfterEach
    void reset() {
        CryptoTypeHandler.configure(null);
    }

    @Test
    void localModeWiresTheInProcessMasterKey() {
        CryptoProperties properties = new CryptoProperties();
        properties.setMode("local");
        properties.setLocalKey("dev-only-not-a-production-key");

        KeyProvider keyProvider = configuration.keyProvider(properties);

        assertInstanceOf(LocalEnvelopeKeyProvider.class, keyProvider);
        assertInstanceOf(
                EnvelopeCryptoService.class,
                configuration.cryptoService(
                        configuration.tenantDataKeyService(keyProvider, jdbcTemplate)));
    }

    @Test
    void kmsModeWiresThePlaceholderUntilTheCloudAccountIsReady() {
        CryptoProperties properties = new CryptoProperties();
        properties.setMode("kms");

        KeyProvider keyProvider = configuration.keyProvider(properties);

        assertInstanceOf(KmsKeyProvider.class, keyProvider);
        assertThrows(
                ApiException.class, keyProvider::generateDataKey, "kms 占位实现调用即失败，绝不悄悄退回 local");
    }

    @Test
    void unknownModeIsRejected() {
        CryptoProperties properties = new CryptoProperties();
        properties.setMode("hsm");

        assertThrows(IllegalStateException.class, () -> configuration.keyProvider(properties));
    }

    @Test
    void cryptoServiceBeanInjectsItselfIntoTheTypeHandler() {
        CryptoService service =
                configuration.cryptoService(
                        configuration.tenantDataKeyService(keyProvider(), jdbcTemplate));

        assertEquals(EnvelopeCryptoService.class, service.getClass());
        // 静态注入后 TypeHandler 立即可用：未注入时这里是 IllegalStateException，注入后走密文解析报 40002
        ApiException ex =
                assertThrows(ApiException.class, () -> decryptThroughHandler("not-a-ciphertext"));
        assertEquals(40002, ex.code());
    }

    @Test
    void reencryptionJobCollectsColumnsFromEveryRegisteredProvider() {
        EncryptedColumnProvider first =
                () ->
                        List.of(
                                new EncryptedColumn(
                                        "it_patient",
                                        "id",
                                        "name_cipher",
                                        "field-name",
                                        null,
                                        null));
        EncryptedColumnProvider second =
                () ->
                        List.of(
                                new EncryptedColumn(
                                        "it_contact",
                                        "id",
                                        "phone_cipher",
                                        "field-phone",
                                        "phone_bi",
                                        "idx-phone"),
                                new EncryptedColumn(
                                        "it_patient", "id", "mrn_cipher", "field-mrn", null, null));

        CryptoReencryptionJob job =
                configuration.cryptoReencryptionJob(
                        mock(CryptoService.class),
                        mock(TenantDataKeyService.class),
                        jdbcTemplate,
                        new CryptoProperties(),
                        providersOf(first, second));

        List<CryptoReencryptionJob.ColumnResult> results = job.migrateAll();

        assertEquals(
                List.of("it_patient", "it_contact", "it_patient"),
                results.stream().map(CryptoReencryptionJob.ColumnResult::tableName).toList(),
                "各模块登记的加密列按顺序全部进入巡检清单");
    }

    @Test
    void rotationServiceIsAssembledFromDataKeysAndAudit() {
        KeyRotationService service =
                configuration.keyRotationService(mock(TenantDataKeyService.class), auditLogger);

        assertEquals(KeyRotationService.class, service.getClass());
    }

    private LocalEnvelopeKeyProvider keyProvider() {
        return new LocalEnvelopeKeyProvider("dev-only-not-a-production-key");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<EncryptedColumnProvider> providersOf(
            EncryptedColumnProvider... providers) {
        ObjectProvider<EncryptedColumnProvider> provider = mock(ObjectProvider.class);
        when(provider.stream()).thenReturn(Stream.of(providers));
        return provider;
    }

    private static String decryptThroughHandler(String stored) throws Exception {
        CryptoTypeHandler handler =
                new CryptoTypeHandler() {
                    @Override
                    protected String purpose() {
                        return "field-name";
                    }
                };
        java.sql.ResultSet resultSet = mock(java.sql.ResultSet.class);
        when(resultSet.getString("name_cipher")).thenReturn(stored);
        return handler.getNullableResult(resultSet, "name_cipher");
    }
}
