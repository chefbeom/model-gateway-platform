package com.aiconnect.llmgateway.beta;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.UUID;
@Service
public class BetaAccessService {
 private final BetaMemberRepository members; private final BetaServiceRepository services;
 @org.springframework.beans.factory.annotation.Autowired
 private com.aiconnect.llmgateway.scheduling.SchedulingPolicyService scheduling;
 public BetaAccessService(BetaMemberRepository members,BetaServiceRepository services){this.members=members;this.services=services;}
 public boolean isBetaProject(UUID id){return members.findByProjectId(id).isPresent();}
 public void requirePublicBetaProject(UUID id){if(!isBetaProject(id)) throw new ApiException(HttpStatus.FORBIDDEN,"BETA_KEY_REQUIRED","A beta API key is required."); checkActiveProject(id);}
 public void checkActiveProject(UUID project){var member=members.findByProjectId(project).orElseThrow(()->denied());var service=services.findById(member.getServiceId()).orElseThrow(()->denied());if(!member.isActive() || !"LOCAL_ONLY".equals(service.getMode()) || !queueEnabled(service))throw denied();}
 public void checkProject(UUID project,UUID logicalService){members.findByProjectId(project).ifPresent(member->{
  BetaService service=services.findById(member.getServiceId()).orElseThrow(()->denied());
  if(!member.isActive() || !"LOCAL_ONLY".equals(service.getMode()) || !service.getLogicalServiceId().equals(logicalService) || !queueEnabled(service)) throw denied();
 });}
 private boolean queueEnabled(BetaService service){return scheduling != null && scheduling.settings(service.getLogicalServiceId()).mode()==com.aiconnect.llmgateway.scheduling.SchedulingMode.LOCAL_ONLY_QUEUE;}
 private ApiException denied(){return new ApiException(HttpStatus.FORBIDDEN,"BETA_ACCESS_DISABLED","Beta access is unavailable.");}
}
