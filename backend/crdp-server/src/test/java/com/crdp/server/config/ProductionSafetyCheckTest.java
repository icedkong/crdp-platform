package com.crdp.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditArchiveProperties;
import com.crdp.framework.crypto.CryptoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class ProductionSafetyCheckTest {

    @Test
    void prodKmsRefusesToStartUntilTheProviderIsConnected() {
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new ProductionSafetyCheck(
                                        environment("prod"), properties("kms"), archive("none")));
        assertTrue(failure.getMessage().contains("KMS"));
        assertTrue(failure.getMessage().contains("demo"));
    }

    @Test
    void prodLocalModeIsStillRejected() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new ProductionSafetyCheck(
                                environment("prod"), properties("local"), archive("none")));
    }

    @Test
    void demoLocalModeStarts() {
        assertDoesNotThrow(
                () ->
                        new ProductionSafetyCheck(
                                environment("demo"), properties("local"), archive("none")));
    }

    /**
     * S3（M3 审查）/ PLT-AUD-010：生产显式配成 none（或漏配 obs）拒绝启动，报错指明缺的 归档桶环境变量（CRDP_OBS_ARCHIVE_*）与回落后果。
     * mode 用非 local / 非 kms 的占位值绕开加密检查（KmsKeyProvider 占位未接入会先抛），单测只锁归档项。
     */
    @Test
    void prodAuditArchiveNoneRefusesToStart() {
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new ProductionSafetyCheck(
                                        environment("prod"),
                                        properties("mode-bypassed（单测占位）"),
                                        archive("none")));
        assertTrue(failure.getMessage().contains("审计归档"), failure.getMessage());
        assertTrue(
                failure.getMessage().contains("CRDP_OBS_ARCHIVE_BUCKET"),
                "报错必须指明缺什么（归档桶配置键）");
        assertTrue(failure.getMessage().contains("不可变副本"), "说明 none 回落的后果");
    }

    /**
     * 生产 type=obs 不触发归档项拒绝：mode=kms 仍会先因 KMS 未接入被拒（KmsKeyProvider 占位实现）， 但报错必须是 KMS 项而不是「审计归档」——
     * 归档检查只拦 type≠obs。缺桶配置由 ObsAuditArchiveStorage 另行 fail-fast（报错指明配置键， ObsAuditArchiveStorageTest 已锁）。
     */
    @Test
    void prodAuditArchiveObsDoesNotTripArchiveCheck() {
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new ProductionSafetyCheck(
                                        environment("prod"), properties("kms"), archive("obs")));
        assertTrue(failure.getMessage().contains("KMS"), "先被 KMS 项拒绝（占位实现尚未接入）");
        assertTrue(
                !failure.getMessage().contains("审计归档"),
                "type=obs 不触发归档项拒绝：" + failure.getMessage());
    }

    private static Environment environment(String profile) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {profile});
        return environment;
    }

    private static CryptoProperties properties(String mode) {
        CryptoProperties properties = new CryptoProperties();
        properties.setMode(mode);
        return properties;
    }

    private static AuditArchiveProperties archive(String storageType) {
        AuditArchiveProperties properties = new AuditArchiveProperties();
        properties.getStorage().setType(storageType);
        return properties;
    }
}
