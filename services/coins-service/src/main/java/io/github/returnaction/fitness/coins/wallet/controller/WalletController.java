package io.github.returnaction.fitness.coins.wallet.controller;

import io.github.returnaction.fitness.coins.wallet.dto.WalletBalanceResponse;
import io.github.returnaction.fitness.coins.wallet.service.WalletQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP-вход Coins для чтения собственного баланса.
 *
 * <p>Spec 017 T004.7: {@code GET /api/v1/coins/me} берёт UUID из
 * {@code SecurityContext}, вызывает use case и возвращает
 * {@code Cache-Control: no-store}.</p>
 */
@RestController
@RequestMapping("/api/v1/coins/me")
@RequiredArgsConstructor
public class WalletController {

    private final WalletQueryService walletQueryService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WalletBalanceResponse> getOwnBalance(@AuthenticationPrincipal UUID userId) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(walletQueryService.getOwnBalance(userId));
    }
}
