package com.crdp.followup.ward.infra.mapper;

import com.crdp.framework.datascope.IgnoreScope;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 病区 / 中心字典 ward（E13-T08 / A-WARD-001、A-WARD-008；表 followup V2 建、V43 补 ward_type / alias /
 * sort_no）。
 *
 * <p>ward 不在 {@code ControlledTables}（字典表，非患者数据），查询不拦截；唯一的受控表访问是「病区下在册
 * 患者数」（pt_patient）——那是字典维护与停用前置检查的管理口径计数（A-WARD-008「病区下仍有在随访患者时 不能停用」），与操作者本人的患者可见范围无关，按
 * {@code @IgnoreScope} 全院计数、只取计数不取患者行。
 */
@Mapper
public interface WardMapper {

    /** 病区行（列表 / 编辑回显；patientCount = 在册患者数，memberCount = 启用状态的负责医生数）。 */
    record WardRow(
            Long id,
            String code,
            String name,
            String wardType,
            String alias,
            Long defaultOwnerId,
            String defaultOwnerName,
            Integer sortNo,
            String status,
            Long patientCount,
            Long memberCount) {}

    /** 成员行（A-WARD-002 在病区页查看成员）。 */
    record MemberRow(Long id, String username, String displayName) {}

    /**
     * 全部病区（含停用；按 sort_no、id 升序）。SELECT 项里的 pt_patient 子查询是每病区在册患者计数 （管理口径聚合，不输出患者行），按
     * {@code @IgnoreScope} 放行——字典维护者未必有患者可见范围。
     */
    @IgnoreScope(
            reason =
                    "病区列表的每病区在册患者计数（pages.md §10「患者数」）：管理口径聚合，"
                            + "不输出任何患者行；字典维护者（followup:ward:edit）未必有患者数据范围")
    @Select(
            """
            SELECT w.id, w.code, w.name, w.ward_type AS wardType, w.alias,
                   w.default_owner_id AS defaultOwnerId, u.display_name AS defaultOwnerName,
                   w.sort_no AS sortNo, w.status,
                   (SELECT COUNT(*) FROM pt_patient p
                     WHERE p.ward_id = w.id AND p.deleted = 0) AS patientCount,
                   (SELECT COUNT(*) FROM sys_user_ward uw
                     JOIN sys_user su ON su.id = uw.user_id
                    WHERE uw.ward_id = w.id AND su.status = 'ENABLED') AS memberCount
            FROM ward w
            LEFT JOIN sys_user u ON u.id = w.default_owner_id
            ORDER BY w.sort_no, w.id
            """)
    List<WardRow> findAll();

    /** 单个病区（编辑回显 / 状态变更前取当前值；不存在 null）。计数口径同 {@link #findAll}。 */
    @IgnoreScope(
            reason =
                    "病区详情的每病区在册患者计数（A-WARD-008 停用提示）：管理口径聚合，"
                            + "不输出任何患者行；字典维护者（followup:ward:edit）未必有患者数据范围")
    @Select(
            """
            SELECT w.id, w.code, w.name, w.ward_type AS wardType, w.alias,
                   w.default_owner_id AS defaultOwnerId, u.display_name AS defaultOwnerName,
                   w.sort_no AS sortNo, w.status,
                   (SELECT COUNT(*) FROM pt_patient p
                     WHERE p.ward_id = w.id AND p.deleted = 0) AS patientCount,
                   (SELECT COUNT(*) FROM sys_user_ward uw
                     JOIN sys_user su ON su.id = uw.user_id
                    WHERE uw.ward_id = w.id AND su.status = 'ENABLED') AS memberCount
            FROM ward w
            LEFT JOIN sys_user u ON u.id = w.default_owner_id
            WHERE w.id = #{id}
            """)
    WardRow findById(@Param("id") long id);

    /** 按编码取病区 id（编码本院唯一，含停用；不存在 null）。 */
    @Select("SELECT id FROM ward WHERE code = #{code} LIMIT 1")
    Long findIdByCode(@Param("code") String code);

    /** 病区成员（负责病区 = 本病区的启用用户，按账号排序）。 */
    @Select(
            """
            SELECT u.id, u.username, u.display_name AS displayName
            FROM sys_user_ward uw
            JOIN sys_user u ON u.id = uw.user_id
            WHERE uw.ward_id = #{wardId} AND u.status = 'ENABLED'
            ORDER BY u.username
            """)
    List<MemberRow> findMembers(@Param("wardId") long wardId);

    /** 病区下在册患者数（A-WARD-008 停用前置检查）：管理口径全院计数，只取计数不取患者数据。 */
    @IgnoreScope(reason = "病区停用前置检查（A-WARD-008）：按全院计数该病区在册患者，" + "计数是字典维护的管理口径而非患者可见性，不输出任何患者数据")
    @Select("SELECT COUNT(*) FROM pt_patient WHERE ward_id = #{wardId} AND deleted = 0")
    long countLivePatients(@Param("wardId") long wardId);

    /** 用户存在且启用（默认随访负责人校验）。 */
    @Select("SELECT COUNT(*) FROM sys_user WHERE id = #{id} AND status = 'ENABLED'")
    int countEnabledUser(@Param("id") long id);

    /** 新建病区；编码唯一键兜底并发（DuplicateKeyException → 40902）。 */
    @Insert(
            """
            INSERT INTO ward
              (code, name, ward_type, alias, default_owner_id, sort_no, status)
            VALUES
              (#{row.code}, #{row.name}, #{row.wardType}, #{row.alias},
               #{row.defaultOwnerId}, #{row.sortNo}, 'ENABLED')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id", keyColumn = "id")
    int insertWard(@Param("row") WardInsert row);

    /** 编辑病区（编码与状态不经本语句：编码不可改、状态走专用接口）；未并发受影响的行数为 0 表示不存在。 */
    @Update(
            """
            UPDATE ward
            SET name = #{name}, ward_type = #{wardType}, alias = #{alias},
                default_owner_id = #{defaultOwnerId}, sort_no = #{sortNo}
            WHERE id = #{id}
            """)
    int updateWard(
            @Param("id") long id,
            @Param("name") String name,
            @Param("wardType") String wardType,
            @Param("alias") String alias,
            @Param("defaultOwnerId") Long defaultOwnerId,
            @Param("sortNo") int sortNo);

    /** 启用 / 停用（A-WARD-008：停用前的患者数拦截在应用层）。 */
    @Update("UPDATE ward SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") long id, @Param("status") String status);
}
