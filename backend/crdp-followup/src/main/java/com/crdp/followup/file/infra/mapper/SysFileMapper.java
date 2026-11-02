package com.crdp.followup.file.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

/**
 * 文件上传台账表 sys_file（E10-T01 / 租户库 followup V25）。调用前必须已在租户上下文。 sys_file 不在
 * ControlledTables：上传时尚未关联患者，按 uploader_id 归属；关联报告单后的数据范围 校验随 E16 报告单子域（ rpt_report_file
 * 挂在患者数据范围下）。
 */
@Mapper
public interface SysFileMapper {

    /** 落一行上传台账（状态 PENDING，未关联报告单）；回填自增 id。 */
    @Insert(
            """
            INSERT INTO sys_file
              (object_key, original_name, format_code, mime_type, size_bytes, sha256,
               status, body, uploader_id)
            VALUES (#{objectKey}, #{originalName}, #{formatCode}, #{mimeType}, #{sizeBytes},
                    #{sha256}, #{status}, #{body}, #{uploaderId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(SysFileRow row);

    /** 按 id 取行（应用层判定归属与状态）。 */
    @Select(
            """
            SELECT id, object_key AS objectKey, original_name AS originalName,
                   format_code AS formatCode, mime_type AS mimeType, size_bytes AS sizeBytes,
                   sha256, status, body, uploader_id AS uploaderId
            FROM sys_file WHERE id = #{id}
            """)
    SysFileRow find(@org.apache.ibatis.annotations.Param("id") long id);

    /** 按 SHA-256 数同哈希文件（C-SCAN-017 重复提示的口径；只统计未删除行）。 */
    @Select("SELECT COUNT(*) FROM sys_file WHERE sha256 = #{sha256} AND status <> 'DELETED'")
    int countBySha256(@org.apache.ibatis.annotations.Param("sha256") String sha256);
}
