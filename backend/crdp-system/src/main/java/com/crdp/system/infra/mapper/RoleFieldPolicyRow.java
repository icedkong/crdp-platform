package com.crdp.system.infra.mapper;

/** 某角色对一个敏感字段的显式策略覆盖行（sys_role_field_policy，E07-T04 登录装配取数）。 */
public class RoleFieldPolicyRow {

    private String resource;
    private String field;
    private String policy;

    public String getResource() {
        return resource;
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }
}
