package com.fu.system.infra.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HospitalMapper {

    @Select(
            """
            SELECT id, username, display_name AS displayName, password_hash AS passwordHash, status
            FROM sys_user
            WHERE username = #{username}
            """)
    HospitalUserRow findUser(@Param("username") String username);

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
}
