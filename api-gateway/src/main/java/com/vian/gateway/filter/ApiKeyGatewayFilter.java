package com.vian.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Validasi API key di edge.
 *
 * Payment service tetap memvalidasi API key sendiri (defense in depth).
 * Gateway hanya menolak request yang jelas tidak membawa key, supaya traffic
 * sampah tidak sampai ke service.
 */
@Component
public class ApiKeyGatewayFilter implements GlobalFilter, Ordered {

    private static final String API_KEY_HEADER = "X-Api-Key";

    private final String expectedApiKey;
    private final ObjectMapper objectMapper;

    public ApiKeyGatewayFilter(@org.springframework.beans.factory.annotation.Value("${app.security.api-key}") String expectedApiKey,
                               ObjectMapper objectMapper) {
        this.expectedApiKey = expectedApiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (isProtected(path)) {
            String provided = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
            if (!expectedApiKey.equals(provided)) {
                return unauthorized(exchange);
            }
        }

        return chain.filter(exchange);
    }

    private boolean isProtected(String path) {
        return path.startsWith("/api/v1/payments")
                && !path.startsWith("/api/v1/webhooks/");
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "type", "about:blank",
                    "title", "Unauthorized",
                    "status", 401,
                    "detail", "Header X-Api-Key tidak ada atau tidak valid"
            ));
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(body))
            );
        } catch (JsonProcessingException e) {
            byte[] fallback = "{\"title\":\"Unauthorized\",\"status\":401}"
                    .getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(fallback))
            );
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
