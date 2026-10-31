package com.crdp.framework.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** E04-T06 / PLT-PERM-010：权限版本号按「端别 + 租户 + 用户」递增； 登录会话带初始版本 1，bump 后旧会话版本判定落后、按新权限重算。 */
class PermissionVersionStoreTest {

    private final Map<String, String> values = new HashMap<>();
    private final AtomicLong counter = new AtomicLong();
    private PermissionVersionStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> values.get(inv.getArgument(0, String.class)));
        // INCR 语义：键不存在从 0 起自增
        when(ops.increment(anyString()))
                .thenAnswer(
                        inv -> {
                            String key = inv.getArgument(0, String.class);
                            long next = Long.parseLong(values.getOrDefault(key, "0")) + 1;
                            values.put(key, String.valueOf(next));
                            counter.incrementAndGet();
                            return next;
                        });
        store = new PermissionVersionStore(redis);
    }

    @Test
    void currentVersionDefaultsToOneWhenNeverBumped() {
        assertEquals(1L, store.current("HOSPITAL", 7L, 100L), "从未递增过的用户当前版本为 1");
    }

    @Test
    void firstBumpGoesStraightToTwo() {
        long version = store.bump("HOSPITAL", 7L, 100L);
        assertEquals(2L, version, "首次递增必须越过存量会话的初始版本 1");
        assertEquals(2L, store.current("HOSPITAL", 7L, 100L));
    }

    @Test
    void bumpIsScopedPerUserSideAndTenant() {
        store.bump("HOSPITAL", 7L, 100L);

        assertEquals(1L, store.current("HOSPITAL", 7L, 200L), "同租户其他用户不受影响");
        assertEquals(1L, store.current("HOSPITAL", 8L, 100L), "其他租户同名用户不受影响");
        assertEquals(1L, store.current("PLATFORM", null, 100L), "平台端独立计数");
    }

    @Test
    void corruptedValueFallsBackToOne() {
        values.put("crdp:permver:HOSPITAL:7:100", "not-a-number");
        assertEquals(1L, store.current("HOSPITAL", 7L, 100L), "脏值按未递增处理");
    }

    @Test
    void repeatedBumpsKeepIncreasing() {
        store.bump("HOSPITAL", 7L, 100L);
        long second = store.bump("HOSPITAL", 7L, 100L);
        assertEquals(3L, second, "第二次递增为 3");
        assertEquals(3L, store.current("HOSPITAL", 7L, 100L));
    }

    @Test
    void currentReadsFromTheSessionBucket() {
        SessionPrincipal principal =
                new SessionPrincipal(
                        "token",
                        "HOSPITAL",
                        7L,
                        "demoa",
                        "虚构示例医院甲",
                        100L,
                        "doctor.demo",
                        "演示医生",
                        List.of("FOLLOWUP_DOCTOR"),
                        List.of(10L),
                        List.of("followup:patient:view"),
                        List.of("base"),
                        1L,
                        ScopeSnapshot.allowAll(),
                        false,
                        null);
        store.bump(principal.sessionType(), principal.tenantId(), principal.userId());
        assertEquals(2L, store.current(principal), "按会话身份读取当前版本");
    }
}
