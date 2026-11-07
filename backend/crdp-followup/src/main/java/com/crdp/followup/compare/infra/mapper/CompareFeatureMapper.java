package com.crdp.followup.compare.infra.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 租户库 cfg_feature 只读（E17-T01：对比标红功能开关 compare_highlight 的运行时判定）。 写入走平台侧配置（无租户自助界面）。 */
@Mapper
public interface CompareFeatureMapper {

    /**
     * 功能开关当前值；无行返回 null（视为默认启用——生产库无种子行，tech-plan §10 对比标红 A 默认开； 关闭显式落 enabled=0，此时接口
     * 40301、前端隐藏入口，AGENTS.md 规则 7）。
     */
    @Select("SELECT enabled FROM cfg_feature WHERE feature_code = #{featureCode}")
    Boolean findEnabled(@Param("featureCode") String featureCode);
}
