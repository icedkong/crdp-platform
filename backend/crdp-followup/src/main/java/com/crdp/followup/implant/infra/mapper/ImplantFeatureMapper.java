package com.crdp.followup.implant.infra.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 租户库 cfg_feature 只读（E21-T01：植入记录功能开关 implant 的运行时判定，AGENTS.md §5.7）。 与
 * wide_import（无行视为默认启用）不同，implant 是医院 C 专属能力且种子默认关闭： <b>无行视为关闭</b>（与前端
 * useFeature「未配置的开关不在返回里＝默认关闭」同向，A / B 租户不受影响）。写入走平台侧配置（无租户自助界面）。
 */
@Mapper
public interface ImplantFeatureMapper {

    /** 功能开关当前值；无行返回 null（视为关闭）。 */
    @Select("SELECT enabled FROM cfg_feature WHERE feature_code = #{featureCode}")
    Boolean findEnabled(@Param("featureCode") String featureCode);
}
