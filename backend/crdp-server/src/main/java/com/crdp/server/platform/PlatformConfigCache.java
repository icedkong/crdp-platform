package com.crdp.server.platform;

import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.system.config.PlatformConfigPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 平台字典与参数定义缓存（E06-T10 / PLT-CFG-011「保存后刷新缓存即时生效」）。
 *
 * <p>医院端的字典合并与参数覆盖计算读平台定义频次高（每个列表 / 表单候选），经 {@link PlatformConfigPort} 本实现取数并缓存 60 秒（与
 * TenantLookupCache 同口径）；平台端保存字典 / 参数后调用 {@link #evict()} 立即作废本节点缓存，下一次读取即见新值——「平台改了定义、租户端立即可见」不用等
 * TTL。 M1 单实例部署（ADR 0003 第 4 条），多节点时其他节点仍靠 TTL 收敛。
 *
 * <p><b>连接口径</b>：直接持平台连接池（{@link TenantRoutingDataSource#platform()}）的 JdbcTemplate，
 * 不走路由——租户侧调用发生在租户事务里（Spring 已把路由数据源的连接绑到线程），走路由会错误复用租户连接 去查平台表；专用连接不受事务绑定影响，始终读平台库。
 */
@Component
public class PlatformConfigCache implements PlatformConfigPort {

    private static final Duration TTL = Duration.ofSeconds(60);

    private final JdbcTemplate platform;

    private volatile Snapshot snapshot;

    public PlatformConfigCache(TenantRoutingDataSource routing) {
        this.platform = new JdbcTemplate(routing.platform());
    }

    @Override
    public List<PlatformDictDef> dicts() {
        return snapshot().dicts();
    }

    @Override
    public Optional<PlatformDictDef> dict(String code) {
        return snapshot().dicts().stream().filter(def -> def.code().equals(code)).findFirst();
    }

    @Override
    public List<PlatformParamDef> params() {
        return snapshot().params();
    }

    @Override
    public Optional<PlatformParamDef> param(String paramKey) {
        return snapshot().params().stream()
                .filter(def -> def.paramKey().equals(paramKey))
                .findFirst();
    }

    /** 平台端保存字典 / 参数后调用：立即作废缓存（PLT-CFG-011）。 */
    public void evict() {
        snapshot = null;
    }

    private Snapshot snapshot() {
        Snapshot current = snapshot;
        if (current != null && current.expiresAt().isAfter(Instant.now())) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            if (current != null && current.expiresAt().isAfter(Instant.now())) {
                return current;
            }
            Snapshot loaded = load();
            snapshot = loaded;
            return loaded;
        }
    }

    /** 一次装载全部定义（同一时刻的快照，字典与参数不会出现半新半旧）。 */
    private Snapshot load() {
        Map<Long, List<PlatformDictItem>> itemsByDict = new HashMap<>();
        platform.query(
                "SELECT dict_id, item_code, label, sort_no, status FROM plt_dict_item"
                        + " ORDER BY dict_id, sort_no, item_code",
                rs -> {
                    itemsByDict
                            .computeIfAbsent(rs.getLong("dict_id"), key -> new ArrayList<>())
                            .add(ITEM_MAPPER.mapRow(rs, rs.getRow()));
                });
        List<PlatformDictDef> dicts = new ArrayList<>();
        // 只对外暴露启用中的字典类型（停用类型对租户隐藏；平台端管理视图另走 admin 服务现读）
        platform.query(
                "SELECT id, code, name, description, status FROM plt_dict"
                        + " WHERE status = 'ENABLED' ORDER BY code",
                rs -> {
                    long id = rs.getLong("id");
                    dicts.add(
                            new PlatformDictDef(
                                    rs.getString("code"),
                                    rs.getString("name"),
                                    rs.getString("description"),
                                    rs.getString("status"),
                                    itemsByDict.getOrDefault(id, List.of())));
                });
        List<PlatformParamDef> params =
                platform.query(
                        "SELECT param_key, name, description, value_type, platform_value,"
                                + " tenant_overridable, value_range FROM plt_param ORDER BY param_key",
                        (rs, rowNum) ->
                                new PlatformParamDef(
                                        rs.getString("param_key"),
                                        rs.getString("name"),
                                        rs.getString("description"),
                                        rs.getString("value_type"),
                                        rs.getString("platform_value"),
                                        rs.getInt("tenant_overridable") == 1,
                                        rs.getString("value_range")));
        return new Snapshot(dicts, params, Instant.now().plus(TTL));
    }

    private static final RowMapper<PlatformDictItem> ITEM_MAPPER =
            new RowMapper<>() {
                @Override
                public PlatformDictItem mapRow(ResultSet rs, int rowNum) throws SQLException {
                    return new PlatformDictItem(
                            rs.getString("item_code"),
                            rs.getString("label"),
                            rs.getInt("sort_no"),
                            rs.getString("status"));
                }
            };

    private record Snapshot(
            List<PlatformDictDef> dicts, List<PlatformParamDef> params, Instant expiresAt) {}
}
