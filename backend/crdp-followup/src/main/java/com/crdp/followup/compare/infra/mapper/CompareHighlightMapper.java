package com.crdp.followup.compare.infra.mapper;

import com.crdp.followup.compare.infra.mapper.CompareSourceMapper.TaskPlanRow;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 对比标红结果持久化与重算的读写侧（E17-T04 / A-FU-027；fu_record.highlight，followup V69）。
 *
 * <p>重算遍历的是「配置派生数据」而不是向用户输出患者行：记录提交与报告单映射写入入口已对患者做过 数据范围校验（40400），规则变更入口按 {@code
 * followup:abnormal:config} 鉴权（E17-T01），因此本 Mapper 的 fu_record 语句按 {@code @IgnoreScope} 放行并在此写明原因
 * （AGENTS §5.2「调度入口用 @IgnoreScope 并写明原因」）；写入只碰派生列 highlight，不触碰业务列与 updated_by / updated_at
 * （系统重算不是用户编辑）。
 *
 * <p>与 {@link CompareSourceMapper} 的分工：那边服务记录详情的 {@code previous}（单记录、用户范围口径）， 这边服务批量重算（整患者 /
 * 整表单）；候选与链定序复用应用层同一份逻辑（{@code CompareSourceService.previousOf}）。
 */
@Mapper
public interface CompareHighlightMapper {

    /** 已提交记录行（重算对象；data 为 JSON 文本，应用层解析；implantId 供 E21-T11 同一器械过滤）。 */
    record SubmittedRow(
            long id,
            long patientId,
            String source,
            LocalDate visitDate,
            Long taskId,
            Long implantId,
            int templateVersion,
            String data) {}

    /** 患者的已提交同表单记录（全部来源：TASK / BACKFILL / UNPLANNED / REMOTE——重算对象含计划外，候选过滤在应用层；软删行不算，E21-T11）。 */
    @com.crdp.framework.datascope.IgnoreScope(
            reason =
                    "对比标重重算（E17-T04）遍历同患者已提交记录：highlight 为规则派生数据、不输出患者行；"
                            + "触发入口（记录提交 / 报告单映射写入）已对患者做过数据范围校验（40400）")
    @Select(
            """
            SELECT id, patient_id AS patientId, source, visit_date AS visitDate,
                   task_id AS taskId, implant_id AS implantId,
                   template_version AS templateVersion, data
            FROM fu_record
            WHERE patient_id = #{patientId} AND form_code = #{formCode} AND status = 'SUBMITTED'
              AND deleted = 0
            ORDER BY visit_date, id
            """)
    List<SubmittedRow> findSubmittedByPatient(
            @Param("patientId") long patientId, @Param("formCode") String formCode);

    /** 规则变更重算的患者分页游标（keyset：patient_id 递增，内存有界——整表单已提交记录可达数万行， 不整批装载）。 */
    @com.crdp.framework.datascope.IgnoreScope(
            reason =
                    "对比标红规则变更重算（E17-T04）按新阈值重算该表单全部已提交记录：只取患者号作分页游标、"
                            + "不输出患者数据；入口已按 followup:abnormal:config 鉴权（E17-T01）")
    @Select(
            """
            SELECT DISTINCT patient_id
            FROM fu_record
            WHERE form_code = #{formCode} AND status = 'SUBMITTED' AND deleted = 0
              AND patient_id > #{afterPatient}
            ORDER BY patient_id
            LIMIT #{limit}
            """)
    List<Long> findSubmittedPatients(
            @Param("formCode") String formCode,
            @Param("afterPatient") long afterPatient,
            @Param("limit") int limit);

    /** 批量取任务计划日期（visit_date 为空的 TASK 记录的对比时点）。重算链定序需要全量候选时点， 不能随触发者数据范围收紧（否则链断序）。 */
    @com.crdp.framework.datascope.IgnoreScope(
            reason = "对比标重重算（E17-T04）批量补查任务计划日期（TASK 记录对比时点）：派生数据定序，不输出患者数据")
    @Select(
            """
            <script>
            SELECT t.id AS taskId, t.plan_date AS planDate
            FROM fu_task t
            WHERE t.id IN
            <foreach collection="taskIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            </script>
            """)
    List<TaskPlanRow> findTaskPlanDates(@Param("taskIds") List<Long> taskIds);

    /**
     * 写一条重算结果（无命中 / 无启用规则为 NULL——不存空数组）。只写派生列：不动 version（重算不是编辑，不触发乐观锁）、 不动 updated_by /
     * updated_at。
     */
    @com.crdp.framework.datascope.IgnoreScope(
            reason =
                    "对比标重重算（E17-T04）只写派生列 highlight（fu_record.highlight）：不触碰业务列、"
                            + "version 与 updated_by / updated_at，无患者数据经此输出")
    @Update("UPDATE fu_record SET highlight = #{highlight} WHERE id = #{id}")
    int updateHighlight(@Param("id") long id, @Param("highlight") String highlight);

    /**
     * 单条记录的持久化命中数组（E21-T32 / C-PAT-018 分组条标红数）：调用方（记录详情）已对记录做过 {@code @DataScope} 可见性校验，本句只读派生列
     * highlight、不吐患者数据；无命中 / 未算为 null。
     */
    @com.crdp.framework.datascope.IgnoreScope(
            reason =
                    "记录详情读取持久化标红命中（E21-T32）：调用方已经 record（@DataScope）确认记录可见，"
                            + "只读派生列 highlight 不吐患者数据")
    @Select("SELECT highlight FROM fu_record WHERE id = #{id} AND deleted = 0")
    String findHighlightOf(@Param("id") long id);
}
