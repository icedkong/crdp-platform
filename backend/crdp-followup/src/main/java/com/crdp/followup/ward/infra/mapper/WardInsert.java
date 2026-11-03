package com.crdp.followup.ward.infra.mapper;

/** 病区新建行（useGeneratedKeys 回写 id）。 */
public class WardInsert {

    private long id;
    private String code;
    private String name;
    private String wardType;
    private String alias;
    private Long defaultOwnerId;
    private int sortNo;

    public long getId() {
        return id;
    }

    public void setId(long id) {
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

    public String getWardType() {
        return wardType;
    }

    public void setWardType(String wardType) {
        this.wardType = wardType;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public Long getDefaultOwnerId() {
        return defaultOwnerId;
    }

    public void setDefaultOwnerId(Long defaultOwnerId) {
        this.defaultOwnerId = defaultOwnerId;
    }

    public int getSortNo() {
        return sortNo;
    }

    public void setSortNo(int sortNo) {
        this.sortNo = sortNo;
    }
}
