package com.crdp.followup.file.infra.preview;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.crdp.followup.file.app.FilePreviewProperties;
import com.crdp.framework.security.TokenGenerator;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.*;

class FilePreviewTokenStoreTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final FilePreviewProperties properties = new FilePreviewProperties();
    private FilePreviewTokenStore store;

    private FilePreviewTokenStore.Grant grant(long expiry) {
        return new FilePreviewTokenStore.Grant(
                2,
                129,
                "demo129",
                9,
                501,
                "session-identity",
                "t/demo129/file",
                "0".repeat(64),
                100,
                "pdf",
                "医生（示例）（account） · 医院（示例） · 2026-11-19 15:00",
                expiry);
    }

    @BeforeEach
    void setup() {
        when(redis.opsForValue()).thenReturn(values);
        store = new FilePreviewTokenStore(redis, new TokenGenerator(), properties);
    }

    @Test
    void storesOnlyTokenHashAndCompleteIdentityBinding() {
        var grant = grant(Instant.now().getEpochSecond() + 300);
        String token = store.issue(grant);
        var key = ArgumentCaptor.forClass(String.class);
        var json = ArgumentCaptor.forClass(String.class);
        verify(values).set(key.capture(), json.capture(), eq(Duration.ofSeconds(300)));
        assertThat(key.getValue()).startsWith("crdp:file-preview:").doesNotContain(token);
        assertThat(json.getValue()).doesNotContain(token);
        when(values.get(anyString())).thenReturn(json.getValue());
        assertThat(store.resolve(token)).contains(grant);
    }

    @Test
    void oldUnboundAndMalformedValuesFailClosed() {
        for (String value : new String[] {"1|demoa|9", "{}", "[]", "not-json"}) {
            when(values.get(anyString())).thenReturn(value);
            assertThat(store.resolve("preview-token")).isEmpty();
        }
    }

    @Test
    void expiryAndMissingValuesFailClosed() throws Exception {
        when(values.get(anyString()))
                .thenReturn(
                        new ObjectMapper()
                                .writeValueAsString(grant(Instant.now().getEpochSecond() - 1)));
        assertThat(store.resolve("preview-token")).isEmpty();
        when(values.get(anyString())).thenReturn(null);
        assertThat(store.resolve("preview-token")).isEmpty();
    }

    @Test
    void invalidTokenDoesNotReadRedis() {
        assertThat(store.resolve("../bad")).isEmpty();
        assertThat(store.resolve("x".repeat(65))).isEmpty();
        verify(values, never()).get(anyString());
    }

    @Test
    void redisFailureDoesNotPermitAccess() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("不可用（示例）"));
        assertThat(store.resolve("preview-token")).isEmpty();
        doThrow(new IllegalStateException())
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));
        assertThatThrownBy(() -> store.issue(grant(Instant.now().getEpochSecond() + 300)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void ttlIsBoundedOnBothSides() {
        properties.setTtlSeconds(-1);
        assertThat(properties.effectiveTtlSeconds()).isEqualTo(1);
        properties.setTtlSeconds(1000);
        assertThat(properties.effectiveTtlSeconds()).isEqualTo(300);
    }
}
