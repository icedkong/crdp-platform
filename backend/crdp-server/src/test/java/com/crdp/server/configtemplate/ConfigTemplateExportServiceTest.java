package com.crdp.server.configtemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.DigestService;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.configtemplate.ConfigTableRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E12-T01 / A-FLD-014：配置模板导出服务（单测，链路级端到端见 {@code ConfigTemplateExportMySqlIT}）。
 *
 * <p>核心口径：导出只遍历注册表条目——患者与业务数据表既不在注册表里，也不会被读取；STRUCTURE_ONLY 表（病区 / 中心）只带计数不带行；术者取值经行排除谓词剔除；包 gzip
 * 存平台库并可读回，写 CONFIG_TEMPLATE_EXPORT 审计。
 */
class ConfigTemplateExportServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantConfigReader reader = mock(TenantConfigReader.class);
    private final TenantRoutingDataSource routing = mock(TenantRoutingDataSource.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final DigestService digest = mock(DigestService.class);
    private final ObjectMapper json = new ObjectMapper();

    private ConfigTemplateExportService service;

    @BeforeEach
    void setUp() {
        service = new ConfigTemplateExportService(jdbc, reader, routing, audit, digest, json);
        SessionPrincipal principal =
                new SessionPrincipal(
                        "token-it",
                        "PLATFORM",
                        null,
                        null,
                        null,
                        9L,
                        "platform.admin",
                        "平台演示管理员",
                        List.of("PLATFORM_ADMIN"),
                        List.of(),
                        List.of("platform:tenant:create"),
                        List.of(),
                        1,
                        ScopeSnapshot.allowAll(),
                        false,
                        null);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(principal, null));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unknownTenantIs40400() {
        assertThatThrownBy(
                        () ->
                                service.export(
                                        new ConfigTemplateExportService.ExportRequest(999L, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(40400));
    }

    @Test
    void tenantWithoutRegisteredDataSourceIs40901() {
        tenantRow();
        when(routing.hasTenant(1L)).thenReturn(false);
        assertThatThrownBy(
                        () ->
                                service.export(
                                        new ConfigTemplateExportService.ExportRequest(1L, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(40901));
    }

    @Test
    void longNameIsRejected() {
        tenantRow();
        when(routing.hasTenant(1L)).thenReturn(true);
        assertThatThrownBy(
                        () ->
                                service.export(
                                        new ConfigTemplateExportService.ExportRequest(
                                                1L, "名".repeat(129))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(40001));
    }

    @Test
    void blankNameDefaultsToTenantName() {
        tenantRow();
        when(routing.hasTenant(1L)).thenReturn(true);
        when(reader.tablePresent(anyString())).thenReturn(false);
        stubStore();

        ConfigTemplateExportService.ExportedView view =
                service.export(new ConfigTemplateExportService.ExportRequest(1L, "  "));
        assertThat(view.name()).isEqualTo("医院 A（示例） 配置模板");
    }

    @Test
    void exportBuildsPackageFromRegistryOnly() {
        tenantRow();
        when(routing.hasTenant(1L)).thenReturn(true);
        // 租户库里只有三张注册表存在：fe_field（COPY，2 行）、ward（STRUCTURE_ONLY，12 行只计数）、
        // cfg_dict_item（COPY，3 行中术者取值被谓词剔除）；其余注册表条目 absent。
        when(reader.tablePresent(anyString()))
                .thenAnswer(
                        inv ->
                                List.of("fe_field", "ward", "cfg_dict_item")
                                        .contains(inv.getArgument(0)));
        when(reader.columns("fe_field")).thenReturn(List.of("id", "field_code"));
        when(reader.columns("ward")).thenReturn(List.of("id", "code"));
        when(reader.columns("cfg_dict_item")).thenReturn(List.of("id", "dict_code"));
        when(reader.countRows("fe_field")).thenReturn(2L);
        when(reader.countRows("ward")).thenReturn(12L);
        when(reader.countRows("cfg_dict_item")).thenReturn(3L);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 11L);
        row.put("field_code", "smoking_history");
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("id", 12L);
        row2.put("field_code", "height_cm");
        when(reader.rows(eq("fe_field"), any(), eq(Map.of()))).thenReturn(List.of(row, row2));
        when(reader.rows(eq("cfg_dict_item"), any(), eq(Map.of("dict_code", "surgeon"))))
                .thenReturn(List.of());
        AtomicReference<byte[]> storedBody = stubStore();
        // SQL 已含 +1：此处桩值即“下个版本号”
        when(jdbc.queryForList(contains("MAX(version)"), eq(1L)))
                .thenReturn(List.of(Map.of("v", 3)));
        when(digest.hashHex(anyString())).thenReturn("hash-it");

        ConfigTemplateExportService.ExportedView view =
                service.export(new ConfigTemplateExportService.ExportRequest(1L, "A 配置模板（示例）"));

        assertThat(view.id()).isEqualTo(77L);
        assertThat(view.version()).isEqualTo(3);
        assertThat(view.tenantCode()).isEqualTo("demoa");
        assertThat(view.tenantName()).isEqualTo("医院 A（示例）");
        assertThat(view.name()).isEqualTo("A 配置模板（示例）");
        // 表数 = 注册表全量（含 absent 条目），行数 = 入包行数合计（2）
        assertThat(view.tableCount()).isEqualTo(ConfigTableRegistry.ENTRIES.size());
        assertThat(view.rowCount()).isEqualTo(2L);
        assertThat(view.packageHash()).isEqualTo("hash-it");

        // 存进去的包能按 id 读回，内容按注册表顺序、只含注册表条目
        when(jdbc.queryForList(contains("package_body"), eq(byte[].class), eq(77L)))
                .thenAnswer(inv -> List.of(storedBody.get()));
        ConfigPackage pkg = service.packageOf(77L);
        assertThat(pkg.tenantCode()).isEqualTo("demoa");
        assertThat(pkg.formatVersion()).isEqualTo(1);
        assertThat(pkg.tables())
                .extracting(ConfigPackage.TableSection::table)
                .containsExactlyElementsOf(ConfigTableRegistry.tables());
        // JSON 往返后小整数变 Integer，按列值断言
        assertThat(section(pkg, "fe_field").rows())
                .extracting(r -> r.get("field_code"))
                .containsExactly("smoking_history", "height_cm");
        assertThat(section(pkg, "fe_field").rowCount()).isEqualTo(2);
        ConfigPackage.TableSection wardSection = section(pkg, "ward");
        assertThat(wardSection.mode()).isEqualTo("STRUCTURE_ONLY");
        assertThat(wardSection.rows()).isEmpty();
        assertThat(wardSection.sourceRowCount()).isEqualTo(12L);
        // STRUCTURE_ONLY 不读行、不带取值
        verify(reader, never()).rows(eq("ward"), any(), any());
        ConfigPackage.TableSection items = section(pkg, "cfg_dict_item");
        assertThat(items.rowCount()).isZero();
        assertThat(items.excludedRowCount()).isEqualTo(3L);
        ConfigPackage.TableSection absent = section(pkg, "ntf_setting");
        assertThat(absent.present()).isFalse();
        assertThat(absent.rows()).isEmpty();
        assertThat(absent.columns()).isEmpty();
        verify(reader, never()).rows(eq("ntf_setting"), any(), any());

        // 审计：CONFIG_TEMPLATE_EXPORT，带租户与包哈希
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("CONFIG_TEMPLATE_EXPORT");
        assertThat(captor.getValue().tenantId()).isEqualTo(1L);
        assertThat(captor.getValue().fileHash()).isEqualTo("hash-it");
    }

    @Test
    void userReferenceColumnsAreNulledInPackage() throws Exception {
        // tech-plan §6「配置模板」：导出前敏感信息检查——配置中引用用户的字段一律置空（账号不随模板复制）
        tenantRow();
        when(routing.hasTenant(1L)).thenReturn(true);
        when(reader.tablePresent(anyString())).thenReturn(false);
        when(reader.tablePresent("fe_form_version")).thenReturn(true);
        when(reader.columns("fe_form_version")).thenReturn(List.of("id", "published_by"));
        when(reader.countRows("fe_form_version")).thenReturn(1L);
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("id", 31L);
        version.put("published_by", 7L);
        when(reader.rows(eq("fe_form_version"), any(), eq(Map.of()))).thenReturn(List.of(version));
        AtomicReference<byte[]> storedBody = stubStore();
        when(digest.hashHex(anyString())).thenReturn("hash-it");

        service.export(new ConfigTemplateExportService.ExportRequest(1L, null));

        when(jdbc.queryForList(contains("package_body"), eq(byte[].class), eq(77L)))
                .thenAnswer(inv -> List.of(storedBody.get()));
        ConfigPackage pkg = service.packageOf(77L);
        ConfigPackage.TableSection section =
                pkg.tables().stream()
                        .filter(t -> "fe_form_version".equals(t.table()))
                        .findFirst()
                        .orElseThrow();
        assertThat(section.rows()).hasSize(1);
        assertThat(section.rows().get(0).get("published_by")).isNull();
    }

    @Test
    void exportIsBlockedWhenConfigValueLooksSensitive() {
        // 导出自检（E12-T03）：配置值里出现疑似患者敏感值（手机号 / 证件号）→ 拦截，不落库，写失败审计
        tenantRow();
        when(routing.hasTenant(1L)).thenReturn(true);
        when(reader.tablePresent(anyString())).thenReturn(false);
        when(reader.tablePresent("ntf_rule")).thenReturn(true);
        when(reader.columns("ntf_rule")).thenReturn(List.of("id", "event_type", "remark"));
        when(reader.countRows("ntf_rule")).thenReturn(1L);
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", 21L);
        rule.put("event_type", "TASK_OVERDUE");
        rule.put("remark", "联系 13812345678（示例）");
        when(reader.rows(eq("ntf_rule"), any(), eq(Map.of()))).thenReturn(List.of(rule));
        stubStore();

        assertThatThrownBy(
                        () ->
                                service.export(
                                        new ConfigTemplateExportService.ExportRequest(1L, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        ex -> {
                            assertThat(((ApiException) ex).code()).isEqualTo(40901);
                            assertThat(ex.getMessage()).contains("敏感");
                        });
        // 不落库：INSERT 包体从未执行；审计写 FAIL
        verify(jdbc, never()).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("CONFIG_TEMPLATE_EXPORT");
        assertThat(captor.getValue().result()).isEqualTo(com.crdp.framework.audit.AuditResult.FAIL);
    }

    @Test
    void exportIsBlockedWhenExcludedSurgeonRowLeaksIn() {
        // 防御深度：行排除谓词失效（读行侧漏进术者取值）→ 自检拦截导出
        tenantRow();
        when(routing.hasTenant(1L)).thenReturn(true);
        when(reader.tablePresent(anyString())).thenReturn(false);
        when(reader.tablePresent("cfg_dict_item")).thenReturn(true);
        when(reader.columns("cfg_dict_item")).thenReturn(List.of("id", "dict_code"));
        when(reader.countRows("cfg_dict_item")).thenReturn(1L);
        Map<String, Object> surgeon = new LinkedHashMap<>();
        surgeon.put("id", 41L);
        surgeon.put("dict_code", "surgeon");
        when(reader.rows(eq("cfg_dict_item"), any(), eq(Map.of("dict_code", "surgeon"))))
                .thenReturn(List.of(surgeon));
        stubStore();

        assertThatThrownBy(
                        () ->
                                service.export(
                                        new ConfigTemplateExportService.ExportRequest(1L, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(40901));
        verify(jdbc, never()).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    /** 捕获 INSERT 的包体（setBytes 第 10 参）并让主键固定为 77；版本号缺省取 1（无历史模板）。 */
    private AtomicReference<byte[]> stubStore() {
        AtomicReference<byte[]> body = new AtomicReference<>();
        when(jdbc.queryForList(contains("MAX(version)"), eq(1L)))
                .thenReturn(List.of(Map.of("v", 1)));
        try {
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString(), anyInt())).thenReturn(statement);
            doAnswer(
                            inv -> {
                                if (Integer.valueOf(10).equals(inv.getArgument(0))) {
                                    body.set(inv.getArgument(1));
                                }
                                return null;
                            })
                    .when(statement)
                    .setBytes(anyInt(), any(byte[].class));
            doAnswer(
                            inv -> {
                                PreparedStatementCreator creator = inv.getArgument(0);
                                creator.createPreparedStatement(connection);
                                KeyHolder holder = inv.getArgument(1);
                                holder.getKeyList().add(Map.of("GENERATED_KEY", 77L));
                                return 1;
                            })
                    .when(jdbc)
                    .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return body;
    }

    private void tenantRow() {
        when(jdbc.queryForList(contains("FROM plt_tenant"), eq(1L)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id", 1L,
                                        "code", "demoa",
                                        "name", "医院 A（示例）",
                                        "status", "ENABLED")));
    }

    private static ConfigPackage.TableSection section(ConfigPackage pkg, String table) {
        return pkg.tables().stream().filter(t -> t.table().equals(table)).findFirst().orElseThrow();
    }
}
