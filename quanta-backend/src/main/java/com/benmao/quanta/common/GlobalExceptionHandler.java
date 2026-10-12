package com.benmao.quanta.common;

import com.benmao.quanta.common.exception.BizException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：所有异常统一收敛为 {@link Result}，HTTP 状态码与业务错误码分离。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：沿用异常自身的错误码与 HTTP 状态码。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<?>> handleBiz(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus()).body(Result.fail(e.getCode(), e.getMessage()));
    }

    /** 参数校验失败（@RequestBody 上的 @Valid）。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<?>> handleBind(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return badRequest(detail.isEmpty() ? ResultCode.PARAM_ERROR.message() : detail);
    }

    /** 参数校验失败（方法参数上的 @Validated）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<?>> handleConstraint(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return badRequest(detail.isEmpty() ? ResultCode.PARAM_ERROR.message() : detail);
    }

    /** 非法参数（代码里主动抛的 IllegalArgumentException）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return badRequest(e.getMessage());
    }

    /** 请求体无法解析（JSON 格式错误等）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<?>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return badRequest("请求体格式错误");
    }

    /** 请求方法不支持。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<?>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}", e.getMessage());
        return ResponseEntity.status(405)
                .body(Result.fail(ResultCode.METHOD_NOT_ALLOWED.code(), ResultCode.METHOD_NOT_ALLOWED.message()));
    }

    /** 静态资源不存在。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<?>> handleNoResource(NoResourceFoundException e) {
        log.warn("资源不存在: {}", e.getMessage());
        return ResponseEntity.status(404)
                .body(Result.fail(ResultCode.NOT_FOUND.code(), ResultCode.NOT_FOUND.message()));
    }

    /** 兜底：未预期异常。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> handleOther(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(500)
                .body(Result.fail(ResultCode.SYSTEM_ERROR.code(), ResultCode.SYSTEM_ERROR.message()));
    }

    private ResponseEntity<Result<?>> badRequest(String message) {
        return ResponseEntity.badRequest()
                .body(Result.fail(ResultCode.PARAM_ERROR.code(), message));
    }

    private String formatFieldError(FieldError fe) {
        return fe.getField() + ": " + fe.getDefaultMessage();
    }
}