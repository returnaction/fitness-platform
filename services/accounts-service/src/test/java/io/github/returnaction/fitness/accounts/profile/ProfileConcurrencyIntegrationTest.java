package io.github.returnaction.fitness.accounts.profile;

import io.github.returnaction.fitness.accounts.profile.dto.ProfileResponse;
import io.github.returnaction.fitness.accounts.profile.dto.UpdateProfileRequest;
import io.github.returnaction.fitness.accounts.profile.entity.ProfileVisibility;
import io.github.returnaction.fitness.accounts.profile.entity.UserProfileEntity;
import io.github.returnaction.fitness.accounts.profile.exception.NicknameAlreadyTakenException;
import io.github.returnaction.fitness.accounts.profile.exception.ProfileVersionConflictException;
import io.github.returnaction.fitness.accounts.profile.grpc.SecureGrpcServer;
import io.github.returnaction.fitness.accounts.profile.repository.ProfileRepository;
import io.github.returnaction.fitness.accounts.profile.service.ProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
class ProfileConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    ProfileService service;
    @Autowired
    ProfileRepository repository;
    @MockitoBean
    SecureGrpcServer grpcServer;

    @Test
    void concurrentUpdatesWithSameVersionCommitExactlyOneCompleteForm() throws Exception {
        var owner = createProfile();
        var unrelated = createProfile();
        var first = new Attempt(owner.getUserId(), request(newNickname(), 1, false));
        var second = new Attempt(owner.getUserId(), request(newNickname(), 1, true));

        var outcomes = race(first, second);

        var winner = onlyWinner(outcomes);
        assertThat(outcomes.stream().filter(outcome -> outcome.failure() != null).toList())
            .singleElement().satisfies(outcome ->
                assertThat(outcome.failure()).isInstanceOf(ProfileVersionConflictException.class));
        assertThat(winner.response()).isEqualTo(expected(winner.attempt().request()));
        assertThat(service.getOwnProfile(owner.getUserId())).isEqualTo(winner.response());
        var stored = repository.findById(owner.getUserId()).orElseThrow();
        assertThat(stored.getVersion()).isEqualTo(2);
        assertThat(stored.getCreatedAt()).isEqualTo(owner.getCreatedAt());
        assertThat(stored.getPublicProfileId()).isEqualTo(owner.getPublicProfileId());
        assertUnchanged(unrelated);
    }

    @Test
    void concurrentOwnersCannotClaimSameNicknameIgnoringCase() throws Exception {
        var firstOwner = createProfile();
        var secondOwner = createProfile();
        String nickname = newNickname();
        var first = new Attempt(firstOwner.getUserId(), request(nickname, 1, false));
        var second = new Attempt(secondOwner.getUserId(), request(nickname.toUpperCase(Locale.ROOT), 1, true));

        var outcomes = race(first, second);

        var winner = onlyWinner(outcomes);
        assertThat(outcomes.stream().filter(outcome -> outcome.failure() != null).toList())
            .singleElement().satisfies(outcome ->
                assertThat(outcome.failure()).isInstanceOf(NicknameAlreadyTakenException.class));
        assertThat(winner.response()).isEqualTo(expected(winner.attempt().request()));
        assertThat(service.getOwnProfile(winner.attempt().userId())).isEqualTo(winner.response());
        var loser = winner.attempt().userId().equals(firstOwner.getUserId()) ? secondOwner : firstOwner;
        assertUnchanged(loser);
        // A subsequent valid write also proves the failed transaction has ended.
        assertThat(service.updateOwnProfile(loser.getUserId(), request(loser.getNickname(), 1, false)).version())
            .isEqualTo(2);
    }

    @Test
    void ownerCanKeepNicknameChangeCaseAndReleaseOldNickname() {
        var owner = createProfile();
        var other = createProfile();
        String original = owner.getNickname();
        var same = service.updateOwnProfile(owner.getUserId(), request(original, 1, false));
        assertThat(same.version()).isEqualTo(2);
        var caseChanged = service.updateOwnProfile(owner.getUserId(), request(original.toUpperCase(Locale.ROOT), 2, true));
        assertThat(caseChanged.nickname()).isEqualTo(original.toUpperCase(Locale.ROOT));
        assertThat(caseChanged.version()).isEqualTo(3);
        assertThat(service.getOwnProfile(owner.getUserId())).isEqualTo(caseChanged);

        var renamed = service.updateOwnProfile(owner.getUserId(), request(newNickname(), 3, false));
        var claimed = service.updateOwnProfile(other.getUserId(), request(original, 1, true));

        assertThat(renamed.version()).isEqualTo(4);
        assertThat(claimed.nickname()).isEqualTo(original);
        assertThat(claimed.version()).isEqualTo(2);
        assertThat(service.getOwnProfile(owner.getUserId())).isEqualTo(renamed);
        assertThat(service.getOwnProfile(other.getUserId())).isEqualTo(claimed);
    }

    @Test
    void replayAfterLostResponseCannotOverwriteCommittedOrNewerData() {
        var owner = createProfile();
        var originalRequest = request(owner.getNickname(), 1, false);
        // Model a committed write whose response was not received by the caller.
        service.updateOwnProfile(owner.getUserId(), originalRequest);
        var committed = repository.findById(owner.getUserId()).orElseThrow();
        assertThatThrownBy(() -> service.updateOwnProfile(owner.getUserId(), originalRequest))
            .isInstanceOf(ProfileVersionConflictException.class);
        assertUnchanged(committed);

        service.updateOwnProfile(owner.getUserId(), request(newNickname(), 2, true));
        var newer = repository.findById(owner.getUserId()).orElseThrow();
        assertThatThrownBy(() -> service.updateOwnProfile(owner.getUserId(), originalRequest))
            .isInstanceOf(ProfileVersionConflictException.class);
        assertUnchanged(newer);
        assertThat(newer.getVersion()).isEqualTo(3);
    }

    private List<Outcome> race(Attempt first, Attempt second) throws Exception {
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() -> updateAfterBarrier(start, first));
            var secondFuture = executor.submit(() -> updateAfterBarrier(start, second));
            try {
                return List.of(firstFuture.get(15, TimeUnit.SECONDS), secondFuture.get(15, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private Outcome updateAfterBarrier(CyclicBarrier start, Attempt attempt) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            return new Outcome(attempt, service.updateOwnProfile(attempt.userId(), attempt.request()), null);
        } catch (ProfileVersionConflictException | NicknameAlreadyTakenException exception) {
            return new Outcome(attempt, null, exception);
        }
    }

    private Outcome onlyWinner(List<Outcome> outcomes) {
        var winners = outcomes.stream().filter(outcome -> outcome.response() != null).toList();
        assertThat(winners).hasSize(1);
        return winners.getFirst();
    }

    private void assertUnchanged(UserProfileEntity before) {
        assertThat(repository.findById(before.getUserId()).orElseThrow())
            .usingRecursiveComparison().isEqualTo(before);
    }

    private UserProfileEntity createProfile() {
        var saved = repository.saveAndFlush(new UserProfileEntity(UUID.randomUUID(), UUID.randomUUID(), newNickname()));
        // Read database timestamp precision before comparing unchanged rows.
        return repository.findById(saved.getUserId()).orElseThrow();
    }

    private String newNickname() {
        return "p_" + UUID.randomUUID().toString().substring(0, 20);
    }

    private UpdateProfileRequest request(String nickname, long version, boolean alternative) {
        return new UpdateProfileRequest(
            nickname,
            alternative ? "Мария" : "Анна",
            alternative ? "Петрова" : "Иванова",
            alternative ? "second bio" : "first bio",
            alternative ? LocalDate.of(1999, 1, 1) : LocalDate.of(2000, 2, 29),
            alternative ? "UTC" : "Europe/Moscow",
            alternative ? ProfileVisibility.PRIVATE : ProfileVisibility.PUBLIC,
            version
        );
    }

    private ProfileResponse expected(UpdateProfileRequest request) {
        return new ProfileResponse(
            request.nickname(),
            request.firstName(),
            request.lastName(),
            request.bio(),
            request.dateOfBirth(),
            request.timezone(),
            request.visibility(),
            request.version() + 1
        );
    }

    private record Attempt(UUID userId, UpdateProfileRequest request) {
    }

    private record Outcome(Attempt attempt, ProfileResponse response, RuntimeException failure) {
    }
}
