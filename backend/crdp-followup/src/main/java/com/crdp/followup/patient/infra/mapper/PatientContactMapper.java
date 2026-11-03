package com.crdp.followup.patient.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 联系方式版本表 pt_contact（E13-T03 / tech-plan §7「每个号码一个版本」；表 followup V39）。
 *
 * <p>查询全部为「当前用户数据范围内」口径（{@code @DataScope}：拦截器按 pt_patient 锚点 EXISTS 改写， 越范围行不可见）；插入不走查询拦截（E07 只拦
 * SELECT），新增版本前的可见性校验由应用层经网关的 {@code @DataScope} 查询先行完成。
 */
@Mapper
public interface PatientContactMapper {

    /** 版本行：电话为密文列，由应用层解密后脱敏输出（明文不回传、不进日志）。 */
    record ContactRow(
            Long id,
            String slot,
            String phoneCipher,
            Boolean wechatAdded,
            LocalDate effectiveDate,
            String source,
            Long sourceRefId,
            Integer version,
            String remark,
            Long createdBy,
            LocalDateTime createdAt) {}

    /** 某槽位的当前版本（version 最大者；无任何版本返回 null）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT id, slot, phone_cipher AS phoneCipher, wechat_added AS wechatAdded,
                   effective_date AS effectiveDate, source, source_ref_id AS sourceRefId,
                   version, remark, created_by AS createdBy, created_at AS createdAt
            FROM pt_contact
            WHERE patient_id = #{patientId} AND slot = #{slot}
            ORDER BY version DESC
            LIMIT 1
            """)
    ContactRow findCurrentVersion(@Param("patientId") long patientId, @Param("slot") String slot);

    /** 全部版本（当前与历史，槽位升序、版本新在前；GET contact-info 数据源）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT id, slot, phone_cipher AS phoneCipher, wechat_added AS wechatAdded,
                   effective_date AS effectiveDate, source, source_ref_id AS sourceRefId,
                   version, remark, created_by AS createdBy, created_at AS createdAt
            FROM pt_contact
            WHERE patient_id = #{patientId}
            ORDER BY slot, version DESC
            """)
    List<ContactRow> findVersions(@Param("patientId") long patientId);

    /** 当前电话1密文（明文查看 phone 字段来源，patient.md §5；无版本返回 null）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT phone_cipher AS phoneCipher
            FROM pt_contact
            WHERE patient_id = #{patientId} AND slot = 'PHONE_1'
            ORDER BY version DESC
            LIMIT 1
            """)
    String findCurrentPhone1Cipher(@Param("patientId") long patientId);

    /** 新增版本（永不覆盖旧行；version 唯一键兜底并发同槽位提交）。 */
    @Insert(
            """
            INSERT INTO pt_contact
              (patient_id, slot, phone_cipher, phone_blind, wechat_added, effective_date,
               source, source_ref_id, version, remark, created_by)
            VALUES
              (#{row.patientId}, #{row.slot}, #{row.phoneCipher}, #{row.phoneBlind},
               #{row.wechatAdded}, #{row.effectiveDate}, #{row.source}, #{row.sourceRefId},
               #{row.version}, #{row.remark}, #{row.createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id", keyColumn = "id")
    int insertVersion(@Param("row") ContactVersionInsert row);
}
