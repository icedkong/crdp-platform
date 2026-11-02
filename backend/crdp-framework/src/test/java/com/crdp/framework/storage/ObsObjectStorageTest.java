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

import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.obs.services.IObsClient;
import com.obs.services.exception.ObsException;
import com.obs.services.model.ObsObject;
import com.obs.services.model.PutObjectRequest;
import com.obs.services.model.ServerEncryption;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E10-T02 / C-NFR-003：OBS 适配器单元测试（离线，Mockito 桩替 IObsClient，不发真实请求）。
 *
 * <p>锁定任务验收「文件加密存储」：每次 PUT 必带 SSE-KMS 头（encryption=OBS_KMS + 配置的 kms-key-id）， 对象键带租户前缀；404 →
 * null、其他服务商错误 → 50000（不把 OBS 错误明细抛给调用方）。 云侧真实行为（密钥有效、加密生效）由 E22 演练验证，本机无云凭证。
 */
class ObsObjectStorageTest {

    private final IObsClient client = mock(IObsClient.class);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static ObjectStorageProperties props(String kmsKeyId) {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setType("obs");
        properties.getObs().setEndpoint("obs.cn-north-4.myhuaweicloud.com");
        properties.getObs().setAccessKey("AKEXAMPLE");
        properties.getObs().setSecretKey("SKEXAMPLE");
        properties.getObs().setBucket("crdp-demo");
        properties.getObs().setKmsKeyId(kmsKeyId);
        return properties;
    }

    /** PUT：桶 + 租户前缀键 + SSE-KMS（指定密钥）；返回完整键。 */
    @Test
    void putCarriesSseKmsAndTenantPrefix() {
        TenantContext.useTenant(1L, "demoa");
        ObsObjectStorage storage = new ObsObjectStorage(client, props("kms-key-1"));

        byte[] body = "%PDF-1.4（示例）".getBytes();
        String key = storage.put("files/202611/uuid.pdf", body, "application/pdf");

        assertEquals("t/demoa/files/202611/uuid.pdf", key);
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture());
        assertEquals("crdp-demo", request.getValue().getBucketName());
        assertEquals(key, request.getValue().getObjectKey());
        assertEquals("application/pdf", request.getValue().getMetadata().getContentType());
        assertEquals(body.length, request.getValue().getMetadata().getContentLength());
        assertEquals(
                ServerEncryption.OBS_KMS, request.getValue().getSseKmsHeader().getEncryption());
        assertEquals("kms-key-1", request.getValue().getSseKmsHeader().getKmsKeyId());
    }

    /** SSE-KMS 不配密钥 ID 时省略，走桶默认 KMS 密钥（加密开关本身不可关）。 */
    @Test
    void putWithoutKmsKeyIdUsesBucketDefaultKey() {
        TenantContext.useTenant(2L, "demob");
        ObsObjectStorage storage = new ObsObjectStorage(client, props(""));

        storage.put("files/a.mp3", new byte[] {1}, "audio/mpeg");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture());
        assertEquals("t/demob/files/a.mp3", request.getValue().getObjectKey());
        assertEquals(
                ServerEncryption.OBS_KMS, request.getValue().getSseKmsHeader().getEncryption());
        assertNull(request.getValue().getSseKmsHeader().getKmsKeyId());
    }

    /** GET：读回对象内容。 */
    @Test
    void getReadsObjectContent() throws IOException {
        TenantContext.useTenant(1L, "demoa");
        byte[] body = "录音（示例）".getBytes();
        ObsObject object = new ObsObject();
        object.setObjectContent(new ByteArrayInputStream(body));
        when(client.getObject(anyString(), anyString())).thenReturn(object);

        ObsObjectStorage storage = new ObsObjectStorage(client, props("kms-key-1"));
        assertArrayEquals(body, storage.get("files/202611/a.mp3"));
        verify(client).getObject("crdp-demo", "t/demoa/files/202611/a.mp3");
    }

    /** GET 404 → null；其他 OBS 错误 → 50000（不向调用方泄露服务商明细）。 */
    @Test
    void getMapsNotFoundToNullAndErrorsTo50000() {
        TenantContext.useTenant(1L, "demoa");
        ObsObjectStorage storage = new ObsObjectStorage(client, props("kms-key-1"));

        when(client.getObject(anyString(), anyString()))
                .thenReturn(null)
                .thenThrow(obsFailure(404))
                .thenThrow(obsFailure(403));

        assertNull(storage.get("files/missing.pdf"), "ObsObject 为空按不存在处理");
        assertNull(storage.get("files/missing.pdf"), "404 按不存在处理");
        ApiException denied = assertThrows(ApiException.class, () -> storage.get("files/x.pdf"));
        assertEquals(50000, denied.code());
    }

    /** PUT / DELETE 服务商错误同样转 50000。 */
    @Test
    void putAndDeleteErrorsMapTo50000() {
        TenantContext.useTenant(1L, "demoa");
        ObsObjectStorage storage = new ObsObjectStorage(client, props("kms-key-1"));

        when(client.putObject(any(PutObjectRequest.class))).thenThrow(obsFailure(500));
        ApiException put =
                assertThrows(
                        ApiException.class,
                        () -> storage.put("a.pdf", new byte[1], "application/pdf"));
        assertEquals(50000, put.code());
        assertTrue(put.getMessage().contains("系统繁忙"), "对外统一文案：" + put.getMessage());

        when(client.deleteObject(anyString(), anyString())).thenThrow(obsFailure(500));
        ApiException delete = assertThrows(ApiException.class, () -> storage.delete("a.pdf"));
        assertEquals(50000, delete.code());
    }

    /** DELETE：按完整键调用（幂等语义由 OBS 提供，任何结果都不抛）。 */
    @Test
    void deleteUsesScopedKey() {
        TenantContext.useTenant(1L, "demoa");
        ObsObjectStorage storage = new ObsObjectStorage(client, props("kms-key-1"));

        storage.delete("files/202611/uuid.pdf");
        verify(client).deleteObject("crdp-demo", "t/demoa/files/202611/uuid.pdf");
    }

    /** type=obs 但必填配置缺失：启动即失败（fail-fast），不等到第一次上传才炸。 */
    @Test
    void missingConfigurationFailsFast() {
        ObjectStorageProperties noSecret = props("kms-key-1");
        noSecret.getObs().setSecretKey("");
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> new ObsObjectStorage(noSecret));
        assertTrue(ex.getMessage().contains("secret-key"), ex.getMessage());

        ObjectStorageProperties noBucket = props("kms-key-1");
        noBucket.getObs().setBucket("");
        assertThrows(IllegalStateException.class, () -> new ObsObjectStorage(noBucket));
    }

    // ---------------------------------------------------------------- 工具

    private static ObsException obsFailure(int status) {
        ObsException failure = new ObsException("OBS " + status);
        failure.setResponseCode(status);
        failure.setErrorCode("Test" + status);
        return failure;
    }
}
