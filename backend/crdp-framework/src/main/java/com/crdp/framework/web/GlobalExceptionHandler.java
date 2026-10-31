package com.crdp.framework.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Object>> handleApi(ApiException ex) {
        if (ex.httpStatus() >= 500) {
            log.error("api error {}", ex.code(), ex);
        } else {
            log.warn("api error {}: {}", ex.code(), ex.getMessage());
        }
        return ResponseEntity.status(ex.httpStatus())
                .body(ApiResponse.fail(ex.code(), ex.getMessage(), ex.data()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(this::fieldMessage)
                        .reduce((a, b) -> a + "；" + b)
                        .orElse("请求参数不正确");
        return ResponseEntity.badRequest().body(ApiResponse.fail(40001, message));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(40300, "没有权限"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex) {
        log.error("unhandled", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(50000, "系统繁忙，请稍后再试"));
    }

    private String fieldMessage(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }
}
