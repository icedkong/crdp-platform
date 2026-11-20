package com.crdp.followup.plan.infra.mapper;

import com.crdp.framework.datascope.IgnoreScope;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 内部派生日期与指令绑定；HTTP 调用方已校验患者范围，调度入口按租户执行，不向外输出记录数据。 */
@Mapper
public interface CurrentDeviceScheduleMapper {
    record Device(long id, LocalDate implantDate) {}

    record Submitted(
            long id, LocalDate visitDate, String formCode, int templateVersion, String data) {}

    record Enrollment(
            long id,
            long patientId,
            long planId,
            String status,
            String mode,
            LocalDate startDate,
            LocalDate enrolledOn,
            LocalDate lastVisitDate,
            LocalDate doctorNextDate,
            Long nodeId,
            Integer occurrence,
            Long implantId,
            Long recordId,
            String commandMode) {}

    record Task(
            long id,
            long nodeId,
            int occurrence,
            LocalDate planDate,
            String status,
            boolean protectedTask,
            boolean doctorOverride) {}

    @IgnoreScope(reason = "内部排期事务锁定患者，入口已校验患者范围；调度按租户逐入组执行，不输出患者信息")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT id FROM pt_patient WHERE id=#{patientId} AND deleted=0 FOR UPDATE")
    Long lockPatient(long patientId);

    @IgnoreScope(reason = "内部排期只取当前器械标识与植入日；入口已校验患者范围或来自租户调度")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select(
            "SELECT id,implant_date AS implantDate FROM pt_implant WHERE patient_id=#{patientId} AND status='ACTIVE' ORDER BY id DESC LIMIT 1")
    Device device(long patientId);

    @IgnoreScope(reason = "当前器械排期变更在患者锁后锁定器械，入口已校验范围，避免更换竞争")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select(
            "SELECT id,implant_date AS implantDate FROM pt_implant WHERE patient_id=#{patientId} AND status='ACTIVE' ORDER BY id DESC LIMIT 1 FOR UPDATE")
    Device lockDevice(long patientId);

    @IgnoreScope(reason = "当前器械日期派生重算，入口已校验患者范围；仅服务内部按模板解析，不输出临床数据")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select(
            "SELECT id,visit_date AS visitDate,form_code AS formCode,template_version AS templateVersion,data FROM fu_record WHERE patient_id=#{patientId} AND implant_id=#{implantId} AND status='SUBMITTED' AND deleted=0 ORDER BY id FOR UPDATE")
    List<Submitted> submitted(
            @Param("patientId") long patientId, @Param("implantId") long implantId);

    @IgnoreScope(reason = "已授权记录详情 / 统计只读核验指定来源是否仍为当前器械最新有效记录，不输出临床数据")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select(
            "SELECT id,visit_date AS visitDate,form_code AS formCode,template_version AS templateVersion,data FROM fu_record WHERE patient_id=#{patientId} AND implant_id=#{implantId} AND status='SUBMITTED' AND deleted=0 ORDER BY id")
    List<Submitted> readSubmitted(
            @Param("patientId") long patientId, @Param("implantId") long implantId);

    @IgnoreScope(reason = "记录提交入口已校验范围；仅判定过去的指定日期是否属于历史补录，不输出记录数据")
    @Select(
            "SELECT COUNT(*)>0 FROM fu_record WHERE id=#{recordId} AND patient_id=#{patientId} AND source='BACKFILL' AND status='SUBMITTED' AND deleted=0 AND visit_date < #{today}")
    boolean historicalBackfill(
            @Param("patientId") long patientId,
            @Param("recordId") long recordId,
            @Param("today") LocalDate today);

    @IgnoreScope(reason = "起算日期重算入口已校验范围；仅判断该次数是否有已生效的医生指定排期来源")
    @Select(
            "SELECT COUNT(*)>0 FROM fu_task WHERE enrollment_id=#{id} AND node_id=#{nodeId} AND occurrence=#{occurrence} AND followup_seq=0 AND doctor_override_record_id IS NOT NULL")
    boolean doctorTask(
            @Param("id") long id,
            @Param("nodeId") long nodeId,
            @Param("occurrence") int occurrence);

    String COLUMNS =
            "id,patient_id AS patientId,plan_id AS planId,status,mode,start_date AS startDate,DATE(created_at) AS enrolledOn,last_visit_date AS lastVisitDate,doctor_next_date AS doctorNextDate,doctor_next_node_id AS nodeId,doctor_next_occurrence AS occurrence,doctor_next_implant_id AS implantId,doctor_next_record_id AS recordId,doctor_next_mode AS commandMode";

    @IgnoreScope(reason = "内部派生日期 / 指令绑定锁定在组入组；入口已校验患者范围，不向外输出患者数据")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM fu_enrollment WHERE patient_id=#{patientId} AND status IN ('ACTIVE','PAUSED') ORDER BY id FOR UPDATE")
    List<Enrollment> enrollments(long patientId);

    @IgnoreScope(reason = "调度或统计入口已定位合法入组，仅取排期绑定元数据，不输出患者明细")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT " + COLUMNS + " FROM fu_enrollment WHERE id=#{id}")
    Enrollment enrollment(long id);

    @IgnoreScope(reason = "记录详情已校验患者范围；只取当前入组标识装配指定排期状态，不输出患者明细")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select(
            "SELECT id FROM fu_enrollment WHERE patient_id=#{patientId} AND status IN ('ACTIVE','PAUSED') ORDER BY id")
    List<Long> enrollmentIds(long patientId);

    @IgnoreScope(reason = "调度在患者锁后重新读取当前可调入组，防止遍历后发生暂停 / 切换 / 死亡；只取排期元数据")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select(
            "SELECT e.id,e.patient_id AS patientId,e.plan_id AS planId,e.start_date AS startDate,e.mode,DATE(e.created_at) AS enrolledOn,DATE(e.pause_from) AS pauseFrom,DATE(e.pause_to) AS pauseTo,e.doctor_next_date AS doctorNextDate FROM fu_enrollment e JOIN pt_patient p ON p.id=e.patient_id JOIN fu_plan fp ON fp.id=e.plan_id WHERE e.id=#{id} AND e.status='ACTIVE' AND p.deleted=0 AND p.followup_status NOT IN ('DEAD','TERMINATED','LOST') AND fp.status IN ('PUBLISHED','DISABLED') FOR UPDATE")
    com.crdp.followup.task.infra.mapper.FollowupTaskMapper.EnrollmentRow schedulable(long id);

    @IgnoreScope(reason = "内部排期只读任务日期 / 状态与保护位；入口已校验患者范围或来自租户调度")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select(
            "SELECT t.id,t.node_id AS nodeId,t.occurrence,t.plan_date AS planDate,t.status,(EXISTS(SELECT 1 FROM fu_contact_log c WHERE c.task_id=t.id) OR EXISTS(SELECT 1 FROM fu_record r WHERE r.task_id=t.id AND r.deleted=0)) AS protectedTask,t.doctor_override_record_id IS NOT NULL AS doctorOverride FROM fu_task t WHERE t.enrollment_id=#{id} AND t.followup_seq=0 AND t.create_type!='TEMP' ORDER BY t.node_id,t.occurrence")
    List<Task> tasks(long id);

    @IgnoreScope(reason = "内部排期变更在患者锁后读取任务当前状态；入口已校验范围，不输出患者明细")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select(
            "SELECT t.id,t.node_id AS nodeId,t.occurrence,t.plan_date AS planDate,t.status,(EXISTS(SELECT 1 FROM fu_contact_log c WHERE c.task_id=t.id) OR EXISTS(SELECT 1 FROM fu_record r WHERE r.task_id=t.id AND r.deleted=0)) AS protectedTask,t.doctor_override_record_id IS NOT NULL AS doctorOverride FROM fu_task t WHERE t.enrollment_id=#{id} AND t.followup_seq=0 AND t.create_type!='TEMP' ORDER BY t.node_id,t.occurrence FOR UPDATE")
    List<Task> lockTasks(long id);

    @Update(
            "UPDATE fu_enrollment SET last_visit_date=#{date} WHERE id=#{id} AND status IN ('ACTIVE','PAUSED')")
    int setLastVisit(@Param("id") long id, @Param("date") LocalDate date);

    @Update(
            "UPDATE fu_enrollment SET doctor_next_date=#{date},doctor_next_node_id=#{nodeId},doctor_next_occurrence=#{occurrence},doctor_next_implant_id=#{implantId},doctor_next_record_id=#{recordId},doctor_next_mode=mode WHERE id=#{id} AND status='ACTIVE'")
    int bind(
            @Param("id") long id,
            @Param("date") LocalDate date,
            @Param("nodeId") long nodeId,
            @Param("occurrence") int occurrence,
            @Param("implantId") long implantId,
            @Param("recordId") long recordId);

    @Update(
            "UPDATE fu_task SET doctor_override_record_id=#{recordId} WHERE enrollment_id=#{id} AND node_id=#{nodeId} AND occurrence=#{occurrence} AND followup_seq=0")
    int markDoctorTask(
            @Param("id") long id,
            @Param("nodeId") long nodeId,
            @Param("occurrence") int occurrence,
            @Param("recordId") long recordId);

    @Update(
            "UPDATE fu_enrollment SET doctor_next_date=NULL,doctor_next_node_id=NULL,doctor_next_occurrence=NULL,doctor_next_implant_id=NULL,doctor_next_record_id=NULL,doctor_next_mode=NULL WHERE id=#{id} AND doctor_next_node_id=#{nodeId} AND doctor_next_occurrence=#{occurrence} AND doctor_next_date=#{date}")
    int consume(
            @Param("id") long id,
            @Param("nodeId") long nodeId,
            @Param("occurrence") int occurrence,
            @Param("date") LocalDate date);

    @Update(
            "UPDATE fu_enrollment SET doctor_next_date=NULL,doctor_next_node_id=NULL,doctor_next_occurrence=NULL,doctor_next_implant_id=NULL,doctor_next_record_id=NULL,doctor_next_mode=NULL WHERE patient_id=#{patientId} AND doctor_next_record_id=#{recordId}")
    int revoke(@Param("patientId") long patientId, @Param("recordId") long recordId);
}
