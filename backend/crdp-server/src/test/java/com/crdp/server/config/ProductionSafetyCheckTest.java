package com.crdp.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.followup.report.app.MobileUploadProperties;
import com.crdp.framework.audit.AuditArchiveProperties;
import com.crdp.framework.crypto.CryptoProperties;
import com.crdp.framework.notify.sms.TencentSmsProperties;
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
                                        environment("prod"),
                                        properties("kms"),
                                        archive("none"),
                                        sms(false, null, null),
                                        mobile("https://m.example-hospital.cn")));
        assertTrue(failure.getMessage().contains("KMS"));
        assertTrue(failure.getMessage().contains("demo"));
    }

    @Test
    void prodLocalModeIsStillRejected() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new ProductionSafetyCheck(
                                environment("prod"),
                                properties("local"),
                                archive("none"),
                                sms(false, null, null),
                                mobile("https://m.example-hospital.cn")));
    }

    @Test
    void demoLocalModeStarts() {
        assertDoesNotThrow(
                () ->
                        new ProductionSafetyCheck(
                                environment("demo"),
                                properties("local"),
                                archive("none"),
                                sms(true, null, null),
                                mobile(null)));
    }

    /**
     * S3（M3 审查）/ PLT-AUD-010：生产显式配成 none（或漏配 obs）拒绝启动，报错指明缺的 归档桶环境变量（CRDP_OBS_ARCHIVE_*）与回落后果。 mode
     * 用非 local / 非 kms 的占位值绕开加密检查（KmsKeyProvider 占位未接入会先抛），单测只锁归档项。
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
                                        archive("none"),
                                        sms(false, null, null),
                                        mobile("https://m.example-hospital.cn")));
        assertTrue(failure.getMessage().contains("审计归档"), failure.getMessage());
        assertTrue(failure.getMessage().contains("CRDP_OBS_ARCHIVE_BUCKET"), "报错必须指明缺什么（归档桶配置键）");
        assertTrue(failure.getMessage().contains("不可变副本"), "说明 none 回落的后果");
    }

    /**
     * 生产 type=obs 不触发归档项拒绝：mode=kms 仍会先因 KMS 未接入被拒（KmsKeyProvider 占位实现）， 但报错必须是 KMS 项而不是「审计归档」——
     * 归档检查只拦 type≠obs。缺桶配置由 ObsAuditArchiveStorage 另行 fail-fast（报错指明配置键， ObsAuditArchiveStorageTest
     * 已锁）。
     */
    @Test
    void prodAuditArchiveObsDoesNotTripArchiveCheck() {
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new ProductionSafetyCheck(
                                        environment("prod"),
                                        properties("kms"),
                                        archive("obs"),
                                        sms(false, null, null),
                                        mobile("https://m.example-hospital.cn")));
        assertTrue(failure.getMessage().contains("KMS"), "先被 KMS 项拒绝（占位实现尚未接入）");
        assertTrue(
                !failure.getMessage().contains("审计归档"),
                "type=obs 不触发归档项拒绝：" + failure.getMessage());
    }

    /**
     * G9①（M3 审查）/ E22-T10 PT-SELF-02：短信通道启用而回执 Basic Auth 凭据缺失 = 公开口只剩 IP
     * 限流一道闸——生产拒绝启动，报错指明控制台与两个环境变量；凭据齐全（或通道关闭）不触发。
     */
    @Test
    void prodSmsEnabledWithoutReceiptAuthRefusesToStart() {
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new ProductionSafetyCheck(
                                        environment("prod"),
                                        properties("mode-bypassed（单测占位）"),
                                        archive("obs"),
                                        sms(true, null, null),
                                        mobile("https://m.example-hospital.cn")));
        assertTrue(failure.getMessage().contains("回执"), "指明缺的是回执凭据");
        assertTrue(
                failure.getMessage().contains("CRDP_SMS_RECEIPT_AUTH_USER"),
                "报错指明环境变量（与 S3 归档同标准）");

        // 凭据齐全：不再触发本项（KMS 项先抛——占位值绕开加密检查后无后续异常即过）
        assertDoesNotThrow(
                () ->
                        new ProductionSafetyCheck(
                                environment("prod"),
                                properties("mode-bypassed（单测占位）"),
                                archive("obs"),
                                sms(true, "receipt-user（示例）", "receipt-pass（示例）"),
                                mobile("https://m.example-hospital.cn")));

        // 通道关闭：凭据缺失也不触发（默认 enabled=false 的生产形态）
        assertDoesNotThrow(
                () ->
                        new ProductionSafetyCheck(
                                environment("prod"),
                                properties("mode-bypassed（单测占位）"),
                                archive("obs"),
                                sms(false, null, null),
                                mobile("https://m.example-hospital.cn")));
    }

    /** G9①：local / demo 剖面通道启用而凭据缺失不拒绝启动（SmsReceiptController 启动告警兜底）。 */
    @Test
    void nonProdSmsWithoutReceiptAuthStillStarts() {
        assertDoesNotThrow(
                () ->
                        new ProductionSafetyCheck(
                                environment("local"),
                                properties("local"),
                                archive("none"),
                                sms(true, null, null),
                                mobile(null)));
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

    private static TencentSmsProperties sms(boolean enabled, String user, String password) {
        TencentSmsProperties properties = new TencentSmsProperties();
        properties.setEnabled(enabled);
        properties.setReceiptAuthUsername(user);
        properties.setReceiptAuthPassword(password);
        return properties;
    }

    private static MobileUploadProperties mobile(String baseUrl) {
        MobileUploadProperties properties = new MobileUploadProperties();
        properties.setMobileBaseUrl(baseUrl);
        return properties;
    }
}
