package com.crdp.server.configtemplate;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.DigestService;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.configtemplate.ConfigCopyMode;
import com.crdp.platform.configtemplate.ConfigTableEntry;
import com.crdp.platform.configtemplate.ConfigTableRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

/**
 * 配置模板导出（E12-T01 / A-FLD-014，docs/api/platform.md §7）。
 *
 * <p>从源租户导出配置包：只遍历 {@link ConfigTableRegistry} 登记的配置表（白名单；「配置表在注册表中显式标注， 未标注的表一律不导出」），COPY
 * 表带全量行、STRUCTURE_ONLY 表（术者、病区 / 中心）只带计数、术者字典取值经行排除谓词剔除。 包 gzip JSON 存平台库 plt_config_template（与
 * job_export.file_body 同构先例），写 CONFIG_TEMPLATE_EXPORT 审计。导入与 ID 重映射是 E12-T02，包含 / 排除规则与敏感信息检查是
 * E12-T03。
 */
@Service
public class ConfigTemplateExportService {

    /** 包结构版本（ConfigPackage.formatVersion）。 */
    public static final int FORMAT_VERSION = 1;

    private static final int NAME_MAX = 128;
    private static final int MAX_PAGE_SIZE = 100;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 导出请求：tenantId 必填；name 可选（缺省「{租户名} 配置模板」）。 */
    public record ExportRequest(long tenantId, String name) {}

    /** 导出结果（列表项同构，另见 {@link #list}）。 */
    public record ExportedView(
            long id,
            long tenantId,
            String tenantCode,
            String tenantName,
            String name,
            int version,
            int formatVersion,
            int tableCount,
            long rowCount,
            String packageHash,
            String exportedAt) {}

    /** 模板详情：头信息 + 每张配置表的元信息（不带行）。 */
    public record TemplateDetail(
            ExportedView template, List<ConfigPackage.TableSectionMeta> tables) {}

    /** 分页视图（AGENTS §6 分页约定）。 */
    public record PageView(List<ExportedView> items, long total, int pageNo, int pageSize) {}

    private record Stored(long id, int version) {}

    private final JdbcTemplate platform;
    private final TenantConfigReader reader;
    private final TenantRoutingDataSource routing;
    private final AuditLogger audit;
    private final DigestService digest;
    private final ObjectMapper json;

    public ConfigTemplateExportService(
            JdbcTemplate platform,
            TenantConfigReader reader,
            TenantRoutingDataSource routing,
            AuditLogger audit,
            DigestService digest,
            ObjectMapper json) {
        this.platform = platform;
        this.reader = reader;
        this.routing = routing;
        this.audit = audit;
        this.digest = digest;
        this.json = json;
    }

    /** 从源租户导出配置模板（platform:tenant:create）。 */
    public ExportedView export(ExportRequest request) {
        SessionPrincipal principal = CurrentSession.requirePlatform();
        Map<String, Object> tenant = tenantRow(request.tenantId());
        String code = String.valueOf(tenant.get("code"));
        String tenantName = String.valueOf(tenant.get("name"));
        String name = normalizeName(request.name(), tenantName);
        if (!routing.hasTenant(request.tenantId())) {
            throw new ApiException(409, 40901, "租户数据源未注册（未开通或已注销），无法读取配置：" + code);
        }

        List<ConfigPackage.TableSection> sections =
                TenantContext.callAsTenant(
                        request.tenantId(), code, () -> readSections(ConfigTableRegistry.ENTRIES));
        String exportedAt = OffsetDateTime.now(ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        ConfigPackage pkg =
                new ConfigPackage(FORMAT_VERSION, code, tenantName, exportedAt, sections);

        byte[] body;
        String hash;
        try {
            byte[] raw = json.writeValueAsBytes(pkg);
            hash = digest.hashHex(new String(raw, StandardCharsets.UTF_8));
            body = gzip(raw);
        } catch (JsonProcessingException ex) {
            throw new ApiException(500, 50000, "配置包序列化失败：" + ex.getMessage());
        }

        long rowCount = sections.stream().mapToLong(ConfigPackage.TableSection::rowCount).sum();
        Stored stored =
                TenantContext.callAsPlatform(
                        () ->
                                store(
                                        request.tenantId(),
                                        code,
                                        tenantName,
                                        name,
                                        body,
                                        hash,
                                        rowCount,
                                        principal));
        ExportedView view =
                new ExportedView(
                        stored.id(),
                        request.tenantId(),
                        code,
                        tenantName,
                        name,
                        stored.version(),
                        FORMAT_VERSION,
                        sections.size(),
                        rowCount,
                        hash,
                        exportedAt);
        audit.append(
                AuditEntry.operation("platform", "CONFIG_TEMPLATE_EXPORT")
                        .tenantId(request.tenantId())
                        .objectType("plt_config_template")
                        .objectId(String.valueOf(stored.id()))
                        .description(
                                "从租户 "
                                        + code
                                        + " 导出配置模板 v"
                                        + stored.version()
                                        + "：表 "
                                        + sections.size()
                                        + " 张 / 配置行 "
                                        + rowCount)
                        .recordCount(rowCount)
                        .fileHash(hash)
                        .success()
                        .build());
        return view;
    }

    /** 模板分页（新→旧）。 */
    public PageView list(int pageNo, int pageSize) {
        CurrentSession.requirePlatform();
        int page = Math.max(1, pageNo);
        int size = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        return TenantContext.callAsPlatform(
                () -> {
                    Long total =
                            platform.queryForObject(
                                    "SELECT COUNT(*) FROM plt_config_template", Long.class);
                    int offset = (page - 1) * size;
                    List<ExportedView> items =
                            platform.query(
                                    "SELECT id, tenant_id, tenant_code, tenant_name, name, version,"
                                            + " format_version, table_count, row_count, package_hash,"
                                            + " created_at FROM plt_config_template"
                                            + " ORDER BY id DESC LIMIT ? OFFSET ?",
                                    (rs, rowNum) ->
                                            new ExportedView(
                                                    rs.getLong("id"),
                                                    rs.getLong("tenant_id"),
                                                    rs.getString("tenant_code"),
                                                    rs.getString("tenant_name"),
                                                    rs.getString("name"),
                                                    rs.getInt("version"),
                                                    rs.getInt("format_version"),
                                                    rs.getInt("table_count"),
                                                    rs.getLong("row_count"),
                                                    rs.getString("package_hash"),
                                                    rs.getTimestamp("created_at")
                                                            .toLocalDateTime()
                                                            .atZone(ZONE)
                                                            .format(
                                                                    DateTimeFormatter
                                                                            .ISO_OFFSET_DATE_TIME)),
                                    size,
                                    offset);
                    return new PageView(items, total == null ? 0 : total, page, size);
                });
    }

    /** 模板详情：头信息 + 每张表元信息（不含行）。 */
    public TemplateDetail detail(long id) {
        CurrentSession.requirePlatform();
        ConfigPackage pkg = packageOf(id);
        return new TemplateDetail(
                header(id), pkg.tables().stream().map(ConfigPackage.TableSection::toMeta).toList());
    }

    /** 读取模板包（gzip 解压后的完整配置包）。纯读，不写审计——HTTP 下载入口走 {@link #download} 补审计。 */
    public ConfigPackage packageOf(long id) {
        CurrentSession.requirePlatform();
        byte[] body =
                TenantContext.callAsPlatform(
                        () -> {
                            List<byte[]> rows =
                                    platform.queryForList(
                                            "SELECT package_body FROM plt_config_template WHERE id = ?",
                                            byte[].class,
                                            id);
                            if (rows.isEmpty()) {
                                throw new ApiException(404, 40400, "配置模板不存在: " + id);
                            }
                            return rows.get(0);
                        });
        try {
            return json.readValue(ungzip(body), ConfigPackage.class);
        } catch (IOException ex) {
            throw new ApiException(500, 50000, "配置包解析失败：" + ex.getMessage());
        }
    }

    /** 下载模板包（AGENTS §5：下载写审计）。 */
    public ConfigPackage download(long id) {
        CurrentSession.requirePlatform();
        ConfigPackage pkg = packageOf(id);
        ConfigTemplateExportService.ExportedView head = header(id);
        audit.append(
                AuditEntry.operation("platform", "CONFIG_TEMPLATE_DOWNLOAD")
                        .tenantId(head.tenantId())
                        .objectType("plt_config_template")
                        .objectId(String.valueOf(id))
                        .description("下载配置模板 " + head.tenantCode() + " v" + head.version() + " 包体")
                        .fileHash(head.packageHash())
                        .success()
                        .build());
        return pkg;
    }

    /** 注册表清单（平台端预览「会导出什么」用，E12-T04）。 */
    public List<ConfigTableEntry> registry() {
        CurrentSession.requirePlatform();
        return ConfigTableRegistry.snapshot();
    }

    // ---------------------------------------------------------------- 导出内部步骤

    private List<ConfigPackage.TableSection> readSections(List<ConfigTableEntry> entries) {
        List<ConfigPackage.TableSection> sections = new ArrayList<>();
        for (ConfigTableEntry entry : entries) {
            boolean present = reader.tablePresent(entry.table());
            if (!present) {
                sections.add(
                        new ConfigPackage.TableSection(
                                entry.table(),
                                entry.module(),
                                entry.mode().name(),
                                entry.category(),
                                false,
                                0,
                                0,
                                0,
                                List.of(),
                                List.of()));
                continue;
            }
            List<String> columns = reader.columns(entry.table());
            long sourceCount = reader.countRows(entry.table());
            if (entry.mode() == ConfigCopyMode.STRUCTURE_ONLY) {
                // 只复制结构：不带取值（术者、病区 / 中心，A-FLD-014）
                sections.add(
                        new ConfigPackage.TableSection(
                                entry.table(),
                                entry.module(),
                                entry.mode().name(),
                                entry.category(),
                                true,
                                0,
                                0,
                                sourceCount,
                                columns,
                                List.of()));
                continue;
            }
            List<Map<String, Object>> rows =
                    reader.rows(entry.table(), columns, entry.excludeRows());
            sections.add(
                    new ConfigPackage.TableSection(
                            entry.table(),
                            entry.module(),
                            entry.mode().name(),
                            entry.category(),
                            true,
                            rows.size(),
                            sourceCount - rows.size(),
                            sourceCount,
                            columns,
                            rows));
        }
        return sections;
    }

    private Stored store(
            long tenantId,
            String code,
            String tenantName,
            String name,
            byte[] body,
            String hash,
            long rowCount,
            SessionPrincipal principal) {
        int version =
                TenantContext.callAsPlatform(
                        () -> {
                            List<Map<String, Object>> rows =
                                    platform.queryForList(
                                            "SELECT COALESCE(MAX(version), 0) + 1 AS v"
                                                    + " FROM plt_config_template WHERE tenant_id = ?",
                                            tenantId);
                            return ((Number) rows.get(0).get("v")).intValue();
                        });
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String sql =
                "INSERT INTO plt_config_template (tenant_id, tenant_code, tenant_name, name,"
                        + " version, format_version, table_count, row_count, package_hash,"
                        + " package_body, created_by, created_by_name) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        platform.update(
                (PreparedStatementCreator)
                        connection -> {
                            PreparedStatement statement =
                                    connection.prepareStatement(
                                            sql, Statement.RETURN_GENERATED_KEYS);
                            statement.setLong(1, tenantId);
                            statement.setString(2, code);
                            statement.setString(3, tenantName);
                            statement.setString(4, name);
                            statement.setInt(5, version);
                            statement.setInt(6, FORMAT_VERSION);
                            statement.setInt(7, ConfigTableRegistry.ENTRIES.size());
                            statement.setLong(8, rowCount);
                            statement.setString(9, hash);
                            statement.setBytes(10, body);
                            if (principal.userId() == null) {
                                statement.setNull(11, java.sql.Types.BIGINT);
                            } else {
                                statement.setLong(11, principal.userId());
                            }
                            statement.setString(12, principal.username());
                            return statement;
                        },
                keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("未取到 plt_config_template 自增主键");
        }
        return new Stored(key.longValue(), version);
    }

    private ExportedView header(long id) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> row =
                            platform.queryForMap(
                                    "SELECT id, tenant_id, tenant_code, tenant_name, name, version,"
                                            + " format_version, table_count, row_count, package_hash,"
                                            + " created_at FROM plt_config_template WHERE id = ?",
                                    id);
                    return new ExportedView(
                            ((Number) row.get("id")).longValue(),
                            ((Number) row.get("tenant_id")).longValue(),
                            String.valueOf(row.get("tenant_code")),
                            String.valueOf(row.get("tenant_name")),
                            String.valueOf(row.get("name")),
                            ((Number) row.get("version")).intValue(),
                            ((Number) row.get("format_version")).intValue(),
                            ((Number) row.get("table_count")).intValue(),
                            ((Number) row.get("row_count")).longValue(),
                            String.valueOf(row.get("package_hash")),
                            toIso(row.get("created_at")));
                });
    }

    /** created_at（MySQL 驱动回 LocalDateTime，也可能回 Timestamp）→ ISO-8601 带时区。 */
    private static String toIso(Object createdAt) {
        if (createdAt == null) {
            return null;
        }
        LocalDateTime time =
                createdAt instanceof java.sql.Timestamp stamp
                        ? stamp.toLocalDateTime()
                        : (LocalDateTime) createdAt;
        return time.atZone(ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private Map<String, Object> tenantRow(long tenantId) {
        return TenantContext.callAsPlatform(
                () -> {
                    List<Map<String, Object>> rows =
                            platform.queryForList(
                                    "SELECT id, code, name, status FROM plt_tenant WHERE id = ?",
                                    tenantId);
                    if (rows.isEmpty()) {
                        throw new ApiException(404, 40400, "租户不存在: " + tenantId);
                    }
                    return rows.get(0);
                });
    }

    private static String normalizeName(String name, String tenantName) {
        if (name == null || name.isBlank()) {
            return tenantName + " 配置模板";
        }
        String trimmed = name.trim();
        if (trimmed.length() > NAME_MAX) {
            throw new ApiException(400, 40001, "模板名称过长（最多 128 字）");
        }
        return trimmed;
    }

    static byte[] gzip(byte[] raw) {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                gzip.write(raw);
            }
            return buffer.toByteArray();
        } catch (IOException ex) {
            throw new ApiException(500, 50000, "配置包压缩失败：" + ex.getMessage());
        }
    }

    static byte[] ungzip(byte[] body) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(body);
                GZIPInputStream gzip = new GZIPInputStream(input);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            gzip.transferTo(buffer);
            return buffer.toByteArray();
        } catch (IOException ex) {
            throw new ApiException(500, 50000, "配置包解压失败：" + ex.getMessage());
        }
    }
}
