package com.crdp.framework.crypto;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 按租户解析数据密钥（ADR 0004）。租户库里只存被主密钥包装后的密钥（crypto_data_key），明文解开后仅 放内存缓存（键：租户:用途:版本）。首次使用某用途时惰性生成版本
 * 1；唯一键冲突说明并发竞争，回退到 库里已存在的那一行。轮换产生的新版本（E03-T05）同样走这张表，旧版本保留解密。
 */
public class TenantDataKeyService {

    private final KeyProvider keyProvider;
    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentHashMap<String, SecretKey> cache = new ConcurrentHashMap<>();

    public TenantDataKeyService(KeyProvider keyProvider, JdbcTemplate jdbcTemplate) {
        this.keyProvider = keyProvider;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 该用途当前应使用的密钥版本；库中还没有时生成版本 1。必须在租户上下文内调用。 */
    public int currentVersion(String purpose) {
        TenantContext.require();
        Integer max =
                jdbcTemplate.queryForObject(
                        "SELECT MAX(key_version) FROM crypto_data_key WHERE purpose = ?",
                        Integer.class,
                        purpose);
        if (max != null) {
            return max;
        }
        createVersion(purpose, 1);
        return 1;
    }

    /** 取指定用途与版本的数据密钥，优先命中内存缓存。必须在租户上下文内调用。 */
    public SecretKey keyFor(String purpose, int keyVersion) {
        String cacheKey = cacheKey(TenantContext.require(), purpose, keyVersion);
        return cache.computeIfAbsent(cacheKey, ignored -> load(purpose, keyVersion));
    }

    private SecretKey load(String purpose, int keyVersion) {
        // 用 queryForList 区分"无行"：queryForObject 在 0 行时抛异常而不是返回 null
        List<String> rows =
                jdbcTemplate.queryForList(
                        "SELECT wrapped_key FROM crypto_data_key"
                                + " WHERE purpose = ? AND key_version = ?",
                        String.class,
                        purpose,
                        keyVersion);
        if (rows.isEmpty()) {
            throw new ApiException(400, 40002, "数据密钥不存在: " + purpose + " v" + keyVersion);
        }
        return keyProvider.unwrap(rows.get(0));
    }

    private void createVersion(String purpose, int keyVersion) {
        GeneratedDataKey generated = keyProvider.generateDataKey();
        boolean inserted = false;
        try {
            jdbcTemplate.update(
                    "INSERT INTO crypto_data_key (purpose, key_version, wrapped_key) VALUES (?, ?, ?)",
                    purpose,
                    keyVersion,
                    generated.wrappedKey());
            inserted = true;
        } catch (DuplicateKeyException ex) {
            // 并发竞争：另一请求已建好同一版本，改用库里的那一行
        }
        SecretKey effective = inserted ? generated.key() : load(purpose, keyVersion);
        cache.put(cacheKey(TenantContext.require(), purpose, keyVersion), effective);
    }

    private static String cacheKey(TenantContext.State tenant, String purpose, int keyVersion) {
        return tenant.tenantId() + ":" + purpose + ":" + keyVersion;
    }
}
