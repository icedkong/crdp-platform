package com.crdp.framework.datascope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.security.ScopeSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

/**
 * E07-T03 / PLT-PERM-008：研究项目数据范围扩展点。验收口径「扩展点接口与空实现可用」：
 *
 * <ul>
 *   <li>空实现 {@link NoopResearchProjectScopeProvider} 恒返回空清单，且默认注册为 Spring 组件（无 rct 模块时底座可启动、登录可装配）；
 *   <li>接口按用户 id 取「成员项目」清单（真实成员关系由 rct 模块实现，首期不建）；
 *   <li>快照带项目清单时改写器 fail closed（拒绝执行而不是静默少过滤）——项目 → 患者的 SQL 分支属 rct 模块规则，未接入前不允许半生效。
 * </ul>
 */
class ResearchProjectScopeExtensionTest {

    @Test
    void noopProviderReturnsNoProjectsForAnyUser() {
        NoopResearchProjectScopeProvider noop = new NoopResearchProjectScopeProvider();
        assertEquals(List.of(), noop.memberProjectIds(1L));
        assertEquals(List.of(), noop.memberProjectIds(0L));
    }

    /** 空实现必须默认注册（@Component），否则无 rct 模块时 HospitalDirectory 注入失败、底座起不来。 */
    @Test
    void noopProviderIsRegisteredAsSpringComponent() {
        assertNotNull(
                NoopResearchProjectScopeProvider.class.getAnnotation(Component.class),
                "空实现以 @Component 默认注册；rct 模块落地时以 @Primary 实现替换");
    }

    /** 扩展点可被业务模块实现：返回的项目清单冻结进快照（登录装配路径由 HospitalDirectory 测试覆盖）。 */
    @Test
    void extensionImplementationFeedsSnapshotProjectBranch() {
        ResearchProjectScopeProvider twoProjects = userId -> List.of(7L, 8L);
        ScopeSnapshot scope =
                ScopeSnapshot.restricted(
                        List.of(), List.of(), false, twoProjects.memberProjectIds(5L));
        assertEquals(List.of(7L, 8L), scope.projectIds());
    }

    @Test
    void snapshotWithProjectsFailsClosedInGuard() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(), List.of(), false, List.of(7L));
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                DataScopeGuard.enforce(
                                        "SELECT id FROM pt_patient WHERE deleted = 0",
                                        scope,
                                        5L,
                                        false));
        assertTrue(ex.getMessage().contains("研究项目"), "拒绝原因说明项目分支待 rct 模块提供：" + ex.getMessage());
    }

    /** 与其他分支并集时同样拒绝：半接入的扩展实现不得只放行病区 / 科室分支（宁可拒绝执行）。 */
    @Test
    void snapshotWithProjectsAndWardsStillFailsClosed() {
        ScopeSnapshot scope = ScopeSnapshot.restricted(List.of(3L), List.of(), false, List.of(7L));
        assertThrows(
                IllegalStateException.class,
                () ->
                        DataScopeGuard.enforce(
                                "SELECT id FROM pt_patient WHERE deleted = 0", scope, 5L, false));
    }
}
