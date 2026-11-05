package com.crdp.followup.file.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 文件上传台账表 sys_file（E10-T01 / 租户库 followup V25）。调用前必须已在租户上下文。 sys_file 不在
 * ControlledTables：上传时尚未关联患者，按 uploader_id 归属；关联报告单后的数据范围 校验随 E16 报告单子域（ rpt_report_file
 * 挂在患者数据范围下）。
 */
@Mapper
public interface SysFileMapper {

    /** 落一行上传台账（状态 PENDING，未关联报告单；文件体在对象存储，不带 body；图片带缩略图键与页数）；回填自增 id。 */
    @Insert(
            """
            INSERT INTO sys_file
              (object_key, thumbnail_key, original_name, format_code, mime_type, size_bytes,
               sha256, pages, status, uploader_id)
            VALUES (#{objectKey}, #{thumbnailKey}, #{originalName}, #{formatCode}, #{mimeType},
                    #{sizeBytes}, #{sha256}, #{pages}, #{status}, #{uploaderId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(SysFileRow row);

    /** 按 id 取行（应用层判定归属与状态；文件体经对象键从对象存储取，随 E10-T04/T05 下载链路）。 */
    @Select(
            """
            SELECT id, object_key AS objectKey, thumbnail_key AS thumbnailKey,
                   original_name AS originalName, format_code AS formatCode,
                   mime_type AS mimeType, size_bytes AS sizeBytes,
                   sha256, pages, status, uploader_id AS uploaderId
            FROM sys_file WHERE id = #{id}
            """)
    SysFileRow find(@org.apache.ibatis.annotations.Param("id") long id);

    /** 按 SHA-256 数同哈希文件（C-SCAN-017 重复提示的口径；只统计未删除行）。 */
    @Select("SELECT COUNT(*) FROM sys_file WHERE sha256 = #{sha256} AND status <> 'DELETED'")
    int countBySha256(@org.apache.ibatis.annotations.Param("sha256") String sha256);

    /**
     * 关联沟通记录录音（E15-T04 / A-FU-025）：PENDING → ATTACHED（守卫更新，落空 0 = 已关联他处或已删除， 调用方按 40001
     * 转译，防止同一文件挂两条沟通记录；报告单关联随 E16 复用同一状态机）。
     */
    @Update("UPDATE sys_file SET status = 'ATTACHED' WHERE id = #{id} AND status = 'PENDING'")
    int markAttached(@org.apache.ibatis.annotations.Param("id") long id);

    /**
     * 删除沟通记录连带软删录音（E15-T04；A-ATT-017「在任一处删除即两处同时删除」）：非 DELETED → DELETED。对象本体清理策略随 E10-T05 /
     * E16（当前软删即可，预览与下载都会按 DELETED 拒绝）。
     */
    @Update("UPDATE sys_file SET status = 'DELETED' WHERE id = #{id} AND status <> 'DELETED'")
    int markDeleted(@org.apache.ibatis.annotations.Param("id") long id);
}
