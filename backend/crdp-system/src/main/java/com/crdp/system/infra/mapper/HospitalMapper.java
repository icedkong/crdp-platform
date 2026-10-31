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

    @Select(
            """
            SELECT DISTINCT rm.menu_id
            FROM sys_role_menu rm
            JOIN sys_user_role ur ON ur.role_id = rm.role_id
            WHERE ur.user_id = #{userId}
            """)
    List<Long> menuIds(@Param("userId") long userId);

    @Select(
            """
            SELECT DISTINCT s.scope_type
            FROM sys_role_scope s
            JOIN sys_user_role ur ON ur.role_id = s.role_id
            WHERE ur.user_id = #{userId}
            """)
    List<String> scopeTypes(@Param("userId") long userId);

    @Select("SELECT ward_id FROM sys_user_ward WHERE user_id = #{userId}")
    List<Long> wardIds(@Param("userId") long userId);

    @Select("SELECT dept_id FROM sys_user_dept_scope WHERE user_id = #{userId}")
    List<Long> deptIds(@Param("userId") long userId);

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
}
