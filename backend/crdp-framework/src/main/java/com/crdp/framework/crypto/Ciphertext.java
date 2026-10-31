package com.crdp.framework.crypto;

import com.crdp.framework.web.ApiException;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 密文的自描述格式：{@code v{格式版本}.{算法 ID}.{密钥版本}.{用途}.{Base64 IV}.{Base64 密文与认证标签}}。 解析只做语法校验；算法与密钥版本是否被支持由
 * {@link CryptoService} 实现按密文里的值分派，因此新旧 算法、新旧密钥版本可以并存（技术方案第 4 章：解密按密文中的算法 ID 选择实现）。
 *
 * @param version 格式版本，目前只有 1
 * @param algorithm 算法 ID，如 AES-256-GCM
 * @param keyVersion 写入时使用的密钥版本，轮换后旧版本保留用于解密（E03-T05）
 * @param purpose 加密用途，决定使用哪把数据密钥
 * @param iv 初始向量
 * @param data 密文与认证标签
 */
public record Ciphertext(
        int version, String algorithm, int keyVersion, String purpose, byte[] iv, byte[] data) {

    private static final Pattern ALGORITHM = Pattern.compile("^[A-Za-z0-9-]{1,32}$");
    private static final Pattern PURPOSE = Pattern.compile("^[a-z0-9_-]{1,32}$");
    private static final Pattern KEY_VERSION = Pattern.compile("^[1-9][0-9]*$");

    public static Ciphertext parse(String value) {
        if (value == null || value.isBlank()) {
            throw malformed("密文为空");
        }
        String[] parts = value.split("\\.", 6);
        if (parts.length != 6) {
            throw malformed("密文格式无法识别");
        }
        int version = parseVersion(parts[0]);
        if (!ALGORITHM.matcher(parts[1]).matches()) {
            throw malformed("密文算法标识不合法");
        }
        if (!KEY_VERSION.matcher(parts[2]).matches()) {
            throw malformed("密文密钥版本不合法");
        }
        if (!PURPOSE.matcher(parts[3]).matches()) {
            throw malformed("密文用途不合法");
        }
        return new Ciphertext(
                version,
                parts[1],
                Integer.parseInt(parts[2]),
                parts[3],
                decode(parts[4], "IV"),
                decode(parts[5], "密文"));
    }

    public String encode() {
        Base64.Encoder encoder = Base64.getEncoder();
        return "v"
                + version
                + "."
                + algorithm
                + "."
                + keyVersion
                + "."
                + purpose
                + "."
                + encoder.encodeToString(iv)
                + "."
                + encoder.encodeToString(data);
    }

    private static int parseVersion(String part) {
        if (!part.startsWith("v") || !part.matches("v[0-9]+")) {
            throw malformed("密文格式无法识别");
        }
        int version = Integer.parseInt(part.substring(1));
        if (version != 1) {
            // 未来格式版本写入的数据：明确报错，绝不拿当前实现硬解
            throw malformed("密文版本无法识别");
        }
        return version;
    }

    private static byte[] decode(String part, String field) {
        try {
            return Base64.getDecoder().decode(part);
        } catch (IllegalArgumentException ex) {
            throw malformed("密文" + field + "不是合法 Base64");
        }
    }

    private static ApiException malformed(String message) {
        return new ApiException(400, 40002, message);
    }
}
