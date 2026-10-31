package com.fu.framework.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 统一响应。成功码为 0，便于前端按 Vben 后端模式判断。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data, TraceIds.current());
    }

    public static ApiResponse<Void> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, TraceIds.current());
    }
}
