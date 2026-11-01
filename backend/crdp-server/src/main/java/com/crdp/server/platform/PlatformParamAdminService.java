package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformConfigMapper;
import com.crdp.system.param.ParamValueValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E06-T10 / PLT-CFG-003：系统参数管理（平台库 plt_param，权限 platform:param:edit）。
 *
 * <p>平台级参数：参数键全局唯一、创建后不可改；值类型 文本 / 数值 / 布尔 / 枚举 / JSON；层级 平台锁定 / 租户可覆盖；取值范围（可覆盖时必填，BOOLEAN / JSON
 * 除外）租户值不得超出（platform-spec §10「关键字字段 · 系统参数」）。保存写 PARAM_CREATE / PARAM_UPDATE
 * 平台审计并作废定义缓存（PLT-CFG-011 即时生效）。
 *
 * <p>参数改为「平台锁定」时不回写租户库——租户已覆盖的值即时失效（TenantParamService 计算生效值时回落平台 值并提示），覆盖行保留，平台再次放开时自动恢复生效。
 */
@Service
public class PlatformParamAdminService {

    private static final Pattern PARAM_KEY = Pattern.compile("^[a-z][a-z0-9.]{2,63}$");

    private static final int NAME_MAX = 128;
    private static final int DESC_MAX = 255;

    private final PlatformConfigMapper mapper;
    private final PlatformConfigCache cache;
    private final AuditLogger audit;

    public PlatformParamAdminService(
            PlatformConfigMapper mapper, PlatformConfigCache cache, AuditLogger audit) {
        this.mapper = mapper;
        this.cache = cache;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 视图

    /** 参数视图（按参数键排序）。 */
    public record ParamView(
            String paramKey,
            String name,
            String description,
            String valueType,
            String platformValue,
            boolean tenantOverridable,
            String valueRange,
            String updatedAt) {}

    /** 新建参数：全部字段必填（说明可空）。 */
    public record ParamSave(
            String paramKey,
            String name,
            String description,
            String valueType,
            String platformValue,
            Boolean tenantOverridable,
            String valueRange) {}

    /** 维护参数（参数键不可改，路径定位）。 */
    public record ParamUpdate(
            String name,
            String description,
            String valueType,
            String platformValue,
            Boolean tenantOverridable,
            String valueRange) {}

    // ---------------------------------------------------------------- 读取

    public List<ParamView> list() {
        return TenantContext.callAsPlatform(
                () -> {
                    List<ParamView> views = new ArrayList<>();
                    for (PlatformConfigMapper.ParamRow row : mapper.listParams()) {
                        views.add(viewOf(row));
                    }
                    return views;
                });
    }

    // ---------------------------------------------------------------- 维护

    /** 新建系统参数：参数键格式与唯一性（40902）、值类型与范围合法、平台默认值过范围校验。 */
    @Transactional
    public ParamView create(ParamSave save) {
        if (save == null) {
            throw bad("请求体不能为空");
        }
        String paramKey = trim(save.paramKey());
        if (paramKey == null || !PARAM_KEY.matcher(paramKey).matches()) {
            throw bad("参数键必须是小写字母开头的 3–64 位小写字母、数字或点（如 tenant.db.pool.max）");
        }
        if (save.tenantOverridable() == null) {
            throw bad("请选择参数层级（平台锁定 / 租户可覆盖）");
        }
        boolean overridable = save.tenantOverridable();
        String valueType = valueTypeOf(save.valueType());
        String valueRange = rangeOf(valueType, save.valueRange(), overridable);
        String platformValue = platformValueOf(valueType, valueRange, save.platformValue());
        String name = requireName(save.name());
        String description = optionalLength(save.description(), DESC_MAX, "说明");
        return TenantContext.callAsPlatform(
                () -> {
                    if (mapper.findParam(paramKey) != null) {
                        throw new ApiException(409, 40902, "参数键已存在");
                    }
                    PlatformConfigMapper.ParamRow row = new PlatformConfigMapper.ParamRow();
                    row.setParamKey(paramKey);
                    row.setName(name);
                    row.setDescription(description);
                    row.setValueType(valueType);
                    row.setPlatformValue(platformValue);
                    row.setTenantOverridable(overridable);
                    row.setValueRange(valueRange);
                    row.setUpdatedBy(operator());
                    mapper.insertParam(row);
                    audit.record(
                            new AuditEvent(
                                    "PARAM_CREATE",
                                    row.getUpdatedBy(),
                                    "CREATE",
                                    "plt_param:" + paramKey,
                                    "系统参数已创建："
                                            + paramKey
                                            + "="
                                            + platformValue
                                            + "，层级="
                                            + (overridable ? "租户可覆盖" : "平台锁定")
                                            + "（即时生效：全部租户）"));
                    cache.evict();
                    return viewOf(mapper.findParam(paramKey));
                });
    }

    /** 维护系统参数：名称 / 说明 / 值类型 / 平台值 / 层级 / 范围；参数键不可改。 */
    @Transactional
    public ParamView update(String paramKey, ParamUpdate update) {
        if (update == null) {
            throw bad("请求体不能为空");
        }
        if (update.tenantOverridable() == null) {
            throw bad("请选择参数层级（平台锁定 / 租户可覆盖）");
        }
        boolean overridable = update.tenantOverridable();
        String valueType = valueTypeOf(update.valueType());
        String valueRange = rangeOf(valueType, update.valueRange(), overridable);
        String platformValue = platformValueOf(valueType, valueRange, update.platformValue());
        String name = requireName(update.name());
        String description = optionalLength(update.description(), DESC_MAX, "说明");
        return TenantContext.callAsPlatform(
                () -> {
                    PlatformConfigMapper.ParamRow row = mapper.findParam(paramKey);
                    if (row == null) {
                        throw new ApiException(404, 40400, "参数不存在");
                    }
                    boolean lockedNow = !overridable && row.isTenantOverridable();
                    row.setName(name);
                    row.setDescription(description);
                    row.setValueType(valueType);
                    row.setPlatformValue(platformValue);
                    row.setTenantOverridable(overridable);
                    row.setValueRange(valueRange);
                    row.setUpdatedBy(operator());
                    mapper.updateParam(row);
                    String note =
                            lockedNow ? "；已改为平台锁定：租户已覆盖的值即时失效并提示（覆盖行保留，重新放开自动恢复）" : "（即时生效：全部租户）";
                    audit.record(
                            new AuditEvent(
                                    "PARAM_UPDATE",
                                    row.getUpdatedBy(),
                                    "UPDATE",
                                    "plt_param:" + paramKey,
                                    "系统参数已更新："
                                            + paramKey
                                            + "="
                                            + platformValue
                                            + "，层级="
                                            + (overridable ? "租户可覆盖" : "平台锁定")
                                            + note));
                    cache.evict();
                    return viewOf(mapper.findParam(paramKey));
                });
    }

    // ---------------------------------------------------------------- 私有

    private static ParamView viewOf(PlatformConfigMapper.ParamRow row) {
        return row == null
                ? null
                : new ParamView(
                        row.getParamKey(),
                        row.getName(),
                        row.getDescription(),
                        row.getValueType(),
                        row.getPlatformValue(),
                        row.isTenantOverridable(),
                        row.getValueRange(),
                        PlatformDictAdminService.formatTime(row.getUpdatedAt()));
    }

    private static String valueTypeOf(String valueType) {
        if (valueType == null || !ParamValueValidator.VALUE_TYPES.contains(valueType)) {
            throw bad("值类型必须是 TEXT / NUMBER / BOOLEAN / ENUM / JSON");
        }
        return valueType;
    }

    private static String rangeOf(String valueType, String valueRange, boolean overridable) {
        String range = trim(valueRange);
        String error = ParamValueValidator.rangeError(valueType, range, overridable);
        if (error != null) {
            throw bad(error);
        }
        return range;
    }

    private static String platformValueOf(String valueType, String valueRange, String raw) {
        String value = trim(raw);
        if (value == null) {
            throw bad("平台默认值不能为空");
        }
        String error = ParamValueValidator.valueError(valueType, valueRange, value);
        if (error != null) {
            throw bad("平台默认值：" + error);
        }
        return value;
    }

    private static Long operator() {
        return CurrentSession.current().map(principal -> principal.userId()).orElse(null);
    }

    private static String requireName(String raw) {
        String name = trim(raw);
        if (name == null) {
            throw bad("名称不能为空");
        }
        if (name.length() > NAME_MAX) {
            throw bad("名称不能超过 " + NAME_MAX + " 个字符");
        }
        return name;
    }

    private static String optionalLength(String raw, int max, String field) {
        String value = trim(raw);
        if (value != null && value.length() > max) {
            throw bad(field + "不能超过 " + max + " 个字符");
        }
        return value;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }
}
