package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String REFRESH_COOKIE = "aiconnect_refresh";
    private final IdentityService identity;
    public AuthController(IdentityService identity) { this.identity = identity; }

    @PostMapping("/bootstrap")
    public ResponseEntity<SessionResponse> bootstrap(@Valid @RequestBody Credentials request) {
        identity.bootstrap(request.email(), request.password());
        return toResponse(identity.login(request.email(), request.password()));
    }
    @PostMapping("/login")
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody Credentials request) { return toResponse(identity.login(request.email(), request.password())); }
    @PostMapping("/refresh")
    public ResponseEntity<SessionResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REQUIRED", "A refresh token cookie is required.");
        return toResponse(identity.refresh(refreshToken));
    }
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null) identity.logout(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expiredCookie().toString()).build();
    }
    private ResponseEntity<SessionResponse> toResponse(IdentityService.Session session) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                .body(new SessionResponse(session.accessToken(), UserView.from(session.user())));
    }
    private ResponseCookie refreshCookie(String token) { return ResponseCookie.from(REFRESH_COOKIE, token).httpOnly(true).secure(true).sameSite("Strict").path("/api/auth").maxAge(60L * 60 * 24 * 30).build(); }
    private ResponseCookie expiredCookie() { return ResponseCookie.from(REFRESH_COOKIE, "").httpOnly(true).secure(true).sameSite("Strict").path("/api/auth").maxAge(0).build(); }
    public record Credentials(@NotBlank @Email @Size(max = 320) String email, @NotBlank @Size(min = 12, max = 128) String password) { }
    public record SessionResponse(String accessToken, UserView user) { }
    public record UserView(java.util.UUID id, String email, boolean platformAdmin) { static UserView from(AppUser user) { return new UserView(user.getId(), user.getEmail(), user.isPlatformAdmin()); } }
}
