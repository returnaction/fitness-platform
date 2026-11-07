package io.github.returnaction.fitness.identity.registration.application;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import io.github.returnaction.fitness.identity.registration.entity.RegistrationOperationEntity;
import io.github.returnaction.fitness.identity.registration.entity.RoleCode;
import io.github.returnaction.fitness.identity.registration.entity.RoleEntity;
import io.github.returnaction.fitness.identity.registration.entity.UserCredentialEntity;
import io.github.returnaction.fitness.identity.registration.entity.UserEntity;
import io.github.returnaction.fitness.identity.registration.entity.UserRoleEntity;
import io.github.returnaction.fitness.identity.registration.entity.UserRoleId;
import io.github.returnaction.fitness.identity.registration.entity.UserStatus;
import io.github.returnaction.fitness.identity.registration.repository.RegistrationOperationRepository;
import io.github.returnaction.fitness.identity.registration.repository.RoleRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserCredentialRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserRoleRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Создаёт локальную часть PENDING-регистрации в одной транзакции Identity.
 */
@Service
public class RegistrationService {

    private final RegistrationInputValidator inputValidator;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final RegistrationOperationRepository operationRepository;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "TransactionTemplate is a Spring infrastructure bean and is never exposed by the service.")
    private final TransactionTemplate transactionTemplate;

    public RegistrationService(
            RegistrationInputValidator inputValidator,
            PasswordEncoder passwordEncoder,
            UserRepository userRepository,
            UserCredentialRepository userCredentialRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            RegistrationOperationRepository operationRepository,
            TransactionTemplate transactionTemplate) {
        this.inputValidator = inputValidator;
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
        this.userCredentialRepository = userCredentialRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.operationRepository = operationRepository;
        this.transactionTemplate = transactionTemplate;
    }

    public RegistrationStartResult start(UUID idempotencyKey, RegisterUserRequest request) {
        RegistrationInputValidator.ValidatedRegistration input = inputValidator.validate(request);
        byte[] digest = sha256(idempotencyKey.toString());
        try {
            RegistrationStartResult result = transactionTemplate.execute(status -> createOrRepeat(digest, input));
            if (result == null) {
                throw new IllegalStateException("Registration transaction completed without a result");
            }
            return result;
        } catch (DataIntegrityViolationException exception) {
            return resolveWriteConflict(digest, input, exception);
        }
    }

    private RegistrationStartResult createOrRepeat(
            byte[] digest, RegistrationInputValidator.ValidatedRegistration input) {
        RegistrationOperationEntity existing =
                operationRepository.findByIdempotencyKeyDigest(digest).orElse(null);
        if (existing != null) {
            return repeat(existing, input);
        }
        if (userRepository.findByEmailCanonical(input.emailCanonical()).isPresent()) {
            throw new RegistrationConflictException("email_already_registered");
        }

        RoleEntity userRole = roleRepository
                .findByCode(RoleCode.USER)
                .orElseThrow(() -> new IllegalStateException("Required USER role is missing"));
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity(userId, input.emailCanonical(), UserStatus.PENDING);
        userRepository.save(user);
        userCredentialRepository.save(new UserCredentialEntity(userId, passwordEncoder.encode(input.password())));
        userRoleRepository.save(new UserRoleEntity(new UserRoleId(userId, userRole.getId())));

        RegistrationOperationEntity operation = new RegistrationOperationEntity(UUID.randomUUID(), digest, userId);
        operationRepository.save(operation);
        return new RegistrationStartResult(operation.getId(), userId, input.emailCanonical());
    }

    private RegistrationStartResult resolveWriteConflict(
            byte[] digest,
            RegistrationInputValidator.ValidatedRegistration input,
            DataIntegrityViolationException exception) {
        RegistrationOperationEntity existing =
                operationRepository.findByIdempotencyKeyDigest(digest).orElse(null);
        if (existing != null) {
            return repeat(existing, input);
        }
        if (userRepository.findByEmailCanonical(input.emailCanonical()).isPresent()) {
            throw new RegistrationConflictException("email_already_registered");
        }
        throw exception;
    }

    private RegistrationStartResult repeat(
            RegistrationOperationEntity operation, RegistrationInputValidator.ValidatedRegistration input) {
        UserEntity user = userRepository
                .findById(operation.getUserId())
                .orElseThrow(() -> new IllegalStateException("Registration operation references a missing user"));
        UserCredentialEntity credentials = userCredentialRepository
                .findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("User is missing credentials"));
        if (!user.getEmailCanonical().equals(input.emailCanonical())
                || !passwordEncoder.matches(input.password(), credentials.getPasswordHash())) {
            throw new RegistrationConflictException("idempotency_key_conflict");
        }
        return new RegistrationStartResult(operation.getId(), user.getId(), user.getEmailCanonical());
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record RegistrationStartResult(UUID operationId, UUID userId, String emailCanonical) {}
}
