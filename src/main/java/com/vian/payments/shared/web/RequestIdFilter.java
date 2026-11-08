package com.vian.payments.shared.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Meneruskan X-Request-Id dari API Gateway ke log service.
 *
 * Kalau request datang langsung tanpa header, filter membuat id baru. Response
 * juga diberi header yang sama supaya client bisa korelasikan log.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put("requestId", requestId);
        response.setHeader(HEADER, requestId);
        try {
            log.info("requestId={} method={} path={}", requestId, request.getMethod(), request.getRequestURI());
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}
