package com.fu.framework.tenant;

import com.fu.framework.web.ApiException;

public class UnknownTenantDataSourceException extends ApiException {

    public UnknownTenantDataSourceException(String key) {
        super(500, 50002, "未注册的数据源: " + key);
    }
}
