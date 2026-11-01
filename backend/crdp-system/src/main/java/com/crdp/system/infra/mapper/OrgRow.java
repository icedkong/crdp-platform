package com.crdp.system.infra.mapper;

/** 租户库 sys_org 行（院区）。字段见 docs/requirements/platform-spec.md「关键字段 · 院区与科室」。 */
public class OrgRow {

    private Long id;
    private String code;
    private String name;
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
