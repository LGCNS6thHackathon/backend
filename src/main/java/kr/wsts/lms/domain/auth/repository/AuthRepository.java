package kr.wsts.lms.domain.auth.repository;

import java.time.Instant;
import java.util.Optional;
import kr.wsts.lms.domain.auth.model.RefreshToken;
import kr.wsts.lms.domain.auth.model.User;

/**
 * JDBC persistence contract only; implement and register an adapter in this feature.
 * Accounts are issued by a global administrator, never through public sign-up.
 * Issuance adapters preserve provisionedBy/provisionedAt; bootstrap and legacy
 * accounts may have no known issuer. Credentials are handed out by the operator.
 * Password hashes and token hashes belong to internal models, never response DTOs.
 * Token rotation and account changes must share their service transaction.
 */
public interface AuthRepository {
    Optional<User> findById(long userId);
    Optional<User> findActiveById(long userId);
    Optional<User> findByEmail(String normalizedEmail);
    User saveUser(User user);
    Optional<RefreshToken> findRefreshTokenByHashForUpdate(String tokenHash);
    RefreshToken saveRefreshToken(RefreshToken token);
    void revokeRefreshFamily(String familyId, Instant revokedAt);
    void revokeAllRefreshTokens(long userId, Instant revokedAt);
}
