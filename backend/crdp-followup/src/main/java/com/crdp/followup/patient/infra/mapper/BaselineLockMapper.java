package com.crdp.followup.patient.infra.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 基线锁定状态表 pt_baseline_lock（E13-T02 / A-PAT-006；表 followup V60）。
 *
 * <p>一行 / 患者（tech-plan §7）：locked 为当前态，最近一次锁定 / 解锁的人、时间与原因随行保留； 历次流转的操作审计（BASELINE_LOCK /
 * BASELINE_UNLOCK，留痕）在 {@code AuditLogger}，只追加。查询带 {@code @DataScope}（越范围行不可见），
 * 写入前的可见性校验由应用层经网关先行完成。
 */
@Mapper
public interface BaselineLockMapper {

    /** 锁定状态行。 */
    record LockRowRaw(
            long id,
            long patientId,
            boolean locked,
            String lockReason,
            Long lockedBy,
            LocalDateTime lockedAt,
            String unlockReason,
            Long unlockedBy,
            LocalDateTime unlockedAt) {}

    /** 患者锁定状态（越范围 / 未建行 null——未建行视为未锁定）。 */
    @com.crdp.framework.datascope.DataScope(resource = "patient")
    @Select(
            """
            SELECT id, patient_id AS patientId, locked, lock_reason AS lockReason,
                   locked_by AS lockedBy, locked_at AS lockedAt,
                   unlock_reason AS unlockReason, unlocked_by AS unlockedBy, unlocked_at AS unlockedAt
            FROM pt_baseline_lock
            WHERE patient_id = #{patientId}
            """)
    LockRowRaw findLock(@Param("patientId") long patientId);

    /** 锁定（行不存在则建行）：置 locked、记录锁定人与时间（幂等键 uk_pt_baseline_lock 由应用层先查当前态）。 */
    @Insert(
            """
            INSERT INTO pt_baseline_lock (patient_id, locked, lock_reason, locked_by, locked_at)
            VALUES (#{patientId}, 1, #{reason}, #{lockedBy}, CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
              locked = 1, lock_reason = VALUES(lock_reason),
              locked_by = VALUES(locked_by), locked_at = VALUES(locked_at)
            """)
    int lock(
            @Param("patientId") long patientId,
            @Param("reason") String reason,
            @Param("lockedBy") long lockedBy);

    /** 解锁：清 locked、记录解锁人 / 时间与原因（必填，A-PAT-006）；最近一次锁定的信息保留。 */
    @Update(
            """
            UPDATE pt_baseline_lock
            SET locked = 0, unlock_reason = #{reason},
                unlocked_by = #{unlockedBy}, unlocked_at = CURRENT_TIMESTAMP(3)
            WHERE patient_id = #{patientId} AND locked = 1
            """)
    int unlock(
            @Param("patientId") long patientId,
            @Param("reason") String reason,
            @Param("unlockedBy") long unlockedBy);

    /** 操作人显示名（锁定 / 解锁视图与审计描述用；取不到 null）。 */
    @Select("SELECT display_name FROM sys_user WHERE id = #{userId}")
    String findDisplayName(@Param("userId") Long userId);
}
