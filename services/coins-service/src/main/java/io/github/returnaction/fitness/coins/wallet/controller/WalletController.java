package io.github.returnaction.fitness.coins.wallet.controller;

import io.github.returnaction.fitness.coins.wallet.dto.WalletBalanceResponse;
import io.github.returnaction.fitness.coins.wallet.service.WalletQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/coins/me")
@RequiredArgsConstructor
public class WalletController {

    private final WalletQueryService walletQueryService;

    /**
     * Возвращает доступный и заблокированный баланс текущего пользователя.
     *
     * userId берётся из SecurityContext, заполненного фильтром аутентификации.
     * Сервис читает кошелёк из БД, а контроллер возвращает баланс в JSON с HTTP 200.
     * Заголовок Cache-Control: no-store устанавливает WalletRequestInterceptor.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WalletBalanceResponse> getOwnBalance(@AuthenticationPrincipal UUID userId) {

        return ResponseEntity.ok()
                .body(walletQueryService.getOwnBalance(userId));
    }
}
