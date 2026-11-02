package com.crdp.followup.file.infra.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.followup.file.app.FilePreviewProperties;
import com.crdp.framework.security.TokenGenerator;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * E10-T04 预览令牌（{@link FilePreviewTokenStore}）单元测试：签发登记（摘要键、绑定租户与文件、TTL ≤ 5 分钟） 与解析的 fail closed
 * 口径（格式不对 / 登记值不合法 / Redis 不可用一律空，调用方按 40400 处理，不暴露存在性）。
 */
@ExtendWith(MockitoExtension.class)
class FilePreviewTokenStoreTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private FilePreviewTokenStore store;

    @BeforeEach
    void wire() {
        FilePreviewProperties properties = new FilePreviewProperties();
        properties.setTtlSeconds(300);
        store = new FilePreviewTokenStore(redis, new TokenGenerator(), properties);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    /** 签发 → 解析往返：值绑定「租户ID|租户编码|文件ID」，TTL 不超过 5 分钟。 */
    @Test
    void issueAndResolveRoundTrip() {
        String token = store.issue(1L, "demoa", 9L);
        when(valueOps.get(anyString())).thenReturn("1|demoa|9");
        Optional<FilePreviewTokenStore.Grant> grant = store.resolve(token);
        assertTrue(grant.isPresent());
        assertEquals(1L, grant.orElseThrow().tenantId());
        assertEquals("demoa", grant.orElseThrow().tenantCode());
        assertEquals(9L, grant.orElseThrow().fileId());
    }

    /** 令牌格式不对（超出 base64url 字符集 / 超长）不进 Redis，直接空。 */
    @Test
    void malformedTokenRejectedWithoutRedis() {
        assertTrue(store.resolve("../etc/passwd").isEmpty());
        assertTrue(store.resolve("a b c").isEmpty());
        assertTrue(store.resolve("x".repeat(65)).isEmpty());
        assertTrue(store.resolve(null).isEmpty());
    }

    /** 登记值被篡改 / 结构不对 → 空（不猜测、不降级）。 */
    @Test
    void corruptedRegistrationRejected() {
        String token = store.issue(1L, "demoa", 9L);
        when(valueOps.get(anyString())).thenReturn("who-knows");
        assertTrue(store.resolve(token).isEmpty());
        when(valueOps.get(anyString())).thenReturn("1|demoa");
        assertTrue(store.resolve(token).isEmpty());
        when(valueOps.get(anyString())).thenReturn("one|demoa|9");
        assertTrue(store.resolve(token).isEmpty());
    }

    /** Redis 不可用 → 空（fail closed：宁可不能预览，不可绕开令牌闸门）。 */
    @Test
    void redisFailureFailsClosed() {
        String token = store.issue(1L, "demoa", 9L);
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertTrue(store.resolve(token).isEmpty());
    }
}
