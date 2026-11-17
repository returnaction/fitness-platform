package io.github.returnaction.fitness.coins.dailyreward.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "coins.daily-reward")
public record DailyRewardProperties(long amount) {

    public DailyRewardProperties {
        if (amount <= 0) {
            throw new IllegalArgumentException("coins.daily-reward.amount must be positive");
        }
    }
}
