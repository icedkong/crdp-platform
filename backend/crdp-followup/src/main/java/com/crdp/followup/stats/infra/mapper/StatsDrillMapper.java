package com.crdp.followup.stats.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 统计集合与明细共用的受控输入，始终按 patient 范围查询。 */
@Mapper
public interface StatsDrillMapper {
    record Enrollment(
            long id,
            long patientId,
            long planId,
            LocalDate startDate,
            String mode,
            LocalDate enrolledOn,
            LocalDate lastVisitDate,
            LocalDate doctorNextDate,
            Long doctorId) {}

    record Task(
            long id,
            long patientId,
            long enrollmentId,
            long nodeId,
            int occurrence,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            Long recordId,
            String status) {}

    record Patient(long id, String nameCipher) {}

    record Fact(
            long id,
            long patientId,
            long enrollmentId,
            long planId,
            long nodeId,
            int occurrence,
            Integer followupYear,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            boolean followed,
            boolean inWindow,
            boolean lost,
            boolean death,
            Long taskId,
            Long recordId,
            String planName,
            String nodeName) {}

    @DataScope(resource = "patient")
    @Select(
            """
        <script>
        SELECT e.id,e.patient_id AS patientId,e.plan_id AS planId,
          COALESCE(e.start_date,CASE fp.start_date_field WHEN 'SURGERY_DATE' THEN p.surgery_date WHEN 'DISCHARGE_DATE' THEN p.discharge_date WHEN 'REGISTRATION_DATE' THEN DATE(p.created_at) WHEN 'IMPLANT_DATE' THEN i.implant_date END) AS startDate,
          e.mode,DATE(e.created_at) AS enrolledOn,e.last_visit_date AS lastVisitDate,e.doctor_next_date AS doctorNextDate,p.responsible_user_id AS doctorId
        FROM fu_enrollment e JOIN pt_patient p ON p.id=e.patient_id AND p.deleted=0
          JOIN fu_plan fp ON fp.id=e.plan_id LEFT JOIN pt_implant i ON i.patient_id=p.id AND i.status='ACTIVE'
        WHERE e.status IN ('ACTIVE','PAUSED') AND p.followup_status NOT IN ('DEAD','TERMINATED','LOST')
        <if test='wardId != null'>AND p.ward_id=#{wardId}</if>
        ORDER BY e.id
        </script>
        """)
    List<Enrollment> enrollments(@Param("wardId") Long wardId);

    @DataScope(resource = "patient")
    @Select(
            """
        <script>
        SELECT t.id,t.patient_id AS patientId,t.enrollment_id AS enrollmentId,t.node_id AS nodeId,t.occurrence,
          t.plan_date AS planDate,t.window_start AS windowStart,t.window_end AS windowEnd,t.record_id AS recordId,t.status
        FROM fu_task t JOIN pt_patient p ON p.id=t.patient_id AND p.deleted=0
        JOIN fu_enrollment e ON e.id=t.enrollment_id AND e.status IN ('ACTIVE','PAUSED')
        WHERE t.occurrence&gt;0 AND t.followup_seq=0 AND t.create_type!='TEMP'
        <if test='wardId != null'>AND p.ward_id=#{wardId}</if>
        ORDER BY t.enrollment_id,t.node_id,t.occurrence
        </script>
        """)
    List<Task> tasks(@Param("wardId") Long wardId);

    String RATE_FILTER =
            """
        WHERE o.due=1
        <if test='wardId != null'>AND p.ward_id=#{wardId}</if>
        <if test='cohortYear != null'>AND o.cohort_year=#{cohortYear}</if>
        """;

    @DataScope(resource = "patient")
    @Select(
            """
        <script>
        SELECT o.id,o.patient_id AS patientId,o.enrollment_id AS enrollmentId,o.plan_id AS planId,o.node_id AS nodeId,
          o.occurrence,o.followup_year AS followupYear,o.plan_date AS planDate,o.window_start AS windowStart,o.window_end AS windowEnd,
          o.followed,o.in_window AS inWindow,o.lost,o.death_confirmed AS death,o.task_id AS taskId,o.record_id AS recordId,fp.name AS planName,fn.name AS nodeName
        FROM st_node_occurrence o JOIN pt_patient p ON p.id=o.patient_id AND p.deleted=0 JOIN fu_plan fp ON fp.id=o.plan_id JOIN fu_plan_node fn ON fn.id=o.node_id
        """
                    + RATE_FILTER
                    + """
        <if test='planId != null'>AND o.plan_id=#{planId}</if>
        <if test='nodeId != null'>AND o.node_id=#{nodeId}</if>
        <if test='occurrence != null'>AND o.occurrence=#{occurrence}</if>
        <if test='year != null'>AND o.followup_year=#{year}</if>
        ORDER BY o.patient_id,o.id
        </script>
        """)
    List<Fact> facts(
            @Param("wardId") Long wardId,
            @Param("cohortYear") Integer cohortYear,
            @Param("planId") Long planId,
            @Param("nodeId") Long nodeId,
            @Param("occurrence") Integer occurrence,
            @Param("year") Integer year);

    @DataScope(resource = "patient")
    @Select(
            "<script>SELECT p.id,p.name_cipher AS nameCipher FROM pt_patient p WHERE p.deleted=0 AND p.id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY p.id</script>")
    List<Patient> patients(@Param("ids") List<Long> ids);
}
