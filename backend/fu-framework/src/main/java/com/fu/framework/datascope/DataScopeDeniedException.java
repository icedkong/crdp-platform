package com.fu.framework.datascope;

import com.fu.framework.web.ApiException;

public class DataScopeDeniedException extends ApiException {

    public DataScopeDeniedException() {
        super(403, 40302, "查询受控数据但未声明数据范围");
    }
}
