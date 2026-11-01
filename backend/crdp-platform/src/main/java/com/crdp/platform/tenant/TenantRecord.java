package com.crdp.platform.tenant;

/**
 * 登录闸门看到的租户。{@link #routable()} 是「可用」口径（平台规格第 7 章医院端登录流程）：试用 / 启用可登录， 宽限期允许登录并提示续费
 * （PLT-TEN-012，E05-T03 放开），开通中、停用、注销中、已注销拒绝。 {@code graceReadOnly}
 * 是宽限期是否只读（PLT-TEN-011），供宽限只读拦截与续费提示用。
 */
public record TenantRecord(
        long id, String code, String name, String status, boolean graceReadOnly) {

    public boolean routable() {
        return "ENABLED".equals(status) || "TRIAL".equals(status) || "GRACE".equals(status);
    }
}
