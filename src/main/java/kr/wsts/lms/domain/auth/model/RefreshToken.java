package kr.wsts.lms.domain.auth.model;

import java.time.Instant;

/**
 * JDBC domain state for {@code refresh_tokens}.
 * Stores a token hash only; raw refresh tokens are not persisted.
 * Timestamp components use UTC instants.
 * Persistence mapping and business operations are implemented by the feature owner.
 */
public record RefreshToken(
        Long id,
        Long userId,
        String tokenHash,
        String familyId,
        Instant expiresAt,
        Instant revokedAt,
        Instant createdAt
) {
}
