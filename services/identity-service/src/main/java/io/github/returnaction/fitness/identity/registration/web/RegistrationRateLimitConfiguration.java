package io.github.returnaction.fitness.identity.registration.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RegistrationRateLimitConfiguration implements WebMvcConfigurer {
    private final RegistrationRateLimiter limiter;
    private final RegistrationClientAddress clientAddress;

    public RegistrationRateLimitConfiguration(
            RegistrationRateLimiter limiter, RegistrationClientAddress clientAddress) {
        this.limiter = limiter;
        this.clientAddress = clientAddress;
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
    }
}
