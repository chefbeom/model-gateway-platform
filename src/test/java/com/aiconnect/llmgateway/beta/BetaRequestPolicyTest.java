package com.aiconnect.llmgateway.beta;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class BetaRequestPolicyTest {
 @Test void boundedOutputAndTextOnly() throws Exception {
  ObjectMapper mapper=new ObjectMapper(); ObjectNode input=mapper.createObjectNode(); BetaRequestPolicy.apply(input); assertThat(input.path("max_tokens").asInt()).isEqualTo(2048);
  var vision=(ObjectNode)mapper.readTree("{\"messages\":[{\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.invalid/x.png\"}}]}]}");
  assertThatThrownBy(()->BetaRequestPolicy.apply(vision)).isInstanceOf(ApiException.class);
  input.put("max_tokens",2049); assertThatThrownBy(()->BetaRequestPolicy.apply(input)).isInstanceOf(ApiException.class);
 }
 @Test void normalizesOutputLimitAndRejectsNonTextParts() throws Exception {
  ObjectMapper mapper=new ObjectMapper();
  var input=(ObjectNode)mapper.readTree("{\"max_completion_tokens\":512,\"max_tokens\":999999,\"messages\":[{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}]}");
  BetaRequestPolicy.apply(input); assertThat(input.path("max_tokens").asInt()).isEqualTo(512); assertThat(input.has("max_completion_tokens")).isFalse();
  var audio=(ObjectNode)mapper.readTree("{\"messages\":[{\"content\":[{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"AA\"}}]}]}");
  assertThatThrownBy(()->BetaRequestPolicy.apply(audio)).isInstanceOf(ApiException.class);
  var deceptive=(ObjectNode)mapper.readTree("{\"messages\":[{\"content\":[{\"type\":\"text\",\"text\":\"hi\",\"file\":\"untrusted\"}]}]}");
  assertThatThrownBy(()->BetaRequestPolicy.apply(deceptive)).isInstanceOf(ApiException.class);
  var huge=(ObjectNode)mapper.readTree("{\"n\":4294967297}");
  assertThatThrownBy(()->BetaRequestPolicy.apply(huge)).isInstanceOf(ApiException.class);
 }
}
