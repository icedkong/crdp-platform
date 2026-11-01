package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.tenant.TenantRecord;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 技术方案第 3 章：按子域名 / 租户编码查租户，本地缓存 60 秒（正负结果都缓存）。 */
class TenantLookupCacheTest {

    private final PlatformMapper mapper = Mockito.mock(PlatformMapper.class);

    @AfterEach
    void clear() {
        com.crdp.framework.tenant.TenantContext.clear();
    }

    @Test
    void cachesTheLookupResultForTheTtl() {
        when(mapper.findTenantByCode("demoa"))
                .thenReturn(new TenantRecord(7L, "demoa", "虚构示例医院甲", "ENABLED", false));
        TenantLookupCache cache = new TenantLookupCache(mapper, Duration.ofMinutes(10));

        Optional<TenantRecord> first = cache.findByCode("demoa");
        Optional<TenantRecord> second = cache.findByCode("demoa");

        assertEquals(7L, first.orElseThrow().id());
        assertEquals(7L, second.orElseThrow().id());
        verify(mapper, times(1)).findTenantByCode("demoa");
    }

    @Test
    void cachesNegativeResultsToo() {
        when(mapper.findTenantByCode("nobody")).thenReturn(null);
        TenantLookupCache cache = new TenantLookupCache(mapper, Duration.ofMinutes(10));

        assertEquals(Optional.empty(), cache.findByCode("nobody"));
        assertEquals(Optional.empty(), cache.findByCode("nobody"));
        verify(mapper, times(1)).findTenantByCode("nobody");
    }

    @Test
    void refreshesAfterExpiry() {
        when(mapper.findTenantByCode("demoa"))
                .thenReturn(new TenantRecord(7L, "demoa", "虚构示例医院甲", "ENABLED", false));
        TenantLookupCache cache = new TenantLookupCache(mapper, Duration.ofMillis(1));

        cache.findByCode("demoa");
        sleepQuietly();
        cache.findByCode("demoa");

        verify(mapper, times(2)).findTenantByCode("demoa");
    }

    @Test
    void nullCodeShortCircuitsWithoutTouchingTheDatabase() {
        TenantLookupCache cache = new TenantLookupCache(mapper, Duration.ofMinutes(10));

        assertEquals(Optional.empty(), cache.findByCode(null));
        Mockito.verifyNoInteractions(mapper);
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
