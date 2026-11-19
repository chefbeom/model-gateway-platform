package com.aiconnect.llmgateway.beta;
import com.aiconnect.llmgateway.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BetaAccessServiceTest {
 @Test void defaultPausedAndInactiveCannotAccessAndNonBetaCannotUsePublic(){
  var members=mock(BetaMemberRepository.class);var services=mock(BetaServiceRepository.class);var guard=new BetaAccessService(members,services);
  var project=UUID.randomUUID();var model=UUID.randomUUID();var beta=new BetaService(UUID.randomUUID(),model);var id=UUID.randomUUID();ReflectionTestUtils.setField(beta,"id",id);var member=new BetaMember(id,project,"test@example.invalid");
  var scheduling=mock(com.aiconnect.llmgateway.scheduling.SchedulingPolicyService.class);
  when(scheduling.settings(model)).thenReturn(new com.aiconnect.llmgateway.scheduling.SchedulingSettings(com.aiconnect.llmgateway.scheduling.SchedulingMode.LOCAL_ONLY_QUEUE,0,5000,10,1024,1));
  ReflectionTestUtils.setField(guard,"scheduling",scheduling);
  when(members.findByProjectId(project)).thenReturn(Optional.of(member));when(services.findById(id)).thenReturn(Optional.of(beta));
  assertThatThrownBy(()->guard.checkProject(project,model)).isInstanceOf(ApiException.class);
  beta.setMode("LOCAL_ONLY");guard.checkProject(project,model);
  assertThatThrownBy(()->guard.checkProject(project,UUID.randomUUID())).isInstanceOf(ApiException.class);
  member.suspend();assertThatThrownBy(()->guard.checkProject(project,model)).isInstanceOf(ApiException.class);
  var other=UUID.randomUUID();guard.checkProject(other,model);assertThatThrownBy(()->guard.requirePublicBetaProject(other)).isInstanceOf(ApiException.class);
 }
}
