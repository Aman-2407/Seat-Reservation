package org.example.seatreservation.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("http");

    // health probes and metrics scrapes would drown out the real traffic in the logs
    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return req.getRequestURI().startsWith("/actuator");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
//Reuse the caller's id if they sent a sane one, so a trace can span services.
        // Otherwise make one. The pattern check stops someone from injecting junk into our logs.
        String id = request.getHeader("X-Request-Id");
        if (id == null || id.length() > 64 || !id.matches("[A-Za-z0-9._-]+")) {
            id = UUID.randomUUID().toString();
        }
        MDC.put("requestId", id);          // every log line in this request now carries it
        response.setHeader("X-Request-Id", id); // handy for the client and for the evaluators

        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            // userId is set by AuthFilter, which runs later, so read it after the chain finished
            Object user = request.getAttribute("userId");
            log.atInfo()
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("durationMs", ms)
                    .addKeyValue("userId", user == null ? "-" : user.toString())
                    .log("request completed");
            MDC.remove("requestId");       // threads are reused, so clean up
        }
    }
    }
