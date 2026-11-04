package com.crdp.followup.patient.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 批量患者操作的数据面（E13-T07 / A-PAT-016、A-PAT-017、C-PAT-016；表 followup V37）。
 *
 * <p>批量入口按「结果逐条可查」设计（tasks.csv E13-T07 验收）：一次批量查询取操作者数据范围内的患者行（姓名 / 病案号密文由应用层解密后脱敏输出，AGENTS §5.3），
 * 范围外 / 不存在的 id 不出现在结果里（上层按 NOT_FOUND 逐条列明，不暴露对象存在，§5.2）。
 *
 * <p>医生相关查询（sys_user / sys_user_ward / ward）不在 {@code ControlledTables}——医生姓名与病区归属是组织信息而非患者数据，与
 * {@code WardMapper.findMembers} 同口径不拦截；「只能选该病区的医生」（A-PAT-017）由服务层按患者行 ward_id 逐条校验。
 *
 * <p>{@link #updateResponsibleUser} 为定向 UPDATE：数据范围前置校验由批量查询（@DataScope）承担，ward_id
 * 守卫保证并发转病区时不把医生落到别的病区的患者上。
 */
@Mapper
public interface PatientBatchMapper {

    /** 批量患者行：姓名 / 病案号为密文，应用层解密后脱敏输出。 */
    record BatchPatientRow(
            long id,
            String nameCipher,
            String mrnCipher,
            Long wardId,
            String wardName,
            String followupStatus) {}

    /** 批量取操作者数据范围内的在册患者行（范围外 id 不返回；软删不可见）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT p.id, p.name_cipher AS nameCipher, p.mrn_cipher AS mrnCipher,
                   p.ward_id AS wardId, w.name AS wardName, p.followup_status AS followupStatus
            FROM pt_patient p
            LEFT JOIN ward w ON w.id = p.ward_id
            WHERE p.deleted = 0 AND p.id IN
              <foreach collection="ids" item="item" open="(" separator="," close=")">#{item}</foreach>
            </script>
            """)
    List<BatchPatientRow> findPatientsInScope(@Param("ids") List<Long> ids);

    /** 医生存在且启用（批量指定的整体校验，A-PAT-017）。 */
    @Select("SELECT COUNT(*) FROM sys_user WHERE id = #{id} AND status = 'ENABLED'")
    int countEnabledDoctor(@Param("id") long id);

    /** 医生负责的病区清单（sys_user_ward；A-PAT-017「只能选该病区的医生」）。 */
    @Select("SELECT ward_id FROM sys_user_ward WHERE user_id = #{doctorId}")
    List<Long> findDoctorWardIds(@Param("doctorId") long doctorId);

    /**
     * 指定责任医生（A-PAT-017）：只改 pt_patient.responsible_user_id 并递增乐观锁版本； ward_id 守卫——并发转病区后 0
     * 行由调用方按跳过处理。定向 UPDATE，数据范围前置校验由批量查询（@DataScope）承担（与 PatientMapper.updateWard 同口径）。
     */
    @Update(
            """
            UPDATE pt_patient
            SET responsible_user_id = #{doctorId}, updated_by = #{operatorId}, version = version + 1
            WHERE id = #{patientId} AND deleted = 0 AND ward_id = #{wardId}
            """)
    int updateResponsibleUser(
            @Param("patientId") long patientId,
            @Param("wardId") long wardId,
            @Param("doctorId") long doctorId,
            @Param("operatorId") Long operatorId);

    /** 责任医生候选行（批量指定 / 批量入组页右侧下拉，A-PAT-016 / A-PAT-017）。 */
    record DoctorOptionRow(
            long id, String username, String displayName, long wardId, String wardName) {}

    /**
     * 责任医生候选：启用病区的启用用户（负责病区 = sys_user_ward），可选按病区过滤（前端传所选患者病区并集）。 医生与病区为组织信息（非患者数据，与
     * WardMapper.findMembers 同口径不拦截）；逐条「医生属于患者病区」的强校验在服务层。
     */
    @Select(
            """
            <script>
            SELECT u.id, u.username, u.display_name AS displayName,
                   w.id AS wardId, w.name AS wardName
            FROM sys_user_ward uw
            JOIN sys_user u ON u.id = uw.user_id AND u.status = 'ENABLED'
            JOIN ward w ON w.id = uw.ward_id AND w.status = 'ENABLED'
            <if test="wardIds != null">
              WHERE uw.ward_id IN
              <foreach collection="wardIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            ORDER BY w.sort_no, w.id, u.username
            </script>
            """)
    List<DoctorOptionRow> findDoctorOptions(@Param("wardIds") List<Long> wardIds);
}
