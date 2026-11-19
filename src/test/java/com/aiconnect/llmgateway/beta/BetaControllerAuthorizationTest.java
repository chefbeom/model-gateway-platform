package com.aiconnect.llmgateway.beta;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class BetaControllerAuthorizationTest {
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 @Test void onlyAssignedOperatorCanChangeServiceMode(){
  var admin=mock(BetaAdministration.class);var controller=new BetaController(admin);var actor=UUID.randomUUID();var owned=UUID.randomUUID();var other=UUID.randomUUID();
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthPrincipal(actor,"operator@example.invalid",false),null,List.of()));
  when(admin.canOperate(owned,actor)).thenReturn(true);var request=new MockHttpServletRequest();
  controller.mode(owned,new BetaController.Mode("LOCAL_ONLY"),request);verify(admin).mode(owned,"LOCAL_ONLY");
  assertThatThrownBy(()->controller.mode(other,new BetaController.Mode("LOCAL_ONLY"),request)).isInstanceOf(ApiException.class);verify(admin,never()).mode(other,"LOCAL_ONLY");
 }
}
