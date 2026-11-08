package com.vian.gateway.filter;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Menambahkan X-Request-Id ke request dan response.
 *
 * Ini memudahkan tracing: log gateway, log payment service, dan response
 * bisa dikorelasikan dengan id yang sama.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final String HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        String finalRequestId = requestId;
        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .header(HEADER, finalRequestId)
                .build();

        ServerWebExchange mutated = exchange.mutate().request(request).build();

        // Downstream (payment service) juga mengembalikan header ini. Jangan
        // menimpa/menduplikasi kalau sudah ada; kalau request ditolak di
        // gateway (mis. 401), beforeCommit yang menambahkan.
        mutated.getResponse().beforeCommit(() -> {
            if (!mutated.getResponse().getHeaders().containsKey(HEADER)) {
                mutated.getResponse().getHeaders().set(HEADER, finalRequestId);
            }
            return Mono.empty();
        });

        long started = System.nanoTime();
        return chain.filter(mutated).doFinally(signal -> {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            log.info("gateway requestId={} method={} path={} status={} durationMs={}",
                    finalRequestId,
                    request.getMethod(),
                    request.getURI().getPath(),
                    mutated.getResponse().getStatusCode(),
                    elapsedMs);
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
