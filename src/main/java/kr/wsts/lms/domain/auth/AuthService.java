package kr.wsts.lms.domain.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import kr.wsts.lms.global.error.AppException;
import kr.wsts.lms.global.security.SecurityConfig;
import kr.wsts.lms.global.support.Actor;
import kr.wsts.lms.global.support.Db;
import kr.wsts.lms.global.support.Input;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final long ACCESS_SECONDS = 900;
    private static final long REFRESH_SECONDS = 604800;
    private static final String PUBLIC_COLUMNS = "id,email,name,phone,role,status,created_at,updated_at";
    private final Db db;
    private final Actor actor;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final SecureRandom random = new SecureRandom();

    public AuthService(Db db, Actor actor, PasswordEncoder passwords, JwtEncoder encoder) {
        this.db = db; this.actor = actor; this.passwords = passwords; this.encoder = encoder;
    }

    @Transactional
    public Map<String, Object> register(AuthController.RegisterRequest request) {
        String email = normalizeEmail(request.email());
        validatePassword(request.password());
        String name = request.name().trim();
        if (name.length() < 2) throw AppException.bad("이름은 2자 이상이어야 합니다.");
        if (db.count("SELECT COUNT(*) FROM users WHERE email=?", email) > 0) throw AppException.conflict("이미 등록된 이메일입니다.");
        long id = db.insert("INSERT INTO users(email,password_hash,name,phone,role,status,token_version,created_at,updated_at) VALUES(?,?,?,?,'STUDENT','ACTIVE',0,?,?)",
                email, passwords.encode(request.password()), name, normalizePhone(request.phone()), Db.now(), Db.now());
        return publicUser(id);
    }

    public TokenResponse login(AuthController.LoginRequest request) {
        Map<String, Object> user = db.find("SELECT * FROM users WHERE email=?", normalizeEmail(request.email()))
                .orElseThrow(() -> unauthorized("이메일 또는 비밀번호가 올바르지 않습니다."));
        if (!passwordMatches(request.password(), (String) user.get("passwordHash"))) throw unauthorized("이메일 또는 비밀번호가 올바르지 않습니다.");
        requireActive(user);
        return issue(user, UUID.randomUUID().toString());
    }

    @Transactional(noRollbackFor = AppException.class)
    public TokenResponse refresh(String refreshToken) {
        Map<String, Object> token = db.find("SELECT *, CASE WHEN expires_at > ? THEN 1 ELSE 0 END AS valid_time FROM refresh_tokens WHERE token_hash=? FOR UPDATE", Db.now(), hash(refreshToken))
                .orElseThrow(() -> unauthorized("유효한 갱신 토큰이 아닙니다."));
        String family = (String) token.get("familyId");
        if (token.get("revokedAt") != null) {
            db.update("UPDATE refresh_tokens SET revoked_at=? WHERE family_id=? AND revoked_at IS NULL", Db.now(), family);
            throw unauthorized("이미 사용한 갱신 토큰입니다. 다시 로그인해주세요.");
        }
        if (Db.id(token, "validTime") != 1) throw unauthorized("갱신 토큰이 만료되었습니다.");
        Map<String, Object> user = db.one("SELECT * FROM users WHERE id=?", Db.id(token, "userId"));
        requireActive(user);
        db.update("UPDATE refresh_tokens SET revoked_at=? WHERE id=?", Db.now(), Db.id(token, "id"));
        return issue(user, family);
    }

    @Transactional
    public void logout(String refreshToken) {
        long current = actor.id();
        var token = db.find("SELECT user_id,family_id FROM refresh_tokens WHERE token_hash=?", hash(refreshToken));
        if (token.isEmpty()) return;
        if (Db.id(token.get(), "userId") != current) throw AppException.forbidden();
        db.update("UPDATE refresh_tokens SET revoked_at=? WHERE family_id=? AND revoked_at IS NULL", Db.now(), token.get().get("familyId"));
    }

    public Map<String, Object> me() { return publicUser(actor.id()); }

    @Transactional
    public Map<String, Object> updateMe(Map<String, Object> request) {
        long userId = actor.id();
        Map<String, Object> user = publicUser(userId);
        String name = request.containsKey("name") ? Input.str(request, "name", 2, 80) : (String) user.get("name");
        String phone = request.containsKey("phone") ? normalizePhone(Input.optional(request, "phone", "", 30)) : (String) user.get("phone");
        db.update("UPDATE users SET name=?,phone=?,updated_at=? WHERE id=?", name, phone, Db.now(), userId);
        return publicUser(userId);
    }

    @Transactional
    public void changePassword(AuthController.PasswordRequest request) {
        long userId = actor.id();
        Map<String, Object> user = db.one("SELECT * FROM users WHERE id=? FOR UPDATE", userId);
        if (!passwordMatches(request.currentPassword(), (String) user.get("passwordHash"))) throw unauthorized("현재 비밀번호가 올바르지 않습니다.");
        validatePassword(request.newPassword());
        if (passwordMatches(request.newPassword(), (String) user.get("passwordHash"))) throw AppException.bad("현재 비밀번호와 다른 비밀번호를 입력해주세요.");
        db.update("UPDATE users SET password_hash=?,token_version=token_version+1,updated_at=? WHERE id=?", passwords.encode(request.newPassword()), Db.now(), userId);
        db.update("UPDATE refresh_tokens SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL", Db.now(), userId);
    }

    @Transactional
    public void bootstrapAdministrator(String email, String password) {
        if (email.isBlank() && password.isBlank()) return;
        if (email.isBlank() || password.isBlank()) throw new IllegalStateException("Configure both BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD.");
        email = normalizeEmail(email);
        if (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalStateException("BOOTSTRAP_ADMIN_EMAIL is invalid.");
        validatePassword(password);
        var existing = db.find("SELECT id,role FROM users WHERE email=?", email);
        if (existing.isPresent()) {
            if (!"ADMIN".equals(existing.get().get("role"))) throw new IllegalStateException("Bootstrap email belongs to a non-administrator account.");
            return;
        }
        try {
            db.insert("INSERT INTO users(email,password_hash,name,phone,role,status,token_version,created_at,updated_at) VALUES(?,?,?,NULL,'ADMIN','ACTIVE',0,?,?)",
                    email, passwords.encode(password), "운영자", Db.now(), Db.now());
        } catch (DataIntegrityViolationException concurrentBootstrap) {
            if (!db.find("SELECT role FROM users WHERE email=?", email).map(row -> "ADMIN".equals(row.get("role"))).orElse(false)) throw concurrentBootstrap;
        }
    }

    private TokenResponse issue(Map<String, Object> user, String family) {
        long userId = Db.id(user, "id");
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(SecurityConfig.ISSUER).subject(Long.toString(userId))
                .audience(java.util.List.of("lms-api")).issuedAt(now).expiresAt(now.plusSeconds(ACCESS_SECONDS))
                .id(UUID.randomUUID().toString()).claim("role", user.get("role")).claim("tokenVersion", Db.id(user, "tokenVersion")).build();
        String accessToken = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        byte[] secret = new byte[32]; random.nextBytes(secret);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        db.insert("INSERT INTO refresh_tokens(user_id,token_hash,family_id,expires_at,revoked_at,created_at) VALUES(?,?,?,?,NULL,?)",
                userId, hash(refreshToken), family, Db.now().plusSeconds(REFRESH_SECONDS), Db.now());
        return new TokenResponse(accessToken, "Bearer", ACCESS_SECONDS, refreshToken, REFRESH_SECONDS, publicUser(userId));
    }

    private Map<String, Object> publicUser(long userId) { return db.one("SELECT " + PUBLIC_COLUMNS + " FROM users WHERE id=?", userId); }
    private static String normalizeEmail(String email) { return email.trim().toLowerCase(java.util.Locale.ROOT); }
    private static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        phone = phone.trim();
        if (!phone.matches("[0-9+() -]{7,30}")) throw AppException.bad("전화번호 형식을 확인해주세요.");
        return phone;
    }
    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) throw AppException.bad("비밀번호는 8자 이상, UTF-8 기준 72바이트 이하여야 합니다.");
    }
    private boolean passwordMatches(String password, String encoded) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= 72 && passwords.matches(password, encoded);
    }
    private static void requireActive(Map<String, Object> user) { if (!"ACTIVE".equals(user.get("status"))) throw unauthorized("사용할 수 없는 계정입니다."); }
    private static AppException unauthorized(String message) { return new AppException(401, "UNAUTHORIZED", message); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken, long refreshExpiresIn, Map<String, Object> user) { }
}
