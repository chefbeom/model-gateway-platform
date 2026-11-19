package com.aiconnect.llmgateway.beta;

import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.service.ApiKeyCredentials;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BetaIngressFilterTest {
    @Test void markerIsAuthenticatedAndOrdinaryPathsAreDenied() throws Exception {
        var keys=mock(ApiKeyService.class); var beta=mock(BetaAccessService.class);
        var filter=new BetaIngressFilter("a-long-random-secret-over-32-characters",keys,beta,new ObjectMapper());
        var forged=new MockHttpServletRequest("GET","/v1/models"); forged.addHeader("X-AIConnect-Beta-Ingress","bad");
        var response=new MockHttpServletResponse(); filter.doFilter(forged,response,new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403); verifyNoInteractions(keys);
        var admin=new MockHttpServletRequest("GET","/api/admin/organizations"); admin.addHeader("X-AIConnect-Beta-Ingress","a-long-random-secret-over-32-characters");
        response=new MockHttpServletResponse(); filter.doFilter(admin,response,new MockFilterChain()); assertThat(response.getStatus()).isEqualTo(404);
    }
    @Test void validApiRequiresAnActiveBetaProject() throws Exception {
        var keys=mock(ApiKeyService.class); var beta=mock(BetaAccessService.class); var project=mock(Project.class); UUID id=UUID.randomUUID(); when(project.getId()).thenReturn(id);
        when(keys.authenticate("Bearer test")).thenReturn(new ApiKeyCredentials(null,project));
        var request=new MockHttpServletRequest("GET","/v1/models"); request.addHeader("X-AIConnect-Beta-Ingress","a-long-random-secret-over-32-characters"); request.addHeader("Authorization","Bearer test");
        var response=new MockHttpServletResponse(); new BetaIngressFilter("a-long-random-secret-over-32-characters",keys,beta,new ObjectMapper()).doFilter(request,response,new MockFilterChain());
        verify(beta).requirePublicBetaProject(id); verify(beta).checkActiveProject(id); assertThat(request.getAttribute("aiconnect.beta.ingress")).isEqualTo(true);
    }
}
