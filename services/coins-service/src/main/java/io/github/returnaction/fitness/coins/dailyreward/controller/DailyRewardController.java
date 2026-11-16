package io.github.returnaction.fitness.coins.dailyreward.controller;

import io.github.returnaction.fitness.coins.dailyreward.dto.DailyRewardClaimResponse;
import io.github.returnaction.fitness.coins.dailyreward.dto.DailyRewardStatusResponse;
import io.github.returnaction.fitness.coins.dailyreward.service.DailyRewardClaimService;
import io.github.returnaction.fitness.coins.dailyreward.service.DailyRewardQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/coins/daily-reward")
@RequiredArgsConstructor
public class DailyRewardController {

    private final DailyRewardQueryService dailyRewardQueryService;
    private final DailyRewardClaimService dailyRewardClaimService;

    /**
     * Возвращает статус ежедневной награды и текущий баланс одним ответом.
     *
     * <p>Gateway передаёт доверенный Bearer; Coins берёт userId из SecurityContext.
     * Cache-Control: no-store и запрет query string задаёт WalletRequestInterceptor.</p>
     */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DailyRewardStatusResponse> getStatus(@AuthenticationPrincipal UUID userId) {

        return ResponseEntity.ok()
            .body(dailyRewardQueryService.getStatus(userId));
    }

    /**
     * Начисляет награду или возвращает сохранённый результат повтора ключа.
     *
     * <p>Тело не требуется. Idempotency-Key проверяется в сервисе; отсутствие
     * или невалидный формат дают 400 invalid_request.</p>
     */
    @PostMapping(value = "/claim", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DailyRewardClaimResponse> claim(
        @AuthenticationPrincipal UUID userId,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {

        return ResponseEntity.ok()
            .body(dailyRewardClaimService.claim(userId, idempotencyKey));
    }
}
