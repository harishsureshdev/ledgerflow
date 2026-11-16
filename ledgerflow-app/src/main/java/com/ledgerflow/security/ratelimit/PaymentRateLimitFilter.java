package com.ledgerflow.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/**
 * Rate limits payment creation (POST /api/v1/payments) per authenticated user.
 *
 * <p>Keyed by the JWT subject, never by a client-supplied header such as X-Merchant-Id, which
 * a caller could vary to dodge the limit. Runs inside the security chain after authentication;
 * unauthenticated requests pass through and are rejected by authorization as before.
 *
 * <p>Not a Spring bean on purpose: as a bean, Boot would also register it as a global servlet
 * filter that runs before authentication.
 */
public class PaymentRateLimitFilter extends OncePerRequestFilter {

    private final RedisTokenBucketRateLimiter limiter;
    private final ObjectMapper objectMapper;

    public PaymentRateLimitFilter(RedisTokenBucketRateLimiter limiter, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !("POST".equals(request.getMethod()) && (path.equals("/api/v1/payments") || path.equals("/api/v1/payments/")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            chain.doFilter(request, response);
            return;
        }

        RedisTokenBucketRateLimiter.Decision d = limiter.tryConsume(auth.getName());
        response.setHeader("X-RateLimit-Limit", String.valueOf(limiter.capacity()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(d.remaining()));

        if (d.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Payment rate limit exceeded. Retry after " + d.retryAfterSeconds() + " seconds.");
        problem.setType(URI.create("https://ledgerflow.io/errors/rate-limited"));
        problem.setTitle("Too Many Requests");
        problem.setProperty("timestamp", Instant.now().toString());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(d.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
