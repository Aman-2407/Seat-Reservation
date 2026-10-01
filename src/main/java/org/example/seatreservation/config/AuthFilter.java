package org.example.seatreservation.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AuthFilter extends OncePerRequestFilter {

    private final String adminToken;

    public AuthFilter(@Value("${app.admin-token:admin-secret}") String adminToken) {
        this.adminToken = adminToken;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.equals("/error") || (request.getMethod().equals("GET") && path.startsWith("/shows"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) {
            reject(res, 401, "unauthorized", "Missing bearer token");
            return;
        }
        String token = header.substring(7).trim();
        boolean admin = MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), adminToken.getBytes(StandardCharsets.UTF_8));
        if (!admin && !token.startsWith("user-")) {
            reject(res, 401, "unauthorized", "Invalid token");
            return;
        }
        if (req.getMethod().equals("POST") && req.getRequestURI().equals("/shows") && !admin) {
            reject(res, 403, "forbidden", "Admin only");
            return;
        }
        req.setAttribute("userId", admin ? "admin" : token);
        req.setAttribute("isAdmin", admin);
        chain.doFilter(req, res);
    }

    private void reject(HttpServletResponse res, int status, String code, String msg) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + msg + "\"}");
    }
}
