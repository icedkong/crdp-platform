package com.crdp.system.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.datascope.NoopResearchProjectScopeProvider;
import com.crdp.framework.datascope.ResearchProjectScopeProvider;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.system.infra.mapper.BrandMapper;
import com.crdp.system.infra.mapper.HospitalMapper;
import com.crdp.system.infra.mapper.HospitalUserRow;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E07-T03 / PLT-PERM-008：研究项目数据范围扩展点在登录装配路径可用。HospitalDirectory 装配快照时按 RESEARCH_PROJECT 类型调用 {@link
 * ResearchProjectScopeProvider}；空实现（无 rct 模块）不放宽任何数据。
 *
 * <p>MySQL 取数与端到端链路（含改写侧 fail closed）由 DataScopeTypesIT 覆盖；这里 mock Mapper 只验证 扩展点的装配契约。
 */
class HospitalDirectoryResearchProjectTest {

    private static final long USER_ID = 9L;

    private final HospitalMapper mapper = mock(HospitalMapper.class);
    private final BrandMapper brandMapper = mock(BrandMapper.class);
    private final ResearchProjectScopeProvider provider = mock(ResearchProjectScopeProvider.class);

    private HospitalDirectory directory;

    @BeforeEach
    void setUp() {
        directory = new HospitalDirectory(mapper, brandMapper, provider);
        HospitalUserRow row = new HospitalUserRow();
        row.setId(USER_ID);
        row.setUsername("itr.researcher");
        row.setDisplayName("研究者（示例）");
        row.setPasswordHash("IT-PLACEHOLDER");
        row.setStatus("ENABLED");
        lenient().when(mapper.findUser("itr.researcher")).thenReturn(row);
        lenient().when(mapper.roles(USER_ID)).thenReturn(List.of("ITR_ROLE"));
        lenient().when(mapper.menuIds(USER_ID)).thenReturn(List.of());
        lenient().when(provider.memberProjectIds(USER_ID)).thenReturn(List.of(7L, 8L));
    }

    private Optional<HospitalAccount> find() {
        return directory.find("itr.researcher");
    }

    @Test
    void assembleConsultsExtensionWhenResearchProjectTriggered() {
        when(mapper.scopeTypes(USER_ID)).thenReturn(List.of("RESEARCH_PROJECT"));

        ScopeSnapshot scope = find().orElseThrow().scope();
        assertEquals(List.of(7L, 8L), scope.projectIds(), "扩展点返回的成员项目冻结进快照");
        assertFalse(scope.empty());
        verify(provider).memberProjectIds(USER_ID);
    }

    @Test
    void assembleSkipsExtensionWhenTypeNotTriggered() {
        when(mapper.scopeTypes(USER_ID)).thenReturn(List.of("WARD"));

        ScopeSnapshot scope = find().orElseThrow().scope();
        assertTrue(scope.projectIds().isEmpty(), "未触发研究项目类型不产生项目分支");
        verify(provider, never()).memberProjectIds(anyLong());
    }

    /** 无 rct 模块：底座默认注册的空实现 → RESEARCH_PROJECT 用户为空范围（看不到患者）。 */
    @Test
    void noopProviderKeepsResearchProjectUserEmpty() {
        when(mapper.scopeTypes(USER_ID)).thenReturn(List.of("RESEARCH_PROJECT"));
        HospitalDirectory noopDirectory =
                new HospitalDirectory(mapper, brandMapper, new NoopResearchProjectScopeProvider());

        ScopeSnapshot scope = noopDirectory.find("itr.researcher").orElseThrow().scope();
        assertTrue(scope.projectIds().isEmpty());
        assertTrue(scope.empty(), "空实现不放宽任何数据：只有研究项目类型的用户看不到患者");
    }
}
