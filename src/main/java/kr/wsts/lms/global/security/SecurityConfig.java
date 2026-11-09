package kr.wsts.lms.global.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import kr.wsts.lms.domain.auth.AuthService;
import kr.wsts.lms.global.support.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    public static final String ISSUER = "lms-backend";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecretKey jwtKey(@Value("${app.security.jwt-secret:}") String configured, Environment environment) {
        byte[] key = configured.getBytes(StandardCharsets.UTF_8);
        if (configured.isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("mariadb")) || !environment.acceptsProfiles(Profiles.of("local", "test"))) {
                throw new IllegalStateException("JWT_SECRET must be configured outside local/test profiles.");
            }
            key = new byte[32];
            new SecureRandom().nextBytes(key);
        }
        if (key.length < 32) throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes.");
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
    }

    @Bean
    NimbusJwtDecoder jwtDecoder(SecretKey jwtKey, Db db) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> accountValidator = jwt -> {
            try {
                long userId = Long.parseLong(jwt.getSubject());
                Number version = jwt.getClaim("tokenVersion");
                var account = db.find("SELECT status, token_version FROM users WHERE id = ?", userId);
                if (version != null && account.isPresent()
                        && "ACTIVE".equals(account.get().get("status"))
                        && version.longValue() == Db.id(account.get(), "tokenVersion")) {
                    return OAuth2TokenValidatorResult.success();
                }
            } catch (RuntimeException ignored) {
                // An invalid subject/account is an authentication failure, never a server error.
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "The account or token is no longer valid.", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER), accountValidator));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/public/notices/*", "/actuator/health", "/actuator/health/**", "/api-docs", "/api-docs/**", "/docs/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, exception) -> writeSecurityError(response, 401, "UNAUTHORIZED", "인증이 필요하거나 토큰이 만료되었습니다.")))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeSecurityError(response, 401, "UNAUTHORIZED", "인증이 필요합니다."))
                        .accessDeniedHandler((request, response, exception) -> writeSecurityError(response, 403, "FORBIDDEN", "접근 권한이 없습니다.")));
        return http.build();
    }

    private static void writeSecurityError(jakarta.servlet.http.HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"timestamp\":\"" + Instant.now() + "\"}");
    }

    @Bean
    ApplicationRunner bootstrapAdministrator(AuthService auth,
            @Value("${app.bootstrap.admin-email:}") String email,
            @Value("${app.bootstrap.admin-password:}") String password) {
        return args -> auth.bootstrapAdministrator(email, password);
    }
}
