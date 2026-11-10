package com.crdp.framework.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * local / demo 主密钥的来源：优先用配置或环境变量里的 {@code crdp.crypto.local-key}；为空时在本机文件里生成一把并沿用。
 * 生成结果只用于虚构数据演示。密钥材料不写日志。
 */
public final class LocalKeyMaterial {

    private static final Logger log = LoggerFactory.getLogger(LocalKeyMaterial.class);

    private LocalKeyMaterial() {}

    /**
     * @param configured 已配置的主密钥口令，空白视为没有
     * @param file 未配置时读写的密钥文件
     * @return 非空口令
     */
    public static String resolve(String configured, Path file) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        try {
            if (Files.isRegularFile(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty()) {
                    log.warn(
                            "crdp.crypto.local-key 未配置，沿用已有本地密钥文件 {}。仅用于虚构数据演示，接入华为云 KMS 前禁止导入真实患者数据。",
                            file.toAbsolutePath());
                    return existing;
                }
            }
            String generated = generate();
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, generated + System.lineSeparator(), StandardCharsets.UTF_8);
            log.warn(
                    "已生成本地演示主密钥并写入 {}。请妥善保留该文件或改用环境变量 CRDP_CRYPTO_LOCAL_KEY；丢失后已有密文无法解开。仅用于虚构数据，接入华为云 KMS 前禁止导入真实患者数据。",
                    file.toAbsolutePath());
            return generated;
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "crdp.crypto.local-key 未配置，且无法读写 "
                            + file.toAbsolutePath()
                            + "。请设置环境变量 CRDP_CRYPTO_LOCAL_KEY。",
                    ex);
        }
    }

    private static String generate() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
