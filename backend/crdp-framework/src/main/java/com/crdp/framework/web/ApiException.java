package com.crdp.framework.web;

/** 业务异常。httpStatus 给 HTTP，code 给统一响应体；data 可携带结构化提示（如剩余次数、锁定秒数）。 */
public class ApiException extends RuntimeException {

    private final int httpStatus;
    private final int code;
    private final transient Object data;

    public ApiException(int httpStatus, int code, String message) {
        this(httpStatus, code, message, null);
    }

    public ApiException(int httpStatus, int code, String message, Object data) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.data = data;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public int code() {
        return code;
    }

    public Object data() {
        return data;
    }
}
