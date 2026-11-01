package com.crdp.server.auth;

import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.tenant.TenantRecord;
import com.crdp.system.brand.BrandThemeColor;
import com.crdp.system.infra.mapper.BrandRow;
import com.crdp.system.user.HospitalDirectory;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * E06-T11 / PLT-CFG-008、PLT-AUTH-004：登录页租户识别结果——该院显示名称、Logo、 登录页背景、预设主题色与版权信息（docs/api/auth.md §2
 * 的推断接口按本实现定稿）。 租户不存在 / 未启用时返回通用默认值，不暴露存在性（防探测，与 /auth/sms 同口径）。
 *
 * <p>公开接口必须限流（AGENTS.md 第 6 节）：按 IP 每小时限流（Redis 计数， 与 CaptchaStore 同款实现）；租户识别本身走
 * TenantLookupCache（60 秒 TTL，正负结果都缓存），单次未命中才落库。
 */
@Service
public class LoginBrandService {

    private static final Logger log = LoggerFactory.getLogger(LoginBrandService.class);

    /** 未识别出租户时的通用默认（不暴露存在性；前端据此渲染平台默认登录页）。 */
    private static final String DEFAULT_PLATFORM_TITLE = "临床科研数据一体化平台";

    private final TenantLookupCache tenantLookup;
    private final HospitalDirectory hospitalDirectory;
    private final StringRedisTemplate redis;
    private final SecurityProperties properties;

    public LoginBrandService(
            TenantLookupCache tenantLookup,
            HospitalDirectory hospitalDirectory,
            StringRedisTemplate redis,
            SecurityProperties properties) {
        this.tenantLookup = tenantLookup;
        this.hospitalDirectory = hospitalDirectory;
        this.redis = redis;
        this.properties = properties;
    }

    /** 登录页品牌视图。loginMethods 为该院可用登录方式（当前：账号密码 + 短信验证码）。 */
    public record TenantBrandView(
            String tenantName,
            String logoUrl,
            String loginBgUrl,
            String themeColor,
            String themeColorHex,
            String copyright,
            boolean identified,
            List<String> loginMethods) {}

    private record BrandSnapshot(
            String displayName,
            String logoUrl,
            String loginBgUrl,
            String themeColor,
            String copyright) {}

    private BrandSnapshot emptyBrand() {
        return new BrandSnapshot(null, null, null, null, null);
    }

    /** 按租户编码取登录页品牌；clientIp 为 null 表示内部调用不限流。 */
    public TenantBrandView brandOf(String tenantCode, String clientIp) {
        rateLimit(clientIp);
        TenantRecord tenant =
                tenantCode == null ? null : tenantLookup.findByCode(tenantCode).orElse(null);
        if (tenant == null || !tenant.routable()) {
            // 未知 / 停用租户：与已识别租户同一响应结构，只有通用默认值
            log.info(
                    "login brand defaulted reason={}",
                    tenant == null ? "unknown-tenant" : "inactive-tenant");
            BrandThemeColor color = BrandThemeColor.defaults();
            return new TenantBrandView(
                    DEFAULT_PLATFORM_TITLE,
                    null,
                    null,
                    color.key(),
                    color.hex(),
                    null,
                    false,
                    List.of("PASSWORD", "SMS"));
        }
        BrandSnapshot brand;
        try {
            brand = TenantContext.callAsTenant(tenant.id(), tenant.code(), this::readBrand);
        } catch (RuntimeException ex) {
            // 租户库暂不可达（如开通中）：品牌回落平台默认，登录页仍可用，不让品牌数据源拖垮登录入口
            log.warn(
                    "login brand read failed tenant={} type={}",
                    tenant.code(),
                    ex.getClass().getSimpleName());
            brand = emptyBrand();
        }
        BrandThemeColor color =
                BrandThemeColor.ofKey(brand.themeColor()).orElse(BrandThemeColor.defaults());
        return new TenantBrandView(
                brand.displayName() == null ? tenant.name() : brand.displayName(),
                brand.logoUrl(),
                brand.loginBgUrl(),
                color.key(),
                color.hex(),
                brand.copyright(),
                true,
                List.of("PASSWORD", "SMS"));
    }

    private BrandSnapshot readBrand() {
        // cfg_brand 未配置（无行）时各字段回落平台默认
        BrandRow row = hospitalDirectory.brand();
        if (row == null) {
            return emptyBrand();
        }
        return new BrandSnapshot(
                row.getDisplayName(),
                row.getLogoUrl(),
                row.getLoginBgUrl(),
                row.getThemeColor(),
                row.getCopyright());
    }

    private void rateLimit(String clientIp) {
        if (clientIp == null) {
            return;
        }
        String key = "crdp:brand-lookup:ip:" + clientIp;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofHours(1));
        }
        if (count != null && count > properties.getTenantBrandIpHourlyLimit()) {
            throw new ApiException(429, 42900, "尝试次数过多，请稍后再试");
        }
    }
}
