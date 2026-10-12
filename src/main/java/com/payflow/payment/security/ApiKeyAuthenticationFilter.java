package com.payflow.payment.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    private final ApiKeyAuthenticationService service;
    private final SecurityErrorHandler errors;

    public ApiKeyAuthenticationFilter(
            ApiKeyAuthenticationService service, SecurityErrorHandler errors) {
        this.service = service;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getServletPath();
        return !(p.equals("/api/v1/payments")
                || p.startsWith("/api/v1/payments/")
                || p.equals("/api/v1/refunds")
                || p.startsWith("/api/v1/refunds/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var values = java.util.Collections.list(req.getHeaders("X-API-Key"));
        try {
            if (values.size() != 1)
                throw new BadCredentialsException("Invalid or missing credentials");
            var principal = service.authenticate(values.getFirst());
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(
                            principal, null, List.of(new SimpleGrantedAuthority("MERCHANT"))));
            SecurityContextHolder.setContext(context);
        } catch (org.springframework.security.core.AuthenticationException failure) {
            errors.commence(req, res, failure);
            return;
        } catch (org.springframework.dao.DataAccessException
                | org.springframework.transaction.TransactionException failure) {
            errors.unavailable(req, res);
            return;
        }
        chain.doFilter(req, res);
    }
}
