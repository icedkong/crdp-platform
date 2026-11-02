package com.crdp.framework.storage;

import com.crdp.framework.tenant.TenantContext;

/**
 * 对象键的租户前缀与安全校验（E10-T02 / C-NFR-003、C-NFR-005；ADR 0006）。
 *
 * <p>前缀只来自 {@link TenantContext}（登录令牌解析，不信任请求参数），相对键与租户编码先过校验再拼接： 非空、总长上限、不以 {@code /}
 * 开头、不含反斜杠与控制字符、无 {@code ..} / {@code .} 路径段——把路径穿越挡在拼键之前。 校验失败是调用方缺陷，直接 {@link
 * IllegalArgumentException}（不吞、不降级）。
 */
public final class StorageKeys {

    /** 平台上下文（非医院租户）的对象前缀。 */
    public static final String PLATFORM_PREFIX = "platform/";

    /** 租户前缀模板：t/{租户编码}/（tech-plan §2 修订：一桶、每院一前缀）。 */
    private static final String TENANT_PREFIX_FORMAT = "t/%s/";

    /** 完整键长度上限（OBS 对象键上限 1024 字节，按字符数收紧在 UTF-8 边界内）。 */
    public static final int MAX_KEY_LENGTH = 900;

    /** 租户编码长度上限（plt_tenant.code 列宽之内）。 */
    private static final int MAX_TENANT_CODE = 32;

    private StorageKeys() {}

    /** 把相对键解析为完整对象键；租户来自当前上下文，没有上下文与数据库路由同一口径直接拒绝。 */
    public static String scopedKey(String relativeKey) {
        validateRelativeKey(relativeKey);
        TenantContext.State state = TenantContext.require();
        if (state.platform()) {
            return PLATFORM_PREFIX + relativeKey;
        }
        String tenantCode = state.tenantCode();
        validateTenantCode(tenantCode);
        return TENANT_PREFIX_FORMAT.formatted(tenantCode) + relativeKey;
    }

    /** 相对键校验：见类注释；另外做总长兜底（拼接前无法预知前缀，按最坏 35 字符预留）。 */
    static void validateRelativeKey(String relativeKey) {
        requireText(relativeKey, "相对键不能为空");
        if (relativeKey.length() + 35 > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("相对键过长：" + relativeKey.length() + " 字符");
        }
        if (relativeKey.startsWith("/")) {
            throw new IllegalArgumentException("相对键不能以 / 开头（前缀由租户上下文决定）");
        }
        rejectForbidden(relativeKey, "相对键");
    }

    /** 租户编码校验：来自服务端会话，仍防御分隔符与穿越段（不给前缀注入留口）。 */
    static void validateTenantCode(String tenantCode) {
        requireText(tenantCode, "租户编码不能为空");
        if (tenantCode.length() > MAX_TENANT_CODE) {
            throw new IllegalArgumentException("租户编码过长：" + tenantCode.length() + " 字符");
        }
        if (tenantCode.indexOf('/') >= 0) {
            // 租户编码是前缀的一段，不允许再含层级分隔符（也不允许借此多占一层前缀）
            throw new IllegalArgumentException("租户编码不允许包含 /");
        }
        rejectForbidden(tenantCode, "租户编码");
    }

    /** 通用校验：反斜杠、控制字符、{@code ..} / {@code .} 路径段全部拒绝。 */
    private static void rejectForbidden(String value, String what) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '\\' || value.charAt(i) < 0x20) {
                throw new IllegalArgumentException(what + "含非法字符");
            }
        }
        for (String segment : value.split("/", -1)) {
            if ("..".equals(segment) || ".".equals(segment)) {
                throw new IllegalArgumentException(what + "不允许 .. 或 . 路径段");
            }
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
