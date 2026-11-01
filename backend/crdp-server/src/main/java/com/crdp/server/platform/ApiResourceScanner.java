package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.platform.apires.ApiResourceRow;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.server.security.ApiResourceRegistry;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 接口资源登记（PLT-PERM-004，E05-T06）：启动时扫描全部 Controller 处理方法的注解，自动登记「接口 → 权限码 → 模块」到 plt_api_resource，并刷新
 * {@link ApiResourceRegistry} 供默认拒绝闸门使用。
 *
 * <p>推导规则：
 *
 * <ul>
 *   <li>权限码取方法或类上的 {@code @RequiresPermission}（未标注则空，交由平台端绑定或标记公共）；
 *   <li>模块取 {@code @RequireModule}，否则按权限码首段在 plt_module 里匹配（如 followup:task:view →
 *       followup），都不成立归平台基础 base；
 *   <li>公共接口 = 方法或类上标注 {@code @PublicApi} 且未绑定权限码（绑定权限码的以权限码为准）；
 *   <li>动作类型按 HTTP 方法推导（GET → QUERY，其余 → WRITE），EXPORT / DOWNLOAD 由平台端标记。
 * </ul>
 *
 * <p>同步不覆盖平台端维护的审计级别、敏感级别、动作类型（与模块注册同步同一口径）；扫描来源（SCAN）的行在接口
 * 从代码里消失后自动清除，种子（SEED）与手工标记（MANUAL）的行保留。每次有登记或变化写一条 API_SYNC 审计。
 */
@Service
@Order(6)
public class ApiResourceScanner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiResourceScanner.class);

    private final RequestMappingHandlerMapping handlerMapping;
    private final PlatformMapper platformMapper;
    private final ApiResourceRegistry registry;
    private final AuditLogger audit;

    public ApiResourceScanner(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            PlatformMapper platformMapper,
            ApiResourceRegistry registry,
            AuditLogger audit) {
        this.handlerMapping = handlerMapping;
        this.platformMapper = platformMapper;
        this.registry = registry;
        this.audit = audit;
    }

    /** 登记结果：动作 REGISTERED / UPDATED / UNCHANGED / REMOVED。 */
    public record ScanOutcome(
            String action, String httpMethod, String pathPattern, String permCode) {}

    public record ScanResult(List<ScanOutcome> outcomes, boolean changed) {}

    /** 启动即扫描（平台库 Flyway 与模块注册同步之后：@Order(6) 晚于 ModuleRegistrySyncService 的 5）。 */
    @Override
    public void run(ApplicationArguments args) {
        scan();
    }

    /** 扫描并登记全部接口；也可由平台端 POST /api/platform/apis/sync 手动触发（与启动同一套逻辑）。 */
    public ScanResult scan() {
        return TenantContext.callAsPlatform(
                () -> {
                    List<ScannedEndpoint> endpoints = collectEndpoints();
                    Set<String> moduleCodes =
                            platformMapper.listModuleCodes().stream()
                                    .collect(Collectors.toUnmodifiableSet());
                    Map<String, ApiResourceRow> existing =
                            platformMapper.listApiResources().stream()
                                    .collect(
                                            Collectors.toMap(
                                                    row ->
                                                            key(
                                                                    row.getHttpMethod(),
                                                                    row.getPathPattern()),
                                                    row -> row,
                                                    (left, right) -> left,
                                                    LinkedHashMap::new));
                    List<ScanOutcome> outcomes = new ArrayList<>();
                    Set<String> seen = new HashSet<>();
                    boolean changed = false;
                    for (ScannedEndpoint endpoint : endpoints) {
                        String key = key(endpoint.httpMethod(), endpoint.pathPattern());
                        seen.add(key);
                        ApiResourceRow derived = toRow(endpoint, moduleCodes);
                        ApiResourceRow row = existing.get(key);
                        if (row == null) {
                            platformMapper.insertApiResource(derived);
                            outcomes.add(outcome("REGISTERED", derived));
                            changed = true;
                        } else if (registrationChanged(row, derived)) {
                            derived.setId(row.getId());
                            platformMapper.updateApiResourceRegistration(derived);
                            outcomes.add(outcome("UPDATED", derived));
                            changed = true;
                        } else {
                            platformMapper.touchApiResource(row.getId());
                            outcomes.add(outcome("UNCHANGED", row));
                        }
                    }
                    for (ApiResourceRow row : existing.values()) {
                        if (!seen.contains(key(row.getHttpMethod(), row.getPathPattern()))
                                && "SCAN".equals(row.getSource())) {
                            platformMapper.deleteScannedApiResource(row.getId());
                            outcomes.add(outcome("REMOVED", row));
                            changed = true;
                        }
                    }
                    if (changed) {
                        audit.record(
                                new AuditEvent(
                                        "API_SYNC",
                                        CurrentSession.current()
                                                .map(principal -> principal.userId())
                                                .orElse(null),
                                        "SCAN",
                                        "plt_api_resource",
                                        outcomes.stream()
                                                .filter(o -> !"UNCHANGED".equals(o.action()))
                                                .map(
                                                        o ->
                                                                o.action()
                                                                        + " "
                                                                        + o.httpMethod()
                                                                        + " "
                                                                        + o.pathPattern())
                                                .limit(50)
                                                .collect(Collectors.joining("；"))));
                    }
                    registry.refresh(platformMapper.listApiResources());
                    log.info(
                            "接口资源登记完成：共 {} 条，其中变化 {} 条",
                            outcomes.size(),
                            outcomes.stream().filter(o -> !"UNCHANGED".equals(o.action())).count());
                    return new ScanResult(List.copyOf(outcomes), changed);
                });
    }

    /** 只重读库内行刷新登记册快照（不重扫）：平台端标记接口属性后调用。 */
    public void reloadRegistry() {
        TenantContext.callAsPlatform(
                () -> {
                    registry.refresh(platformMapper.listApiResources());
                    return null;
                });
    }

    /** 扫描到的处理方法（方法 + 完整路径模式）。 */
    record ScannedEndpoint(String httpMethod, String pathPattern, HandlerMethod handler) {}

    /** 只登记 /api/** 业务接口；swagger、actuator 不在默认拒绝闸门的守卫面内（SecurityConfig 单列放行）。 */
    List<ScannedEndpoint> collectEndpoints() {
        return handlerMapping.getHandlerMethods().entrySet().stream()
                .flatMap(
                        entry ->
                                entry.getKey().getMethodsCondition().getMethods().stream()
                                        .map(
                                                method ->
                                                        new ScannedEndpoint(
                                                                method.name(),
                                                                pathPatternOf(entry),
                                                                entry.getValue())))
                .filter(endpoint -> endpoint.pathPattern().startsWith("/api/"))
                .sorted(
                        (left, right) ->
                                left.pathPattern().compareTo(right.pathPattern()) == 0
                                        ? left.httpMethod().compareTo(right.httpMethod())
                                        : left.pathPattern().compareTo(right.pathPattern()))
                .toList();
    }

    private static String pathPatternOf(
            Map.Entry<org.springframework.web.servlet.mvc.method.RequestMappingInfo, HandlerMethod>
                    entry) {
        if (entry.getKey().getPathPatternsCondition() != null
                && !entry.getKey().getPathPatternsCondition().getPatterns().isEmpty()) {
            return entry.getKey()
                    .getPathPatternsCondition()
                    .getPatterns()
                    .iterator()
                    .next()
                    .getPatternString();
        }
        // Ant 风格条件（非默认配置）里 getPatterns 直接是字符串模式
        return entry.getKey().getPatternsCondition().getPatterns().iterator().next();
    }

    /** 注解 → 登记行（包内可见供单测直接断言推导结果）。 */
    static ApiResourceRow toRow(ScannedEndpoint endpoint, Set<String> moduleCodes) {
        HandlerMethod handler = endpoint.handler();
        Method method = handler.getMethod();
        Class<?> beanType = handler.getBeanType();
        String permCode = findAnnotationValue(method, beanType);
        String module = moduleOf(method, beanType, permCode, moduleCodes);
        boolean publicApi =
                AnnotatedElementUtils.hasAnnotation(method, PublicApi.class)
                        || AnnotatedElementUtils.hasAnnotation(beanType, PublicApi.class);
        ApiResourceRow row = new ApiResourceRow();
        row.setHttpMethod(endpoint.httpMethod());
        row.setPathPattern(endpoint.pathPattern());
        row.setPermCode(permCode);
        row.setModuleCode(module);
        // 绑定权限码的接口以权限码为准，不按公共接口处理
        row.setPublicAccess(publicApi && (permCode == null || permCode.isBlank()));
        row.setAuditLevel("NONE");
        row.setSensitiveLevel("NORMAL");
        row.setActionType("GET".equals(endpoint.httpMethod()) ? "QUERY" : "WRITE");
        row.setSource("SCAN");
        return row;
    }

    private static String findAnnotationValue(Method method, Class<?> beanType) {
        RequiresPermission onMethod =
                AnnotatedElementUtils.findMergedAnnotation(method, RequiresPermission.class);
        if (onMethod != null) {
            return onMethod.value();
        }
        RequiresPermission onType =
                AnnotatedElementUtils.findMergedAnnotation(beanType, RequiresPermission.class);
        return onType == null ? null : onType.value();
    }

    private static String moduleOf(
            Method method, Class<?> beanType, String permCode, Set<String> moduleCodes) {
        RequireModule requireModule =
                AnnotatedElementUtils.findMergedAnnotation(method, RequireModule.class);
        if (requireModule == null) {
            requireModule =
                    AnnotatedElementUtils.findMergedAnnotation(beanType, RequireModule.class);
        }
        if (requireModule != null) {
            return requireModule.value();
        }
        if (permCode != null && permCode.contains(":")) {
            String prefix = permCode.substring(0, permCode.indexOf(':'));
            if (moduleCodes.contains(prefix)) {
                return prefix;
            }
        }
        return "base";
    }

    /** 已登记行与代码推导值在「权限码 / 模块 / 公共标记」上有出入才算 UPDATED。 */
    static boolean registrationChanged(ApiResourceRow existing, ApiResourceRow derived) {
        return !Objects.equals(nullable(existing.getPermCode()), nullable(derived.getPermCode()))
                || !Objects.equals(
                        nullable(existing.getModuleCode()), nullable(derived.getModuleCode()))
                || !Objects.equals(
                        Boolean.TRUE.equals(existing.getPublicAccess()),
                        Boolean.TRUE.equals(derived.getPublicAccess()));
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ScanOutcome outcome(String action, ApiResourceRow row) {
        return new ScanOutcome(
                action, row.getHttpMethod(), row.getPathPattern(), row.getPermCode());
    }

    private static String key(String httpMethod, String pathPattern) {
        return httpMethod + " " + pathPattern;
    }
}
