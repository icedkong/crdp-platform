package com.crdp.followup.notice.infra.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 租户通知发送设置 ntf_setting（E09-T07 / A-NTF-005、A-NTF-007；单行 id=1）。 调用前必须已在租户上下文。 免打扰时段（默认
 * 21:00–08:00，医院已确认）与每日短信上限（默认 3）由 E09-T05 汇总作业消费； 逾期类是否允许个人关闭由本任务消费。
 */
@Mapper
public interface NotifySettingMapper {

    /** 生效设置（单行 id=1；种子保证存在，防御性允许空）。 */
    @Select(
            """
            SELECT id, dnd_start AS dndStart, dnd_end AS dndEnd,
                   sms_daily_limit AS smsDailyLimit,
                   overdue_sms_off_allowed AS overdueSmsOffAllowed,
                   updated_by AS updatedBy, updated_at AS updatedAt
            FROM ntf_setting WHERE id = 1
            """)
    NotifySettingRow find();

    /** 保存（种子已有 id=1 行，只更新）。 */
    @Update(
            """
            UPDATE ntf_setting
            SET dnd_start = #{dndStart}, dnd_end = #{dndEnd},
                sms_daily_limit = #{smsDailyLimit},
                overdue_sms_off_allowed = #{overdueSmsOffAllowed},
                updated_by = #{updatedBy}
            WHERE id = 1
            """)
    int update(NotifySettingRow row);
}
