package io.github.returnaction.fitness.coins.shared.persistence;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.hibernate.TransactionException;
import org.hibernate.exception.JDBCConnectionException;

/**
 * Отличает потерю соединения с БД от прочих ошибок Hibernate/JPA.
 */
public final class ConnectionFailures {

    private ConnectionFailures() {
    }

    public static boolean causedByConnectionFailure(Throwable exception) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof JDBCConnectionException) {
                return true;
            }
            if (cause instanceof TransactionException && cause.getCause() instanceof SQLException sql) {
                String state = sql.getSQLState();
                // Hikari's closed-connection sentinel has no SQLState. Hibernate can
                // replace the original connection error with this rollback failure.
                if ((state != null && state.startsWith("08"))
                        || (state == null && "Connection is closed".equals(sql.getMessage()))) {
                    return true;
                }
            }
        }
        return false;
    }
}
