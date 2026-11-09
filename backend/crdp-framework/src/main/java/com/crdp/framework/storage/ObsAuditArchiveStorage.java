package com.crdp.framework.storage;

import com.crdp.framework.audit.AuditArchiveProperties;
import com.obs.services.IObsClient;
import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;
import com.obs.services.exception.ObsException;
import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.ObsObject;
import com.obs.services.model.PutObjectRequest;
import com.obs.services.model.objectlock.ObjectRetention;
import com.obs.services.model.objectlock.SetObjectRetentionRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 华为云 OBS 审计归档桶适配器（E22-T08 / PLT-AUD-010，ADR 0005）。替换 {@link UnsupportedAuditArchiveStorage}：
 * {@code crdp.audit.archive.storage.type=obs} 时装配（{@link AuditArchiveStorageConfiguration}），默认 none
 * 保持 E08-T03 的失败安全口径（无云凭证只做分区维护、不删在线数据）。
 *
 * <p>「归档对象在保留期内不可删改」（验收）在本适配器的形态：
 *
 * <ol>
 *   <li>单次 PUT（不用分段上传——WORM 只保护已合并对象），桶级 WORM 默认保留自对象创建即生效（桶级 WORM 由运维 开启，见
 *       docs/ops/audit-worm-archive.md，必须先开桶再启用本适配器）；
 *   <li>PUT 后立即设置对象级 COMPLIANCE 保留到 retainUntil（SDK {@link ObjectRetention}：epoch 毫秒，北京时间当日 00:00
 *       起算）——OBS 合规模式下保留期内任何账号（含桶拥有者）都不可删除或覆盖，保留期只能延长；
 *   <li>端口没有删除 / 覆盖语义（{@link AuditArchiveStorage} 无 delete 方法），调用方（AuditArchiver）重跑同键 时先 get
 *       按摘要校验，不重传。
 * </ol>
 *
 * <p>对象级保留设置失败（如桶未开 WORM）同样抛错 → 作业 FAILED、分区保留在线，不静默降级；重跑时对象已存在、
 * 摘要一致即不再重传（桶级默认保留已覆盖）。密钥只经环境变量注入；日志只记 OBS 错误码与 requestId。
 */
public class ObsAuditArchiveStorage implements AuditArchiveStorage {

    private static final Logger log = LoggerFactory.getLogger(ObsAuditArchiveStorage.class);

    /** OBS WORM 仅支持合规模式（COMPLIANCE）：保留期内任何人不可删改、保留期只能延长。 */
    private static final String COMPLIANCE = "COMPLIANCE";

    /** 业务日期按北京时间（AGENTS 第 6 节）；retainUntil 为归档日 + 15 年的当日 00:00。 */
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private final IObsClient client;

    private final String bucket;

    /** 生产装配：按配置创建 ObsClient；必填项缺失启动即失败（fail-fast，不等到第一轮归档作业）。 */
    @Autowired
    public ObsAuditArchiveStorage(AuditArchiveProperties properties) {
        this(
                newClient(properties.getStorage().getObs()),
                properties.getStorage().getObs().getBucket());
    }

    /** 测试 / 显式装配（client 由调用方给出，便于注入桩）。 */
    ObsAuditArchiveStorage(IObsClient client, AuditArchiveProperties properties) {
        this(client, properties.getStorage().getObs().getBucket());
    }

    private ObsAuditArchiveStorage(IObsClient client, String bucket) {
        require(bucket, "crdp.audit.archive.storage.obs.bucket");
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void putWithRetention(
            String key, byte[] body, String contentType, LocalDate retainUntil) {
        PutObjectRequest request = new PutObjectRequest();
        request.setBucketName(bucket);
        request.setObjectKey(key);
        request.setInput(new ByteArrayInputStream(body));
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(contentType);
        metadata.setContentLength((long) body.length);
        request.setMetadata(metadata);
        try {
            client.putObject(request);
        } catch (ObsException failure) {
            throw providerFailure("putObject", failure);
        }
        // 对象级保留（epoch 毫秒）：桶级默认保留之上钉死到确切截止日；只能延长、不能缩短（OBS 语义）
        ObjectRetention retention =
                new ObjectRetention(
                        COMPLIANCE, retainUntil.atStartOfDay(BEIJING).toInstant().toEpochMilli());
        try {
            client.setObjectRetention(new SetObjectRetentionRequest(bucket, key, retention));
        } catch (ObsException failure) {
            throw providerFailure("setObjectRetention", failure);
        }
    }

    @Override
    public byte[] get(String key) {
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
            log.warn("OBS 归档对象读取失败 keyLength={}", key.length(), failure);
            throw unavailable();
        } catch (ObsException failure) {
            throw providerFailure("getObject", failure);
        }
    }

    // ---------------------------------------------------------------- 私有

    private static IObsClient newClient(AuditArchiveProperties.Storage.Obs obs) {
        require(obs.getEndpoint(), "crdp.audit.archive.storage.obs.endpoint");
        require(obs.getAccessKey(), "crdp.audit.archive.storage.obs.access-key");
        require(obs.getSecretKey(), "crdp.audit.archive.storage.obs.secret-key");
        require(obs.getBucket(), "crdp.audit.archive.storage.obs.bucket");
        ObsConfiguration configuration = new ObsConfiguration();
        configuration.setEndPoint(obs.getEndpoint());
        configuration.setConnectionTimeout(obs.getConnectTimeoutMs());
        configuration.setSocketTimeout(obs.getSocketTimeoutMs());
        configuration.setHttpsOnly(true);
        return new ObsClient(obs.getAccessKey(), obs.getSecretKey(), configuration);
    }

    /** 服务商错误统一转运行时异常（归档作业计 FAILED、分区保留在线）：日志只记错误码与请求 ID，不抛明细。 */
    private static IllegalStateException providerFailure(String operation, ObsException failure) {
        log.warn(
                "OBS 审计归档桶 {} 失败 errorCode={} responseCode={} requestId={}",
                operation,
                failure.getErrorCode(),
                failure.getResponseCode(),
                failure.getErrorRequestId());
        return unavailable();
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("OBS 审计归档桶未配置：" + name + " 不能为空（storage.type=obs）");
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("审计归档桶访问失败（作业将计 FAILED，在线分区保留）");
    }
}
