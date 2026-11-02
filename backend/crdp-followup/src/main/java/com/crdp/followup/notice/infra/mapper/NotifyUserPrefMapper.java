package com.crdp.followup.notice.infra.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 个人通知偏好表 ntf_user_pref（E09-T07 / A-NTF-007、C-NTF-007）。调用前必须已在租户上下文。
 * 逐用户一行（uk_ntf_user_pref_user），本人维护本人偏好；逾期类能否关闭由租户设置 ntf_setting.overdue_sms_off_allowed
 * 决定（应用层校验，A-NTF-007）。
 */
@Mapper
public interface NotifyUserPrefMapper {

    /** 本人偏好（未设置过返回空）。 */
    @Select(
            """
            SELECT id, user_id AS userId, sms_off AS smsOff, updated_by AS updatedBy,
                   updated_at AS updatedAt
            FROM ntf_user_pref WHERE user_id = #{userId}
            """)
    NotifyUserPrefRow find(@Param("userId") long userId);

    /** 保存（有行覆盖、无行插入；user_id 唯一键）。 */
    @Insert(
            """
            INSERT INTO ntf_user_pref (user_id, sms_off, updated_by)
            VALUES (#{userId}, #{smsOff}, #{updatedBy})
            ON DUPLICATE KEY UPDATE sms_off = VALUES(sms_off), updated_by = VALUES(updated_by)
            """)
    int upsert(
            @Param("userId") long userId,
            @Param("smsOff") String smsOff,
            @Param("updatedBy") long updatedBy);

    /** 给定用户中已关闭指定事件短信的人（规则引擎投递 SMS 前检查，关闭 → 台账 SKIPPED）。 sms_off 是无空格 CSV，FIND_IN_SET 精确匹配编码。 */
    @Select(
            """
            <script>
            SELECT user_id FROM ntf_user_pref
            WHERE FIND_IN_SET(#{eventType}, sms_off) &gt; 0
              AND user_id IN
              <foreach collection="userIds" item="userId" open="(" separator="," close=")">#{userId}</foreach>
            </script>
            """)
    List<Long> findSmsOffUsers(
            @Param("userIds") List<Long> userIds, @Param("eventType") String eventType);
}
