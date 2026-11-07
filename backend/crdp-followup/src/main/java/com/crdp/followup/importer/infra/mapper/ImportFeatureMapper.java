package com.crdp.followup.importer.infra.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 租户库 cfg_feature 只读（E18-T06：患者历史导入功能开关 wide_import 的运行时判定，pages.md §0.1 导入向导行）。
 * 写入走平台侧配置（无租户自助界面）。与 compare 子域的 CompareFeatureMapper 同口径： 无行视为默认启用（种子 V100 A 默认开；关闭显式落
 * enabled=0，此时接口 40301、前端隐藏入口，AGENTS.md 规则 7）。
 */
@Mapper
public interface ImportFeatureMapper {

    /** 功能开关当前值；无行返回 null（视为默认启用）。 */
    @Select("SELECT enabled FROM cfg_feature WHERE feature_code = #{featureCode}")
    Boolean findEnabled(@Param("featureCode") String featureCode);
}
