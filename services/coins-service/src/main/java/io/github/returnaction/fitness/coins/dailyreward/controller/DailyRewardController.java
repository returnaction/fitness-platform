package io.github.returnaction.fitness.coins.dailyreward.controller;

import io.github.returnaction.fitness.coins.dailyreward.dto.DailyRewardClaimResponse;
import io.github.returnaction.fitness.coins.dailyreward.dto.DailyRewardStatusResponse;
import io.github.returnaction.fitness.coins.dailyreward.service.DailyRewardClaimService;
import io.github.returnaction.fitness.coins.dailyreward.service.DailyRewardQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/coins/daily-reward")
@RequiredArgsConstructor
public class DailyRewardController {

    private final DailyRewardQueryService dailyRewardQueryService;
    private final DailyRewardClaimService dailyRewardClaimService;

    /**
     * Возвращает статус ежедневной награды и текущий баланс одним ответом.
     *
     * <p>Coins берёт userId из проверенного контекста аутентификации.
     * Cache-Control: no-store задаёт NoStoreResponseInterceptor,
     * а запрет query string — NoQueryParametersInterceptor.</p>
     */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DailyRewardStatusResponse> getStatus(@AuthenticationPrincipal UUID userId) {

        return ResponseEntity.ok()
            .body(dailyRewardQueryService.getStatus(userId));
    }

    /**
     * Начисляет подарок, сообщает о недоступности до истечения 24 часов
     * или возвращает сохранённый результат повтора ключа.
     * Ответ содержит начисленную сумму, оба баланса и время следующего получения.
     *
     * <p>Тело не требуется. Spring MVC проверяет наличие Idempotency-Key
     * и преобразует его в UUID; отсутствие или ошибка преобразования дают 400 invalid_request.
     * Повтор ключа возвращает исходный снимок ответа, включая исторический баланс.</p>
     */
    @PostMapping(value = "/claims", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DailyRewardClaimResponse> claim(
        @AuthenticationPrincipal UUID userId,
        @RequestHeader(name = "Idempotency-Key") UUID idempotencyKey) {

        return ResponseEntity.ok()
            .body(dailyRewardClaimService.claim(userId, idempotencyKey));
    }
}
