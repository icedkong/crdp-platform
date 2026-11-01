package com.crdp.system.infra.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * E06-T02 / PLT-ORG-001、002：院区与科室的租户库读写。 表结构见 db/tenant/base/V1__init_tenant_base.sql 与
 * V9__org_dept_fields.sql； 数据只在租户上下文内访问（TenantRoutingDataSource 按令牌选库）。
 */
@Mapper
public interface OrgMapper {

    // ---- 院区（sys_org）----

    @Select("SELECT id, code, name, status FROM sys_org ORDER BY id")
    List<OrgRow> listOrgs();

    @Select("SELECT id, code, name, status FROM sys_org WHERE id = #{id}")
    OrgRow findOrg(@Param("id") long id);

    @Select("SELECT id FROM sys_org WHERE code = #{code}")
    Long findOrgIdByCode(@Param("code") String code);

    @Select("SELECT COUNT(*) FROM sys_org WHERE name = #{name}")
    int countOrgByName(@Param("name") String name);

    @Select(
            """
            SELECT COUNT(*) FROM sys_dept
            WHERE org_id = #{orgId} AND status = 'ENABLED'
            """)
    int countEnabledDeptsOfOrg(@Param("orgId") long orgId);

    @Insert("INSERT INTO sys_org (code, name, status) VALUES (#{code}, #{name}, 'ENABLED')")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertOrg(OrgRow org);

    @Update("UPDATE sys_org SET name = #{name} WHERE id = #{id}")
    int updateOrgName(@Param("id") long id, @Param("name") String name);

    @Update("UPDATE sys_org SET status = #{status} WHERE id = #{id}")
    int updateOrgStatus(@Param("id") long id, @Param("status") String status);

    // ---- 科室（sys_dept）----

    @Select(
            """
            SELECT d.id, d.org_id AS orgId, d.parent_id AS parentId, d.code, d.name,
                   d.dept_type AS deptType, d.leader_user_id AS leaderUserId,
                   u.display_name AS leaderName, d.his_code AS hisCode,
                   d.sort_no AS sortNo, d.status
            FROM sys_dept d
            LEFT JOIN sys_user u ON u.id = d.leader_user_id
            ORDER BY d.sort_no, d.id
            """)
    List<DeptRow> listDepts();

    @Select(
            """
            SELECT d.id, d.org_id AS orgId, d.parent_id AS parentId, d.code, d.name,
                   d.dept_type AS deptType, d.leader_user_id AS leaderUserId,
                   u.display_name AS leaderName, d.his_code AS hisCode,
                   d.sort_no AS sortNo, d.status
            FROM sys_dept d
            LEFT JOIN sys_user u ON u.id = d.leader_user_id
            WHERE d.id = #{id}
            """)
    DeptRow findDept(@Param("id") long id);

    @Select("SELECT id FROM sys_dept WHERE code = #{code}")
    Long findDeptIdByCode(@Param("code") String code);

    @Insert(
            """
            INSERT INTO sys_dept
              (org_id, parent_id, code, name, dept_type, leader_user_id, his_code, sort_no, status)
            VALUES
              (#{orgId}, #{parentId}, #{code}, #{name}, #{deptType},
               #{leaderUserId}, #{hisCode}, #{sortNo}, 'ENABLED')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertDept(DeptRow dept);

    @Update(
            """
            UPDATE sys_dept
            SET org_id = #{orgId}, parent_id = #{parentId}, name = #{name},
                dept_type = #{deptType}, leader_user_id = #{leaderUserId},
                his_code = #{hisCode}, sort_no = #{sortNo}
            WHERE id = #{id}
            """)
    int updateDept(DeptRow dept);

    @Update("UPDATE sys_dept SET status = #{status} WHERE id = #{id}")
    int updateDeptStatus(@Param("id") long id, @Param("status") String status);

    @Select(
            """
            SELECT COUNT(*) FROM sys_dept
            WHERE parent_id = #{deptId} AND status = 'ENABLED'
            """)
    int countEnabledChildren(@Param("deptId") long deptId);

    @Select("SELECT COUNT(*) FROM sys_dept WHERE parent_id = #{deptId}")
    int countChildren(@Param("deptId") long deptId);

    /** 科室（含子树）上的成员数：按 sys_user_dept_scope 引用计数（平台规格「科室下有用户……不可删除，只能停用」的影响面）。 */
    @Select(
            """
            <script>
            SELECT COUNT(DISTINCT user_id) FROM sys_user_dept_scope
            WHERE dept_id IN
            <foreach collection="deptIds" item="deptId" open="(" separator="," close=")">#{deptId}</foreach>
            </script>
            """)
    int countDeptMembers(@Param("deptIds") List<Long> deptIds);

    // ---- 负责人候选（E06-T02 暂行，PLT-ORG-012 共用选择器落地后由 E06-T09 接管）----

    @Select(
            """
            SELECT id, username, display_name AS displayName
            FROM sys_user
            WHERE status = 'ENABLED'
              AND (#{keyword} = '' OR username LIKE CONCAT('%', #{keyword}, '%')
                   OR display_name LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY id
            LIMIT 50
            """)
    List<UserOptionRow> userOptions(@Param("keyword") String keyword);

    /** 负责人校验：必须是本院启用中的用户（不受候选下拉前 50 条限制）。 */
    @Select("SELECT id FROM sys_user WHERE id = #{id} AND status = 'ENABLED'")
    Long findEnabledUserId(@Param("id") long id);
}
