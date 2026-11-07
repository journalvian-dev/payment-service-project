package com.vian.payments.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.security.MessageDigest;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * API key sederhana untuk service-to-service auth.
 * Di production: kombinasi mTLS / OAuth2 client credentials, rotasi key, dan audit.
 *
 * Sengaja tidak diberi @Component supaya tidak terdaftar dua kali
 * (global servlet filter + Spring Security chain).
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Api-Key";

    private final byte[] expectedApiKey;

    public ApiKeyAuthenticationFilter(String apiKey) {
        this.expectedApiKey = apiKey == null ? new byte[0] : apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);

        if (provided != null && expectedApiKey.length > 0
                && MessageDigest.isEqual(expectedApiKey, provided.getBytes(StandardCharsets.UTF_8))) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "api-client",
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT"))
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Webhook dilindungi HMAC, sandbox hanya untuk demo lokal.
        return path.startsWith("/api/v1/webhooks")
                || path.startsWith("/sandbox")
                || path.startsWith("/actuator");
    }
}
