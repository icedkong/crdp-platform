package com.crdp.framework.datascope;

import java.util.List;

/**
 * 研究项目数据范围扩展点（E07-T03 / PLT-PERM-008）：按项目成员制控制，底座只负责把「用户作为成员的研究项目」冻结进登录快照 （{@code
 * ScopeSnapshot.projectIds}）；项目内角色与「项目 → 可见患者」的 SQL 规则由 rct 模块实现（tech-plan「首期不做」： 随机化、盲法、质疑流程等 RCT
 * 功能不在本期）。
 *
 * <p>触发时机：登录（及会话 / 权限重算）装配快照时，仅当启用角色的范围类型并集含 RESEARCH_PROJECT 才调用（{@code
 * HospitalDirectory.assemble}）。调用时租户上下文已就位，实现方按当前租户库取数。
 *
 * <p>空实现：底座默认注册 {@link NoopResearchProjectScopeProvider}（恒返回空清单）——项目分支为空即不放宽任何数据，只有
 * RESEARCH_PROJECT 类型的用户为空范围（看不到患者，默认拒绝口径）。rct 模块提供真实实现时以 {@code @Primary}（或移除空实现注册）替换， 并补齐 {@code
 * RestrictedScopeRewriter} 的项目分支：快照带项目清单而改写器没有对应分支时，改写器拒绝执行 （IllegalStateException），不会静默少过滤。
 */
public interface ResearchProjectScopeProvider {

    /**
     * 用户作为成员的研究项目 id 清单（当前租户）。
     *
     * @param userId 租户库用户 id
     * @return 项目 id 清单；非成员或无项目返回空清单，不返回 null
     */
    List<Long> memberProjectIds(long userId);
}
