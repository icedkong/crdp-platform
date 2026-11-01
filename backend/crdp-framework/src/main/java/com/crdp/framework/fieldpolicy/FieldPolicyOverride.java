package com.crdp.framework.fieldpolicy;

/** 某角色对一个敏感字段的显式策略覆盖（租户库 {@code sys_role_field_policy} 一行，resource + field 定位）。 */
public record FieldPolicyOverride(String resource, String field, String policy) {

    public String key() {
        return resource + "." + field;
    }
}
