package com.crdp.server.security;

import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import com.crdp.server.platform.TenantViewAccessRecorder;
import com.crdp.server.platform.TenantViewProxyService;
import com.crdp.server.platform.TenantViewProxyService.WriteClass;
import com.crdp.server.platform.TenantViewRuleExecutor;
import com.crdp.server.platform.ViewRuleResolver.Effect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 租户视角的读写执行点（E20-T03 / PLT-VIEW-012，验收「只读视角写接口全部拒绝」）：视角令牌 （TENANT_VIEW）访问医院端业务接口时——
 *
 * <ul>
 *   <li><b>写受控放行（E20-T06 / PLT-VIEW-013、018）</b>：新增、修改、删除与批量接口默认仍 40303「租户视角只读，禁止写入」；
 *       配置类资源写按「角色可代为配置 ∧ 资源在代为配置范围内」放行，业务数据写按「角色可编辑业务数据 ∧ 命中操作为可编辑的视角规则」放行（任一不满足即只读）； 放行必须带 {@code
 *       X-View-Reason}（二次确认填写的操作原因，必填），成功后写 VIEW_PROXY_CONFIG / VIEW_PROXY_EDIT 平台访问审计（对象、原因、写前同路径
 *       GET 快照与请求载荷的前后值）并递增台账 proxy_count；动作 plain（明文）与 reset（医院侧重置口）永远不放行——重置只走 PLT-VIEW-017
 *       的专用代理口；
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

    private final TenantViewProxyService proxy;

    private final ApiErrorWriter errors;

    /** 响应条数解析（VIEW_QUERY 的 record_count，PLT-VIEW-016「对象与条数」）。 */
    private final ObjectMapper json;

    public TenantViewGuardFilter(
            ApiResourceRegistry registry,
            TenantViewRuleExecutor executor,
            TenantViewAccessRecorder recorder,
            TenantViewProxyService proxy,
            ApiErrorWriter errors,
            ObjectMapper json) {
        this.registry = registry;
        this.executor = executor;
        this.recorder = recorder;
        this.proxy = proxy;
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

        // 受控放行（E20-T06 / PLT-VIEW-013、018）：配置类写 = 角色可代为配置 ∧ 资源在范围；业务数据写 =
        // 角色可编辑业务数据 ∧ 命中「可编辑」规则（任一不满足即只读）。放行须带二次确认原因（X-View-Reason），
        // 成功后写 VIEW_PROXY_* 平台访问审计（对象、原因、前后值）并递增台账 proxy_count。
        String first = firstCode(permCode);
        WriteClass writeClass =
                TenantViewProxyService.classify(entry == null ? null : entry.moduleCode(), first);
        String releaseAction =
                writeClass == WriteClass.NEVER
                        ? null
                        : proxy.releaseAction(
                                principal,
                                writeClass,
                                entry == null ? null : entry.moduleCode(),
                                TenantViewRuleExecutor.resourceOf(first));
        if (releaseAction == null) {
            deny(request, response, principal, 40303, MESSAGE_READONLY);
            return;
        }
        String reason;
        try {
            reason =
                    TenantViewProxyService.requireHeaderReason(
                            request.getHeader(TenantViewProxyService.REASON_HEADER));
        } catch (ApiException badReason) {
            recorder.recordDenied(principal, method, uri, badReason.code(), badReason.getMessage());
            errors.write(
                    response, badReason.httpStatus(), badReason.code(), badReason.getMessage());
            return;
        }
        // 前值快照（PLT-VIEW-016「编辑前后值」）：写前对同路径已登记的 GET 做一次内部快照（FORWARD 分发，
        // 不过安全链）；未登记 GET 或快照失败记 null，不阻塞放行。视角会话的脱敏 / 设盲在装配点照常生效。
        request.setAttribute(
                com.crdp.framework.security.PermissionAuthorizationAspect.VIEW_PROXY_RELEASED,
                releaseAction);
        String beforeJson = snapshotBefore(request, response, uri);
        HttpServletRequest effectiveRequest =
                cacheableBody(request) ? new ContentCachingRequestWrapper(request) : request;
        filterChain.doFilter(effectiveRequest, response);
        String afterJson =
                effectiveRequest instanceof ContentCachingRequestWrapper wrapper
                        ? bodyOf(wrapper.getContentAsByteArray())
                        : "(文件上传，载荷不记录)";
        proxy.recordProxy(
                principal,
                method,
                uri,
                releaseAction,
                objectTypeOf(permCode),
                objectIdOf(uri),
                reason,
                beforeJson,
                afterJson);
    }

    /**
     * 写前快照（纯内部读，FORWARD 分发不经安全链与本过滤器，无递归）：同路径登记了 GET 才快照（REST 单对象 PUT/DELETE 的「前值」）；新建 / 无 GET /
     * 读取失败为 null。快照响应限长，异常只记 DEBUG。
     */
    private String snapshotBefore(
            HttpServletRequest request, HttpServletResponse response, String uri) {
        if (!registry.find(HttpMethod.GET.name(), uri).isPresent()) {
            return null;
        }
        try {
            SnapshotResponse capture = new SnapshotResponse(response);
            HttpServletRequest getRequest =
                    new HttpServletRequestWrapper(request) {
                        @Override
                        public String getMethod() {
                            return HttpMethod.GET.name();
                        }
                    };
            request.getRequestDispatcher(uri).forward(getRequest, capture);
            if (capture.status() < 200 || capture.status() >= 300) {
                return null;
            }
            return bodyOf(capture.body());
        } catch (Exception ex) {
            log.debug("视角代为操作写前快照失败 uri={}", uri, ex);
            return null;
        }
    }

    /** 载荷是否可缓存（JSON / 表单等文本体；multipart 文件上传不缓存）。 */
    private static boolean cacheableBody(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && !type.toLowerCase().startsWith("multipart/");
    }

    /** 载荷读回（UTF-8；空为 null；限长防审计行膨胀）。 */
    private static String bodyOf(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        return TenantViewProxyService.truncate(new String(body, StandardCharsets.UTF_8));
    }

    /** 路径尾段的对象 id（如 .../users/42 → 42；无数字尾段为 null，PLT-VIEW-016「对象」）。 */
    static String objectIdOf(String uri) {
        String[] parts = uri.split("/");
        String last = parts.length == 0 ? "" : parts[parts.length - 1];
        return last.matches("\\d+") ? last : null;
    }

    /**
     * 快照响应捕获（只进内存，不触达客户端连接：输出 / 状态 / 提交全落在包装层，flush 与 sendError 不下传底层
     * ——底层虽是真实响应，但本包装覆盖了全部写路径，快照期间客户端连接不被触碰）。
     */
    static final class SnapshotResponse extends HttpServletResponseWrapper {

        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        private int status = 200;

        SnapshotResponse(HttpServletResponse underlying) {
            super(underlying);
        }

        int status() {
            return status;
        }

        byte[] body() {
            return body.toByteArray();
        }

        @Override
        public void setStatus(int status) {
            this.status = status;
        }

        @Override
        public void sendError(int status) {
            this.status = status;
        }

        @Override
        public void sendError(int status, String message) {
            this.status = status;
        }

        @Override
        public void setContentLength(int len) {
            // 内容只进内存，长度无意义
        }

        @Override
        public void setContentLengthLong(long len) {
            // 同上
        }

        @Override
        public void flushBuffer() {
            // 不下传：快照不得提交底层连接
        }

        @Override
        public boolean isCommitted() {
            return false;
        }

        @Override
        public void reset() {
            body.reset();
            status = 200;
        }

        @Override
        public void setCharacterEncoding(String charset) {
            // 快照按 UTF-8 读回，编码设置无副作用
        }

        @Override
        public String getCharacterEncoding() {
            return StandardCharsets.UTF_8.name();
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    // 快照一次性写入，无需异步监听
                }

                @Override
                public void write(int b) {
                    body.write(b);
                }

                @Override
                public void write(byte[] bytes, int off, int len) {
                    body.write(bytes, off, len);
                }
            };
        }

        @Override
        public PrintWriter getWriter() {
            return new PrintWriter(body, true) {
                // JSON 渲染只 write / flush；字符集按 UTF-8 读回
            };
        }
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
            // E23-T10 UAT-SELF-03：审计与台账计数必须在响应体释放（copyBodyToResponse 会把缓存体
            // 刷回客户端）之前落库——否则快客户端可在 UPDATE 提交前读到旧计数（视角会话页签的
            // page_count / query_count 偶发少计，CI 慢机稳定复现 PlatformTenantViewExecutionMySqlIT）。
            // 条数从 wrapper 缓存读，无需先回写响应体。
            recorder.recordQuery(
                    principal,
                    request.getMethod(),
                    request.getRequestURI(),
                    page,
                    objectType,
                    recordCount);
        } finally {
            try {
                wrapper.copyBodyToResponse();
            } catch (IOException ex) {
                log.error("视角查询响应回写出错 uri={}", request.getRequestURI(), ex);
            }
        }
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
