package io.github.returnaction.fitness.exercisecatalog.shared.web.configuration;

import io.github.returnaction.fitness.exercisecatalog.shared.web.interceptor.NoQueryParametersInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CatalogWebConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new NoQueryParametersInterceptor())
            .order(0)
            .addPathPatterns("/api/v1/exercise-catalog");
    }
}
