package com.crdp.followup.report.infra.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 租户库 cfg_feature 只读（报告单子域，E21-T15）：电子化状态（C-SCAN-011）与补录进度（C-DASH-007） 由 {@code scan_progress}
 * 开关控制（components.md §1.3）——关闭 → 后端 40301、前端隐藏入口（AGENTS §5.7）。 与 {@code CompareFeatureMapper}
 * 同口径；无行视为默认关闭（种子 V100 默认 0，医院 C 上线随 E23 打开）。
 */
@Mapper
public interface ReportFeatureMapper {

    /** 功能开关当前值；无行返回 null（调用方视为默认关闭）。 */
    @Select("SELECT enabled FROM cfg_feature WHERE feature_code = #{featureCode}")
    Boolean findEnabled(@Param("featureCode") String featureCode);
}
