package io.github.returnaction.fitness.identity.registration.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RegistrationRateLimitConfiguration implements WebMvcConfigurer {
    private final RegistrationRateLimiter limiter;
    private final RegistrationClientAddress clientAddress;
    private final RegistrationRateLimiter loginLimiter;
    private final RegistrationRateLimiter refreshLimiter;

    public RegistrationRateLimitConfiguration(
            RegistrationRateLimiter limiter,
            RegistrationClientAddress clientAddress,
            @Value("${identity.registration.login-rate-capacity}") long loginCapacity,
            @Value("${identity.registration.refresh-rate-capacity}") long refreshCapacity,
            @Value("${identity.registration.rate-limit.period-seconds}") long period,
            @Value("${identity.registration.rate-limit.cache-size}") long cacheSize,
            @Value("${identity.registration.rate-limit.cache-expiry-seconds}") long expiry) {
        this.limiter = limiter;
        this.clientAddress = clientAddress;
        this.loginLimiter = new RegistrationRateLimiter(loginCapacity, period, cacheSize, expiry);
        this.refreshLimiter = new RegistrationRateLimiter(refreshCapacity, period, cacheSize, expiry);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        if ("POST".equals(request.getMethod())) {
                            limiter.check(clientAddress.resolve(request));
                        }
                        return true;
                    }
                })
                .addPathPatterns("/api/v1/auth/register");
        registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        if ("POST".equals(request.getMethod())) loginLimiter.check(clientAddress.resolve(request));
                        return true;
                    }
                })
                .addPathPatterns("/api/v1/auth/login");
        registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        if ("POST".equals(request.getMethod())) refreshLimiter.check(clientAddress.resolve(request));
                        return true;
                    }
                })
                .addPathPatterns("/api/v1/auth/refresh");
    }
}
