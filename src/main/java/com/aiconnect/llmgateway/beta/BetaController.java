package com.aiconnect.llmgateway.beta;

import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.service.IssuedApiKey;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
public class BetaController {
 private final BetaAdministration beta;
 public BetaController(BetaAdministration beta){this.beta=beta;}
 @PostMapping("/api/admin/platform/beta-services") public ServiceView create(@Valid @RequestBody Create request,HttpServletRequest http){platform(http);return view(beta.create(request.organizationId(),request.logicalServiceId()));}
 @PutMapping("/api/admin/platform/beta-services/{id}/operators/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void assign(@PathVariable UUID id,@PathVariable UUID userId,HttpServletRequest http){platform(http);beta.assign(id,userId);}
 @GetMapping("/api/beta/operator/services") public List<ServiceView> list(HttpServletRequest http){if(!isPlatform(http)&&actorId()==null)throw new ApiException(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","Authentication is required.");return beta.list(actorId(),isPlatform(http)).stream().map(BetaController::view).toList();}
 @PostMapping("/api/beta/operator/services/{id}/invitations") public ResponseEntity<BetaAdministration.Invite> invite(@PathVariable UUID id,@Valid @RequestBody Invite request,HttpServletRequest http){operator(id,http);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(beta.invite(id,request.email(),request.expiresAt()));}
 @GetMapping("/api/beta/operator/services/{id}/invitations") public List<BetaAdministration.InvitationView> invitations(@PathVariable UUID id,HttpServletRequest http){operator(id,http);return beta.invitations(id);}
 @PostMapping("/api/beta/operator/services/{id}/invitations/{inviteId}/revoke") @ResponseStatus(HttpStatus.NO_CONTENT) public void revoke(@PathVariable UUID id,@PathVariable UUID inviteId,HttpServletRequest http){operator(id,http);beta.revokeInvitation(id,inviteId);}
 @GetMapping("/api/beta/operator/services/{id}/members") public List<MemberView> members(@PathVariable UUID id,HttpServletRequest http){operator(id,http);return beta.members(id).stream().map(m->new MemberView(m.getId(),m.getServiceId(),m.getProjectId(),m.getEmail(),m.isActive())).toList();}
 @PostMapping("/api/beta/operator/services/{id}/members/{memberId}/suspend") @ResponseStatus(HttpStatus.NO_CONTENT) public void suspend(@PathVariable UUID id,@PathVariable UUID memberId,HttpServletRequest http){operator(id,http);beta.suspend(id,memberId);}
 @PatchMapping("/api/beta/operator/services/{id}/mode") @ResponseStatus(HttpStatus.NO_CONTENT) public void mode(@PathVariable UUID id,@Valid @RequestBody Mode request,HttpServletRequest http){operator(id,http);beta.mode(id,request.mode());}
 @PostMapping("/api/beta/invitations/redeem") public ResponseEntity<IssuedApiKey> redeem(@Valid @RequestBody Redeem request,HttpServletRequest http){if(!Boolean.TRUE.equals(http.getAttribute("aiconnect.beta.ingress")))throw new ApiException(HttpStatus.NOT_FOUND,"BETA_INGRESS_REQUIRED","Beta ingress is unavailable.");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(beta.redeem(request.email(),request.token()));}
 private void operator(UUID id,HttpServletRequest http){if(!isPlatform(http)&&!beta.canOperate(id,actorId()))throw new ApiException(HttpStatus.FORBIDDEN,"BETA_OPERATOR_REQUIRED","Beta operator permission is required.");}
 private UUID actorId(){return CurrentActor.principal().map(AuthPrincipal::userId).orElse(null);}
 private boolean isPlatform(HttpServletRequest http){return Boolean.TRUE.equals(http.getAttribute("aiconnect.platform-admin")) || CurrentActor.principal().map(AuthPrincipal::platformAdmin).orElse(false);}
 private void platform(HttpServletRequest http){if(!isPlatform(http))throw new ApiException(HttpStatus.FORBIDDEN,"PLATFORM_ADMIN_REQUIRED","Platform administrator permission is required.");}
 private static ServiceView view(BetaService s){return new ServiceView(s.getId(),s.getOrganizationId(),s.getLogicalServiceId(),s.getMode());}
 public record Create(@NotNull UUID organizationId,@NotNull UUID logicalServiceId){} public record Invite(@NotBlank @Email @Size(max=320) String email,@NotNull Instant expiresAt){} public record Redeem(@NotBlank @Email @Size(max=320) String email,@NotBlank @Size(max=128) String token){} public record Mode(@NotBlank String mode){}
 public record ServiceView(UUID id,UUID organizationId,UUID logicalServiceId,String mode){} public record MemberView(UUID id,UUID serviceId,UUID projectId,String email,boolean active){}
}
