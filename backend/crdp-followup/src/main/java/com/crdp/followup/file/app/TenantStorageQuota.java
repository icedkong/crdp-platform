package com.crdp.followup.file.app;

/**
 * 租户存储配额的读取端口（E05-T09 / PLT-TEN-014：设置了存储上限时，达到上限禁止上传）。
 *
 * <p>配额存平台库（plt_tenant.storage_limit_bytes），crdp-followup 不依赖 crdp-platform（依赖方向： followup → form →
 * framework），经本端口取当前租户的上限；实现是 crdp-server 里的适配器（复用 {@code TenantQuotaGuard} 的 平台连接口径，读不到按不限）。 与
 * {@link FileUploadParamDefaults}（E10-T01）同款「消费方声明端口、装配方实现」的做法。
 */
public interface TenantStorageQuota {

    /** 当前租户的存储上限（字节）；null = 不限（默认）。 */
    Long storageLimitBytes();
}
