package com.crdp.framework.crypto;

import com.crdp.framework.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

/** SHA-256 摘要实现（NFR-CRY-001 首期算法；审计哈希链 PLT-AUD-008 使用）。 */
@Component
public class Sha256DigestService implements DigestService {

    @Override
    public String hashHex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // JVM 规范要求提供 SHA-256，理论不可达
            throw new ApiException(500, 50000, "系统繁忙，请稍后再试");
        }
    }
}
