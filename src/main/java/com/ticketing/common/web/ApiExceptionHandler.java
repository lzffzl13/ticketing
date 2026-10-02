package com.ticketing.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(BusinessException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(ApiResponse.failure(exception.getCode(), exception.getMessage(), null));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        var issues = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ValidationIssue(error.getField(), error.getDefaultMessage()))
                .toList();
        return new ResponseEntity<>(ApiResponse.failure("INVALID_REQUEST", "参数校验失败", issues),
                headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("Framework request failure", exception);
            return new ResponseEntity<>(ApiResponse.failure("INTERNAL_ERROR", "服务暂时不可用，请稍后重试", null),
                    headers, status);
        }
        String code = status.value() == 404 ? "NOT_FOUND" : "INVALID_REQUEST";
        String message = status.value() == 404 ? "资源不存在" : "请求格式或方法不符合接口要求";
        return new ResponseEntity<>(ApiResponse.failure(code, message, null), headers, status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception exception) {
        log.error("Unhandled request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure("INTERNAL_ERROR", "服务暂时不可用，请稍后重试", null));
    }

    public record ValidationIssue(String field, String message) {
    }
}
