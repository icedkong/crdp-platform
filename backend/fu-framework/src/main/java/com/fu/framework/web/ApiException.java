package com.fu.framework.web;

/** 业务异常。httpStatus 给 HTTP，code 给统一响应体。 */
public class ApiException extends RuntimeException {

    private final int httpStatus;
    private final int code;

    public ApiException(int httpStatus, int code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public int code() {
        return code;
    }
}
