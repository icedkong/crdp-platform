package com.crdp.followup.notice.infra.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 接收人解析查询（E09-T04 / A-NTF-004、C-NTF-004）。只查 sys_user / sys_user_ward / sys_role_scope，
 * 不查患者表（任何通知只发医护，首期不发患者）。调用前必须已在租户上下文。
 */
@Mapper
public interface NotifyRecipientMapper {

    /** 用户是否存在且启用。 */
    @Select("SELECT COUNT(*) FROM sys_user WHERE id = #{userId} AND status = 'ENABLED'")
    int countEnabledUser(@Param("userId") long userId);

    /** 用户是否负责该病区（「接收人收到通知前按病区范围复核」）。 */
    @Select(
            """
            SELECT COUNT(*) FROM sys_user_ward WHERE user_id = #{userId} AND ward_id = #{wardId}
            """)
    int countWardMembership(@Param("userId") long userId, @Param("wardId") long wardId);

    /** 用户是否持有启用角色且数据范围为全院（租户管理员等不受病区限制，A-WARD-007）。 */
    @Select(
            """
            SELECT COUNT(*)
            FROM sys_user_role ur
            JOIN sys_role r ON r.id = ur.role_id AND r.status = 'ENABLED'
            JOIN sys_role_scope rs ON rs.role_id = ur.role_id
            WHERE ur.user_id = #{userId} AND rs.scope_type = 'ALL'
            """)
    int countHospitalWideScope(@Param("userId") long userId);

    /** 该病区全部启用成员（无负责人时的兜底接收人：A 是医生与随访员、C 含护士——成员即经「负责病区」关联的医护）。 */
    @Select(
            """
            SELECT u.id
            FROM sys_user u
            JOIN sys_user_ward w ON w.user_id = u.id
            WHERE w.ward_id = #{wardId} AND u.status = 'ENABLED'
            ORDER BY u.id
            """)
    List<Long> listEnabledWardStaff(@Param("wardId") long wardId);

    /** 病区默认随访负责人（A-WARD-001；「逾期抄送病区负责人」暂按此解析，OQ-60）。 */
    @Select("SELECT default_owner_id FROM ward WHERE id = #{wardId}")
    Long findWardDefaultOwner(@Param("wardId") long wardId);

    /** 用户绑定手机号密文（purpose=sys-user-phone 的信封加密；未绑定为 null）。E09-T05 汇总作业取号，解密走 CryptoService。 */
    @Select("SELECT phone FROM sys_user WHERE id = #{userId}")
    String findPhone(@Param("userId") long userId);
}
