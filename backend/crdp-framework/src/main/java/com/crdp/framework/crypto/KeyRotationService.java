package com.crdp.framework.crypto;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.tenant.TenantContext;

/**
 * 密钥轮换入口（E03-T05，NFR-CRY-004）：为某用途生成下一个数据密钥版本。新写入立即用新版本（{@code currentVersion} 取最大版本），旧版本保留解密；存量密文由
 * {@link CryptoReencryptionJob} 后台迁移。轮换动作写审计（NFR-CRY-005），审计里只有版本号与用途，绝不落密钥明文。
 * 年度轮换的调度注册与泄露时的双人复核流程分别是 E14-T04 与平台管理侧的事。
 */
public class KeyRotationService {

    private final TenantDataKeyService dataKeys;
    private final AuditLogger auditLogger;

    public KeyRotationService(TenantDataKeyService dataKeys, AuditLogger auditLogger) {
        this.dataKeys = dataKeys;
        this.auditLogger = auditLogger;
    }

    /**
     * 轮换指定用途的数据密钥，返回新版本号。必须在租户上下文内调用（数据密钥存在租户库）。
     *
     * @param purpose 数据密钥用途，如 field-name
     */
    public int rotate(String purpose) {
        TenantContext.require();
        int previous = dataKeys.currentVersion(purpose);
        int next = dataKeys.createNextVersion(purpose);
        auditLogger.record(
                new AuditEvent(
                        "CRYPTO",
                        null,
                        "ROTATE",
                        "crypto_data_key:" + purpose,
                        "数据密钥轮换：版本 " + previous + " → " + next + "，旧版本保留解密，等待重加密作业迁移存量密文"));
        return next;
    }
}
