package com.crdp.followup.report.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.report.app.MobileUploadTokenStore.Issued;
import com.crdp.followup.report.app.MobileUploadTokenStore.Payload;
import com.crdp.followup.report.app.ReportService.LocationInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * E16-T10 单测锁定（A-ATT-015 / C-SCAN-008 / C-NFR-006，全部虚构数据）：一次性上传令牌存储——15 分钟 TTL 上限、
 * 令牌不透明且不重复、追加文件保持剩余 TTL、提交消费（SUBMITTED 留结果供电脑端轮询）、过期 / 损坏统一按不存在。
 */
@ExtendWith(MockitoExtension.class)
class MobileUploadTokenStoreTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final ObjectMapper json = new ObjectMapper();

    private final MobileUploadProperties properties = new MobileUploadProperties();

    private MobileUploadTokenStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        store = new MobileUploadTokenStore(redis, json, properties);
    }

    @Test
    void issueStoresPendingPayloadUnderTokenKey() throws Exception {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        Issued first =
                store.issue(
                        "issuer-token",
                        1L,
                        "demoa",
                        42L,
                        new LocationInput("BASELINE", null, "CATEGORY", "echo", 3));
        Issued second = store.issue("issuer-token", 1L, "demoa", 42L, null);
        verify(values, org.mockito.Mockito.times(2))
                .set(key.capture(), payload.capture(), any(Duration.class));

        // 令牌 256 位随机（64 hex）、两次签发不重复（一次性，不可预测）
        assertEquals(64, first.token().length());
        assertNotEquals(first.token(), second.token());
        assertEquals("crdp:h5-upload:" + first.token(), key.getAllValues().getFirst());
        // 第一次写入带预选位置（第二次签发无预选，校验首条）
        Payload stored = json.readValue(payload.getAllValues().getFirst(), Payload.class);
        assertEquals("issuer-token", stored.issuerToken());
        assertEquals(1L, stored.tenantId());
        assertEquals("demoa", stored.tenantCode());
        assertEquals(42L, stored.patientId());
        assertEquals(MobileUploadTokenStore.STATUS_PENDING, stored.status());
        assertTrue(stored.fileIds().isEmpty());
        assertNotNull(stored.presetLocation());
    }

    @Test
    void appendFileKeepsRemainingTtlInsteadOfResettingExpiry() throws Exception {
        when(values.get("crdp:h5-upload:t1")).thenReturn(json.writeValueAsString(pending()));
        when(redis.getExpire("crdp:h5-upload:t1")).thenReturn(300L);

        store.appendFile("t1", store.find("t1"), "101");

        // 用剩余 300 秒写回（全额 TTL 会把过期时钟重置，不采用）
        verify(values).set(eq("crdp:h5-upload:t1"), anyString(), eq(Duration.ofSeconds(300)));
    }

    @Test
    void markSubmittedConsumesTokenButKeepsResultForDesktopPolling() throws Exception {
        when(values.get("crdp:h5-upload:t1"))
                .thenReturn(json.writeValueAsString(pending().withFiles(List.of("101"))));
        when(redis.getExpire("crdp:h5-upload:t1")).thenReturn(120L);
        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);

        store.markSubmitted("t1", store.find("t1"), "9001");
        verify(values).set(anyString(), written.capture(), any(Duration.class));

        Payload saved = json.readValue(written.getValue(), Payload.class);
        assertEquals(MobileUploadTokenStore.STATUS_SUBMITTED, saved.status());
        assertEquals("9001", saved.reportId());
        assertEquals(List.of("101"), saved.fileIds());
    }

    @Test
    void missingOrCorruptedPayloadReadsAsNull() throws Exception {
        when(values.get("crdp:h5-upload:missing")).thenReturn(null);
        when(values.get("crdp:h5-upload:broken")).thenReturn("not-json{");

        assertNull(store.find("missing"));
        assertNull(store.find("broken"));
    }

    @Test
    void effectiveTtlCapsAtFifteenMinutes() {
        properties.setTtlSeconds(3600);
        assertEquals(900, properties.effectiveTtlSeconds());
        properties.setTtlSeconds(60);
        assertEquals(60, properties.effectiveTtlSeconds());
    }

    private static Payload pending() {
        return new Payload(
                "issuer-token",
                1L,
                "demoa",
                42L,
                new LocationInput("BASELINE", null, "CATEGORY", "echo", 3),
                List.of(),
                MobileUploadTokenStore.STATUS_PENDING,
                null);
    }
}
