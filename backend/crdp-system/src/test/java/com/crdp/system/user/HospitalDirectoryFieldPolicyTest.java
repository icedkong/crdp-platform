package com.crdp.system.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.datascope.NoopResearchProjectScopeProvider;
import com.crdp.framework.fieldpolicy.SensitiveFieldCatalog;
import com.crdp.system.infra.mapper.BrandMapper;
import com.crdp.system.infra.mapper.HospitalMapper;
import com.crdp.system.infra.mapper.HospitalUserRow;
import com.crdp.system.infra.mapper.RoleFieldPolicyRow;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E07-T04 / PLT-PERM-009：字段策略在登录装配路径可用——{@link HospitalDirectory} 装配账号时
 * 读取启用角色的显式覆盖（sys_role_field_policy 并集）并「取最宽松」合并进 {@link HospitalAccount#fieldPolicies()}。
 *
 * <p>MySQL 取数（只统计启用角色）与端到端链路由 PermissionScopeMaskingIT 覆盖；这里 mock Mapper 验证
 * 装配契约：无覆盖回落平台默认、多角色覆盖取最宽松。
 */
class HospitalDirectoryFieldPolicyTest {

    private static final long USER_ID = 9L;

    private final HospitalMapper mapper = mock(HospitalMapper.class);
    private final BrandMapper brandMapper = mock(BrandMapper.class);

    private HospitalDirectory directory;

    @BeforeEach
    void setUp() {
        directory =
                new HospitalDirectory(mapper, brandMapper, new NoopResearchProjectScopeProvider());
        HospitalUserRow row = new HospitalUserRow();
        row.setId(USER_ID);
        row.setUsername("itr.field.demo");
        row.setDisplayName("字段策略用户（示例）");
        row.setPasswordHash("IT-PLACEHOLDER");
        row.setStatus("ENABLED");
        lenient().when(mapper.findUser("itr.field.demo")).thenReturn(row);
        lenient().when(mapper.roles(USER_ID)).thenReturn(List.of("ITR_ROLE"));
        lenient().when(mapper.menuIds(USER_ID)).thenReturn(List.of());
        lenient().when(mapper.scopeTypes(USER_ID)).thenReturn(List.of());
        lenient().when(mapper.fieldPolicies(USER_ID)).thenReturn(List.of());
    }

    private HospitalAccount find() {
        return directory.find("itr.field.demo").orElseThrow();
    }

    /** 无任何显式覆盖：五位敏感字段全部平台默认 MASKED（AGENTS.md §5.3 默认脱敏）。 */
    @Test
    void assembleWithoutOverridesFallsBackToPlatformDefaults() {
        HospitalAccount account = find();
        for (SensitiveFieldCatalog.Entry entry : SensitiveFieldCatalog.all()) {
            assertEquals(
                    SensitiveFieldCatalog.POLICY_MASKED,
                    account.fieldPolicies().decide(entry.resource(), entry.field()),
                    entry.field() + " 未覆盖应回落默认脱敏");
        }
        verify(mapper).fieldPolicies(USER_ID);
    }

    /** 多角色显式覆盖取最宽松：角色甲把姓名收紧为 HIDDEN、角色乙放开 VISIBLE → 明文；住址只被收紧 → 隐藏。 */
    @Test
    void assembleMergesRoleOverridesMostPermissive() {
        when(mapper.fieldPolicies(USER_ID))
                .thenReturn(
                        List.of(
                                row("patient", "name", "HIDDEN"),
                                row("patient", "address", "HIDDEN"),
                                // 另一角色的行（SQL 已按角色并集返回）
                                row("patient", "name", "VISIBLE"),
                                row("patient", "medicalRecordNo", "VISIBLE")));

        HospitalAccount account = find();
        assertEquals(
                SensitiveFieldCatalog.POLICY_VISIBLE,
                account.fieldPolicies().decide("patient", "name"),
                "隐藏 + 明文 → 明文（叠加授权取最宽松）");
        assertEquals(
                SensitiveFieldCatalog.POLICY_HIDDEN,
                account.fieldPolicies().decide("patient", "address"),
                "唯一显式覆盖生效（未覆盖角色不注入默认）");
        assertEquals(
                SensitiveFieldCatalog.POLICY_VISIBLE,
                account.fieldPolicies().decide("patient", "medicalRecordNo"));
        assertEquals(
                SensitiveFieldCatalog.POLICY_MASKED,
                account.fieldPolicies().decide("patient", "phone"),
                "无覆盖字段继续默认脱敏");
    }

    /** 合并结果随会话冻结：快照只含显式合并项，未覆盖字段留给 decide 回落目录默认。 */
    @Test
    void snapshotCarriesOnlyExplicitMergedEntries() {
        when(mapper.fieldPolicies(USER_ID)).thenReturn(List.of(row("patient", "name", "VISIBLE")));
        HospitalAccount account = find();
        assertTrue(account.fieldPolicies().explicit().containsKey("patient.name"));
        assertEquals(1, account.fieldPolicies().explicit().size());
    }

    private static RoleFieldPolicyRow row(String resource, String field, String policy) {
        RoleFieldPolicyRow row = new RoleFieldPolicyRow();
        row.setResource(resource);
        row.setField(field);
        row.setPolicy(policy);
        return row;
    }
}
