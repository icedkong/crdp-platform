package com.crdp.system.param;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.crdp.system.config.PlatformConfigPort;
import com.crdp.system.infra.mapper.ConfigMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E06-T10 / PLT-CFG-004、011：租户参数覆盖（租户库 cfg_param，权限 tenant:param:edit）。
 *
 * <p>平台定义、租户在允许范围内覆盖（platform-spec §10）：租户值不得超出平台参数的取值范围； 层级为「平台 锁定」的参数不可覆盖；覆盖与恢复都写 {@code
 * cfg_param_log} 变更记录（验收：租户覆盖平台默认值并留记录）与 租户审计。生效值＝有效覆盖值，否则平台默认值。
 *
 * <p>平台参数被改为锁定后，本院已覆盖的值立即失效（effectiveValue 回落平台值，stale=true 提示）， 覆盖行保留 ——平台再次放开覆盖时自动恢复生效（不丢租户配置）。
 */
@Service
public class TenantParamService {

    /** 变更记录读取上限（界面「最近变更」够用；完整历史走审计中心）。 */
    private static final int LOG_LIMIT = 100;

    private static final int REASON_MAX = 200;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("Asia/Shanghai"));

    private final PlatformConfigPort platformConfig;
    private final ConfigMapper mapper;
    private final AuditLogger audit;

    public TenantParamService(
            PlatformConfigPort platformConfig, ConfigMapper mapper, AuditLogger audit) {
        this.platformConfig = platformConfig;
        this.mapper = mapper;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 视图

    /** 参数视图（PLT-CFG-004）：合并平台定义与本院覆盖。 */
    public record ParamView(
            String paramKey,
            String name,
            String description,
            String valueType,
            String platformValue,
            boolean tenantOverridable,
            String valueRange,
            /** 本院是否设置过覆盖（含锁定后暂失效的覆盖）。 */
            boolean overridden,
            /** 本院覆盖值（未覆盖为 null）。 */
            String tenantValue,
            /** 生效值：有效覆盖值，否则平台默认值。 */
            String effectiveValue,
            /** 覆盖值当前是否失效（参数被平台锁定；spec §10：锁定后已覆盖的值失效并提示）。 */
            boolean stale,
            String updatedAt) {}

    /** 覆盖 / 恢复请求。reason 为变更说明（写记录与审计）。 */
    public record OverrideRequest(String value, String reason) {}

    /** 覆盖变更记录行（cfg_param_log，倒序）。 */
    public record ParamLogView(
            String paramKey,
            String action,
            String oldValue,
            String newValue,
            String reason,
            Long operatorId,
            String createdAt) {}

    // ---------------------------------------------------------------- 读取

    /** 全部系统参数（平台定义 + 本院覆盖状态），按参数键排序。 */
    public List<ParamView> list() {
        Map<String, ConfigMapper.ParamRow> overrides = new HashMap<>();
        for (ConfigMapper.ParamRow row : mapper.listParamOverrides()) {
            overrides.put(row.getParamKey(), row);
        }
        List<ParamView> views = new ArrayList<>();
        for (PlatformConfigPort.PlatformParamDef def : platformConfig.params()) {
            views.add(assemble(def, overrides.get(def.paramKey())));
        }
        views.sort(java.util.Comparator.comparing(ParamView::paramKey));
        return views;
    }

    // ---------------------------------------------------------------- 覆盖与恢复

    /**
     * 覆盖平台默认值（PLT-CFG-004）。未知参数 40400；平台锁定 40001；取值超出范围 / 类型不符 40001。 成功后写 cfg_param_log（OVERRIDE）与
     * PARAM_OVERRIDE 审计（PLT-CFG-011）。
     */
    @Transactional
    public ParamView override(String paramKey, OverrideRequest request) {
        PlatformConfigPort.PlatformParamDef def = platformConfig.param(paramKey).orElse(null);
        if (def == null) {
            throw new ApiException(404, 40400, "参数不存在");
        }
        if (request == null || request.value() == null) {
            throw bad("取值不能为空");
        }
        if (!def.tenantOverridable()) {
            throw bad("该参数为平台锁定，不允许租户覆盖");
        }
        String valueError =
                ParamValueValidator.valueError(def.valueType(), def.valueRange(), request.value());
        if (valueError != null) {
            throw bad(valueError);
        }
        String reason = reasonOf(request.reason());

        String value = request.value().trim();
        Long operator = CurrentSession.current().map(principal -> principal.userId()).orElse(null);
        ConfigMapper.ParamRow existing = mapper.findParam(paramKey);
        String oldValue = existing == null ? null : existing.getParamValue();
        if (existing == null) {
            ConfigMapper.ParamRow row = new ConfigMapper.ParamRow();
            row.setParamKey(paramKey);
            row.setParamValue(value);
            row.setRemark(reason);
            row.setUpdatedBy(operator);
            mapper.insertParam(row);
        } else {
            existing.setParamValue(value);
            existing.setRemark(reason);
            existing.setUpdatedBy(operator);
            mapper.updateParam(existing);
        }
        insertLog(paramKey, "OVERRIDE", oldValue, value, reason, operator);
        audit.record(
                new AuditEvent(
                        "PARAM_OVERRIDE",
                        operator,
                        "UPDATE",
                        "cfg_param:" + paramKey,
                        "租户参数已覆盖："
                                + paramKey
                                + "="
                                + value
                                + (reason == null ? "" : "（" + reason + "）")));
        return assemble(def, mapper.findParam(paramKey));
    }

    /** 恢复平台默认值：删除本院覆盖行。未覆盖时幂等返回当前视图（不写记录）。 有覆盖时写 cfg_param_log（RESET）与 PARAM_RESET 审计。 */
    @Transactional
    public ParamView reset(String paramKey) {
        PlatformConfigPort.PlatformParamDef def = platformConfig.param(paramKey).orElse(null);
        if (def == null) {
            throw new ApiException(404, 40400, "参数不存在");
        }
        ConfigMapper.ParamRow existing = mapper.findParam(paramKey);
        if (existing != null) {
            Long operator =
                    CurrentSession.current().map(principal -> principal.userId()).orElse(null);
            mapper.deleteParam(paramKey);
            insertLog(
                    paramKey,
                    "RESET",
                    existing.getParamValue(),
                    null,
                    existing.getRemark(),
                    operator);
            audit.record(
                    new AuditEvent(
                            "PARAM_RESET",
                            operator,
                            "DELETE",
                            "cfg_param:" + paramKey,
                            "租户参数已恢复平台默认：" + paramKey));
        }
        return assemble(def, null);
    }

    /** 覆盖变更记录（倒序，最近 100 条）。 */
    public List<ParamLogView> logs() {
        List<ParamLogView> views = new ArrayList<>();
        for (ConfigMapper.ParamLogRow row : mapper.listParamLogs(LOG_LIMIT)) {
            views.add(
                    new ParamLogView(
                            row.getParamKey(),
                            row.getAction(),
                            row.getOldValue(),
                            row.getNewValue(),
                            row.getReason(),
                            row.getOperatorId(),
                            formatTime(row.getCreatedAt())));
        }
        return views;
    }

    // ---------------------------------------------------------------- 私有

    /** 平台定义 + 本院覆盖行 → 视图（锁定后的失效覆盖也保留展示，PLT-CFG-011 提示）。 */
    private ParamView assemble(
            PlatformConfigPort.PlatformParamDef def, ConfigMapper.ParamRow override) {
        boolean overridden = override != null;
        boolean effectiveOverride = overridden && def.tenantOverridable();
        return new ParamView(
                def.paramKey(),
                def.name(),
                def.description(),
                def.valueType(),
                def.platformValue(),
                def.tenantOverridable(),
                def.valueRange(),
                overridden,
                overridden ? override.getParamValue() : null,
                effectiveOverride ? override.getParamValue() : def.platformValue(),
                overridden && !def.tenantOverridable(),
                overridden ? formatTime(override.getUpdatedAt()) : null);
    }

    private void insertLog(
            String paramKey,
            String action,
            String oldValue,
            String newValue,
            String reason,
            Long operator) {
        ConfigMapper.ParamLogRow row = new ConfigMapper.ParamLogRow();
        row.setParamKey(paramKey);
        row.setAction(action);
        row.setOldValue(oldValue);
        row.setNewValue(newValue);
        row.setReason(reason);
        row.setOperatorId(operator);
        mapper.insertParamLog(row);
    }

    private static String reasonOf(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > REASON_MAX) {
            throw bad("变更说明不能超过 " + REASON_MAX + " 个字符");
        }
        return trimmed;
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

    /** 时间展示口径（北京时间 ISO-8601 带时区）。 */
    static String formatTime(LocalDateTime time) {
        return time == null ? null : TIME.format(time.atZone(ZoneId.systemDefault()).toInstant());
    }
}
