package com.crdp.framework.storage;

import com.crdp.framework.web.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地文件系统替身（E10-T02 / ADR 0006：本机 / CI 无云凭证时的 {@code crdp.storage.type=local}）。
 *
 * <p>目录结构与 OBS 一致（根目录当作桶，对象按 {@code t/{租户}/…} 落盘），前缀隔离语义与生产相同； **不模拟 SSE 加密**——服务端加密是 OBS
 * 的能力，替身只替「桶」不替「加密」（本地无 KMS）。生产换 {@link ObsObjectStorage} 后接口与键口径不变。
 */
public class LocalObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalObjectStorage.class);

    private final Path root;

    /** 生产装配：根目录来自配置（默认 data/storage）。 */
    public LocalObjectStorage(ObjectStorageProperties properties) {
        this(Path.of(properties.getLocal().getRoot()));
    }

    /** 测试 / 显式装配。 */
    public LocalObjectStorage(Path root) {
        this.root = root.normalize();
    }

    @Override
    public String put(String relativeKey, byte[] body, String contentType) {
        String key = StorageKeys.scopedKey(relativeKey);
        Path file = resolve(key);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, body);
        } catch (IOException failure) {
            log.warn("本地对象写入失败 keyLength={} bytes={}", key.length(), body.length, failure);
            throw unavailable();
        }
        return key;
    }

    @Override
    public byte[] get(String relativeKey) {
        String key = StorageKeys.scopedKey(relativeKey);
        Path file = resolve(key);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException failure) {
            log.warn("本地对象读取失败 keyLength={}", key.length(), failure);
            throw unavailable();
        }
    }

    @Override
    public void delete(String relativeKey) {
        String key = StorageKeys.scopedKey(relativeKey);
        Path file = resolve(key);
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            log.warn("本地对象删除失败 keyLength={}", key.length(), failure);
            throw unavailable();
        }
    }

    /** 键已在 {@link StorageKeys} 过穿越校验；normalize 后再校验根目录是双保险（防未来校验松动）。 */
    private Path resolve(String key) {
        Path file = root.resolve(key).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("对象键越界（不在桶内）");
        }
        return file;
    }

    private static ApiException unavailable() {
        return new ApiException(500, 50000, "系统繁忙，请稍后再试");
    }
}
