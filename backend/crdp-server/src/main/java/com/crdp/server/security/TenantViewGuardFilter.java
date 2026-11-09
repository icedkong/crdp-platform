package com.crdp.server.security;

import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import com.crdp.server.platform.TenantViewAccessRecorder;
import com.crdp.server.platform.TenantViewRuleExecutor;
import com.crdp.server.platform.ViewRuleResolver.Effect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 租户视角的读写执行点（E20-T03 / PLT-VIEW-012，验收「只读视角写接口全部拒绝」）：视角令牌 （TENANT_VIEW）访问医院端业务接口时——
 *
 * <ul>
 *   <li><b>写拒绝</b>：新增、修改、删除与批量接口（PUT / PATCH / DELETE，以及非只读动作的 POST）一律 40303「租户视角只读，禁止写入」。规则 {@code
 *       operation=EDITABLE} 的受控放行（代为配置 / 代重置密码 / 编辑业务数据的二次确认与前后值记录，PLT-VIEW-013、017、018）随
 *       E20-T06——在那之前不开任何口子，fail closed；
 *   <li><b>导出 / 下载单独控制</b>：动作 export / download 的接口按视角规则 {@code allowExport} 判定（V109
 *       默认全部禁止，PLT-VIEW-012「导出、打印、下载按规则单独控制」）；报告单与电子化资料的预览 / 下载<b>签发口</b>对视角一律
 *       40303（原件隐藏，PLT-VIEW-008）；
 *   <li><b>读放行</b>：GET / HEAD 与只读动作的 POST（如复杂查询 {@code POST .../search}，AGENTS.md §6）放行——
 *       医院端权限码不再适用（视角无医院身份），由本过滤器与 {@code PermissionAuthorizationAspect} 的视角分支兜底； 放行记 VIEW_QUERY 审计
 *       + 台账计数（菜单请求记页面数），E20-T05 起带「对象与条数」（object_type = 权限码资源段、record_count = 响应条数，PLT-VIEW-016）；
 *   <li><b>模块开关</b>：按该院<b>实时</b>开通状态判 40301（与视角菜单同口径，PLT-VIEW-002）。
 * </ul>
 *
 * <p>医院会话与平台会话不受本过滤器影响；视角会话的外壳接口（/api/hospital/view/**、装壳三条）不经过本层。 脱敏 / 设盲（PLT-VIEW-010、011）在响应装配点经
 * {@code ViewRuleFieldGuard} 执行（共用 {@code ViewRuleResolver}）。
 */
@Component
public class TenantViewGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantViewGuardFilter.class);

    /** 视角外壳前缀（横幅 / 退出 / 凭据换取，E20-T04），不属业务执行点。 */
    private static final String VIEW_SHELL_PREFIX = "/api/hospital/view/";

    private static final String HOSPITAL_PREFIX = "/api/hospital/";

    /** 装壳菜单请求（页面计数口径，platform.md §6）。 */
    private static final String MENUS_URI = "/api/hospital/menus";

    /** 文件预览 / 下载<b>签发口</b>（原件与附件对视角隐藏，PLT-VIEW-008 / V109）。 */
    static final String FILE_GRANT_PREFIX = "/api/hospital/files/";

    static final String READ_ACTION = "view";

    private static final String MESSAGE_READONLY = "租户视角只读，禁止写入";

    private static final String MESSAGE_EXPORT = "租户视角禁止导出与下载";

    private static final String MESSAGE_ORIGINAL = "租户视角只读，报告单与电子化资料原件不提供预览与下载";

    private final ApiResourceRegistry registry;

    private final TenantViewRuleExecutor executor;

    private final TenantViewAccessRecorder recorder;

    private final ApiErrorWriter errors;

    /** 响应条数解析（VIEW_QUERY 的 record_count，PLT-VIEW-016「对象与条数」）。 */
    private final ObjectMapper json;

    public TenantViewGuardFilter(
            ApiResourceRegistry registry,
            TenantViewRuleExecutor executor,
            TenantViewAccessRecorder recorder,
            ApiErrorWriter errors,
            ObjectMapper json) {
        this.registry = registry;
        this.executor = executor;
        this.recorder = recorder;
        this.errors = errors;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        SessionPrincipal principal = currentPrincipal();
        if (principal == null || !principal.tenantView()) {
            filterChain.doFilter(request, response);
            return;
        }
        String uri = request.getRequestURI();
        if (HttpMethod.OPTIONS.matches(request.getMethod())
                || !uri.startsWith(HOSPITAL_PREFIX)
                || uri.startsWith(VIEW_SHELL_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        String method = request.getMethod();

        // 原件与附件的签发口：对视角一律拒绝（内容离站通道，PLT-VIEW-008 原件隐藏）
        if (isFileGrantUri(uri)) {
            deny(request, response, principal, 40303, MESSAGE_ORIGINAL);
            return;
        }

        ApiResourceRegistry.Entry entry = registry.find(method, uri).orElse(null);
        String permCode = entry == null ? null : entry.permCode();

        // 模块开关：按该院实时开通状态（40301 与医院端同文案）
        String moduleCode = entry == null ? null : entry.moduleCode();
        if (moduleCode == null || moduleCode.isBlank()) {
            moduleCode = TenantViewRuleExecutor.moduleOf(firstCode(permCode));
        }
        if (!executor.moduleOpen(principal.tenantId(), moduleCode)) {
            deny(request, response, principal, 40301, "模块未开通，请联系平台");
            return;
        }

        // 导出 / 下载：按视角规则 allowExport 单独控制（默认禁止，PLT-VIEW-012）
        if (exportAction(permCode)) {
            Effect effect =
                    executor.operationEffect(
                            principal.tenantId(),
                            TenantViewRuleExecutor.moduleOf(firstCode(permCode)),
                            TenantViewRuleExecutor.resourceOf(firstCode(permCode)));
            if (effect == null || !effect.allowExport()) {
                deny(request, response, principal, 40303, MESSAGE_EXPORT);
                return;
            }
            allow(request, response, filterChain, principal, permCode);
            return;
        }

        // 只读放行：GET / HEAD，或只读动作的 POST（复杂查询 POST .../search，AGENTS.md §6）
        if (readOnly(method, permCode)) {
            allow(request, response, filterChain, principal, permCode);
            return;
        }

        // 其余（写、明文、重置、导入等一切动作）对视角令牌拒绝（PLT-VIEW-012）
        deny(request, response, principal, 40303, MESSAGE_READONLY);
    }

    private void allow(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain,
            SessionPrincipal principal,
            String permCode)
            throws ServletException, IOException {
        boolean page = MENUS_URI.equals(request.getRequestURI());
        String objectType = objectTypeOf(permCode);
        // 缓存响应体提取「条数」（PLT-VIEW-016）：解析失败只缺 count，不影响放行与响应
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        Long recordCount = null;
        try {
            filterChain.doFilter(request, wrapper);
            recordCount = extractRecordCount(wrapper);
        } finally {
            try {
                wrapper.copyBodyToResponse();
            } catch (IOException ex) {
                log.error("视角查询响应回写出错 uri={}", request.getRequestURI(), ex);
            }
        }
        recorder.recordQuery(
                principal,
                request.getMethod(),
                request.getRequestURI(),
                page,
                objectType,
                recordCount);
    }

    /** 被查询资源（VIEW_QUERY 的 object_type）：取接口登记权限码的资源段（如 patient、report）；未绑码为 null。 */
    static String objectTypeOf(String permCode) {
        String code = firstCode(permCode);
        return code == null ? null : TenantViewRuleExecutor.resourceOf(code);
    }

    /**
     * 响应条数（VIEW_QUERY 的 record_count）：统一响应 {@code {code, data}}——data.total（分页总数）优先， 其次 data
     * 数组长度、data.items 数组长度，单对象记 1、null 记 0；非 JSON / 解析失败返回 null（不阻塞）。 纯函数（供单测）。
     */
    Long extractRecordCount(ContentCachingResponseWrapper wrapper) {
        try {
            byte[] body = wrapper.getContentAsByteArray();
            if (body.length == 0) {
                return null;
            }
            JsonNode root = json.readTree(new String(body, StandardCharsets.UTF_8));
            if (root.path("code").asInt(-1) != 0) {
                return null;
            }
            JsonNode data = root.get("data");
            if (data == null || data.isNull()) {
                return 0L;
            }
            if (data.has("total") && data.get("total").isNumber()) {
                return data.get("total").asLong();
            }
            if (data.isArray()) {
                return (long) data.size();
            }
            if (data.has("items") && data.get("items").isArray()) {
                return (long) data.get("items").size();
            }
            return 1L;
        } catch (Exception ex) {
            return null;
        }
    }

    private void deny(
            HttpServletRequest request,
            HttpServletResponse response,
            SessionPrincipal principal,
            int code,
            String message)
            throws IOException {
        log.info(
                "视角执行点拦截 account={} tenant={} method={} uri={} code={}",
                principal.username(),
                principal.tenantCode(),
                request.getMethod(),
                request.getRequestURI(),
                code);
        recorder.recordDenied(
                principal, request.getMethod(), request.getRequestURI(), code, message);
        errors.write(response, new ApiException(403, code, message).httpStatus(), code, message);
    }

    /** 文件预览 / 下载签发口（GET /api/hospital/files/{id}/preview-url、/download-url）。 */
    static boolean isFileGrantUri(String uri) {
        return uri.startsWith(FILE_GRANT_PREFIX)
                && (uri.endsWith("/preview-url") || uri.endsWith("/download-url"));
    }

    /**
     * 只读判定：绑权限码的接口以动作段为准（view = 读；POST {@code .../search} 复杂查询照常，AGENTS.md §6； 明文查看 GET + plain
     * 等敏感动作一律非读）；未绑码的公共接口 GET / HEAD 视为读。 登记值可为逗号拼接多码（OQ-13），任一 view 即读。
     */
    static boolean readOnly(String method, String permCode) {
        if (permCode == null || permCode.isBlank()) {
            return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method);
        }
        return readAction(permCode);
    }

    /** 登记权限码（可能逗号拼接多候选，OQ-13）中是否存在只读动作。 */
    static boolean readAction(String permCode) {
        if (permCode == null || permCode.isBlank()) {
            return false;
        }
        for (String candidate : permCode.split(",")) {
            if (READ_ACTION.equals(TenantViewRuleExecutor.actionOf(candidate.trim()))) {
                return true;
            }
        }
        return false;
    }

    /** 登记权限码是否导出 / 下载动作（打印接口随需要单独登记动作码后纳入）。 */
    static boolean exportAction(String permCode) {
        if (permCode == null || permCode.isBlank()) {
            return false;
        }
        for (String candidate : permCode.split(",")) {
            String action = TenantViewRuleExecutor.actionOf(candidate.trim());
            if ("export".equals(action) || "download".equals(action)) {
                return true;
            }
        }
        return false;
    }

    private static String firstCode(String permCode) {
        if (permCode == null || permCode.isBlank()) {
            return null;
        }
        return permCode.split(",")[0].trim();
    }

    private static SessionPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof SessionPrincipal principal) {
            return principal;
        }
        return null;
    }
}
