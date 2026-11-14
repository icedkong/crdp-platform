package com.crdp.followup.file.infra.mapper;

import java.time.LocalDateTime;

/**
 * sys_file 行（E10-T01；E10-T02 起文件体只存对象存储，台账不带 body；E10-T03 起图片带缩略图键与页数； E22-T15 起带病毒扫描状态）。 insert 由
 * MyBatis 回填自增 id。
 */
public class SysFileRow {

    private Long id;

    private String objectKey;

    /** 缩略图对象键（仅图片有值，E10-T03；完整键含租户前缀）。 */
    private String thumbnailKey;

    private String originalName;

    private String formatCode;

    private String mimeType;

    private long sizeBytes;

    private String sha256;

    /** 页数（图片恒为 1；PDF 待预览解析能力回填，见 OQ-61）。 */
    private Integer pages;

    private String status;

    /** 病毒扫描状态（E22-T15 / D-01 整改）：UNSCANNED / CLEAN / INFECTED / HASH_MISMATCH（迁移 V139）。 */
    private String virusScanStatus;

    /** 病毒扫描时间（上传同步扫描或补扫作业回填）。 */
    private LocalDateTime virusScannedAt;

    private long uploaderId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getThumbnailKey() {
        return thumbnailKey;
    }

    public void setThumbnailKey(String thumbnailKey) {
        this.thumbnailKey = thumbnailKey;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getFormatCode() {
        return formatCode;
    }

    public void setFormatCode(String formatCode) {
        this.formatCode = formatCode;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public Integer getPages() {
        return pages;
    }

    public void setPages(Integer pages) {
        this.pages = pages;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getVirusScanStatus() {
        return virusScanStatus;
    }

    public void setVirusScanStatus(String virusScanStatus) {
        this.virusScanStatus = virusScanStatus;
    }

    public LocalDateTime getVirusScannedAt() {
        return virusScannedAt;
    }

    public void setVirusScannedAt(LocalDateTime virusScannedAt) {
        this.virusScannedAt = virusScannedAt;
    }

    public long getUploaderId() {
        return uploaderId;
    }

    public void setUploaderId(long uploaderId) {
        this.uploaderId = uploaderId;
    }
}
