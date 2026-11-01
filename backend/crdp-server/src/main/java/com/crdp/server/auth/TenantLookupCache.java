package com.crdp.server.auth;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.tenant.TenantRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 按租户编码查租户的本地缓存（技术方案第 3 章：缓存 60 秒，正负结果都缓存，节点内有效）。 新开通的租户最多 60 秒后才可在本节点登录——开通编排低频，可接受。 */
@Component
public class TenantLookupCache {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final PlatformMapper mapper;
    private final Duration ttl;
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public TenantLookupCache(PlatformMapper mapper) {
        this(mapper, DEFAULT_TTL);
    }

    TenantLookupCache(PlatformMapper mapper, Duration ttl) {
        this.mapper = mapper;
        this.ttl = ttl;
    }

    public Optional<TenantRecord> findByCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Entry entry =
                cache.compute(
                        code,
                        (key, existing) ->
                                existing != null && existing.expiresAt().isAfter(now)
                                        ? existing
                                        : new Entry(lookup(key), now.plus(ttl)));
        return entry.tenant();
    }

    private Optional<TenantRecord> lookup(String code) {
        return Optional.ofNullable(
                TenantContext.callAsPlatform(() -> mapper.findTenantByCode(code)));
    }

    /**
     * 状态变更后立刻作废本节点的缓存（E05-T03）：人工流转与到期自动流转（转宽限 / 自动停用 / 恢复）都调用， 让登录闸门与宽限只读拦截尽快看到新状态； 其他节点仍靠 60 秒
     * TTL 收敛（M1 单实例部署，见 ADR 0003 第 4 条）。
     */
    public void evict(String code) {
        if (code != null) {
            cache.remove(code);
        }
    }

    private record Entry(Optional<TenantRecord> tenant, Instant expiresAt) {}
}
