package io.github.returnaction.fitness.accounts.shared.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration
public class JacksonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictScalarCoercion() {
        return builder -> {
            builder.withCoercionConfig(LogicalType.Textual, config -> {
                config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            });
            builder.withCoercionConfig(LogicalType.Integer,
                    config -> config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail));
            builder.withCoercionConfig(LogicalType.Enum,
                    config -> config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail));
        };
    }
}
