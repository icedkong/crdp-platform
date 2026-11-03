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

    /** 出院登记写入；并发同号由 uk_pt_patient_mrn 兜底（DuplicateKeyException → 40902）。 */
    @Insert(
            """
            INSERT INTO pt_patient
              (mrn_cipher, mrn_blind, name_cipher, name_blind, id_card_cipher, address_cipher,
               gender, birth_date, dept_id, ward_id, surgery_date, admission_date, discharge_date,
               discharge_status, legacy_no, created_by)
            VALUES
              (#{row.mrnCipher}, #{row.mrnBlind}, #{row.nameCipher}, #{row.nameBlind},
               #{row.idCardCipher}, #{row.addressCipher},
               #{row.gender}, #{row.birthDate}, #{row.deptId}, #{row.wardId},
               #{row.surgeryDate}, #{row.admissionDate}, #{row.dischargeDate},
               #{row.dischargeStatus}, #{row.legacyNo}, #{row.createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id", keyColumn = "id")
    int insertPatient(@Param("row") PatientInsert row);
}
