package com.crdp.server.platform;

import com.crdp.followup.file.app.TenantStorageQuota;
import org.springframework.stereotype.Component;

/**
 * {@link TenantStorageQuota} 的装配实现（E05-T09 / PLT-TEN-014）：读当前租户的存储上限（字节，null=不限）。 配额在平台库
 * plt_tenant（V67），连接口径与失败语义见 {@link TenantQuotaGuard}（读不到按不限）。
 */
@Component
public class TenantStorageQuotaAdapter implements TenantStorageQuota {

    private final TenantQuotaGuard guard;

    public TenantStorageQuotaAdapter(TenantQuotaGuard guard) {
        this.guard = guard;
    }

    @Override
    public Long storageLimitBytes() {
        return guard.currentStorageLimitBytes();
    }
}
