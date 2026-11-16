package io.github.returnaction.fitness.coins.shared.persistence;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.hibernate.exception.LockAcquisitionException;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * Отличает конфликт блокировки БД от прочих ошибок Hibernate/JPA.
 */
public final class LockFailures {

    private LockFailures() {
    }

    public static boolean causedByLockConflict(Throwable exception) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof PessimisticLockingFailureException
                    || cause instanceof LockAcquisitionException) {
                return true;
            }
            if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
