package com.crdp.followup.record.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** OQR-01：候选与关联均绑定同一范围内患者，不覆盖已有关联。 */
@Mapper
public interface BackfillTaskLinkMapper {
    record RecordState(
            long id,
            long patientId,
            String source,
            String status,
            int version,
            String formCode,
            int templateVersion,
            LocalDate visitDate,
            Long taskId,
            String digest,
            String result) {}

    record Candidate(
            long taskId,
            long enrollmentId,
            String planName,
            String nodeName,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd) {}

    @DataScope(resource = "patient")
    @Select("SELECT id FROM pt_patient WHERE id=#{id} AND deleted=0 FOR UPDATE")
    Long lockPatient(long id);

    @DataScope(resource = "patient")
    @Select(
            "<script>SELECT id,patient_id AS patientId,source,status,version,form_code AS formCode,template_version AS templateVersion,visit_date AS visitDate,task_id AS taskId,backfill_submit_digest AS digest,backfill_submit_result AS result FROM fu_record WHERE id=#{id} AND deleted=0 <if test='locking'>FOR UPDATE</if></script>")
    RecordState record(@Param("id") long id, @Param("locking") boolean locking);

    @DataScope(resource = "patient")
    @Select(
            """
        <script>
        SELECT t.id AS taskId,t.enrollment_id AS enrollmentId,p.name AS planName,n.name AS nodeName,
          t.plan_date AS planDate,t.window_start AS windowStart,t.window_end AS windowEnd
        FROM fu_task t JOIN fu_enrollment e ON e.id=t.enrollment_id
        JOIN fu_plan p ON p.id=t.plan_id JOIN fu_plan_node n ON n.id=t.node_id
        JOIN pt_patient pt ON pt.id=t.patient_id AND pt.deleted=0 AND pt.followup_status='ACTIVE'
        JOIN pt_implant i ON i.id=#{implantId} AND i.patient_id=t.patient_id AND i.status='ACTIVE'
        WHERE t.patient_id=#{patientId} AND e.patient_id=t.patient_id AND e.status='ACTIVE'
          AND e.start_date &gt;= i.implant_date
          AND t.task_type='FORM' AND t.form_code=#{formCode} AND t.occurrence&gt;0 AND t.followup_seq=0
          AND t.create_type IN ('NORMAL','BACKFILL')
          AND t.status IN ('PENDING','IN_PROGRESS','OVERDUE','LOST_CANDIDATE')
          AND #{visitDate} BETWEEN t.window_start AND t.window_end AND t.record_id IS NULL
          AND NOT EXISTS(SELECT 1 FROM fu_record r WHERE r.task_id=t.id)
        ORDER BY t.id <if test='locking'>FOR UPDATE</if>
        </script>
        """)
    List<Candidate> candidates(
            @Param("patientId") long patientId,
            @Param("implantId") long implantId,
            @Param("formCode") String formCode,
            @Param("visitDate") LocalDate visitDate,
            @Param("locking") boolean locking);

    @DataScope(resource = "patient")
    @Select("SELECT id FROM fu_task WHERE id=#{id} AND patient_id=#{patientId}")
    Long selectedInPatient(@Param("id") long id, @Param("patientId") long patientId);

    @Update(
            "UPDATE fu_task SET record_id=#{recordId},lock_version=lock_version+1 WHERE id=#{taskId} AND patient_id=#{patientId} AND record_id IS NULL AND status IN ('PENDING','IN_PROGRESS','OVERDUE','LOST_CANDIDATE')")
    int linkTask(
            @Param("taskId") long taskId,
            @Param("patientId") long patientId,
            @Param("recordId") long recordId);

    @Update(
            "UPDATE fu_record SET task_id=#{taskId},enrollment_id=#{enrollmentId},node_id=(SELECT node_id FROM fu_task WHERE id=#{taskId}),occurrence=(SELECT occurrence FROM fu_task WHERE id=#{taskId}) WHERE id=#{recordId} AND task_id IS NULL AND status='DRAFT' AND deleted=0")
    int linkRecord(
            @Param("recordId") long recordId,
            @Param("taskId") long taskId,
            @Param("enrollmentId") long enrollmentId);

    @Update(
            "UPDATE fu_record SET backfill_submit_digest=#{digest},backfill_submit_result=#{result} WHERE id=#{id} AND status='SUBMITTED' AND deleted=0")
    int saveResult(
            @Param("id") long id, @Param("digest") String digest, @Param("result") String result);
}
