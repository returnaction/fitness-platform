package io.github.returnaction.fitness.exercisecatalog.shared.web.interceptor;

import io.github.returnaction.fitness.exercisecatalog.shared.web.error.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

public class NoQueryParametersInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        if(request.getQueryString() != null){
            throw new InvalidRequestException();
        }
        return true;
    }
}
