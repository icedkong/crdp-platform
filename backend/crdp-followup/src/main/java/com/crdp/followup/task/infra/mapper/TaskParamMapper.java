package com.crdp.followup.task.infra.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 租户库 cfg_param 只读（E15-T05：失访判定与未联系到跟进参数的租户覆盖值；写入走 crdp-system 的租户参数页）。 */
@Mapper
public interface TaskParamMapper {

    /** 本院覆盖值；未覆盖返回 null（生效值回落平台默认）。 */
    @Select("SELECT param_value FROM cfg_param WHERE param_key = #{paramKey}")
    String findOverride(@Param("paramKey") String paramKey);
}
