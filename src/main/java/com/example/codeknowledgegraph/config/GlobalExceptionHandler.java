package com.example.codeknowledgegraph.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 全局异常捕捉:统一日志输出 + 统一 JSON 错误格式 {"error": "..."}
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数/业务校验错误 -> 400,只打 warn,不刷堆栈 */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> badRequest(HttpServletRequest req, Exception e) {
        log.warn("{} {}: {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /** 资源不存在 -> 404 */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(HttpServletRequest req, NoSuchElementException e) {
        log.warn("{} {}: {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    /** 未预期的异常 -> 500,完整堆栈打日志,前端只收简要信息 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internalError(HttpServletRequest req, Exception e) {
        log.error("{} {} 处理失败", req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "服务器内部错误: " + e.getMessage()));
    }
}
