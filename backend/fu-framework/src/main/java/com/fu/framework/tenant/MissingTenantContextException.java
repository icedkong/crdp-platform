package com.fu.framework.tenant;

import com.fu.framework.web.ApiException;

public class MissingTenantContextException extends ApiException {

    public MissingTenantContextException() {
        super(500, 50001, "缺少租户上下文，已拒绝访问数据库");
    }
}
