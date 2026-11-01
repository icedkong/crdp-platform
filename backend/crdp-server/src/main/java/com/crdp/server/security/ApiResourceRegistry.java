package com.crdp.server.security;

import com.crdp.platform.apires.ApiResourceRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * 接口资源登记册（PLT-PERM-004，E05-T06 验收：未登记接口默认拒绝）：plt_api_resource 的进程内只读快照， 供 {@link
 * ApiResourceGuardFilter} 在请求路径上判定「已登记 / 公共 / 绑定权限码」。
 *
 * <p>快照在启动扫描完成、平台端标记接口属性（PUT /api/platform/apis/{id}）或手动触发同步后整体替换（volatile
 * 引用，读路径无锁）；多节点部署时其余节点至多随下次登记同步收敛（M1 单实例，ADR 0003）。
 */
@Component
public class ApiResourceRegistry {

    /** 精确路径（无路径变量）直接命中；带 {变量} 的模式走 AntPathMatcher。 */
    private volatile Snapshot snapshot = new Snapshot(Map.of(), List.of());

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** moduleCode 供闸门把「模块未开通」让位给 @RequireModule 切面（接口校验链顺序，platform-spec §4）。 */
    public record Entry(
            String httpMethod,
            String pathPattern,
            String permCode,
            String moduleCode,
            boolean publicAccess) {}

    private record Snapshot(Map<String, Map<String, Entry>> exact, List<Entry> patterns) {}

    /** 用库内当前行整体重建快照（不可变替换）。 */
    public synchronized void refresh(List<ApiResourceRow> rows) {
        Map<String, Map<String, Entry>> exact = new HashMap<>();
        List<Entry> patterns = new ArrayList<>();
        for (ApiResourceRow row : rows) {
            Entry entry =
                    new Entry(
                            row.getHttpMethod(),
                            row.getPathPattern(),
                            row.getPermCode(),
                            row.getModuleCode(),
                            Boolean.TRUE.equals(row.getPublicAccess()));
            if (row.getPathPattern().contains("{")) {
                patterns.add(entry);
            } else {
                exact.computeIfAbsent(row.getHttpMethod(), key -> new HashMap<>())
                        .put(row.getPathPattern(), entry);
            }
        }
        this.snapshot = new Snapshot(exact, List.copyOf(patterns));
    }

    /** 按「请求方法 + 实际路径」查登记项；未登记返回 empty（默认拒绝）。 */
    public Optional<Entry> find(String httpMethod, String path) {
        Snapshot current = snapshot;
        Entry entry = current.exact().getOrDefault(httpMethod, Map.of()).get(path);
        if (entry != null) {
            return Optional.of(entry);
        }
        for (Entry candidate : current.patterns()) {
            if (candidate.httpMethod().equals(httpMethod)
                    && MATCHER.match(candidate.pathPattern(), path)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
