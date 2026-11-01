package com.crdp.system.brand;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.crdp.system.infra.mapper.BrandMapper;
import com.crdp.system.infra.mapper.BrandRow;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E06-T11 / PLT-CFG-008：租户个性化维护（租户库 cfg_brand，权限 tenant:brand:edit）。
 *
 * <p>字段与规则（platform-spec「关键字段 · 租户个性化」）：显示名称必填（默认取医院名称， 由调用方以平台租户名称预填）；Logo 与登录页背景未配置用平台默认；
 * 主题色只能从系统预设色板选择（OQ-23：主色固定 #3A75FF， 租户只换 Logo、名称与预设辅助色）；不支持自定义域名与证书（不在本服务范围）。
 *
 * <p>保存即时生效（登录页每次经 /auth/tenant 现读）并写 BRAND_UPDATE 审计（PLT-CFG-011 同口径）。 素材对象存储在 E10 接入，此前存素材
 * URL（OQ-49 暂行）。
 */
@Service
public class BrandAdminService {

    private static final int NAME_MAX = 128;
    private static final int URL_MAX = 512;
    private static final int COPYRIGHT_MAX = 255;

    /** 素材地址放行口径：https/http 绝对地址（对象存储 / 图床），或站内绝对路径（/assets/…）。 不放行 data: 与 javascript:（防注入登录页）。 */
    private static final Pattern ASSET_URL = Pattern.compile("^(https?://[^\\s]+|/[^\\s]*)$");

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("Asia/Shanghai"));

    private final BrandMapper mapper;
    private final AuditLogger audit;

    public BrandAdminService(BrandMapper mapper, AuditLogger audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 视图

    /** 维护视角视图：defaultName 是平台租户名称（显示名称回落值），effectiveName 是实际生效名称。 */
    public record BrandView(
            String displayName,
            String defaultName,
            String logoUrl,
            String loginBgUrl,
            String themeColor,
            String themeColorHex,
            String copyright,
            String updatedAt) {}

    /** 保存请求。displayName 必填；其余字段 null / 空白 = 清空（回落平台默认）。 */
    public record BrandSave(
            String displayName,
            String logoUrl,
            String loginBgUrl,
            String themeColor,
            String copyright) {}

    /** 可选预设色（前端色板）。 */
    public List<ThemeColorOption> themeColors() {
        List<ThemeColorOption> options = new ArrayList<>();
        for (BrandThemeColor color : BrandThemeColor.values()) {
            options.add(new ThemeColorOption(color.key(), color.hex(), color.label()));
        }
        return options;
    }

    public record ThemeColorOption(String key, String hex, String label) {}

    /** 读当前配置。defaultName 由调用方传平台租户名称（服务层不跨库）。 */
    public BrandView view(String defaultName) {
        BrandRow row = mapper.find();
        String displayName = row == null ? null : trimToNull(row.getDisplayName());
        BrandThemeColor color =
                row == null
                        ? BrandThemeColor.defaults()
                        : BrandThemeColor.ofKey(row.getThemeColor())
                                .orElse(BrandThemeColor.defaults());
        return new BrandView(
                displayName,
                defaultName,
                row == null ? null : trimToNull(row.getLogoUrl()),
                row == null ? null : trimToNull(row.getLoginBgUrl()),
                color.key(),
                color.hex(),
                row == null ? null : trimToNull(row.getCopyright()),
                row == null ? null : formatTime(row.getUpdatedAt()));
    }

    /** 保存（全量覆盖）。校验失败 40001，主题色不在预设色板 40001。 */
    @Transactional
    public BrandView save(BrandSave save, String defaultName) {
        if (save == null) {
            throw bad("请求体不能为空");
        }
        String displayName = trimToNull(save.displayName());
        if (displayName == null) {
            throw bad("显示名称不能为空");
        }
        if (displayName.length() > NAME_MAX) {
            throw bad("显示名称不能超过 " + NAME_MAX + " 个字符");
        }
        String themeKey = trimToNull(save.themeColor());
        if (themeKey == null) {
            themeKey = BrandThemeColor.defaults().key();
        } else if (BrandThemeColor.ofKey(themeKey).isEmpty()) {
            throw bad("主题色只能从系统预设色板中选择");
        }
        String logoUrl = assetUrl(save.logoUrl(), "Logo 地址");
        String loginBgUrl = assetUrl(save.loginBgUrl(), "登录页背景地址");
        String copyright = trimToNull(save.copyright());
        if (copyright != null && copyright.length() > COPYRIGHT_MAX) {
            throw bad("版权与备案信息不能超过 " + COPYRIGHT_MAX + " 个字符");
        }

        BrandRow row = new BrandRow();
        row.setDisplayName(displayName);
        row.setLogoUrl(logoUrl);
        row.setLoginBgUrl(loginBgUrl);
        row.setThemeColor(themeKey);
        row.setCopyright(copyright);
        row.setUpdatedBy(
                CurrentSession.current().map(principal -> principal.userId()).orElse(null));
        int changed = mapper.update(row);
        if (changed == 0) {
            mapper.insert(row);
        }
        audit.record(
                new AuditEvent(
                        "BRAND_UPDATE",
                        row.getUpdatedBy(),
                        "UPDATE",
                        "cfg_brand:1",
                        "租户个性化已更新：显示名称=" + displayName + "，主题色=" + themeKey));
        return view(defaultName);
    }

    // ---------------------------------------------------------------- 私有

    private static String assetUrl(String raw, String field) {
        String url = trimToNull(raw);
        if (url == null) {
            return null;
        }
        if (url.length() > URL_MAX) {
            throw bad(field + "不能超过 " + URL_MAX + " 个字符");
        }
        if (!ASSET_URL.matcher(url).matches()) {
            throw bad(field + "必须是 http(s) 地址或站内路径");
        }
        return url;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

    /** 时间展示口径（北京时间 ISO-8601 带时区），供视图组装。 */
    static String formatTime(LocalDateTime time) {
        return time == null ? null : TIME.format(time.atZone(ZoneId.systemDefault()).toInstant());
    }
}
