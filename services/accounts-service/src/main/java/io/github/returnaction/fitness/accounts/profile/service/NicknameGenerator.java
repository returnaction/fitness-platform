package io.github.returnaction.fitness.accounts.profile.service;

import java.security.SecureRandom;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class NicknameGenerator {
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        return "Player_" + String.format(Locale.ROOT, "%012d", random.nextLong(1_000_000_000_000L));
    }
}
