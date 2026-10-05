package com.aiconnect.llmgateway.playground;

import com.aiconnect.llmgateway.playground.ModelPlaygroundService.ChatResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ModelPlaygroundControllerStreamingTest {
    private final ModelPlaygroundService playground = mock(ModelPlaygroundService.class);
    private final MockMvc mvc = standaloneSetup(new ModelPlaygroundController(playground)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void streamResponseIsHandledAsAsyncSseInsteadOfJsonMessageConversion() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String eventStream = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"
                + "data: [DONE]\n\n";
        UUID conversationId = UUID.randomUUID();
        StreamingResponseBody upstream = output -> output.write(eventStream.getBytes(StandardCharsets.UTF_8));
        when(playground.chat(eq(organizationId), any(ObjectNode.class)))
                .thenReturn(ChatResult.stream(200, "playground-stream-id", upstream, conversationId));

        MvcResult started = mvc.perform(post("/api/admin/organizations/{organizationId}/playground/chat", organizationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":\"" + targetId + "\",\"stream\":true}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "playground-stream-id"))
                .andExpect(header().string("X-Playground-Conversation-Id", conversationId.toString()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(eventStream));
    }

    @Test
    void jsonErrorResultStillUsesStreamingBodyHandlerAndPreservesHttpStatus() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ObjectNode error = mapper.createObjectNode();
        error.putObject("error").put("code", "UPSTREAM_REJECTED").put("message", "unsupported request");
        when(playground.chat(eq(organizationId), any(ObjectNode.class)))
                .thenReturn(new ChatResult(400, "playground-error-id", error, null));

        MvcResult started = mvc.perform(post("/api/admin/organizations/{organizationId}/playground/chat", organizationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":\"" + targetId + "\",\"stream\":true}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "playground-error-id"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(error.toString()));
    }
}
