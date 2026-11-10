package com.crdp.followup.patient.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import com.crdp.framework.datascope.IgnoreScope;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 患者主表 pt_patient（E13-T01 / A-PAT-003、A-PAT-007、C-PAT-001、C-PAT-002；表 followup V37）。
 *
 * <p>读取分两类（E07 默认拒绝口径）：「当前用户数据范围内」的摘要查询标 {@code @DataScope}（拦截器按病区 / 科室 /
 * 本人改写，越范围行不可见）；「病案号本院唯一」的全院存在性检查标 {@code @IgnoreScope}——唯一性是
 * 全院约束而非用户可见性（A-PAT-007：范围外的同号患者同样阻止登记），查询只取 id，不输出任何患者数据。 插入语句不走查询拦截（E07 只拦 SELECT），唯一键
 * uk_pt_patient_mrn 兜底并发登记。
 */
@Mapper
public interface PatientMapper {

    /** 查重摘要行（范围内）：姓名为密文，由应用层解密后脱敏输出。 */
    record DuplicateRow(Long id, String nameCipher, String wardName, LocalDate dischargeDate) {}

    /** 明文来源行（E07-T05 端口实现用）：全部为密文列。 */
    record SensitiveRow(
            String nameCipher, String mrnCipher, String idCardCipher, String addressCipher) {}

    /** 全院存在性：同盲索引的在册患者 id（软删除不占用病案号）。 */
    @IgnoreScope(reason = "病案号本院唯一（A-PAT-007 / C-PAT-002）：全院查重只取存在性，不输出患者数据；" + "范围外同号患者同样阻止登记")
    @Select(
            """
            SELECT id FROM pt_patient
            WHERE mrn_blind = #{mrnBlind} AND deleted = 0
            LIMIT 1
            """)
    Long findLiveIdByMrnBlind(@Param("mrnBlind") String mrnBlind);

    /**
     * 批量全院存在性（E18-T03 导入预检查重）：给定盲索引中在册（未软删）的子集—— 只返回盲索引本身，不输出任何患者数据； 病区数据范围在导入确认时校验（E18-T04 /
     * T06），预检重复提示按全院口径（范围外同号患者同样构成「重复」）。
     */
    @IgnoreScope(
            reason =
                    "导入预检按病案号全院查重（A-PAT-018 / tech-plan §12④「按盲索引查病案号重复」）："
                            + "只取盲索引存在性，不输出患者数据；病区范围在导入确认时校验（E18-T04 / T06）")
    @Select(
            """
            <script>
            SELECT DISTINCT mrn_blind FROM pt_patient
            WHERE deleted = 0 AND mrn_blind IN
              (<foreach collection='blinds' item='blind' separator=','>#{blind}</foreach>)
            </script>
            """)
    java.util.Set<String> findLiveMrnBlinds(@Param("blinds") java.util.Collection<String> blinds);

    /** 查重摘要（当前用户数据范围内；越范围返回 null，不暴露存在）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT p.id, p.name_cipher AS nameCipher, w.name AS wardName,
                   p.discharge_date AS dischargeDate
            FROM pt_patient p
            LEFT JOIN ward w ON w.id = p.ward_id
            WHERE p.mrn_blind = #{mrnBlind} AND p.deleted = 0
            LIMIT 1
            """)
    DuplicateRow findLiveByMrnBlind(@Param("mrnBlind") String mrnBlind);

    /** 范围内在册患者 id（E13-T03 联系方式 / 状态历史入口的 40400 可见性校验；越范围 null）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT id FROM pt_patient
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    Long findLiveIdById(@Param("id") long id);

    /** 「姓名 + 出生日期相同」的可能重复（C-PAT-002；范围内最多 {@code limit} 条，排除病案号已相同者）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT p.id, p.name_cipher AS nameCipher, w.name AS wardName,
                   p.discharge_date AS dischargeDate
            FROM pt_patient p
            LEFT JOIN ward w ON w.id = p.ward_id
            WHERE p.name_blind = #{nameBlind}
              AND p.birth_date = #{birthDate}
              AND p.deleted = 0
              AND p.mrn_blind != #{mrnBlind}
            ORDER BY p.id
            LIMIT #{limit}
            """)
    List<DuplicateRow> findLiveByIdentity(
            @Param("nameBlind") String nameBlind,
            @Param("birthDate") LocalDate birthDate,
            @Param("mrnBlind") String mrnBlind,
            @Param("limit") int limit);

    /** 明文来源（E07-T05）：当前用户数据范围内该患者的全部敏感密文列；不存在 / 越范围返回 null。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT name_cipher AS nameCipher, mrn_cipher AS mrnCipher,
                   id_card_cipher AS idCardCipher, address_cipher AS addressCipher
            FROM pt_patient
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    SensitiveRow findSensitiveById(@Param("id") long id);

    /** 病区候选（启用；病区字典维护随 E13-T08）。 */
    @Select("SELECT id, name FROM ward WHERE status = 'ENABLED' ORDER BY id")
    List<OptionRow> findWards();

    /** 科室候选（启用；C-PAT-001 患者登记用）。 */
    @Select("SELECT id, name FROM sys_dept WHERE status = 'ENABLED' ORDER BY id")
    List<OptionRow> findDepts();

    /** 病区 / 科室候选行。 */
    record OptionRow(Long id, String name) {}

    /** 病区存在且启用（登记校验）。 */
    @Select(
            """
            SELECT COUNT(*) FROM ward
            WHERE id = #{id} AND status = 'ENABLED'
            """)
    int countEnabledWard(@Param("id") long id);

    /** 科室存在且启用（登记校验）。 */
    @Select(
            """
            SELECT COUNT(*) FROM sys_dept
            WHERE id = #{id} AND status = 'ENABLED'
            """)
    int countEnabledDept(@Param("id") long id);

    /** 在册患者 id 与当前病区（E13-T08 转病区入口的范围内校验；越范围 null）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT id, ward_id AS wardId FROM pt_patient
            WHERE id = #{id} AND deleted = 0
            LIMIT 1
            """)
    WardRef findLiveWardById(@Param("id") long id);

    /** 在册患者 id 与当前病区行。 */
    record WardRef(Long id, Long wardId) {}

    // ---------------------------------------------------------------- 软删除与恢复（E13-T09 /
    // A-PAT-012、A-NFR-008）

    /** 已软删患者行（恢复入口定位用；只取存在性 / 病案号盲索引 / 删除原因，不输出患者数据）。 */
    record DeletedRow(Long id, String mrnBlind, String deleteReason) {}

    /**
     * 按 id 取已软删行（E13-T09 恢复入口）。@IgnoreScope 理由：软删后行已不可见（全部在册查询都带 deleted =
     * 0），恢复入口必须能定位不可见行（A-PAT-012「可由租户管理员恢复」）；本查询不输出任何患者 明文或脱敏数据，病案号盲索引只用于恢复前的占位冲突检查。
     */
    @IgnoreScope(
            reason =
                    "恢复软删患者（A-PAT-012）必须能定位不可见行；只取 id / 病案号盲索引 / 删除原因，"
                            + "不输出患者数据，冲突检查口径与登记查重（findLiveIdByMrnBlind）一致")
    @Select(
            """
            SELECT id, mrn_blind AS mrnBlind, delete_reason AS deleteReason
            FROM pt_patient
            WHERE id = #{id} AND deleted = 1
            LIMIT 1
            """)
    DeletedRow findDeletedById(@Param("id") long id);

    /**
     * 软删除（A-PAT-012、A-NFR-008 只软删）：置删除标记并令 delete_token = id 释放病案号 （uk_pt_patient_mrn
     * 允许软删后病案号复用），全部数据保留；乐观锁版本随变更递增。 返回 0 表示已不在册（并发删除 / 已删）。
     */
    @Update(
            """
            UPDATE pt_patient
            SET deleted = 1, delete_token = id, delete_reason = #{reason},
                updated_by = #{updatedBy}, version = version + 1
            WHERE id = #{id} AND deleted = 0
            """)
    int softDelete(
            @Param("id") long id,
            @Param("reason") String reason,
            @Param("updatedBy") long updatedBy);

    /**
     * 恢复（A-PAT-012）：清删除标记与 token、清删除原因；病案号被新在册患者占用时由 uk_pt_patient_mrn 兜底 （服务层先行冲突检查给 40902）。返回 0
     * 表示已被恢复。
     */
    @Update(
            """
            UPDATE pt_patient
            SET deleted = 0, delete_token = 0, delete_reason = NULL,
                updated_by = #{updatedBy}, version = version + 1
            WHERE id = #{id} AND deleted = 1
            """)
    int restore(@Param("id") long id, @Param("updatedBy") long updatedBy);

    /** 转病区（A-WARD-005）：只改归属列并递增乐观锁版本；任务 / 附件经 patient_id 关联患者自动随迁。 */
    @Update(
            """
            UPDATE pt_patient
            SET ward_id = #{wardId}, updated_by = #{updatedBy}, version = version + 1
            WHERE id = #{id} AND deleted = 0
            """)
    int updateWard(
            @Param("id") long id, @Param("wardId") long wardId, @Param("updatedBy") long updatedBy);

    /**
     * 随访状态流转（E14-T06 / A-FU-007、A-FU-008、A-PAT-011）：只改状态位并递增乐观锁版本，guardedOn 非空时只在当前值等于它才更新（并发流转 0
     * 行由调用方处理）。定向 UPDATE，数据范围前置校验由调用方 （经 PatientContactGateway 40400）完成；状态时间线 pt_status_history 由
     * PatientStatusHistoryService 只追加写入。
     */
    @Update(
            """
            <script>
            UPDATE pt_patient
            SET followup_status = #{status}, updated_by = #{updatedBy}, version = version + 1
            WHERE id = #{id} AND deleted = 0
              <if test="guardedOn != null">AND followup_status = #{guardedOn}</if>
            </script>""")
    int updateFollowupStatus(
            @Param("id") long id,
            @Param("status") String status,
            @Param("guardedOn") String guardedOn,
            @Param("updatedBy") Long updatedBy);

    /**
     * 出院登记写入；并发同号由 uk_pt_patient_mrn 兜底（DuplicateKeyException → 40902）。 followup_status
     * 随行写入（E23-T09 / UAT-SELF-05）：出院状态 = 3 死亡 → DEAD（A 规格 §10 状态规则表），其余 ACTIVE（OQ-71②）；此前依赖列默认
     * ACTIVE 导致死亡患者可被批量入组。
     */
    @Insert(
            """
            INSERT INTO pt_patient
              (mrn_cipher, mrn_blind, name_cipher, name_blind, id_card_cipher, address_cipher,
               gender, birth_date, dept_id, ward_id, surgery_date, admission_date, discharge_date,
               discharge_status, legacy_no, created_by, followup_status)
            VALUES
              (#{row.mrnCipher}, #{row.mrnBlind}, #{row.nameCipher}, #{row.nameBlind},
               #{row.idCardCipher}, #{row.addressCipher},
               #{row.gender}, #{row.birthDate}, #{row.deptId}, #{row.wardId},
               #{row.surgeryDate}, #{row.admissionDate}, #{row.dischargeDate},
               #{row.dischargeStatus}, #{row.legacyNo}, #{row.createdBy},
               COALESCE(#{row.followupStatus}, 'ACTIVE'))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id", keyColumn = "id")
    int insertPatient(@Param("row") PatientInsert row);
}
