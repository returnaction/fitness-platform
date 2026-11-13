package io.github.returnaction.fitness.accounts.profile.exception;

public class NicknameAlreadyTakenException extends RuntimeException {

    public NicknameAlreadyTakenException(Throwable cause) {
        super("Nickname is already taken", cause);
    }
}
