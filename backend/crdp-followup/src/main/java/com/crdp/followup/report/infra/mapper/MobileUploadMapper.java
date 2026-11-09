package com.crdp.followup.report.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 手机扫码上传的租户库只读（E16-T10 / A-ATT-015、C-SCAN-008）：患者脱敏信息源（姓名 / 病案号密文、 病区名）与 {@code h5_upload_login}
 * 功能开关（C-NFR-006「H5 页面仅在登录后可用」——C 上线时经配置打开，A 默认关闭）。 写入（令牌）在 Redis，无表变更。 患者查询
 * {@code @DataScope(resource = "patient")} 出行（pt_patient 受控表，E07-T02 改写）：H5 会话 = 二维码签发人实时会话
 * （H5UploadTokenFilter 装载），越范围 / 患者已删除按 null 处理（上层按二维码过期口径提示重新生成）。
 */
@Mapper
public interface MobileUploadMapper {

    /** 患者简要（H5 页头脱敏展示）：密文由服务层解密 + 脱敏；已删除 / 不存在 / 越范围返回 null。 */
    @DataScope(resource = "patient")
    @Select(
            """
            SELECT pt.name_cipher AS nameCipher, pt.mrn_cipher AS mrnCipher, w.name AS wardName
            FROM pt_patient pt
            LEFT JOIN ward w ON w.id = pt.ward_id
            WHERE pt.id = #{patientId} AND pt.deleted = 0
            """)
    PatientBriefRow findPatientBrief(@Param("patientId") long patientId);

    record PatientBriefRow(String nameCipher, String mrnCipher, String wardName) {}

    /**
     * 功能开关当前值；无行返回 null（视为关闭——A 默认不要求手机端登录，C 上线时经租户配置打开， AGENTS.md 规则 7：不写 if (tenant ==
     * "C")）；cfg_feature 非受控表。
     */
    @Select("SELECT enabled FROM cfg_feature WHERE feature_code = 'h5_upload_login'")
    Boolean findLoginRequired();
}
