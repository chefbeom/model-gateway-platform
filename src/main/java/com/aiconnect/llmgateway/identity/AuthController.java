package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String REFRESH_COOKIE = "aiconnect_refresh";
    private final IdentityService identity;
    private final TotpService totpService;
    private final AccountInvitationService invitations;
    private final AuthProperties properties;

    public AuthController(IdentityService identity, TotpService totpService,
                          AccountInvitationService invitations, AuthProperties properties) {
        this.identity = identity;
        this.totpService = totpService;
        this.invitations = invitations;
        this.properties = properties;
    }

    @PostMapping("/bootstrap")
    public ResponseEntity<SessionResponse> bootstrap(@Valid @RequestBody Credentials request) {
        identity.bootstrap(request.email(), request.password());
        return toResponse(identity.login(request.email(), request.password()));
    }

    @PostMapping("/login")
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request) {
        return toResponse(identity.login(request.email(), request.password(), request.totpCode(), request.recoveryCode()));
    }

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

    @PostMapping("/totp/setup")
    public ResponseEntity<TotpService.TotpSetup> setupTotp(HttpServletRequest request) {
        SessionContext ctx = requireAuthenticatedSession(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(totpService.beginSetup(ctx.userId()));
    }

    @PostMapping("/totp/enroll")
    public ResponseEntity<TotpEnrollResponse> enrollTotp(@Valid @RequestBody TotpCodeRequest body, HttpServletRequest request) {
        SessionContext ctx = requireAuthenticatedSession(request);
        IdentityService.EnrollmentResult result = identity.enrollTotp(ctx.userId(), ctx.sessionId(), body.code());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.session().refreshToken()).toString())
                .body(new TotpEnrollResponse(toSessionBody(result.session()), result.recoveryCodes()));
    }

    @PostMapping("/totp/verify")
    public ResponseEntity<SessionResponse> verifyTotp(@Valid @RequestBody TotpCodeRequest body, HttpServletRequest request) {
        SessionContext ctx = requireAuthenticatedSession(request);
        IdentityService.Session session = identity.verifyTotp(ctx.userId(), ctx.sessionId(), body.code());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                .body(toSessionBody(session));
    }

    @PostMapping("/invitations/inspect")
    public ResponseEntity<AccountInvitationService.InspectInvitationResponse> inspectInvitation(
            @Valid @RequestBody InspectInvitationRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(invitations.inspect(request.code()));
    }

    @PostMapping("/invitations/accept")
    public ResponseEntity<SessionResponse> acceptInvitation(@Valid @RequestBody AcceptInvitationRequest request) {
        return toResponse(invitations.accept(request.code(), request.email(), request.password()));
    }

    private SessionContext requireAuthenticatedSession(HttpServletRequest request) {
        AuthPrincipal principal = CurrentActor.principal().orElse(null);
        Object rawSessionId = request.getAttribute("aiconnect.session-id");
        UUID sessionId = rawSessionId instanceof UUID id ? id : null;
        if (principal == null || sessionId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "An authenticated session is required.");
        }
        return new SessionContext(principal.userId(), sessionId);
    }

    private ResponseEntity<SessionResponse> toResponse(IdentityService.Session session) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                .body(toSessionBody(session));
    }

    private SessionResponse toSessionBody(IdentityService.Session session) {
        return new SessionResponse(session.accessToken(), UserView.from(session.user()), session.mfaState());
    }

    private ResponseCookie refreshCookie(String token) {
        return ResponseCookie.from(REFRESH_COOKIE, token).httpOnly(true).secure(properties.cookieSecure())
                .sameSite("Strict").path("/api/auth").maxAge(properties.refreshTokenSeconds()).build();
    }

    private ResponseCookie expiredCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "").httpOnly(true).secure(properties.cookieSecure())
                .sameSite("Strict").path("/api/auth").maxAge(0).build();
    }

    private record SessionContext(UUID userId, UUID sessionId) { }

    public record Credentials(@NotBlank @Email @Size(max = 320) String email, @NotBlank @Size(min = 12, max = 128) String password) { }
    public record LoginRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @Size(max = 32) String totpCode,
            @Size(max = 32) String recoveryCode
    ) { }
    public record TotpCodeRequest(@NotBlank @Size(max = 32) String code) { }
    public record InspectInvitationRequest(@NotBlank @Size(max = 128) String code) { }
    public record AcceptInvitationRequest(
            @NotBlank @Size(max = 128) String code,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 128) String password
    ) { }
    public record SessionResponse(String accessToken, UserView user, String mfaState) { }
    public record TotpEnrollResponse(SessionResponse session, List<String> recoveryCodes) { }
    public record UserView(UUID id, String email, boolean platformAdmin, String accountTier, boolean totpEnabled) {
        public static UserView from(AppUser user) {
            return new UserView(
                    user.getId(),
                    user.getEmail(),
                    user.isPlatformAdmin(),
                    AccountTier.effectiveFor(user).name(),
                    user.isTotpEnabled()
            );
        }
    }
}
