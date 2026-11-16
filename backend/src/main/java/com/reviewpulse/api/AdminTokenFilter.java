package com.reviewpulse.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.reviewpulse.config.ReviewPulseProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Guards /api/admin/** with a shared token (header X-Admin-Token), compared in constant time.
 * Fails closed: with no token configured, admin endpoints answer 503 instead of being open.
 *
 * <p>This is a local-demo control, not user authentication. See docs/security.md for the trust boundary.
 */
public class AdminTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Admin-Token";
    private static final Logger log = LoggerFactory.getLogger(AdminTokenFilter.class);

    private final ReviewPulseProperties.Admin admin;

    public AdminTokenFilter(ReviewPulseProperties.Admin admin) {
        this.admin = admin;
        if (!admin.enabled()) {
            log.warn("REVIEWPULSE_ADMIN_TOKEN is not set: /api/admin/** endpoints are disabled");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!admin.enabled()) {
            reject(response, HttpStatus.SERVICE_UNAVAILABLE, "ADMIN_DISABLED", "admin endpoints are disabled: no admin token configured");
            return;
        }
        String supplied = request.getHeader(HEADER);
        boolean ok = supplied != null && MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8), admin.token().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            log.warn("rejected admin request {} {} from {}", request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            reject(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "missing or invalid " + HEADER + " header");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"success\":false,\"data\":null,\"error\":{\"code\":\"" + code
                + "\",\"message\":\"" + message + "\",\"details\":[]},\"meta\":{}}");
    }
}
