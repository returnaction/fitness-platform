package io.github.returnaction.fitness.identity.registration.controller;

import io.github.returnaction.fitness.identity.registration.dto.RegisterResponse;
import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import io.github.returnaction.fitness.identity.registration.service.RegistrationFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationFacade registrationFacade;

    /**
     * Регистрирует пользователя и возвращает данные аккаунта и первой сессии.
     *
     * <p>
     * Передаёт ключ идемпотентности и данные пользователя в фасад регистрации.
     * После завершения подготовки аккаунта ответ содержит пользователя со статусом
     * {@code ACTIVE}, access-токен и refresh-токен.
     *
     * @param idempotencyKey
     *            ключ идемпотентности запроса в формате UUID версии 4
     * @param request
     *            адрес электронной почты и пароль нового пользователя
     * @return ответ со статусом {@code 201 Created}, данными пользователя и
     *         токенами; ответ помечен заголовком {@code Cache-Control: no-store}
     */
    @PostMapping(value = "/register", produces = "application/json")
    ResponseEntity<RegisterResponse> register(@RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody RegisterUserRequest request) {

        RegisterResponse response = registrationFacade.register(idempotencyKey, request);

        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store").body(response);
    }
}
