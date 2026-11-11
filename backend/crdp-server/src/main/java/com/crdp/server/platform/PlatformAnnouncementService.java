package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformAnnouncementMapper;
import com.crdp.platform.infra.mapper.PlatformAnnouncementMapper.AnnouncementRow;
import com.crdp.platform.infra.mapper.PlatformAnnouncementMapper.TenantLinkRow;
import com.crdp.platform.infra.mapper.PlatformAnnouncementMapper.TenantRow;
import com.crdp.server.system.audit.AuditQuerySupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E05-T11 / PLT-CFG-009：系统公告——平台向全部或指定租户发布公告（平台库 plt_announcement， 权限 platform:notice:edit）。
 *
 * <p>发布即生效（status=PUBLISHED，无草稿态，P2 范围内不排期）；scope=ALL 面向全部租户、 scope=TENANTS 只面向登记的租户（未知租户
 * 40400、重复去重）。公告发布后不可编辑，需要更正时 撤下（REVOKED，医院端即刻不可见）后重新发布——撤下与发布都写平台审计 （ANNOUNCEMENT_PUBLISH /
 * ANNOUNCEMENT_REVOKE，PLT-CFG-011）。医院端读取 {@link #listForTenant(long)} 只返回本院可见的生效公告。
 */
@Service
public class PlatformAnnouncementService {

    private static final int TITLE_MAX = 128;
    private static final int CONTENT_MAX = 5000;
    private static final int KEYWORD_MAX = 64;
    private static final int TENANT_LIST_MAX = 50;

    private final PlatformAnnouncementMapper mapper;
    private final AuditLogger audit;

    public PlatformAnnouncementService(PlatformAnnouncementMapper mapper, AuditLogger audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 视图

    /** 定向租户引用（编码 + 名称）。 */
    public record TenantRef(long tenantId, String tenantCode, String tenantName) {}

    /** 平台端公告视图（列表与详情同构；scopeText 为范围文案：全部租户 / 指定租户）。 */
    public record AnnouncementView(
            long id,
            String title,
            String content,
            String scope,
            String scopeText,
            String status,
            List<TenantRef> tenants,
            String publishedAt,
            String publishedByName,
            String revokedAt) {}

    /** 公告分页（新→旧）。 */
    public record AnnouncementPage(
            List<AnnouncementView> items, long total, int pageNo, int pageSize) {}

    /** 发布命令：scope=ALL 不带 tenantIds；scope=TENANTS 至少一个租户。 */
    public record PublishCommand(
            String title, String content, String scope, List<Long> tenantIds) {}

    /** 医院端公告视图（本院可见的生效公告）。 */
    public record TenantAnnouncementView(
            long id, String title, String content, String publishedAt) {}

    // ---------------------------------------------------------------- 平台端维护

    /** 发布公告：发布即生效；定向租户必须全部存在（未知 40400），重复 ID 去重。 */
    @Transactional
    public AnnouncementView publish(PublishCommand command) {
        if (command == null) {
            throw bad("请求体不能为空");
        }
        String title = trimToNull(command.title());
        if (title == null) {
            throw bad("标题不能为空");
        }
        if (title.length() > TITLE_MAX) {
            throw bad("标题不能超过 " + TITLE_MAX + " 个字符");
        }
        String content = trimToNull(command.content());
        if (content == null) {
            throw bad("正文不能为空");
        }
        if (content.length() > CONTENT_MAX) {
            throw bad("正文不能超过 " + CONTENT_MAX + " 个字符");
        }
        boolean toAll = "ALL".equals(command.scope());
        boolean toTenants = "TENANTS".equals(command.scope());
        if (!toAll && !toTenants) {
            throw bad("发布范围只能是 ALL（全部租户）或 TENANTS（指定租户）");
        }
        List<Long> tenantIds = dedupe(command.tenantIds());
        if (toAll && !tenantIds.isEmpty()) {
            throw bad("面向全部租户时不能携带指定租户");
        }
        if (toTenants && tenantIds.isEmpty()) {
            throw bad("指定租户不能为空");
        }
        if (tenantIds.size() > TENANT_LIST_MAX) {
            throw bad("指定租户一次最多 " + TENANT_LIST_MAX + " 个");
        }
        return TenantContext.callAsPlatform(
                () -> {
                    Map<Long, TenantRow> tenants = loadTenants(tenantIds);
                    for (Long id : tenantIds) {
                        if (!tenants.containsKey(id)) {
                            throw new ApiException(
                                    404, 40400, "租户不存在：" + tenantsMissing(tenants, tenantIds));
                        }
                    }
                    Long operatorId = operatorId();
                    AnnouncementRow row = new AnnouncementRow();
                    row.setTitle(title);
                    row.setContent(content);
                    row.setScope(command.scope());
                    row.setStatus("PUBLISHED");
                    row.setPublishedAt(LocalDateTime.now());
                    row.setPublishedBy(operatorId);
                    row.setPublishedByName(operatorName());
                    mapper.insert(row);
                    if (toTenants) {
                        mapper.insertTenants(row.getId(), tenantIds);
                    }
                    audit.record(
                            new AuditEvent(
                                    "ANNOUNCEMENT_PUBLISH",
                                    operatorId,
                                    "CREATE",
                                    "plt_announcement:" + row.getId(),
                                    "系统公告已发布："
                                            + title
                                            + "（"
                                            + (toAll
                                                    ? "全部租户"
                                                    : "指定租户 " + tenantNames(tenants, tenantIds))
                                            + "）"));
                    return viewOf(row, linkRefs(tenants, tenantIds));
                });
    }

    /** 公告分页（新→旧；keyword 按标题模糊，最长 64 字）。 */
    public AnnouncementPage page(Integer pageNo, Integer pageSize, String keyword) {
        int no = AuditQuerySupport.pageNo(pageNo);
        int size = AuditQuerySupport.pageSize(pageSize);
        String kw = trimToNull(keyword);
        if (kw != null && kw.length() > KEYWORD_MAX) {
            throw bad("关键词不能超过 " + KEYWORD_MAX + " 个字符");
        }
        return TenantContext.callAsPlatform(
                () -> {
                    List<AnnouncementRow> rows = mapper.page(kw, (no - 1) * size, size);
                    return new AnnouncementPage(viewsWithTenants(rows), mapper.count(kw), no, size);
                });
    }

    /** 公告详情（含定向租户名单）；未知 40400。 */
    public AnnouncementView detail(long id) {
        return TenantContext.callAsPlatform(
                () -> {
                    AnnouncementRow row = requireRow(id);
                    return viewsWithTenants(List.of(row)).get(0);
                });
    }

    /** 撤下公告（医院端即刻不可见）；未知 40400、已撤下 40901。 */
    @Transactional
    public AnnouncementView revoke(long id) {
        return TenantContext.callAsPlatform(
                () -> {
                    AnnouncementRow row = requireRow(id);
                    if (!"PUBLISHED".equals(row.getStatus())) {
                        throw new ApiException(409, 40901, "公告已撤下，不能重复撤下");
                    }
                    Long operatorId = operatorId();
                    int updated = mapper.revoke(id, LocalDateTime.now(), operatorId);
                    if (updated != 1) {
                        throw new ApiException(409, 40901, "公告已撤下，不能重复撤下");
                    }
                    row.setStatus("REVOKED");
                    row.setRevokedAt(LocalDateTime.now());
                    audit.record(
                            new AuditEvent(
                                    "ANNOUNCEMENT_REVOKE",
                                    operatorId,
                                    "UPDATE",
                                    "plt_announcement:" + id,
                                    "系统公告已撤下：" + row.getTitle()));
                    return viewsWithTenants(List.of(row)).get(0);
                });
    }

    // ---------------------------------------------------------------- 医院端读取

    /** 本院可见的生效公告（全部租户或定向命中；新→旧，上限 50 条）。 */
    public List<TenantAnnouncementView> listForTenant(long tenantId) {
        return TenantContext.callAsPlatform(
                () ->
                        mapper.listForTenant(tenantId).stream()
                                .map(
                                        row ->
                                                new TenantAnnouncementView(
                                                        row.id(),
                                                        row.title(),
                                                        row.content(),
                                                        AuditQuerySupport.format(
                                                                row.publishedAt())))
                                .toList());
    }

    // ---------------------------------------------------------------- 私有

    private AnnouncementRow requireRow(long id) {
        AnnouncementRow row = mapper.find(id);
        if (row == null) {
            throw new ApiException(404, 40400, "公告不存在");
        }
        return row;
    }

    /** 行 → 视图（定向租户名单一次查询拼装）。 */
    private List<AnnouncementView> viewsWithTenants(List<AnnouncementRow> rows) {
        List<Long> scopedIds =
                rows.stream()
                        .filter(row -> "TENANTS".equals(row.getScope()))
                        .map(AnnouncementRow::getId)
                        .toList();
        Map<Long, List<TenantRef>> refs =
                scopedIds.isEmpty()
                        ? Map.of()
                        : mapper.findTenantsOf(scopedIds).stream()
                                .collect(
                                        Collectors.groupingBy(
                                                TenantLinkRow::announcementId,
                                                Collectors.mapping(
                                                        link ->
                                                                new TenantRef(
                                                                        link.tenantId(),
                                                                        link.tenantCode(),
                                                                        link.tenantName()),
                                                        Collectors.toList())));
        return rows.stream()
                .map(row -> viewOf(row, refs.getOrDefault(row.getId(), List.of())))
                .toList();
    }

    private static AnnouncementView viewOf(AnnouncementRow row, List<TenantRef> tenants) {
        return new AnnouncementView(
                row.getId(),
                row.getTitle(),
                row.getContent(),
                row.getScope(),
                "ALL".equals(row.getScope()) ? "全部租户" : "指定租户",
                row.getStatus(),
                tenants,
                AuditQuerySupport.format(row.getPublishedAt()),
                row.getPublishedByName(),
                AuditQuerySupport.format(row.getRevokedAt()));
    }

    private static List<TenantRef> linkRefs(Map<Long, TenantRow> tenants, List<Long> tenantIds) {
        List<TenantRef> refs = new ArrayList<>();
        for (Long id : tenantIds) {
            TenantRow tenant = tenants.get(id);
            if (tenant != null) {
                refs.add(new TenantRef(tenant.id(), tenant.code(), tenant.name()));
            }
        }
        return refs;
    }

    private Map<Long, TenantRow> loadTenants(List<Long> tenantIds) {
        if (tenantIds.isEmpty()) {
            return Map.of();
        }
        return mapper.findTenants(tenantIds).stream()
                .collect(Collectors.toMap(TenantRow::id, tenant -> tenant));
    }

    private static List<Long> dedupe(List<Long> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    private static Long operatorId() {
        return CurrentSession.current().map(principal -> principal.userId()).orElse(null);
    }

    private static String operatorName() {
        return CurrentSession.current()
                .map(principal -> principal.displayName() + "（" + principal.username() + "）")
                .orElse(null);
    }

    private static String tenantNames(Map<Long, TenantRow> tenants, List<Long> tenantIds) {
        return tenantIds.stream()
                .map(id -> tenants.containsKey(id) ? tenants.get(id).code() : String.valueOf(id))
                .collect(Collectors.joining("、"));
    }

    private static String tenantsMissing(Map<Long, TenantRow> tenants, List<Long> tenantIds) {
        return tenantIds.stream()
                .filter(id -> !tenants.containsKey(id))
                .map(String::valueOf)
                .collect(Collectors.joining("、"));
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
}
