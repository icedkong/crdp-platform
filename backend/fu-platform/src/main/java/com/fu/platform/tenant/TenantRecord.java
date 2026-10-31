package com.fu.platform.tenant;

public record TenantRecord(long id, String code, String name, String status) {

    public boolean routable() {
        return "ENABLED".equals(status) || "TRIAL".equals(status);
    }
}
