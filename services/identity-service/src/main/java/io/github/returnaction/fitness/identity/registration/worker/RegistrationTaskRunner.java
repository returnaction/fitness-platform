package io.github.returnaction.fitness.identity.registration.worker;

import io.github.returnaction.fitness.identity.registration.repository.RegistrationLeaseRepository;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Общий ограниченный пул: задача арендуется только в свободном потоке. */
@Component
public final class RegistrationTaskRunner implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationTaskRunner.class);
    private final int batchSize;
    private final int parallelism;
    private final ExecutorService executor;
    private final ReentrantLock passLock = new ReentrantLock();

    public RegistrationTaskRunner(@Value("${identity.registration.worker.batch-size}") int batchSize,
        @Value("${identity.registration.worker.parallelism}") int parallelism) {
        if (batchSize < 1 || batchSize > 20 || parallelism < 1 || parallelism > 4) {
            throw new IllegalArgumentException("Invalid worker batch size or parallelism");
        }
        this.batchSize = batchSize;
        this.parallelism = parallelism;
        this.executor = Executors.newFixedThreadPool(parallelism);
    }

    public void runPass(Supplier<List<RegistrationLeaseRepository.Lease>> claim,
        Consumer<RegistrationLeaseRepository.Lease> process) {
        if (!passLock.tryLock())
            return;
        try {
            for (int submitted = 0; submitted < batchSize;) {
                int waveSize = Math.min(parallelism, batchSize - submitted);
                List<Future<Boolean>> wave = new ArrayList<>(waveSize);
                for (int index = 0; index < waveSize; index++) {
                    wave.add(executor.submit(() -> processOne(claim, process)));
                }
                submitted += waveSize;
                boolean claimedAny = false;
                for (Future<Boolean> future : wave) {
                    try {
                        claimedAny |= future.get();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (ExecutionException exception) {
                        LOGGER.error("Creation worker failed; error_type={}",
                                exception.getCause().getClass().getName());
                    }
                }
                if (!claimedAny)
                    return;
            }
        } finally {
            passLock.unlock();
        }
    }

    private boolean processOne(Supplier<List<RegistrationLeaseRepository.Lease>> claim,
        Consumer<RegistrationLeaseRepository.Lease> process) {
        var leases = claim.get();
        if (leases.isEmpty())
            return false;
        try {
            process.accept(leases.getFirst());
        } catch (RuntimeException exception) {
            LOGGER.error("Creation failed; lease will expire, error_type={}", exception.getClass().getName());
        }
        return true;
    }

    @PreDestroy
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS))
                executor.shutdownNow();
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
