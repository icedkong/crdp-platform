package com.crdp.followup.dashboard.infra.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 租户库 cfg_feature 只读（E19-T02）：指标卡指标集按租户差异（A-DASH-001 与 C-DASH-001，stats.md §3「A 与 C
 * 指标不同，按租户配置」）以功能开关判定——{@code implant} 开（医院 C 植入数据形态）取 C 指标集， 关取 A 指标集（AGENTS
 * §5.7：医院差异走功能开关，不写死租户编码）。 写入走平台侧配置（与 CompareFeatureMapper 同口径）。
 */
@Mapper
public interface DashboardFeatureMapper {

    /** 功能开关当前值；无行返回 null（视为默认关闭——implant 种子 V100 默认 0，医院 C 上线随 E21 / E23 打开）。 */
    @Select("SELECT enabled FROM cfg_feature WHERE feature_code = #{featureCode}")
    Boolean findEnabled(@Param("featureCode") String featureCode);
}
