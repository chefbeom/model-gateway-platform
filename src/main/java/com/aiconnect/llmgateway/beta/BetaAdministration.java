package com.aiconnect.llmgateway.beta;

import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.ProjectServiceAccess;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.IssuedApiKey;
import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class BetaAdministration {
 private final BetaServiceRepository services; private final BetaInvitationRepository invitations; private final BetaMemberRepository members;
 private final ProjectRepository projects; private final LlmServiceRepository logical; private final ProjectServiceAccessRepository access;
 private final ApiKeyService keys; private final EntityManager em; private final AuditService audit; private final String pepper; private final BetaOperatorRepository operators; private final com.aiconnect.llmgateway.scheduling.SchedulingPolicyService scheduling;
 @org.springframework.beans.factory.annotation.Autowired(required = false) private OrganizationRepository organizations;
 private static final SecureRandom RANDOM=new SecureRandom();
 public BetaAdministration(BetaServiceRepository s,BetaInvitationRepository i,BetaMemberRepository m,ProjectRepository p,LlmServiceRepository l,ProjectServiceAccessRepository a,ApiKeyService k,EntityManager em,AuditService audit,com.aiconnect.llmgateway.config.GatewayProperties config,BetaOperatorRepository operators,com.aiconnect.llmgateway.scheduling.SchedulingPolicyService scheduling){services=s;invitations=i;members=m;projects=p;logical=l;access=a;keys=k;this.em=em;this.audit=audit;pepper=config.apiKeyPepper();this.operators=operators;this.scheduling=scheduling;}
 @Transactional public BetaService create(UUID org,UUID model){var llm=logical.findById(model).orElseThrow(()->notFound()); if(!llm.getOrganizationId().equals(org) || !llm.isEnabled() || llm.isDeleted()) throw notFound(); var result=services.save(new BetaService(org,model)); audit.record(org,CurrentActor.userIdOrNull(),"BETA_CREATED","BETA_SERVICE",result.getId(),Map.of());return result;}
 public BetaService require(UUID id){return services.findById(id).orElseThrow(()->notFound());}
 public boolean canOperate(UUID service,UUID actor){return actor!=null && operators.existsById(new BetaOperator.Key(service,actor));}
 public List<BetaService> list(UUID actor,boolean platform){return services.findAll().stream().filter(s->platform||(canOperate(s.getId(),actor)&&isOrganizationActive(s.getOrganizationId()))).toList();}
 @Transactional public void assign(UUID id,UUID user){var service=require(id); if(((Number)em.createNativeQuery("select count(*) from app_user where id = :id and enabled = true").setParameter("id",user.toString()).getSingleResult()).longValue()!=1) throw notFound(); if(!canOperate(id,user)){operators.save(new BetaOperator(id,user));audit.record(service.getOrganizationId(),CurrentActor.userIdOrNull(),"BETA_OPERATOR_ASSIGNED","BETA_SERVICE",id,Map.of("userId",user.toString()));}}
 @Transactional public void mode(UUID id,String mode){if(!Set.of("PAUSED","LOCAL_ONLY").contains(mode))throw new ApiException(HttpStatus.BAD_REQUEST,"BETA_MODE_INVALID","Only PAUSED and LOCAL_ONLY are supported.");var s=require(id);requireActiveOrganization(s.getOrganizationId());if("LOCAL_ONLY".equals(mode) && scheduling.settings(s.getLogicalServiceId()).mode()!=com.aiconnect.llmgateway.scheduling.SchedulingMode.LOCAL_ONLY_QUEUE)throw new ApiException(HttpStatus.CONFLICT,"BETA_QUEUE_REQUIRED","Configure bounded local-only scheduling before enabling beta.");s.setMode(mode);audit.record(s.getOrganizationId(),CurrentActor.userIdOrNull(),"BETA_MODE_CHANGED","BETA_SERVICE",id,Map.of("mode",mode));}
 @Transactional public Invite invite(UUID service,String email,Instant expiry){var s=require(service);requireActiveOrganization(s.getOrganizationId());String normalized=normalize(email);if(expiry==null||!expiry.isAfter(Instant.now())||expiry.isAfter(Instant.now().plusSeconds(7*86400))) throw new ApiException(HttpStatus.BAD_REQUEST,"BETA_INVITATION_EXPIRY_INVALID","Invitation expiry must be within seven days.");if(members.existsByServiceIdAndEmail(service,normalized))throw new ApiException(HttpStatus.CONFLICT,"BETA_MEMBER_EXISTS","Member already exists."); byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);String token=HexFormat.of().formatHex(bytes);var inv=invitations.save(new BetaInvitation(service,normalized,hash(token),expiry));audit.record(s.getOrganizationId(),CurrentActor.userIdOrNull(),"BETA_INVITED","BETA_INVITATION",inv.id,Map.of());return new Invite(inv.id,token,expiry);}
 @Transactional public IssuedApiKey redeem(String email,String token){String normalized=normalize(email);var inv=invitations.findLockedByHash(hash(token)).filter(i->i.consumedAt==null&&i.revokedAt==null&&i.expiresAt.isAfter(Instant.now())&&i.normalizedEmail.equals(normalized)).orElseThrow(()->new ApiException(HttpStatus.BAD_REQUEST,"BETA_INVITATION_INVALID","Invitation is invalid or expired.")); var service=require(inv.serviceId); if(!isOrganizationActive(service.getOrganizationId())) throw new ApiException(HttpStatus.FORBIDDEN,"ORGANIZATION_SUSPENDED","The organization is suspended."); if(members.existsByServiceIdAndEmail(service.getId(),normalized))throw new ApiException(HttpStatus.CONFLICT,"BETA_MEMBER_EXISTS","Member already exists.");var project=new Project(service.getOrganizationId(),"Beta "+UUID.randomUUID());project.configureExternalAiBlocked(true);project=projects.save(project);access.save(new ProjectServiceAccess(project.getId(),service.getLogicalServiceId()));members.save(new BetaMember(service.getId(),project.getId(),normalized));inv.consumedAt=Instant.now();return keys.issue(project.getId(),"Beta key",Instant.now().plusSeconds(90L*86400));}
 @Transactional public void suspend(UUID service,UUID member){var s=require(service);requireActiveOrganization(s.getOrganizationId());var m=members.findById(member).filter(x->x.getServiceId().equals(service)).orElseThrow(()->notFound());m.suspend();audit.record(s.getOrganizationId(),CurrentActor.userIdOrNull(),"BETA_MEMBER_SUSPENDED","BETA_MEMBER",member,Map.of());}
 public List<BetaMember> members(UUID service){var s=require(service);requireActiveOrganization(s.getOrganizationId());return members.findByServiceId(service);}
 public List<InvitationView> invitations(UUID service){var s=require(service);requireActiveOrganization(s.getOrganizationId());Instant now=Instant.now();return invitations.findByServiceId(service).stream().map(i->new InvitationView(i.id,i.normalizedEmail,i.expiresAt,i.consumedAt!=null?"CONSUMED":i.revokedAt!=null?"REVOKED":!i.expiresAt.isAfter(now)?"EXPIRED":"PENDING")).toList();}
 @Transactional public void revokeInvitation(UUID service,UUID id){var s=require(service);requireActiveOrganization(s.getOrganizationId());var i=invitations.findLocked(id,service).orElseThrow(()->notFound());if(i.consumedAt!=null)throw new ApiException(HttpStatus.CONFLICT,"BETA_INVITATION_USED","Invitation has already been consumed.");if(i.revokedAt==null)i.revokedAt=Instant.now();audit.record(s.getOrganizationId(),CurrentActor.userIdOrNull(),"BETA_INVITATION_REVOKED","BETA_INVITATION",id,Map.of());}
 private boolean isOrganizationActive(UUID organizationId){return organizations==null || organizations.findById(organizationId).map(o->"ACTIVE".equalsIgnoreCase(o.getStatus())).orElse(false);}
 private void requireActiveOrganization(UUID organizationId){boolean platform=CurrentActor.principal().map(AuthPrincipal::platformAdmin).orElse(false);if(!platform && !isOrganizationActive(organizationId)) throw new ApiException(HttpStatus.FORBIDDEN,"ORGANIZATION_SUSPENDED","The organization is suspended.");}
 private String normalize(String value){if(value==null||value.isBlank()||value.length()>320)throw new ApiException(HttpStatus.BAD_REQUEST,"BETA_EMAIL_INVALID","Email is required.");return value.trim().toLowerCase(Locale.ROOT);}
 private String hash(String value){try{if(value==null||value.length()>128)throw new IllegalArgumentException();Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new ApiException(HttpStatus.BAD_REQUEST,"BETA_INVITATION_INVALID","Invitation is invalid or expired.");}}
 private ApiException notFound(){return new ApiException(HttpStatus.NOT_FOUND,"BETA_NOT_FOUND","Resource was not found.");}
 public record Invite(UUID id,String token,Instant expiresAt){}
 public record InvitationView(UUID id,String email,Instant expiresAt,String status){}
}
