package io.github.returnaction.fitness.identity.registration.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.returnaction.fitness.identity.registration.entity.operation.RegistrationOperationEntity;
import io.github.returnaction.fitness.identity.registration.exception.RegistrationConflictException;
import io.github.returnaction.fitness.identity.registration.model.RegistrationStartResult;
import io.github.returnaction.fitness.identity.shared.model.ValidatedCredentials;
import io.github.returnaction.fitness.identity.registration.repository.RegistrationOperationRepository;
import io.github.returnaction.fitness.identity.registration.entity.role.RoleCode;
import io.github.returnaction.fitness.identity.registration.entity.role.RoleEntity;
import io.github.returnaction.fitness.identity.registration.entity.role.UserRoleEntity;
import io.github.returnaction.fitness.identity.registration.entity.role.UserRoleId;
import io.github.returnaction.fitness.identity.registration.entity.user.UserCredentialEntity;
import io.github.returnaction.fitness.identity.registration.entity.user.UserEntity;
import io.github.returnaction.fitness.identity.registration.entity.user.UserStatus;
import io.github.returnaction.fitness.identity.registration.repository.RoleRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserCredentialRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserRepository;
import io.github.returnaction.fitness.identity.registration.repository.UserRoleRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Создаёт локальные записи регистрации в короткой транзакции. */
@Service
@RequiredArgsConstructor
public class RegistrationCreationService {

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final RegistrationOperationRepository operationRepository;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring infrastructure bean, not exposed or modified by this service.")
    private final TransactionTemplate transactionTemplate;

    public RegistrationStartResult createOrRepeat(byte[] digest, ValidatedCredentials input) {
        RegistrationStartResult result = transactionTemplate
                .execute(status -> createOrRepeatInTransaction(digest, input));
        if (result == null)
            throw new IllegalStateException("Registration transaction completed without a result");
        return result;
    }

    private RegistrationStartResult createOrRepeatInTransaction(byte[] digest, ValidatedCredentials input) {
        RegistrationOperationEntity existing = operationRepository.findByIdempotencyKeyDigest(digest).orElse(null);
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

    RegistrationStartResult repeat(RegistrationOperationEntity operation, ValidatedCredentials input) {
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
}
