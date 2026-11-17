package io.github.returnaction.fitness.challenge.idempotency;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Хранит HTTP-ответы по ключу (user_id, method, path, key). Тело и заголовки
 * пишутся в JSONB; уникальность ключа обеспечивает схема.
 */
@Repository
@RequiredArgsConstructor
public class IdempotencyStore {

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<StoredRecord> find(UUID userId, String method, String path, UUID key) {
        return jdbc.query("""
                SELECT request_fingerprint,
                       response_status,
                       response_body::text AS response_body,
                       response_headers::text AS response_headers
                FROM challenge_idempotency_records
                WHERE user_id = :userId
                  AND method = :method
                  AND path = :path
                  AND key = :key
                """, new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("method", method)
                        .addValue("path", path)
                        .addValue("key", key),
                (resultSet, rowNumber) -> new StoredRecord(
                        resultSet.getBytes("request_fingerprint"),
                        new StoredHttpResponse(
                                resultSet.getInt("response_status"),
                                resultSet.getString("response_body"),
                                resultSet.getString("response_headers"))))
                .stream()
                .findFirst();
    }

    public void insert(UUID userId, String method, String path, UUID key, byte[] requestFingerprint,
                       StoredHttpResponse response) {
        jdbc.update("""
                INSERT INTO challenge_idempotency_records
                    (id, user_id, method, path, key, request_fingerprint,
                     response_status, response_body, response_headers)
                VALUES (:id, :userId, :method, :path, :key, :fingerprint,
                        :status, CAST(:body AS jsonb), CAST(:headers AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("userId", userId)
                .addValue("method", method)
                .addValue("path", path)
                .addValue("key", key)
                .addValue("fingerprint", requestFingerprint)
                .addValue("status", response.status())
                .addValue("body", response.body())
                .addValue("headers", response.headers()));
    }

    public record StoredRecord(byte[] requestFingerprint, StoredHttpResponse response) {

        public StoredRecord {
            requestFingerprint = requestFingerprint == null ? null : requestFingerprint.clone();
        }

        @Override
        public byte[] requestFingerprint() {
            return requestFingerprint == null ? null : requestFingerprint.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StoredRecord(byte[] fingerprint, StoredHttpResponse storedResponse)
                    && Arrays.equals(requestFingerprint, fingerprint)
                    && Objects.equals(response, storedResponse);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(requestFingerprint) + Objects.hashCode(response);
        }

        @Override
        public @NonNull String toString() {
            return "StoredRecord[requestFingerprint="
                    + (requestFingerprint == null ? "null" : HexFormat.of().formatHex(requestFingerprint))
                    + ", response=" + response + ']';
        }
    }
}
