package io.github.returnaction.fitness.accounts.profile.exception;

public class ProfileVersionConflictException extends RuntimeException {

    public ProfileVersionConflictException() {
        super("Profile version conflict");
    }
}
