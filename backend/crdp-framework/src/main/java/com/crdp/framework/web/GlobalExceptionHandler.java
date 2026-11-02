package com.crdp.framework.web;

import com.crdp.framework.audit.DeniedOperationLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ObjectProvider<DeniedOperationLogger> deniedLoggers;

    /** 兜底写被拒操作审计（audit.md §1）；ObjectProvider 解耦，缺 bean 的裁剪上下文照常工作。 */
    public GlobalExceptionHandler(ObjectProvider<DeniedOperationLogger> deniedLoggers) {
        this.deniedLoggers = deniedLoggers;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Object>> handleApi(ApiException ex) {
        if (ex.httpStatus() >= 500) {
            log.error("api error {}", ex.code(), ex);
        } else {
            log.warn("api error {}: {}", ex.code(), ex.getMessage());
        }
        deniedLoggers.ifAvailable(logger -> logger.onDenied(ex));
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
        // Security 过滤链的拒绝同样落 DENIED 审计（40300）
        deniedLoggers.ifAvailable(logger -> logger.onDenied(new ApiException(403, 40300, "没有权限")));
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(40300, "没有权限"));
    }

    /** 单请求超出 multipart 兜底上限（E10-T01：spring.servlet.multipart，200 MB）：40001 而非 500。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUpload(MaxUploadSizeExceededException ex) {
        log.warn("上传超过 multipart 兜底上限：{}", ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(40001, "文件过大，超过系统单文件大小上限"));
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
