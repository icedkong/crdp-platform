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

    /**
     * 落一行上传台账（状态 PENDING，未关联报告单；文件体在对象存储，不带 body；图片带缩略图键与页数； 病毒扫描状态随 E22-T15：扫描启用通过 = CLEAN，禁用期 =
     * UNSCANNED 待补扫）；回填自增 id。
     */
    @Insert(
            """
            INSERT INTO sys_file
              (object_key, thumbnail_key, original_name, format_code, mime_type, size_bytes,
               sha256, pages, status, virus_scan_status, virus_scanned_at, uploader_id)
            VALUES (#{objectKey}, #{thumbnailKey}, #{originalName}, #{formatCode}, #{mimeType},
                    #{sizeBytes}, #{sha256}, #{pages}, #{status}, #{virusScanStatus},
                    #{virusScannedAt}, #{uploaderId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(SysFileRow row);

    /** 按 id 取行（应用层判定归属与状态；文件体经对象键从对象存储取，随 E10-T04/T05 下载链路）。 */
    @Select(
            """
            SELECT id, object_key AS objectKey, thumbnail_key AS thumbnailKey,
                   original_name AS originalName, format_code AS formatCode,
                   mime_type AS mimeType, size_bytes AS sizeBytes,
                   sha256, pages, status, virus_scan_status AS virusScanStatus,
                   virus_scanned_at AS virusScannedAt, uploader_id AS uploaderId
            FROM sys_file WHERE id = #{id}
            """)
    SysFileRow find(@org.apache.ibatis.annotations.Param("id") long id);

    /** 按 SHA-256 数同哈希文件（C-SCAN-017 重复提示的口径；只统计未删除行）。 */
    @Select("SELECT COUNT(*) FROM sys_file WHERE sha256 = #{sha256} AND status <> 'DELETED'")
    int countBySha256(@org.apache.ibatis.annotations.Param("sha256") String sha256);

    /** 存储用量（E05-T09 / PLT-TEN-014）：未删除行的 size_bytes 合计；空表返回 0。 上传前的配额校验（达到上限禁止上传）按本口径。 */
    @Select("SELECT COALESCE(SUM(size_bytes), 0) FROM sys_file WHERE status <> 'DELETED'")
    long sumActiveBytes();

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

    /**
     * 补扫批次（E22-T15 / D-01）：未删除且未扫描的行按 id 升序取一批（{@code LIMIT} 上限由补扫作业按
     * crdp.file.virus-scan.retro-batch-limit 传入）；只取补扫需要的列。
     */
    @Select(
            """
            SELECT id, object_key AS objectKey, original_name AS originalName,
                   size_bytes AS sizeBytes, sha256
            FROM sys_file
            WHERE virus_scan_status = 'UNSCANNED' AND status <> 'DELETED'
            ORDER BY id
            LIMIT #{limit}
            """)
    java.util.List<SysFileRow> findUnscanned(
            @org.apache.ibatis.annotations.Param("limit") int limit);

    /**
     * 回填补扫结论（E22-T15）：CLEAN / INFECTED / HASH_MISMATCH。守卫 {@code virus_scan_status =
     * 'UNSCANNED'}——只前进不回退，重复执行幂等（与上传同步扫描互不覆盖：同步扫描在 INSERT 时已定型）。
     */
    @Update(
            """
            UPDATE sys_file
            SET virus_scan_status = #{status}, virus_scanned_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND virus_scan_status = 'UNSCANNED'
            """)
    int markVirusScanResult(
            @org.apache.ibatis.annotations.Param("id") long id,
            @org.apache.ibatis.annotations.Param("status") String status);
}
