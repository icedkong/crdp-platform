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
import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.configtemplate.ConfigImportSpec;
import com.crdp.platform.configtemplate.ConfigImportSpecs;
import com.crdp.server.platform.TenantScopeService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * E12-T02 / A-FLD-014：配置模板导入服务（单测；真实库端到端见 {@code ConfigTemplateImportMySqlIT}）。
 *
 * <p>核心口径：用户引用列置空、数值引用按「源 ID → 目标 ID」改写（改写不到丢行）、角色菜单与新租户可分配范围取交集、 目标租户库缺表 / 源模块未开节的跳过、自租户导入拒绝；导入完成写
 * CONFIG_TEMPLATE_APPLY 审计并递增目标租户用户权限版本。
 */
class ConfigTemplateImportServiceTest {

    private static final long SOURCE_TENANT = 1L; // demoa
    private static final long TARGET_TENANT = 2L; // demob

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantConfigReader reader = mock(TenantConfigReader.class);
    private final ConfigTemplateExportService exporter = mock(ConfigTemplateExportService.class);
    private final TenantScopeService tenantScopes = mock(TenantScopeService.class);
    private final TenantRoutingDataSource routing = mock(TenantRoutingDataSource.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final PermissionVersionStore permissionVersions = mock(PermissionVersionStore.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);

    private ConfigTemplateImportService service;

    @BeforeEach
    void setUp() {
        service =
                new ConfigTemplateImportService(
                        jdbc,
                        reader,
                        exporter,
                        tenantScopes,
                        routing,
                        audit,
                        permissionVersions,
                        transactionManager);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
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

    // ---------------------------------------------------------------- 入口校验

    @Test
    void missingTenantIdIs40001() {
        assertThatThrownBy(
                        () -> service.apply(9L, new ConfigTemplateImportService.ApplyRequest(null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(40001));
    }

    @Test
    void applyingToSourceTenantItselfIsRejected() {
        stubTemplateHeader(9L);
        stubTenantRow(SOURCE_TENANT, "demoa");
        assertThatThrownBy(
                        () ->
                                service.apply(
                                        9L,
                                        new ConfigTemplateImportService.ApplyRequest(
                                                SOURCE_TENANT)))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        ex -> {
                            assertThat(((ApiException) ex).code()).isEqualTo(40001);
                            assertThat(ex.getMessage()).contains("源租户");
                        });
        verify(exporter, never()).packageOf(9L);
    }

    @Test
    void unknownTargetTenantIs40400() {
        stubTemplateHeader(9L);
        when(jdbc.queryForList(contains("FROM plt_tenant"), eq(999L))).thenReturn(List.of());
        assertThatThrownBy(
                        () -> service.apply(9L, new ConfigTemplateImportService.ApplyRequest(999L)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(40400));
    }

    @Test
    void targetWithoutRegisteredDataSourceIs40901() {
        stubTemplateHeader(9L);
        stubTenantRow(TARGET_TENANT, "demob");
        when(routing.hasTenant(TARGET_TENANT)).thenReturn(false);
        assertThatThrownBy(
                        () ->
                                service.apply(
                                        9L,
                                        new ConfigTemplateImportService.ApplyRequest(
                                                TARGET_TENANT)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(40901));
    }

    @Test
    void unsupportedFormatVersionIs40901() {
        stubTemplateHeader(9L);
        stubTenantRow(TARGET_TENANT, "demob");
        when(routing.hasTenant(TARGET_TENANT)).thenReturn(true);
        when(exporter.packageOf(9L))
                .thenReturn(
                        new ConfigPackage(
                                99, "demoa", "医院 A（示例）", "2026-11-06T00:00:00+08:00", List.of()));
        assertThatThrownBy(
                        () ->
                                service.apply(
                                        9L,
                                        new ConfigTemplateImportService.ApplyRequest(
                                                TARGET_TENANT)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(40901));
    }

    // ---------------------------------------------------------------- 行级纯逻辑

    @Test
    void prepareRowNullsUserReferences() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 11L);
        row.put("code", "it-dict");
        row.put("created_by", 7L);
        row.put("updated_by", 8L);
        row.put("published_by", 9L);
        ConfigImportSpec spec = ConfigImportSpecs.of("cfg_dict");
        String drop = ConfigTemplateImportService.prepareRow(row, spec, Map.of());
        assertThat(drop).isNull();
        assertThat(row.get("created_by")).isNull();
        assertThat(row.get("updated_by")).isNull();
        assertThat(row.get("published_by")).isNull();
    }

    @Test
    void prepareRowRewritesNumericReferences() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role_id", 33);
        row.put("scope_type", "ALL");
        ConfigImportSpec spec = ConfigImportSpecs.of("sys_role_scope");
        String drop =
                ConfigTemplateImportService.prepareRow(
                        row, spec, Map.of("sys_role", Map.of(33L, 500L)));
        assertThat(drop).isNull();
        assertThat(row.get("role_id")).isEqualTo(500L);
    }

    @Test
    void prepareRowDropsUnresolvableReference() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role_id", 404);
        row.put("menu_id", 10);
        ConfigImportSpec spec = ConfigImportSpecs.of("sys_role_menu");
        String drop =
                ConfigTemplateImportService.prepareRow(
                        row, spec, Map.of("sys_role", Map.of(33L, 500L)));
        assertThat(drop).contains("role_id=404").contains("sys_role");
    }

    // ---------------------------------------------------------------- 导入主流程（小包：字典 + 角色授权）

    @Test
    void applyImportsMergesAndFiltersByAssignableRange() throws Exception {
        stubTemplateHeader(9L);
        stubTenantRow(TARGET_TENANT, "demob");
        when(routing.hasTenant(TARGET_TENANT)).thenReturn(true);
        when(tenantScopes.assignableMenuIds(TARGET_TENANT)).thenReturn(Set.of(10L));
        // 包：cfg_dict 2 行（1 行目标已有 → 合并；1 行新插）+ sys_role 1 行 + sys_role_menu 3 行
        // （范围内、范围外 999999、角色未映射）
        when(exporter.packageOf(9L))
                .thenReturn(
                        new ConfigPackage(
                                1,
                                "demoa",
                                "医院 A（示例）",
                                "2026-11-06T00:00:00+08:00",
                                List.of(
                                        section(
                                                "cfg_dict",
                                                List.of("id", "code", "name", "updated_by"),
                                                List.of(
                                                        row(11L, "IT_EXISTING", "既有（示例）", 7L),
                                                        row(12L, "IT_NEW", "新增（示例）", 7L))),
                                        section(
                                                "sys_role",
                                                List.of("id", "code", "name"),
                                                List.of(row(33L, "IT_ROLE", "导入角色（示例）"))),
                                        section(
                                                "sys_role_menu",
                                                List.of("role_id", "menu_id"),
                                                List.of(
                                                        row2(33L, 10L),
                                                        row2(33L, 999_999L),
                                                        row2(404L, 10L))))));
        when(reader.tablePresent(anyString())).thenReturn(true);
        when(reader.columns("cfg_dict")).thenReturn(List.of("id", "code", "name", "updated_by"));
        when(reader.columns("sys_role")).thenReturn(List.of("id", "code", "name"));
        when(reader.columns("sys_role_menu")).thenReturn(List.of("role_id", "menu_id"));
        // 目标库既有：cfg_dict 一行（自然键命中 → 合并）、sys_role 无、角色菜单授权表由 INSERT IGNORE 兜底
        // （loadExistingIds 用单参 queryForList(String) 重载）
        when(jdbc.queryForList(contains("FROM `cfg_dict`")))
                .thenReturn(List.of(Map.of("id", 55L, "code", "IT_EXISTING")));
        when(jdbc.queryForList(contains("FROM `sys_role`"))).thenReturn(List.of());
        stubInsertReturningIds();
        when(jdbc.update(contains("INSERT IGNORE INTO `sys_role_menu`"), any(Object[].class)))
                .thenReturn(1);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(5L));

        ConfigTemplateImportService.AppliedView view =
                service.apply(9L, new ConfigTemplateImportService.ApplyRequest(TARGET_TENANT));

        assertThat(view.tenantCode()).isEqualTo("demob");
        assertThat(view.sourceTenantCode()).isEqualTo("demoa");
        Map<String, ConfigTemplateImportService.TableResult> byTable =
                view.tables().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        ConfigTemplateImportService.TableResult::table, t -> t));
        // cfg_dict：1 合并 + 1 插入；updated_by 不进 SET 之外（列含 updated_by 且被置空 → SET NULL）
        ConfigTemplateImportService.TableResult dict = byTable.get("cfg_dict");
        assertThat(dict.inserted()).isEqualTo(1);
        assertThat(dict.merged()).isEqualTo(1);
        // sys_role_menu：范围内 1 插入；范围外 999999 与未映射角色 404 各丢弃 1
        ConfigTemplateImportService.TableResult menus = byTable.get("sys_role_menu");
        assertThat(menus.inserted()).isEqualTo(1);
        assertThat(menus.dropped()).isEqualTo(2);
        // 汇总计数与审计
        assertThat(view.insertedRows()).isGreaterThanOrEqualTo(3);
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("CONFIG_TEMPLATE_APPLY");
        assertThat(captor.getValue().tenantId()).isEqualTo(TARGET_TENANT);
        assertThat(captor.getValue().fileHash()).isEqualTo("hash-it");
        // 目标租户用户权限版本递增（角色授权可能变化）
        verify(permissionVersions).bump(eq("HOSPITAL"), eq(TARGET_TENANT), eq(5L));
    }

    @Test
    void sectionsMissingInTargetDatabaseAreSkipped() {
        stubTemplateHeader(9L);
        stubTenantRow(TARGET_TENANT, "demob");
        when(routing.hasTenant(TARGET_TENANT)).thenReturn(true);
        when(tenantScopes.assignableMenuIds(TARGET_TENANT)).thenReturn(Set.of());
        when(exporter.packageOf(9L))
                .thenReturn(
                        new ConfigPackage(
                                1,
                                "demoa",
                                "医院 A（示例）",
                                "2026-11-06T00:00:00+08:00",
                                List.of(
                                        section(
                                                "ntf_rule",
                                                List.of("id", "event_type"),
                                                List.of(row(21L, "TASK_OVERDUE"))),
                                        absentSection("fe_field"))));
        // 目标租户库没有 ntf_rule 表（模块未开通）→ 整表跳过；源 absent 节也跳过
        when(reader.tablePresent("ntf_rule")).thenReturn(false);
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of());

        ConfigTemplateImportService.AppliedView view =
                service.apply(9L, new ConfigTemplateImportService.ApplyRequest(TARGET_TENANT));

        Map<String, ConfigTemplateImportService.TableResult> byTable =
                view.tables().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        ConfigTemplateImportService.TableResult::table, t -> t));
        assertThat(byTable.get("ntf_rule").imported()).isFalse();
        assertThat(byTable.get("ntf_rule").skipped()).isEqualTo(1);
        assertThat(byTable.get("fe_field").skipped()).isZero();
        assertThat(byTable.get("fe_field").note()).contains("present=false");
    }

    // ---------------------------------------------------------------- 桩

    private void stubTemplateHeader(long id) {
        when(exporter.headerOf(id))
                .thenReturn(
                        new ConfigTemplateExportService.ExportedView(
                                id,
                                SOURCE_TENANT,
                                "demoa",
                                "医院 A（示例）",
                                "A 配置模板（示例）",
                                1,
                                1,
                                20,
                                10,
                                "hash-it",
                                "2026-11-06T00:00:00+08:00"));
    }

    private void stubTenantRow(long tenantId, String code) {
        when(jdbc.queryForList(contains("FROM plt_tenant"), eq(tenantId)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id",
                                        tenantId,
                                        "code",
                                        code,
                                        "name",
                                        "目标医院（示例）",
                                        "status",
                                        "TRIAL")));
    }

    /** 让每个 INSERT ... RETURN_GENERATED_KEYS 递增分配主键（100 起）。 */
    private void stubInsertReturningIds() {
        try {
            AtomicLong sequence = new AtomicLong(100);
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString(), anyInt())).thenReturn(statement);
            doAnswer(
                            inv -> {
                                PreparedStatementCreator creator = inv.getArgument(0);
                                creator.createPreparedStatement(connection);
                                KeyHolder holder = inv.getArgument(1);
                                holder.getKeyList()
                                        .add(Map.of("GENERATED_KEY", sequence.incrementAndGet()));
                                return 1;
                            })
                    .when(jdbc)
                    .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ConfigPackage.TableSection section(
            String table, List<String> columns, List<Map<String, Object>> rows) {
        return new ConfigPackage.TableSection(
                table, "base", "COPY", "IT", true, rows.size(), 0, rows.size(), columns, rows);
    }

    private static ConfigPackage.TableSection absentSection(String table) {
        return new ConfigPackage.TableSection(
                table, "followup", "COPY", "IT", false, 0, 0, 0, List.of(), List.of());
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", pairs[0]);
        row.put("code", pairs[1]);
        row.put("name", pairs.length > 2 ? pairs[2] : null);
        if (pairs.length > 3) {
            row.put("updated_by", pairs[3]);
        }
        return row;
    }

    private static Map<String, Object> row2(long roleId, long menuId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role_id", roleId);
        row.put("menu_id", menuId);
        return row;
    }
}
