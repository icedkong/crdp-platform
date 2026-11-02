package com.crdp.framework.storage;

import com.crdp.framework.web.ApiException;
import com.obs.services.IObsClient;
import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;
import com.obs.services.exception.ObsException;
import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.ObsObject;
import com.obs.services.model.PutObjectRequest;
import com.obs.services.model.ServerEncryption;
import com.obs.services.model.SseKmsHeader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 华为云 OBS 适配器（E10-T02 / C-NFR-003「对象存储加密」；ADR 0006）。一个业务桶、每院一个对象键前缀（tech-plan §2 修订）。
 *
 * <p>服务端加密：每次 PUT 强制 SSE-KMS（{@code SseKmsHeader}，可配 kms-key-id，留空走桶默认密钥）—— 应用不接触加密密钥、明文不落盘，密钥轮换由
 * KMS 承担；加密不可配置关闭（任务验收：文件加密存储）。桶本身的多可用区 / 版本控制由运维建桶时开启（E01-T08 / E22）。
 *
 * <p>密钥只经配置（环境变量）注入；日志只记 OBS 错误码与 requestId，不记 AK/SK 与文件内容。 适配器面向 {@link IObsClient} 编程，单测注入
 * Mockito 桩即可离线验证请求（桶、租户前缀键、SSE 头）与 404 语义。
 */
public class ObsObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(ObsObjectStorage.class);

    private final IObsClient client;

    private final String bucket;

    private final String kmsKeyId;

    /** 生产装配：按配置创建 ObsClient；必填项缺失启动即失败（fail-fast，不等到第一次上传）。 */
    @Autowired
    public ObsObjectStorage(ObjectStorageProperties properties) {
        this(
                newClient(properties.getObs()),
                properties.getObs().getBucket(),
                properties.getObs().getKmsKeyId());
    }

    /** 测试 / 显式装配（client 由调用方给出，便于注入桩）。 */
    ObsObjectStorage(IObsClient client, ObjectStorageProperties properties) {
        this(client, properties.getObs().getBucket(), properties.getObs().getKmsKeyId());
    }

    private ObsObjectStorage(IObsClient client, String bucket, String kmsKeyId) {
        require(bucket, "crdp.storage.obs.bucket");
        this.client = client;
        this.bucket = bucket;
        this.kmsKeyId = isBlank(kmsKeyId) ? null : kmsKeyId;
    }

    @Override
    public String put(String relativeKey, byte[] body, String contentType) {
        String key = StorageKeys.scopedKey(relativeKey);
        PutObjectRequest request = new PutObjectRequest();
        request.setBucketName(bucket);
        request.setObjectKey(key);
        request.setInput(new ByteArrayInputStream(body));
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(contentType);
        metadata.setContentLength((long) body.length);
        request.setMetadata(metadata);
        // SSE-KMS：服务端加密每个对象（C-NFR-003；不可配置关闭）
        SseKmsHeader sseKms = new SseKmsHeader();
        sseKms.setEncryption(ServerEncryption.OBS_KMS);
        if (kmsKeyId != null) {
            sseKms.setKmsKeyId(kmsKeyId);
        }
        request.setSseKmsHeader(sseKms);
        try {
            client.putObject(request);
        } catch (ObsException failure) {
            throw providerFailure("putObject", failure);
        }
        return key;
    }

    @Override
    public byte[] get(String relativeKey) {
        String key = StorageKeys.scopedKey(relativeKey);
        ObsObject object;
        try {
            object = client.getObject(bucket, key);
        } catch (ObsException failure) {
            if (failure.getResponseCode() == 404) {
                return null;
            }
            throw providerFailure("getObject", failure);
        }
        if (object == null) {
            return null;
        }
        try (InputStream content = object.getObjectContent()) {
            return content.readAllBytes();
        } catch (IOException failure) {
            log.warn("OBS 对象读取失败 keyLength={}", key.length(), failure);
            throw unavailable();
        } catch (ObsException failure) {
            throw providerFailure("getObject", failure);
        }
    }

    @Override
    public void delete(String relativeKey) {
        String key = StorageKeys.scopedKey(relativeKey);
        try {
            client.deleteObject(bucket, key);
        } catch (ObsException failure) {
            throw providerFailure("deleteObject", failure);
        }
    }

    // ---------------------------------------------------------------- 私有

    private static IObsClient newClient(ObjectStorageProperties.Obs obs) {
        require(obs.getEndpoint(), "crdp.storage.obs.endpoint");
        require(obs.getAccessKey(), "crdp.storage.obs.access-key");
        require(obs.getSecretKey(), "crdp.storage.obs.secret-key");
        require(obs.getBucket(), "crdp.storage.obs.bucket");
        ObsConfiguration configuration = new ObsConfiguration();
        configuration.setEndPoint(obs.getEndpoint());
        configuration.setConnectionTimeout(obs.getConnectTimeoutMs());
        configuration.setSocketTimeout(obs.getSocketTimeoutMs());
        configuration.setHttpsOnly(true);
        return new ObsClient(obs.getAccessKey(), obs.getSecretKey(), configuration);
    }

    /** 服务商错误统一转 50000：不把 OBS 错误明细抛给调用方，日志只记错误码与请求 ID。 */
    private static ApiException providerFailure(String operation, ObsException failure) {
        log.warn(
                "OBS {} 失败 errorCode={} responseCode={} requestId={}",
                operation,
                failure.getErrorCode(),
                failure.getResponseCode(),
                failure.getErrorRequestId());
        return unavailable();
    }

    private static void require(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalStateException("OBS 对象存储未配置：" + name + " 不能为空（type=obs）");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ApiException unavailable() {
        return new ApiException(500, 50000, "系统繁忙，请稍后再试");
    }
}
