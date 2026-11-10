package com.crdp.server.config;

import com.crdp.followup.report.app.MobileUploadProperties;
import com.crdp.framework.audit.AuditArchiveProperties;
import com.crdp.framework.crypto.CryptoProperties;
import com.crdp.framework.crypto.KmsKeyProvider;
import com.crdp.framework.notify.sms.TencentSmsProperties;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProductionSafetyCheck {

    public ProductionSafetyCheck(
            Environment environment,
            CryptoProperties cryptoProperties,
            AuditArchiveProperties auditArchive,
            TencentSmsProperties smsProperties,
            MobileUploadProperties mobileUploadProperties) {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (!prod) {
            return;
        }
        String mode = cryptoProperties.getMode();
        if ("local".equalsIgnoreCase(mode)) {
            throw new IllegalStateException(
                    "生产环境禁止 crdp.crypto.mode=local。演示请使用 --spring.profiles.active=demo（仅虚构数据，接入华为云 KMS 前禁止导入真实患者）。");
        }
        if ("kms".equalsIgnoreCase(mode) && !new KmsKeyProvider().connected()) {
            throw new IllegalStateException(
                    "生产剖面要求华为云 KMS（crdp.crypto.mode=kms），但 KmsKeyProvider 尚未接入，进程拒绝启动，避免第一次加密才返回 501。"
                            + "演示与虚构数据请使用 --spring.profiles.active=demo。"
                            + "接入 KMS 后让 KmsKeyProvider.connected() 返回 true。"
                            + "接入之前禁止导入真实患者数据。");
        }
        // S3（M3 审查）/ PLT-AUD-010：生产必须开启审计归档（在线 1 年 + WORM 15 年）。
        // application-prod.yml 默认 type=obs，缺 CRDP_OBS_ARCHIVE_ENDPOINT/AK/SK/BUCKET 任一项由
        // ObsAuditArchiveStorage 启动即失败（报错指明配置键，与业务桶 CRDP_OBS_* 同标准）；
        // 显式配成 none 也拒绝启动——none 不上传也不删在线数据，审计被改时没有不可变副本。
        if (!"obs".equalsIgnoreCase(auditArchive.getStorage().getType())) {
            throw new IllegalStateException(
                    "生产剖面必须开启审计归档（crdp.audit.archive.storage.type=obs，PLT-AUD-010：在线 1 年、"
                            + "归档 WORM 15 年）：缺少归档桶配置 CRDP_OBS_ARCHIVE_ENDPOINT / CRDP_OBS_ARCHIVE_AK /"
                            + " CRDP_OBS_ARCHIVE_SK / CRDP_OBS_ARCHIVE_BUCKET 任一项时进程拒绝启动，"
                            + "不静默回落 none（none 不上传、不删在线数据，审计没有不可变副本）。"
                            + "演示 / 本机请使用 demo / local 剖面（type=none 可用）。");
        }
        // G9①（M3 审查）/ E22-T10 PT-SELF-02：短信通道启用而回执 Basic Auth 凭据缺失时，
        // 公开回执口只剩 IP 限流一道闸（任意来源可伪造回执污染对账数据）——生产拒绝启动
        // （fail fast），local / demo 不受影响（SmsReceiptController 启动告警兜底）。
        if (smsProperties.isEnabled() && !smsProperties.receiptAuthConfigured()) {
            throw new IllegalStateException(
                    "生产剖面已启用短信通道（crdp.sms.enabled=true）但回执回调凭据未配置"
                            + "（crdp.sms.receipt-auth-username / receipt-auth-password，与腾讯云控制台「状态回执推送」"
                            + "Basic Auth 一致，经 CRDP_SMS_RECEIPT_AUTH_USER / CRDP_SMS_RECEIPT_AUTH_PASSWORD 注入）："
                            + "公开回执口将只剩 IP 限流，任意来源可伪造回执污染对账数据，进程拒绝启动。"
                            + "请在腾讯云控制台配置状态回执 Basic Auth 并注入两项环境变量后重启。");
        }
}
