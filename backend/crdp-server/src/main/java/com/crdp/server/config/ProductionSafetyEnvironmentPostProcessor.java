package com.crdp.server.config;

import com.crdp.followup.report.app.MobileUploadProperties;
import com.crdp.framework.audit.AuditArchiveProperties;
import com.crdp.framework.crypto.CryptoProperties;
import com.crdp.framework.notify.sms.TencentSmsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/** 在数据源等 Bean 创建之前拒绝未接入的生产 KMS。否则演示库里的本地包装口令会先抛出另一句错误，把 KMS 未接入盖住。 */
public class ProductionSafetyEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        CryptoProperties crypto = new CryptoProperties();
        crypto.setMode(environment.getProperty("crdp.crypto.mode", "local"));
        // S3：归档项同在数据源之前校验（type 从环境读，缺省 obs 与 prod yml 一致）
        AuditArchiveProperties auditArchive = new AuditArchiveProperties();
        auditArchive
                .getStorage()
                .setType(
                        environment.getProperty(
                                "crdp.audit.archive.storage.type",
                                String.class,
                                auditArchive.getStorage().getType()));
        // G9① / G10：短信回执凭据与手机上传基地址也从环境读（此时 Bean 还没建，直接 new 属性对象）
        TencentSmsProperties sms = new TencentSmsProperties();
        sms.setEnabled(Boolean.parseBoolean(environment.getProperty("crdp.sms.enabled", "false")));
        sms.setReceiptAuthUsername(environment.getProperty("crdp.sms.receipt-auth-username", ""));
        sms.setReceiptAuthPassword(environment.getProperty("crdp.sms.receipt-auth-password", ""));
        MobileUploadProperties mobile = new MobileUploadProperties();
        mobile.setMobileBaseUrl(environment.getProperty("crdp.h5-upload.mobile-base-url", ""));
        new ProductionSafetyCheck(environment, crypto, auditArchive, sms, mobile);
    }
}
