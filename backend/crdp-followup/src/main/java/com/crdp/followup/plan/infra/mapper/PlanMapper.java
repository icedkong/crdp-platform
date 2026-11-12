package com.crdp.followup.plan.infra.mapper;

import com.crdp.framework.datascope.IgnoreScope;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 计划与节点（E14-T01 / A-FU-016、A-FU-018、A-FU-019；表 followup V46 建）。
 *
 * <p>fu_plan / fu_plan_node 是配置表，但「在组人数」是 SELECT 项里对 fu_enrollment（患者面受控表，E14-T01 起登记 {@code
 * ControlledTables}）的在组计数——管理口径聚合、不输出患者行，按 {@code @IgnoreScope} 放行：计划配置页的维护者 （followup:plan:view /
 * edit）未必有患者数据范围，与病区列表的在册患者计数（WardMapper）同口径。
 */
@Mapper
public interface PlanMapper {

    /** 计划行（autoEnroll = 自动入组开关 Q35；activeCount = 在组入组数，用于「随访中」派生态与列表在组人数）。 */
    record PlanRow(
            Long id,
            String code,
            String name,
            Integer version,
            String status,
            String startDateField,
            String cycleType,
            Integer cycleYears,
            Boolean autoEnroll,
            String remark,
            Integer lockVersion,
            LocalDateTime publishedAt,
            Long activeCount) {}

    /** 节点行（SUBPLAN / NODE 共表；两层的列统一带出，用不到的一层为 NULL / 默认值）。 */
    record NodeRow(
            Long id,
            Long parentId,
            String nodeType,
            String name,
            String repeatMode,
            Integer repeatCount,
            Integer cycleValue,
            String cycleUnit,
            String basis,
            Integer offsetValue,
            String offsetUnit,
            Integer remindValue,
            String remindUnit,
            Integer windowBeforeValue,
            String windowBeforeUnit,
            Integer windowAfterValue,
            String windowAfterUnit,
            String taskType,
            String ownerType,
            String formCode,
            String passedWindowAction,
            String enrollMode,
            Integer sortNo) {}

    String ACTIVE_COUNT =
            "(SELECT COUNT(*) FROM fu_enrollment e"
                    + " WHERE e.plan_id = p.id AND e.status = 'ACTIVE')";

    String PLAN_COLUMNS =
            "p.id, p.code, p.name, p.version, p.status, p.start_date_field AS startDateField,"
                    + " p.cycle_type AS cycleType, p.cycle_years AS cycleYears,"
                    + " p.auto_enroll AS autoEnroll, p.remark,"
                    + " p.lock_version AS lockVersion, p.published_at AS publishedAt, "
                    + ACTIVE_COUNT
                    + " AS activeCount";

    /** 全部计划（含各版本与全部状态；按 code、版本倒序——列表每族最新版本在前）。 */
    @IgnoreScope(
            reason =
                    "计划列表的在组人数（pages.md §8 列）：对 fu_enrollment 的管理口径聚合计数，"
                            + "不输出任何患者行；计划配置页维护者未必有患者数据范围（与 WardMapper 在册计数同口径）")
    @Select(
            "SELECT "
                    + PLAN_COLUMNS
                    + " FROM fu_plan p ORDER BY p.code ASC, p.version DESC, p.id DESC")
    List<PlanRow> findAll();

    /** 单个计划版本。 */
    @IgnoreScope(reason = "计划详情的在组人数（派生「随访中」状态用，A-FU-018）：聚合计数不输出患者行，" + "与列表同口径")
    @Select("SELECT " + PLAN_COLUMNS + " FROM fu_plan p WHERE p.id = #{id}")
    PlanRow findById(long id);

    /**
     * 自动入组的预置计划（E14-T05 / Q35）：auto_enroll = 1 且状态 PUBLISHED（「随访中」派生态同为 PUBLISHED）。 PAUSED「不开启新任务」、
     * DISABLED「不再允许入组」不参与；被新版本顶替的旧版本已转 DISABLED → 自动入组必然落在新版本（A-FU-019）。 配置行读取、不输出患者数据。
     */
    @IgnoreScope(
            reason =
                    "自动入组（Q35）只读计划配置行（auto_enroll 开关与状态位），不输出患者数据；"
                            + "随后的入组复用 PlanEnrollmentService.enroll 的全部校验")
    @Select(
            "SELECT "
                    + PLAN_COLUMNS
                    + " FROM fu_plan p WHERE p.auto_enroll = 1 AND p.status = 'PUBLISHED'"
                    + " ORDER BY p.id")
    List<PlanRow> findAutoEnrollPlans();

    /** 指定计划族的未发布草稿 id（至多一个；复制前查重）。 */
    @Select("SELECT id FROM fu_plan WHERE code = #{code} AND status = 'UNPUBLISHED'" + " LIMIT 1")
    Long findDraftIdByCode(String code);

    /**
     * 指定计划族的已发布版本（E21-T05 / C-FU-003 器械类型映射按 plan_code 解析入组目标）。
     *
     * <p>发布新版本时同族旧 PUBLISHED / PAUSED 已自动转 DISABLED（OQ-76①），正常至多一行 PUBLISHED； 取版本最大的一行兜底（与
     * findAutoEnrollPlans「只认 PUBLISHED」同口径：PAUSED 不开启新任务、DISABLED 不再允许入组）。 无已发布版本返回
     * null（由调用方按业务跳过）。 配置行读取、不输出患者数据。
     */
    @IgnoreScope(
            reason =
                    "器械类型映射解析入组目标计划（C-FU-003）只读计划配置行，不输出患者数据；"
                            + "随后的入组复用 PlanEnrollmentService.enroll 的全部校验")
    @Select(
            "SELECT "
                    + PLAN_COLUMNS
                    + " FROM fu_plan p WHERE p.code = #{code} AND p.status = 'PUBLISHED'"
                    + " ORDER BY p.version DESC LIMIT 1")
    PlanRow findPublishedByCode(String code);

    /** 指定计划族当前最大版本号（复制时 +1）。 */
    @Select("SELECT COALESCE(MAX(version), 0) FROM fu_plan WHERE code = #{code}")
    int findMaxVersionByCode(String code);

    /**
     * 指定计划族的最新版本行（E21-T05 / C-FU-003 映射配置页显示计划族名称；与 {@link #findPublishedByCode} 的差别： 不筛状态，族存在即回显）。
     * 配置行读取、不输出患者数据。
     */
    @IgnoreScope(reason = "器械→计划映射配置页回显计划族名称（C-FU-003）只读计划配置行，不输出患者数据")
    @Select(
            "SELECT "
                    + PLAN_COLUMNS
                    + " FROM fu_plan p WHERE p.code = #{code}"
                    + " ORDER BY p.version DESC LIMIT 1")
    PlanRow findLatestByCode(String code);

    /** 同族里将被新版本顶替的已发布 / 已暂停版本（发布新版本时自动转 DISABLED）。 */
    @Select(
            "SELECT id FROM fu_plan WHERE code = #{code} AND status IN ('PUBLISHED','PAUSED')"
                    + " AND id <> #{excludeId}")
    List<Long> findSupersededIds(@Param("code") String code, @Param("excludeId") long excludeId);

    /** 新建计划（未发布，lock_version 从 0 起；auto_enroll 随 E14-T05 / Q35 入列）。 */
    @Insert(
            """
            INSERT INTO fu_plan
              (code, name, version, status, start_date_field, cycle_type, cycle_years,
               auto_enroll, remark, created_by)
            VALUES
              (#{code}, #{name}, #{version}, #{status}, #{startDateField}, #{cycleType},
               #{cycleYears}, #{autoEnroll}, #{remark}, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertPlan(PlanInsertRow row);

    /** 编辑基本信息（仅未发布；乐观锁：命中 lock_version 才更新，行数 0 = 冲突或已发布）。 */
    @Update(
            """
            UPDATE fu_plan SET name = #{name}, start_date_field = #{startDateField},
              cycle_type = #{cycleType}, cycle_years = #{cycleYears}, auto_enroll = #{autoEnroll},
              remark = #{remark}, lock_version = lock_version + 1
            WHERE id = #{id} AND status = 'UNPUBLISHED' AND lock_version = #{baseVersion}
            """)
    int updateBasics(
            @Param("id") long id,
            @Param("name") String name,
            @Param("startDateField") String startDateField,
            @Param("cycleType") String cycleType,
            @Param("cycleYears") Integer cycleYears,
            @Param("autoEnroll") boolean autoEnroll,
            @Param("remark") String remark,
            @Param("baseVersion") int baseVersion);

    /** 节点整体保存时同步推进乐观锁（编辑面与节点面共用一个 lock_version）。 */
    @Update(
            "UPDATE fu_plan SET lock_version = lock_version + 1"
                    + " WHERE id = #{id} AND status = 'UNPUBLISHED' AND lock_version = #{baseVersion}")
    int bumpLockVersion(@Param("id") long id, @Param("baseVersion") int baseVersion);

    /** 状态迁移（guarded：fromStatus 不匹配命中 0 行，服务层据此判定非法迁移）。 */
    @Update(
            "UPDATE fu_plan SET status = #{toStatus}, lock_version = lock_version + 1"
                    + " WHERE id = #{id} AND status = #{fromStatus}")
    int transitionStatus(
            @Param("id") long id,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    /** 发布补记（时间与操作人，只写一次）。 */
    @Update(
            "UPDATE fu_plan SET published_at = #{publishedAt}, published_by = #{publishedBy}"
                    + " WHERE id = #{id} AND published_at IS NULL")
    int markPublished(
            @Param("id") long id,
            @Param("publishedAt") LocalDateTime publishedAt,
            @Param("publishedBy") Long publishedBy);

    /** 指定计划的全部节点（同层按 sort_no、id 升序）。 */
    @Select(
            """
            SELECT id, parent_id AS parentId, node_type AS nodeType, name,
              repeat_mode AS repeatMode, repeat_count AS repeatCount, cycle_value AS cycleValue,
              cycle_unit AS cycleUnit, basis, offset_value AS offsetValue,
              offset_unit AS offsetUnit, remind_value AS remindValue, remind_unit AS remindUnit,
              window_before_value AS windowBeforeValue, window_before_unit AS windowBeforeUnit,
              window_after_value AS windowAfterValue, window_after_unit AS windowAfterUnit,
              task_type AS taskType, owner_type AS ownerType, form_code AS formCode,
              passed_window_action AS passedWindowAction, enroll_mode AS enrollMode, sort_no AS sortNo
            FROM fu_plan_node WHERE plan_id = #{planId} ORDER BY sort_no ASC, id ASC
            """)
    List<NodeRow> findNodesByPlanId(long planId);

    /** 插入节点（id 自增回填）。 */
    @Insert(
            """
            INSERT INTO fu_plan_node
              (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value,
               cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit,
               window_before_value, window_before_unit, window_after_value, window_after_unit,
               task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
            VALUES
              (#{planId}, #{parentId}, #{nodeType}, #{name}, #{repeatMode}, #{repeatCount},
               #{cycleValue}, #{cycleUnit}, #{basis}, #{offsetValue}, #{offsetUnit},
               #{remindValue}, #{remindUnit}, #{windowBeforeValue}, #{windowBeforeUnit},
               #{windowAfterValue}, #{windowAfterUnit}, #{taskType}, #{ownerType}, #{formCode},
               #{passedWindowAction}, #{enrollMode}, #{sortNo})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertNode(PlanNodeInsertRow row);

    /** 整体替换前清空旧节点（仅未发布版本可清）。 */
    @Delete("DELETE FROM fu_plan_node WHERE plan_id = #{planId}")
    int deleteNodesByPlanId(long planId);

    /** 发布前置：至少一个子计划与一个节点。 */
    @Select(
            """
            SELECT COUNT(*) FROM fu_plan_node WHERE plan_id = #{planId} AND node_type = 'SUBPLAN'
            """)
    int countSubplans(long planId);

    /** 发布前置：节点数。 */
    @Select("SELECT COUNT(*) FROM fu_plan_node WHERE plan_id = #{planId} AND node_type = 'NODE'")
    int countNodes(long planId);
}
