package com.crdp.framework.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditArchiveProperties;
import com.obs.services.IObsClient;
import com.obs.services.exception.ObsException;
import com.obs.services.model.PutObjectRequest;
import com.obs.services.model.objectlock.ObjectRetention;
import com.obs.services.model.objectlock.SetObjectRetentionRequest;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E22-T08 / PLT-AUD-010：审计归档桶 OBS 适配器单元测试（离线，Mockito 桩替 IObsClient，不发真实请求）。
 *
 * <p>锁定验收「归档对象在保留期内不可删改」的应用侧形态：每次写入 = 单次 PUT + 对象级 COMPLIANCE 保留到 retainUntil（epoch
 * 毫秒，北京时间当日起算），且端口本身没有删除 / 覆盖语义。云侧真实 WORM 拒删行为由 docs/ops/audit-worm-archive.md 的演练验证（本机无云凭证）。
 */
class ObsAuditArchiveStorageTest {

    private final IObsClient client = mock(IObsClient.class);

    private static AuditArchiveProperties props() {
        AuditArchiveProperties properties = new AuditArchiveProperties();
        properties.getStorage().setType("obs");
        properties.getStorage().getObs().setEndpoint("obs.cn-north-4.myhuaweicloud.com");
        properties.getStorage().getObs().setAccessKey("AKEXAMPLE");
        properties.getStorage().getObs().setSecretKey("SKEXAMPLE");
        properties.getStorage().getObs().setBucket("crdp-audit-archive");
        return properties;
    }

    /** PUT：单次上传 + 对象级 COMPLIANCE 保留 = retainUntil 北京时间当日 00:00 的 epoch 毫秒。 */
    @Test
    void putWithRetentionSendsSinglePutAndComplianceRetention() {
        ObsAuditArchiveStorage storage = new ObsAuditArchiveStorage(client, props());
        LocalDate retainUntil = LocalDate.of(2042, 6, 15);
        byte[] body = "JSONL 归档体（示例）".getBytes();

        assertTrue(storage.enabled(), "配置了归档桶即视为启用（未配置时装配的是 Unsupported 实现）");
        storage.putWithRetention(
                "audit/tenant/demoa/aud_log/2026-01.jsonl.gz",
                body,
                "application/gzip",
                retainUntil);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(put.capture());
        assertEquals("crdp-audit-archive", put.getValue().getBucketName());
        assertEquals("audit/tenant/demoa/aud_log/2026-01.jsonl.gz", put.getValue().getObjectKey());
        assertEquals("application/gzip", put.getValue().getMetadata().getContentType());
        assertEquals(body.length, put.getValue().getMetadata().getContentLength());

        ArgumentCaptor<SetObjectRetentionRequest> retention =
                ArgumentCaptor.forClass(SetObjectRetentionRequest.class);
        verify(client).setObjectRetention(retention.capture());
        assertEquals("crdp-audit-archive", retention.getValue().getBucketName());
        assertEquals(
                "audit/tenant/demoa/aud_log/2026-01.jsonl.gz", retention.getValue().getObjectKey());
        ObjectRetention worm = retention.getValue().getRetention();
        assertEquals("COMPLIANCE", worm.getMode(), "OBS WORM 仅支持合规模式（保留期内任何人不可删改）");
        assertEquals(
                retainUntil.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
                worm.getRetainUntilDate(),
                "对象级保留截止 = retainUntil 北京时间当日 00:00（epoch 毫秒，SDK 口径）");
    }

    /** GET：读回对象内容（归档后校验与按需恢复查询用）。 */
    @Test
    void getReadsObjectContent() throws Exception {
        byte[] body = "{\"rowCount\":2}".getBytes();
        com.obs.services.model.ObsObject object = new com.obs.services.model.ObsObject();
        object.setObjectContent(new ByteArrayInputStream(body));
        when(client.getObject(anyString(), anyString())).thenReturn(object);

        ObsAuditArchiveStorage storage = new ObsAuditArchiveStorage(client, props());
        assertArrayEquals(body, storage.get("audit/platform/plt_audit_log/2026-01.jsonl.gz"));
        verify(client)
                .getObject("crdp-audit-archive", "audit/platform/plt_audit_log/2026-01.jsonl.gz");
    }

    /** GET 404 → null（重跑时桶中无对象即走上传路径）；其他错误与 PUT / 保留设置失败 → 作业可感知的运行时异常。 */
    @Test
    void getMapsNotFoundToNullAndFailuresSurface() {
        ObsAuditArchiveStorage storage = new ObsAuditArchiveStorage(client, props());

        when(client.getObject(anyString(), anyString()))
                .thenReturn(null)
                .thenThrow(obsFailure(404))
                .thenThrow(obsFailure(403));
        assertNull(storage.get("audit/missing.jsonl.gz"), "ObsObject 为空按不存在处理");
        assertNull(storage.get("audit/missing.jsonl.gz"), "404 按不存在处理");
        IllegalStateException denied =
                assertThrows(IllegalStateException.class, () -> storage.get("audit/x.jsonl.gz"));
        assertTrue(
                denied.getMessage().contains("审计归档桶"), "异常带统一前缀，不含 OBS 明细：" + denied.getMessage());

        when(client.putObject(any(PutObjectRequest.class))).thenThrow(obsFailure(500));
        assertThrows(
                IllegalStateException.class,
                () ->
                        storage.putWithRetention(
                                "k", new byte[1], "application/gzip", LocalDate.of(2042, 1, 1)));

        // PUT 成功但对象级保留设置失败（如桶未开 WORM）：同样失败并保留在线分区，不静默降级
        when(client.putObject(any(PutObjectRequest.class))).thenReturn(null);
        when(client.setObjectRetention(any(SetObjectRetentionRequest.class)))
                .thenThrow(obsFailure(400));
        assertThrows(
                IllegalStateException.class,
                () ->
                        storage.putWithRetention(
                                "k", new byte[1], "application/gzip", LocalDate.of(2042, 1, 1)));
    }

    /** type=obs 但必填配置缺失：启动即失败（fail-fast），不等到第一轮 04:10 作业才炸。 */
    @Test
    void missingConfigurationFailsFast() {
        AuditArchiveProperties noEndpoint = props();
        noEndpoint.getStorage().getObs().setEndpoint("");
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class, () -> new ObsAuditArchiveStorage(noEndpoint));
        assertTrue(ex.getMessage().contains("endpoint"), ex.getMessage());

        AuditArchiveProperties noBucket = props();
        noBucket.getStorage().getObs().setBucket("");
        assertThrows(IllegalStateException.class, () -> new ObsAuditArchiveStorage(noBucket));
    }

    private static ObsException obsFailure(int status) {
        ObsException failure = new ObsException("OBS " + status);
        failure.setResponseCode(status);
        failure.setErrorCode("Test" + status);
        return failure;
    }
}
