package com.crdp.followup.patient.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 随访状态时间线 pt_status_history（E13-T03 / tech-plan §7「每次状态变化一行」；表 followup V39）。
 *
 * <p>表只追加：登记写初始行（source=REGISTRATION），暂停 / 移除 / 恢复 / 停止 / 失访 / 死亡的流转随 E14-T06 经 {@code
 * PatientStatusHistoryService} 写入。查询为范围内口径（{@code @DataScope}）；插入不走查询拦截。
 */
@Mapper
public interface PatientStatusHistoryMapper {

    /** 状态行（GET status-history 数据源）。 */
    record StatusRow(
            Long id,
            String status,
            LocalDate effectiveDate,
            String reason,
            String source,
            Long createdBy,
            LocalDateTime createdAt) {}

    /** 某患者的状态时间线（生效日期新在前，同日按写入序新在前）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT id, status, effective_date AS effectiveDate, reason, source,
                   created_by AS createdBy, created_at AS createdAt
            FROM pt_status_history
            WHERE patient_id = #{patientId}
            ORDER BY effective_date DESC, id DESC
            """)
    List<StatusRow> findHistory(@Param("patientId") long patientId);

    /** 追加一行状态记录（登记初始行与流转共用；永不 UPDATE / DELETE）。 */
    @Insert(
            """
            INSERT INTO pt_status_history
              (patient_id, status, effective_date, reason, source, created_by)
            VALUES
              (#{row.patientId}, #{row.status}, #{row.effectiveDate}, #{row.reason},
               #{row.source}, #{row.createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id", keyColumn = "id")
    int insertHistory(@Param("row") StatusHistoryInsert row);
}
