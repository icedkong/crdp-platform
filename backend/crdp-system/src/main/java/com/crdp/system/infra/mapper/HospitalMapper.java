package com.crdp.system.infra.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HospitalMapper {

    @Select(
            """
            SELECT id, username, display_name AS displayName, password_hash AS passwordHash, status,
                   phone, must_change_password AS mustChangePassword,
                   password_updated_at AS passwordUpdatedAt
            FROM sys_user
            WHERE username = #{username}
            """)
    HospitalUserRow findUser(@Param("username") String username);

    /** 短信验证码登录按手机号盲索引取账号（E04-T04）。phone_idx 是 HMAC，不落明文。 */
    @Select(
            """
            SELECT id, username, display_name AS displayName, password_hash AS passwordHash, status,
                   phone, must_change_password AS mustChangePassword,
                   password_updated_at AS passwordUpdatedAt
            FROM sys_user
            WHERE phone_idx = #{phoneIdx}
            """)
    HospitalUserRow findUserByPhoneIdx(@Param("phoneIdx") String phoneIdx);

    /** 管理员解锁（E04-T05 / PLT-AUTH-007）：按 id 找账号，取用户名清锁定桶。 */
    @Select(
            """
            SELECT id, username, display_name AS displayName, password_hash AS passwordHash, status,
                   phone, must_change_password AS mustChangePassword,
                   password_updated_at AS passwordUpdatedAt
            FROM sys_user
            WHERE id = #{id}
            """)
    HospitalUserRow findUserById(@Param("id") long id);

    @Select("SELECT param_value FROM cfg_param WHERE param_key = #{paramKey}")
    String paramValue(@Param("paramKey") String paramKey);

    @Select(
            """
            SELECT r.code
            FROM sys_role r
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId} AND r.status = 'ENABLED'
            """)
    List<String> roles(@Param("userId") long userId);

    /** 角色授权并集（E06-T05 起只统计启用角色：停用角色的菜单立即失效，PLT-ORG-004「停用角色」）。 */
    @Select(
            """
            SELECT DISTINCT rm.menu_id
            FROM sys_role_menu rm
            JOIN sys_role r ON r.id = rm.role_id AND r.status = 'ENABLED'
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
            """)
    List<Long> menuIds(@Param("userId") long userId);

    /** 角色数据范围并集（与菜单同口径：只统计启用角色）。 */
    @Select(
            """
            SELECT DISTINCT s.scope_type
            FROM sys_role_scope s
            JOIN sys_role r ON r.id = s.role_id AND r.status = 'ENABLED'
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
            """)
    List<String> scopeTypes(@Param("userId") long userId);

    @Select("SELECT ward_id FROM sys_user_ward WHERE user_id = #{userId}")
    List<Long> wardIds(@Param("userId") long userId);

    @Select("SELECT dept_id FROM sys_user_dept_scope WHERE user_id = #{userId}")
    List<Long> deptIds(@Param("userId") long userId);

    /** 主科室（sys_user.main_dept_id，V12）：「本科室（DEPT）」类型的取值来源；未配置返回 null。 */
    @Select("SELECT main_dept_id FROM sys_user WHERE id = #{userId}")
    Long mainDeptId(@Param("userId") long userId);

    /**
     * 角色自定义科室清单（CUSTOM_DEPT，E07-T02 生效）：只统计启用角色且该角色确有 CUSTOM_DEPT 类型行 —— RoleAdminService
     * 保存时已约束两表同步，这里防御性再校验一次；多角色由 DISTINCT 并集。
     */
    @Select(
            """
            SELECT DISTINCT rsd.dept_id
            FROM sys_role_scope_dept rsd
            JOIN sys_role r ON r.id = rsd.role_id AND r.status = 'ENABLED'
            JOIN sys_role_scope rs ON rs.role_id = r.id AND rs.scope_type = 'CUSTOM_DEPT'
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
            """)
    List<Long> customDeptIds(@Param("userId") long userId);

    /**
     * 角色字段策略显式覆盖并集（E07-T04 / PLT-PERM-009 生效）：只统计启用角色（停用角色的覆盖立即失效， 与菜单 / 范围同口径）；多角色同字段的多行由
     * FieldPolicySnapshot.merge「取最宽松」归并，未覆盖字段回落平台默认。
     */
    @Select(
            """
            SELECT DISTINCT fp.resource AS resource, fp.field AS field, fp.policy AS policy
            FROM sys_role_field_policy fp
            JOIN sys_role r ON r.id = fp.role_id AND r.status = 'ENABLED'
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
            """)
    List<RoleFieldPolicyRow> fieldPolicies(@Param("userId") long userId);

    // ---- E04-T05 / PLT-AUTH-006、010：自助改密 ----

    /** 改密落库：清强制改密标记并把 password_updated_at 推到现在（有效期 90 天从此重算）。 */
    @Update(
            """
            UPDATE sys_user
            SET password_hash = #{passwordHash}, must_change_password = 0,
                password_updated_at = #{updatedAt}
            WHERE id = #{userId}
            """)
    int updatePassword(
            @Param("userId") long userId,
            @Param("passwordHash") String passwordHash,
            @Param("updatedAt") LocalDateTime updatedAt);

    /** 最近若干个历史密码哈希（新到旧），用于「不得与最近 5 次相同」。 */
    @Select(
            """
            SELECT password_hash FROM sys_password_history
            WHERE user_id = #{userId}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<String> passwordHistory(@Param("userId") long userId, @Param("limit") int limit);

    @Insert(
            "INSERT INTO sys_password_history (user_id, password_hash) VALUES (#{userId}, #{passwordHash})")
    int insertPasswordHistory(
            @Param("userId") long userId, @Param("passwordHash") String passwordHash);

    // ---- E06-T11 / PLT-ORG-011：个人中心 ----

    /** 本人改显示名（PLT-ORG-011 基本信息）。 */
    @Update("UPDATE sys_user SET display_name = #{displayName} WHERE id = #{userId}")
    int updateDisplayName(@Param("userId") long userId, @Param("displayName") String displayName);

    /** 换绑手机号（PLT-ORG-011）：同时落密文与盲索引（本院唯一约束由 uk_sys_user_phone_idx 保证）。 */
    @Update(
            """
            UPDATE sys_user SET phone = #{phoneCipher}, phone_idx = #{phoneIdx}
            WHERE id = #{userId}
            """)
    int updatePhone(
            @Param("userId") long userId,
            @Param("phoneCipher") String phoneCipher,
            @Param("phoneIdx") String phoneIdx);

    /** 本人最近登录记录（aud_login_log，倒序取前若干条；个人中心「登录记录」）。 */
    @Select(
            """
            SELECT success, message, created_at AS createdAt
            FROM aud_login_log
            WHERE username = #{username}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<LoginLogRow> loginLogs(@Param("username") String username, @Param("limit") int limit);

    /** 账号创建时间（个人中心基本信息展示）。 */
    @Select("SELECT created_at FROM sys_user WHERE id = #{userId}")
    LocalDateTime createdAt(@Param("userId") long userId);
}
