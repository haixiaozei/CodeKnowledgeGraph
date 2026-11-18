package com.example.codeknowledgegraph.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP 请求访问日志:每次请求打一行 INFO 摘要(方法 路径 状态码 耗时)
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            String uri = request.getRequestURI();
            // 静态资源(页面自身)不打,只记录接口调用
            if (!uri.startsWith("/api")) {
                return;
            }
            long cost = System.currentTimeMillis() - start;
            log.info("{} {} -> {} ({}ms)", request.getMethod(), uri,
                    response.getStatus(), cost);
        }
    }
}
