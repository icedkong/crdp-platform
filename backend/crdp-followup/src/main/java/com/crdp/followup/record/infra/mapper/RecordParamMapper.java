package com.crdp.followup.record.infra.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 租户库 cfg_param 只读（E21-T14 / C-FU-014：补录建议随访时间点的天数区间租户覆盖值；写入走 crdp-system 的租户参数页）。 与
 * TaskParamMapper（E15-T05）、VisitNoMapper（E21-T10）同口径。
 */
@Mapper
public interface RecordParamMapper {

    /** 本院覆盖值；未覆盖返回 null（生效值回落平台默认，再回落代码内置缺省区间）。 */
    @Select("SELECT param_value FROM cfg_param WHERE param_key = #{paramKey}")
    String findOverride(@Param("paramKey") String paramKey);
}
