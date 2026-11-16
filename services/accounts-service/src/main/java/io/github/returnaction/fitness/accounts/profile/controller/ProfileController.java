package io.github.returnaction.fitness.accounts.profile.controller;

import io.github.returnaction.fitness.accounts.profile.dto.ProfileResponse;
import io.github.returnaction.fitness.accounts.profile.dto.UpdateProfileRequest;
import io.github.returnaction.fitness.accounts.profile.service.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/me")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    /**
     * Возвращает профиль текущего пользователя.
     *
     * userId берётся из SecurityContext, заполненного фильтром аутентификации.
     * Сервис читает профиль из БД, а контроллер возвращает его в JSON с HTTP 200.
     * Заголовок Cache-Control: no-store устанавливает ProfileRequestInterceptor.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProfileResponse> getOwnProfile(@AuthenticationPrincipal UUID userId) {

        return ResponseEntity.ok()
            .body(profileService.getOwnProfile(userId));
    }

    /**
     * Обновляет профиль текущего пользователя данными из тела запроса.
     *
     * userId берётся из SecurityContext, заполненного фильтром аутентификации.
     * Сервис проверяет данные и сохраняет изменения в БД.
     * Контроллер возвращает обновлённый профиль в JSON с HTTP 200.
     */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProfileResponse> updateOwnProfile(@AuthenticationPrincipal UUID userId,
                                                            @RequestBody UpdateProfileRequest request) {

        return ResponseEntity.ok()
            .body(profileService.updateOwnProfile(userId, request));
    }
}
