package com.crdp.system.infra.mapper;

/** 负责人候选（E06-T02 暂行接口；PLT-ORG-012 的共用用户选择器落地于 E06-T09 后替换）。 */
public class UserOptionRow {

    private Long id;
    private String username;
    private String displayName;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
