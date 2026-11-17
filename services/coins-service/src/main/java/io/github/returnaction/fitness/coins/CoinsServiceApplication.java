package io.github.returnaction.fitness.coins;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class CoinsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoinsServiceApplication.class, args);
    }
}
