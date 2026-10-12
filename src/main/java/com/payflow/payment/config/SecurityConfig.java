package com.payflow.payment.config;

import com.payflow.payment.security.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type =
                org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type
                        .SERVLET)
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ApiKeyAuthenticationService keys,
            SecurityErrorHandler errors,
            @Value("${payflow.mock-providers.enabled:false}") boolean mock,
            org.springframework.core.env.Environment environment)
            throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .formLogin(c -> c.disable())
                .httpBasic(c -> c.disable())
                .logout(c -> c.disable())
                .exceptionHandling(
                        e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(
                        a -> {
                            a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                                    .permitAll();
                            a.requestMatchers(
                                            org.springframework.http.HttpMethod.GET,
                                            "/api/v1/health")
                                    .permitAll();
                            a.requestMatchers(
                                            org.springframework.http.HttpMethod.POST,
                                            "/api/v1/webhooks/providers/provider-a",
                                            "/api/v1/webhooks/providers/provider-b")
                                    .permitAll();
                            a.requestMatchers(
                                            org.springframework.http.HttpMethod.GET,
                                            "/actuator/health",
                                            "/actuator/health/liveness",
                                            "/actuator/health/readiness")
                                    .permitAll();
                            if (environment.acceptsProfiles(
                                    org.springframework.core.env.Profiles.of(
                                            "observability-local", "compose", "kubernetes")))
                                a.requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/actuator/info",
                                                "/actuator/metrics",
                                                "/actuator/metrics/**",
                                                "/actuator/prometheus")
                                        .permitAll();
                            if (mock) a.requestMatchers("/mock/**").permitAll();
                            a.requestMatchers(
                                            "/api/v1/payments",
                                            "/api/v1/payments/**",
                                            "/api/v1/refunds",
                                            "/api/v1/refunds/**")
                                    .hasAuthority("MERCHANT");
                            a.anyRequest().denyAll();
                        })
                .addFilterBefore(
                        new ApiKeyAuthenticationFilter(keys, errors),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
