package com.crdp.followup.patient.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 转科室历史 pt_dept_history（E21-T21 / C-WARD-006「保留转科历史」；表 followup V97）。
 *
 * <p>与 {@link PatientWardHistoryMapper} 同构（A-WARD-005 转病区历史模型）：表只追加（永不 UPDATE / DELETE），当前唯一写入口是
 * {@code PatientTransferService} 的转科室动作。查询为范围内口径（{@code @DataScope}，pt_dept_history 在
 * ControlledTables：经 patient_id 锚点按当前用户病区 / 科室 / 本人范围 改写，科室与病区分支 OR——E07-T02）；插入不走查询拦截（E07 只拦
 * SELECT）。
 */
@Mapper
public interface PatientDeptHistoryMapper {

    /** 转科室历史行（GET dept-history 数据源；from/to 为科室 id，名称由应用层联字典补齐）。 */
    record DeptHistoryRow(
            Long id,
            Long fromDeptId,
            Long toDeptId,
            String reason,
            Long transferredBy,
            String operatorName,
            LocalDateTime transferredAt) {}

    /** 某患者的转科室历史（时间新在前）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT h.id, h.from_dept_id AS fromDeptId, h.to_dept_id AS toDeptId, h.reason,
                   h.transferred_by AS transferredBy, u.display_name AS operatorName,
                   h.transferred_at AS transferredAt
            FROM pt_dept_history h
            LEFT JOIN sys_user u ON u.id = h.transferred_by
            WHERE h.patient_id = #{patientId}
            ORDER BY h.transferred_at DESC, h.id DESC
            """)
    List<DeptHistoryRow> findHistory(@Param("patientId") long patientId);

    /** 追加一行转科室历史（转科室事务内调用）。 */
    @Insert(
            """
            INSERT INTO pt_dept_history
              (patient_id, from_dept_id, to_dept_id, reason, transferred_by)
            VALUES
              (#{row.patientId}, #{row.fromDeptId}, #{row.toDeptId}, #{row.reason},
               #{row.transferredBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id", keyColumn = "id")
    int insertHistory(@Param("row") DeptHistoryInsert row);
}
