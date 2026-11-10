package io.github.returnaction.fitness.identity.session.service;

import io.github.returnaction.fitness.identity.session.dto.RefreshResponse;
import io.github.returnaction.fitness.identity.session.exception.AuthenticationFailureException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static io.github.returnaction.fitness.identity.session.exception.AuthenticationFailureException.Reason.INVALID_REFRESH;
import static io.github.returnaction.fitness.identity.session.exception.AuthenticationFailureException.Reason.REFRESH_ACCOUNT_UNAVAILABLE;

/** Преобразует результат после завершения транзакции ротации refresh. */
@Service
@RequiredArgsConstructor
public class RefreshSessionService {

    private final RefreshTokenService tokenService;

    public RefreshResponse refresh(String token) {
        var result = tokenService.refresh(token);
        if (result.code() != null) {
            throw new AuthenticationFailureException("account_unavailable".equals(result.code())
                    ? REFRESH_ACCOUNT_UNAVAILABLE
                    : INVALID_REFRESH);
        }
        return new RefreshResponse(result.access(), result.refresh(), "Bearer",
                (int) Math.min(900, result.remaining()), result.remaining());
    }
}
