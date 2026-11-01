package com.crdp.framework.datascope;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 研究项目数据范围扩展点的空实现（E07-T03 / PLT-PERM-008）：rct 模块首期不建（tech-plan「不做」清单）， 无处提供项目成员关系，恒返回空清单——
 * RESEARCH_PROJECT 分支不放宽任何数据，符合数据范围默认拒绝口径。 rct 模块落地时提供 {@code @Primary} 实现替换本组件。
 */
@Component
public class NoopResearchProjectScopeProvider implements ResearchProjectScopeProvider {

    @Override
    public List<Long> memberProjectIds(long userId) {
        return List.of();
    }
}
