package kr.wsts.lms.domain.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest request) { return auth.register(request); }

    @PostMapping("/api/auth/login")
    public AuthService.TokenResponse login(@Valid @RequestBody LoginRequest request) { return auth.login(request); }

    @PostMapping("/api/auth/refresh")
    public AuthService.TokenResponse refresh(@Valid @RequestBody RefreshRequest request) { return auth.refresh(request.refreshToken()); }

    @PostMapping("/api/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) { auth.logout(request.refreshToken()); }

    @GetMapping("/api/me")
    public Map<String, Object> me() { return auth.me(); }

    @PatchMapping("/api/me")
    public Map<String, Object> updateMe(@RequestBody Map<String, Object> request) { return auth.updateMe(request); }

    @PatchMapping("/api/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody PasswordRequest request) { auth.changePassword(request); }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(min = 2, max = 80) String name,
            @Size(max = 30) String phone) { }
    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) { }
    public record RefreshRequest(@NotBlank @Size(min = 20, max = 512) String refreshToken) { }
    public record PasswordRequest(@NotBlank @Size(max = 72) String currentPassword, @NotBlank @Size(min = 8, max = 72) String newPassword) { }
}
