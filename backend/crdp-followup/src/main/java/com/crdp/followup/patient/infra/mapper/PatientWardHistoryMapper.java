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
 * 转病区历史 pt_ward_history（E13-T08 / A-WARD-005「保留转病区历史」、C-WARD-006；表 followup V43）。
 *
 * <p>表只追加（永不 UPDATE / DELETE），当前唯一写入口是 {@code PatientTransferService} 的转病区动作。
 * 查询为范围内口径（{@code @DataScope}，pt_ward_history 在 ControlledTables：经 patient_id 锚点按当前用户病区 / 科室 /
 * 本人范围改写）； 插入不走查询拦截（E07 只拦 SELECT）。
 */
@Mapper
public interface PatientWardHistoryMapper {

    /** 转病区历史行（GET ward-history 数据源；from/to 为病区 id，名称由应用层联字典补齐）。 */
    record WardHistoryRow(
            Long id,
            Long fromWardId,
            Long toWardId,
            String reason,
            Long transferredBy,
            String operatorName,
            LocalDateTime transferredAt) {}

    /** 某患者的转病区历史（时间新在前）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT h.id, h.from_ward_id AS fromWardId, h.to_ward_id AS toWardId, h.reason,
                   h.transferred_by AS transferredBy, u.display_name AS operatorName,
                   h.transferred_at AS transferredAt
            FROM pt_ward_history h
            LEFT JOIN sys_user u ON u.id = h.transferred_by
            WHERE h.patient_id = #{patientId}
            ORDER BY h.transferred_at DESC, h.id DESC
            """)
    List<WardHistoryRow> findHistory(@Param("patientId") long patientId);

    /** 追加一行转病区历史（转病区事务内调用）。 */
    @Insert(
            """
            INSERT INTO pt_ward_history
              (patient_id, from_ward_id, to_ward_id, reason, transferred_by)
            VALUES
              (#{row.patientId}, #{row.fromWardId}, #{row.toWardId}, #{row.reason},
               #{row.transferredBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id", keyColumn = "id")
    int insertHistory(@Param("row") WardHistoryInsert row);
}
