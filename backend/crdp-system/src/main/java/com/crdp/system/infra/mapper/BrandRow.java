package com.crdp.system.infra.mapper;

import java.time.LocalDateTime;

/** cfg_brand 配置行（E06-T11）。displayName 之外的字段可为 null（未配置 = 用平台默认）。 */
public class BrandRow {

    private String displayName;
    private String logoUrl;
    private String loginBgUrl;
    private String themeColor;
    private String copyright;
    private Long updatedBy;
    private LocalDateTime updatedAt;

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public String getLoginBgUrl() {
        return loginBgUrl;
    }

    public void setLoginBgUrl(String loginBgUrl) {
        this.loginBgUrl = loginBgUrl;
    }

    public String getThemeColor() {
        return themeColor;
    }

    public void setThemeColor(String themeColor) {
        this.themeColor = themeColor;
    }

    public String getCopyright() {
        return copyright;
    }

    public void setCopyright(String copyright) {
        this.copyright = copyright;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
