package com.payflow.payment.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.exception.ApiError;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper mapper;

    public SecurityErrorHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(
            HttpServletRequest req,
            HttpServletResponse res,
            org.springframework.security.core.AuthenticationException e)
            throws IOException {
        write(req, res, 401, "Unauthorized", "Invalid or missing credentials");
    }

    @Override
    public void handle(
            HttpServletRequest req,
            HttpServletResponse res,
            org.springframework.security.access.AccessDeniedException e)
            throws IOException {
        write(req, res, 403, "Forbidden", "Access denied");
    }

    public void unavailable(HttpServletRequest req, HttpServletResponse res) throws IOException {
        write(req, res, 503, "Service Unavailable", "Authentication temporarily unavailable");
    }

    private void write(
            HttpServletRequest req,
            HttpServletResponse res,
            int status,
            String error,
            String message)
            throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        mapper.writeValue(
                res.getOutputStream(),
                new ApiError(Instant.now(), status, error, message, req.getRequestURI()));
    }
}
