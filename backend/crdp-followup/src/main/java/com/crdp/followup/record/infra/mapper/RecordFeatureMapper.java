package com.crdp.followup.record.infra.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 租户库 cfg_feature 只读（E21-T11 / C-FU-026、OQ-14）：修改已提交记录双权限码的租户差异开关 {@code
 * record_edit_submitted}——开关关闭（无行，A / B 默认）修改已提交沿用 {@code followup:record:edit}；开关开启（医院 C
 * 随租户配置写入）必须 {@code followup:record:edit-submitted} （AGENTS §5.7：医院差异走功能开关，不写 if tenant）。与 implant
 * 同口径：无行视为关闭。
 */
@Mapper
public interface RecordFeatureMapper {

    /** 功能开关当前值；无行返回 null（视为关闭，A / B 行为）。 */
    @Select("SELECT enabled FROM cfg_feature WHERE feature_code = #{featureCode}")
    Boolean findEnabled(@Param("featureCode") String featureCode);
}
