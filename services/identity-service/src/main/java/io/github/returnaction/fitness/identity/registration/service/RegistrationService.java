package io.github.returnaction.fitness.identity.registration.service;

import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import io.github.returnaction.fitness.identity.shared.validation.CredentialsValidator;
import io.github.returnaction.fitness.identity.registration.entity.operation.RegistrationOperationEntity;
import io.github.returnaction.fitness.identity.registration.exception.RegistrationConflictException;
import io.github.returnaction.fitness.identity.registration.model.RegistrationStartResult;
import io.github.returnaction.fitness.identity.shared.model.ValidatedCredentials;
import io.github.returnaction.fitness.identity.registration.repository.RegistrationOperationRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Запускает регистрацию пользователя и помогает безопасно обработать повторные
 * запросы.
 *
 * <p>
 * Сначала проверяет данные пользователя, затем сохраняет операцию регистрации.
 * Если запрос пришёл повторно с тем же ключом идемпотентности, сервис находит
 * предыдущую операцию и продолжает её, а не создаёт новую.
 *
 * <p>
 * Если одновременно пришли конфликтующие запросы, сервис проверяет, не
 * зарегистрирован ли уже такой адрес электронной почты. Если зарегистрирован —
 * сообщает о конфликте. Выпуск токенов и создание сессии этот класс не
 * выполняет.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final CredentialsValidator inputValidator;
    private final RegistrationCreationService registrationCreationService;
    private final RegistrationOperationRepository operationRepository;
    private final UserRepository userRepository;

    public RegistrationStartResult start(UUID idempotencyKey, RegisterUserRequest request) {
        // Проверяет данные запроса и подготавливает email и пароль для регистрации.
        ValidatedCredentials input = inputValidator.validate(request == null ? null : request.email(),
                request == null ? null : request.password());
        // Создаёт SHA-256-хеш ключа идемпотентности для безопасного хранения и поиска
        // операции.
        byte[] digest = sha256(idempotencyKey.toString());

        try {
            return registrationCreationService.createOrRepeat(digest, input);
        } catch (DataIntegrityViolationException exception) {
            return resolveWriteConflict(digest, input, exception);
        }
    }

    private RegistrationStartResult resolveWriteConflict(byte[] digest, ValidatedCredentials input,
        DataIntegrityViolationException exception) {

        RegistrationOperationEntity existing = operationRepository.findByIdempotencyKeyDigest(digest).orElse(null);
        if (existing != null) {
            return registrationCreationService.repeat(existing, input);
        }
        if (userRepository.findByEmailCanonical(input.emailCanonical()).isPresent()) {
            throw new RegistrationConflictException("email_already_registered");
        }

        throw exception;
    }

    private byte[] sha256(String value) {

        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
