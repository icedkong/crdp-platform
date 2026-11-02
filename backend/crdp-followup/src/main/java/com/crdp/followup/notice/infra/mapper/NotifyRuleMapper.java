package com.crdp.followup.notice.infra.mapper;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 通知事件规则表 ntf_rule（E09-T04 / A-NTF-001、C-NTF-001）。调用前必须已在租户上下文。 缺行 = 未配置，应用层回落到 {@link
 * com.crdp.followup.notice.domain.NotifyEventType} 目录默认值（全新租户由迁移 V22 落同值种子）。
 */
@Mapper
public interface NotifyRuleMapper {

    /** 全部已配置行。 */
    @Select(
            """
            SELECT id, event_type AS eventType, enabled, advance_days AS advanceDays,
                   send_time AS sendTime, channels, recipients,
                   sms_template_code AS smsTemplateCode,
                   updated_by AS updatedBy, updated_at AS updatedAt
            FROM ntf_rule
            """)
    List<NotifyRuleRow> findAll();

    /** 单事件行（缺行 = 用默认值）。 */
    @Select(
            """
            SELECT id, event_type AS eventType, enabled, advance_days AS advanceDays,
                   send_time AS sendTime, channels, recipients,
                   sms_template_code AS smsTemplateCode,
                   updated_by AS updatedBy, updated_at AS updatedAt
            FROM ntf_rule WHERE event_type = #{eventType}
            """)
    Optional<NotifyRuleRow> find(@Param("eventType") String eventType);

    /** 新配置行（正常路径：迁移种子已建全部事件行，这里只是兜底）。 */
    @Insert(
            """
            INSERT INTO ntf_rule
              (event_type, enabled, advance_days, send_time, channels, recipients,
               sms_template_code, updated_by)
            VALUES (#{eventType}, #{enabled}, #{advanceDays}, #{sendTime}, #{channels},
                    #{recipients}, #{smsTemplateCode}, #{updatedBy})
            """)
    int insert(NotifyRuleRow row);

    /** 更新配置（种子行都在；只改请求给出的字段以外全部覆盖为应用层合并后的完整值）。 */
    @Update(
            """
            UPDATE ntf_rule
            SET enabled = #{enabled}, advance_days = #{advanceDays}, send_time = #{sendTime},
                channels = #{channels}, recipients = #{recipients},
                sms_template_code = #{smsTemplateCode}, updated_by = #{updatedBy}
            WHERE event_type = #{eventType}
            """)
    int update(NotifyRuleRow row);
}
