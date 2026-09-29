package com.aiconnect.llmgateway.playground;

import com.aiconnect.llmgateway.admin.DiscoveredRuntimeModel;
import com.aiconnect.llmgateway.admin.LmStudioModelDiscovery;
import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.PlaygroundRequest;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.gateway.RequestCapabilityDetector;
import com.aiconnect.llmgateway.repository.ExternalProviderRepository;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.PlaygroundRequestRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.runtime.InferenceRuntimeClient;
import com.aiconnect.llmgateway.runtime.OpenAiRuntimeClient;
import com.aiconnect.llmgateway.runtime.RuntimeResult;
import com.aiconnect.llmgateway.runtime.RuntimeUnavailableException;
import com.aiconnect.llmgateway.runtime.StreamingLmStudioRuntimeClient;
import com.aiconnect.llmgateway.runtime.StreamingOpenAiRuntimeClient;
import com.aiconnect.llmgateway.runtime.StreamingRuntimeResult;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Direct administrator-only model tests. Only request metadata is retained; prompts and files are not. */
@Service
public class ModelPlaygroundService {
    private static final int MAX_MESSAGES = 48;
    private static final int MAX_ATTACHMENTS = 20;
    private static final int MAX_ATTACHMENT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ATTACHMENT_TOTAL_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PROMPT_CHARS = 180_000;
    private static final int MAX_PDF_PAGES = 100;
    private static final int MAX_PDF_TEXT_CHARS = 120_000;
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "text", "md", "markdown", "json", "csv", "tsv", "xml", "log");

    private final InferenceNodeRepository nodes;
    private final RuntimeEndpointRepository endpoints;
    private final ExternalProviderRepository providers;
    private final ModelDeploymentRepository deployments;
    private final PlaygroundRequestRepository traces;
    private final InferenceRuntimeClient runtimeClient;
    private final OpenAiRuntimeClient openAiClient;
    private final StreamingLmStudioRuntimeClient streamingRuntimeClient;
    private final StreamingOpenAiRuntimeClient streamingOpenAiClient;
    private final LmStudioModelDiscovery modelDiscovery;
    private final SecretCipher cipher;
    private final ObjectMapper mapper;

    public ModelPlaygroundService(InferenceNodeRepository nodes, RuntimeEndpointRepository endpoints,
                                  ExternalProviderRepository providers, ModelDeploymentRepository deployments,
                                  PlaygroundRequestRepository traces, InferenceRuntimeClient runtimeClient,
                                  OpenAiRuntimeClient openAiClient,
                                  StreamingLmStudioRuntimeClient streamingRuntimeClient,
                                  StreamingOpenAiRuntimeClient streamingOpenAiClient,
                                  LmStudioModelDiscovery modelDiscovery,
                                  SecretCipher cipher, ObjectMapper mapper) {
        this.nodes = nodes;
        this.endpoints = endpoints;
        this.providers = providers;
        this.deployments = deployments;
        this.traces = traces;
        this.runtimeClient = runtimeClient;
        this.openAiClient = openAiClient;
        this.streamingRuntimeClient = streamingRuntimeClient;
        this.streamingOpenAiClient = streamingOpenAiClient;
        this.modelDiscovery = modelDiscovery;
        this.cipher = cipher;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<TargetView> targets(UUID organizationId) {
        List<TargetView> result = new ArrayList<>();
        for (InferenceNode node : nodes.findByOrganizationId(organizationId)) {
            for (RuntimeEndpoint endpoint : endpoints.findByNodeId(node.getId())) {
                LiveRuntimeState liveState = endpoint.getRuntimeType() == com.aiconnect.llmgateway.domain.RuntimeType.LLAMA_CPP
                        ? liveLlamaState(endpoint) : null;
                for (ModelDeployment deployment : deployments.findByRuntimeEndpointId(endpoint.getId())) {
                    result.add(view(deployment, endpoint, null, node.getName(), liveState));
                }
            }
        }
        for (ExternalProvider provider : providers.findByOrganizationIdOrderByDisplayNameAsc(organizationId)) {
            for (ModelDeployment deployment : deployments.findByExternalProviderId(provider.getId())) {
                result.add(view(deployment, null, provider, null, null));
            }
        }
        return result.stream().sorted((a, b) -> {
            int provider = a.providerName().compareToIgnoreCase(b.providerName());
            return provider == 0 ? a.displayName().compareToIgnoreCase(b.displayName()) : provider;
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<TraceView> requests(UUID organizationId) {
        return traces.findTop50ByOrganizationIdOrderByStartedAtDesc(organizationId)
                .stream().map(TraceView::from).toList();
    }

    public ProbeView probe(UUID organizationId, UUID targetId) {
        Resolved resolved = resolve(organizationId, targetId);
        long started = System.nanoTime();
        try {
            RuntimeResult response = resolved.external()
                    ? openAiClient.listModels(resolved.provider())
                    : runtimeClient.listModels(resolved.endpoint());
            long latency = elapsed(started);
            if (!response.isSuccessful()) {
                return new ProbeView(false, response.statusCode(), latency, false, 0,
                        "상위 서버가 HTTP " + response.statusCode() + "을 반환했습니다.");
            }
            List<DiscoveredRuntimeModel> models = resolved.external() ? discoverExternalModels(response.body())
                    : modelDiscovery.discover(response.body(), resolved.endpoint().getRuntimeType());
            boolean found = models.stream().anyMatch(model -> resolved.deployment().getProviderModelId().equals(model.providerModelId())
                    || resolved.deployment().getCompatibilityKey().equals(model.compatibilityKey()));
            int count = models.size();
            return new ProbeView(true, response.statusCode(), latency, found, count,
                    found ? null : "연결은 되었지만 등록된 모델 ID가 현재 모델 목록에서 확인되지 않았습니다.");
        } catch (RuntimeUnavailableException exception) {
            return new ProbeView(false, 0, elapsed(started), false, 0, redact(safeMessage(exception), resolved.secret()));
        }
    }

    public ChatResult chat(UUID organizationId, ObjectNode body) {
        UUID targetId = parseUuid(body.path("targetId").asText(null), "PLAYGROUND_TARGET_REQUIRED", "테스트할 모델을 선택하세요.");
        Resolved resolved = resolve(organizationId, targetId);
        if (!resolved.enabled()) throw new ApiException(HttpStatus.CONFLICT, "PLAYGROUND_TARGET_DISABLED", "선택한 Runtime, Provider 또는 모델이 비활성화되어 있습니다.");
        requireLlamaModelLoaded(resolved);

        boolean stream = body.path("stream").asBoolean(false);
        String requestId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        PlaygroundRequest trace = traces.save(new PlaygroundRequest(requestId, organizationId,
                resolved.deployment().getId(), resolved.external() ? "EXTERNAL_PROVIDER" : "RUNTIME",
                resolved.providerName(), resolved.deployment().getProviderModelId(), resolved.endpointUrl(), stream,
                CurrentActor.userIdOrNull()));

        try {
            ObjectNode upstream;
            try {
                upstream = prepareRequest(body, resolved, stream);
            } catch (ApiException exception) {
                traces.save(updateTrace(trace, false, exception.getStatus().value(), elapsed(startedNanos),
                        null, null, exception.getCode()));
                throw exception;
            trace.recordRequestMetadata(RequestCapabilityDetector.requestType(upstream),
                    upstream.path("reasoning_effort").asText(null), upstream.path("service_tier").asText(null));
            traces.save(trace);
            }
            if (stream) return startStream(resolved, upstream, trace, requestId, startedNanos);
            RuntimeResult result = resolved.external()
                    ? openAiClient.chatCompletion(resolved.provider(), upstream, upstream.has("temperature"))
                    : runtimeClient.chatCompletion(resolved.endpoint(), upstream);
            long latency = elapsed(startedNanos);
            Integer input = usage(result.body(), "prompt_tokens", "input_tokens");
            Integer output = usage(result.body(), "completion_tokens", "output_tokens");
            boolean success = result.isSuccessful();
            traces.save(updateTrace(trace, success, result.statusCode(), latency, input, output,
                    success ? null : errorCode(result.body(), result.statusCode())));
            return ChatResult.json(result.statusCode(), requestId, sanitize(result.body(), resolved.secret()));
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeUnavailableException exception) {
            long latency = Duration.between(startedAt, Instant.now()).toMillis();
            traces.save(updateTrace(trace, false, 502, latency, null, null, "UPSTREAM_UNAVAILABLE"));
            return ChatResult.json(502, requestId, error("UPSTREAM_UNAVAILABLE", redact(safeMessage(exception), resolved.secret()), requestId));
        } catch (RuntimeException exception) {
            traces.save(updateTrace(trace, false, 500, elapsed(startedNanos), null, null, "PLAYGROUND_REQUEST_FAILED"));
            throw exception;
        }
    }

    private ChatResult startStream(Resolved resolved, ObjectNode upstream, PlaygroundRequest trace,
                                   String requestId, long startedNanos) {
        StreamingRuntimeResult upstreamResult;
        try {
            // Usage streaming is supported by the OpenAI API but not consistently by local OpenAI-compatible servers.
            if (resolved.external()) upstream.withObject("stream_options").put("include_usage", true);
            upstreamResult = resolved.external()
                    ? streamingOpenAiClient.chatCompletion(resolved.provider(), upstream, upstream.has("temperature"))
                    : streamingRuntimeClient.chatCompletion(resolved.endpoint(), upstream);
        } catch (RuntimeUnavailableException exception) {
            traces.save(updateTrace(trace, false, 502, elapsed(startedNanos), null, null, "UPSTREAM_UNAVAILABLE"));
            return ChatResult.json(502, requestId, error("UPSTREAM_UNAVAILABLE", redact(safeMessage(exception), resolved.secret()), requestId));
        }

        if (upstreamResult.statusCode() < 200 || upstreamResult.statusCode() >= 300) {
            JsonNode errorBody = readErrorBody(upstreamResult.body(), resolved.secret());
            traces.save(updateTrace(trace, false, upstreamResult.statusCode(), elapsed(startedNanos), null, null,
                    errorCode(errorBody, upstreamResult.statusCode())));
            return ChatResult.json(upstreamResult.statusCode(), requestId, sanitize(errorBody, resolved.secret()));
        }

        StreamingResponseBody stream = output -> relayStream(upstreamResult.body(), output, trace,
                startedNanos, resolved.secret());
        return ChatResult.stream(upstreamResult.statusCode(), requestId, stream);
    }

    private void relayStream(InputStream source, OutputStream output, PlaygroundRequest trace,
                             long startedNanos, String secret) throws IOException {
        Integer inputTokens = null;
        Integer outputTokens = null;
        boolean hadUsage = false;
        boolean completed = false;
        String errorCode = null;
        try (InputStream upstream = source;
             java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(upstream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String safeLine = redact(line, secret);
                output.write((safeLine + "\n").getBytes(StandardCharsets.UTF_8));
                if (safeLine.isEmpty()) output.flush();
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) completed = errorCode == null;
                    else {
                        try {
                            JsonNode event = mapper.readTree(data);
                            if (event.hasNonNull("error")) errorCode = errorCode(event, 502);
                            JsonNode usage = event.path("usage");
                            if (!usage.isMissingNode() && !usage.isNull()) {
                                inputTokens = usageValue(usage, "prompt_tokens", "input_tokens");
                                outputTokens = usageValue(usage, "completion_tokens", "output_tokens");
                                hadUsage = inputTokens != null || outputTokens != null;
                            }
                        } catch (Exception ignored) { }
                    }
                }
            }
            output.flush();
        } catch (IOException exception) {
            errorCode = "STREAM_INTERRUPTED";
            throw exception;
        } finally {
            traces.save(updateTrace(trace, completed, completed ? 200 : 502, elapsed(startedNanos),
                    hadUsage ? inputTokens : null, hadUsage ? outputTokens : null,
                    completed ? null : (errorCode == null ? "STREAM_INCOMPLETE" : errorCode)));
        }
    }

    private ObjectNode prepareRequest(ObjectNode body, Resolved resolved, boolean stream) {
        JsonNode incomingMessages = body.path("messages");
        if (!incomingMessages.isArray() || incomingMessages.isEmpty() || incomingMessages.size() > MAX_MESSAGES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYGROUND_MESSAGES_INVALID", "메시지는 1~24개까지 보낼 수 있습니다.");
        }
        ObjectNode request = mapper.createObjectNode();
        request.put("model", resolved.deployment().getProviderModelId());
        request.put("stream", stream);
        ArrayNode messages = request.putArray("messages");
        int charCount = 0;
        int lastUserIndex = -1;
        for (JsonNode message : incomingMessages) {
            String role = message.path("role").asText("");
            if (!Set.of("system", "user", "assistant").contains(role)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYGROUND_MESSAGE_ROLE_INVALID", "system, user, assistant 역할만 사용할 수 있습니다.");
            }
            JsonNode content = message.get("content");
            if (content == null || !content.isTextual()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYGROUND_MESSAGE_CONTENT_INVALID", "메시지 본문은 텍스트로 보내고, 이미지/파일은 첨부 기능으로 추가해 주세요.");
            }
            charCount += content.asText().length();
            ObjectNode copy = messages.addObject().put("role", role);
            copy.set("content", content.deepCopy());
            if ("user".equals(role)) lastUserIndex = messages.size() - 1;
        }
        if (lastUserIndex < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYGROUND_USER_MESSAGE_REQUIRED", "사용자 메시지가 필요합니다.");
        if (charCount > MAX_PROMPT_CHARS) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PLAYGROUND_PROMPT_TOO_LARGE", "대화 텍스트가 너무 깁니다. 새 대화를 시작하거나 내용을 줄여 주세요.");

        JsonNode temperature = body.get("temperature");
        if (temperature != null && !temperature.isNull()) {
            if (!temperature.isNumber() || temperature.asDouble() < 0 || temperature.asDouble() > 2) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYGROUND_TEMPERATURE_INVALID", "Temperature는 0~2 사이여야 합니다.");
            }
            request.set("temperature", temperature.deepCopy());
        }
        JsonNode maxTokens = body.get("maxCompletionTokens");
        if (maxTokens != null && !maxTokens.isNull()) {
            if (!maxTokens.canConvertToInt() || maxTokens.asInt() < 1 || maxTokens.asInt() > 32768) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYGROUND_MAX_TOKENS_INVALID", "최대 출력 토큰은 1~32768 사이여야 합니다.");
            }
            request.put(resolved.external() ? "max_completion_tokens" : "max_tokens", maxTokens.asInt());
        }
        if ("json".equals(body.path("responseMode").asText("text"))) {
            request.set("response_format", mapper.createObjectNode().put("type", "json_object"));
        }

        JsonNode attachments = body.path("attachments");
        if (attachments.isArray() && !attachments.isEmpty()) {
            if (attachments.size() > MAX_ATTACHMENTS) throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYGROUND_TOO_MANY_FILES", "대화 전체에서 최대 20개 파일까지만 참조할 수 있습니다.");
            java.util.Map<Integer, ArrayNode> contentByMessage = new java.util.HashMap<>();
            int totalBytes = 0;
            for (JsonNode file : attachments) {
                String filename = file.path("name").asText("attachment");
                String mediaType = file.path("mediaType").asText("application/octet-stream").toLowerCase(Locale.ROOT);
                int messageIndex = file.has("messageIndex") ? file.path("messageIndex").asInt(-1) : lastUserIndex;
                if (messageIndex < 0 || messageIndex >= messages.size() || !"user".equals(messages.get(messageIndex).path("role").asText())) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYGROUND_FILE_MESSAGE_INVALID", "첨부 파일과 연결된 사용자 메시지를 찾을 수 없습니다.");
                }
                ArrayNode content = contentByMessage.computeIfAbsent(messageIndex, index -> {
                    ArrayNode parts = mapper.createArrayNode();
                    JsonNode oldContent = messages.get(index).path("content");
                    parts.addObject().put("type", "text").put("text", oldContent.isTextual() ? oldContent.asText() : oldContent.toString());
                    return parts;
                });
                byte[] bytes;
                try { bytes = Base64.getDecoder().decode(file.path("base64").asText("")); }
                catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYGROUND_FILE_INVALID", "첨부 파일 인코딩을 읽지 못했습니다."); }
                if (bytes.length == 0 || bytes.length > MAX_ATTACHMENT_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PLAYGROUND_FILE_TOO_LARGE", "각 파일은 1바이트 이상 4MB 이하로 첨부해 주세요.");
                totalBytes += bytes.length;
                if (totalBytes > MAX_ATTACHMENT_TOTAL_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PLAYGROUND_FILES_TOO_LARGE", "첨부 파일 전체 용량은 8MB까지 지원합니다.");
                if (IMAGE_TYPES.contains(mediaType)) {
                    if (!resolved.capabilities().contains("VISION")) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYGROUND_VISION_UNSUPPORTED", "선택한 모델은 AICONNECT에 VISION Capability가 등록되어 있지 않아 이미지 입력을 지원하지 않습니다.");
                    }
                    content.addObject().put("type", "image_url").putObject("image_url")
                            .put("url", "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes));
                } else {
                    String extension = extension(filename);
                    if ("pdf".equals(extension)) {
                        String extracted = extractPdfText(bytes);
                        charCount += extracted.length();
                        if (charCount > MAX_PROMPT_CHARS) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PLAYGROUND_PROMPT_TOO_LARGE", "대화 텍스트와 파일에서 추출한 내용이 너무 깁니다. 파일이나 내용을 줄여 주세요.");
                        content.addObject().put("type", "text").put("text", "[PDF 첨부: " + filename + "]\n" + extracted);
                    } else if (TEXT_EXTENSIONS.contains(extension) || mediaType.startsWith("text/")) {
                        String text = new String(bytes, StandardCharsets.UTF_8);
                        if (text.indexOf('\u0000') >= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYGROUND_TEXT_FILE_INVALID", "텍스트 파일에서 바이너리 데이터를 발견했습니다.");
                        charCount += text.length();
                        if (charCount > MAX_PROMPT_CHARS) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PLAYGROUND_PROMPT_TOO_LARGE", "대화 텍스트와 파일에서 추출한 내용이 너무 깁니다. 파일이나 내용을 줄여 주세요.");
                        content.addObject().put("type", "text").put("text", "[텍스트 파일: " + filename + "]\n" + text);
                    } else {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYGROUND_FILE_TYPE_UNSUPPORTED", "지원 파일은 이미지(JPEG/PNG/WebP/GIF), PDF(텍스트 추출 가능한 문서), TXT/JSON/CSV/MD/XML/LOG입니다.");
                    }
                }
            }
            contentByMessage.forEach((index, content) -> {
                boolean containsImage = false;
                StringBuilder text = new StringBuilder();
                for (JsonNode part : content) {
                    if ("image_url".equals(part.path("type").asText())) containsImage = true;
                    if ("text".equals(part.path("type").asText())) {
                        if (text.length() > 0) text.append('\n');
                        text.append(part.path("text").asText(""));
                    }
                }
                ObjectNode message = (ObjectNode) messages.get(index);
                if (containsImage) message.set("content", content);
                else message.put("content", text.toString());
            });
        }
        if (resolved.external()) resolved.deployment().applyOpenAiDefaults(request);
        return request;
    }

    private String extractPdfText(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.getNumberOfPages() > MAX_PDF_PAGES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PLAYGROUND_PDF_TOO_MANY_PAGES", "PDF는 최대 100페이지까지 테스트할 수 있습니다.");
            String text = new PDFTextStripper().getText(document);
            if (text == null || text.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYGROUND_PDF_TEXT_NOT_FOUND", "PDF에서 텍스트를 추출하지 못했습니다. 스캔 PDF는 OCR 처리 후 이미지로 첨부해 주세요.");
            }
            if (text.length() > MAX_PDF_TEXT_CHARS) text = text.substring(0, MAX_PDF_TEXT_CHARS);
            return text;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYGROUND_PDF_INVALID", "PDF를 읽을 수 없습니다. 파일이 손상되었거나 암호화되었는지 확인하세요.");
        }
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private TargetView view(ModelDeployment deployment, RuntimeEndpoint endpoint, ExternalProvider provider, String nodeName,
                            LiveRuntimeState liveState) {
        boolean external = provider != null;
        String providerName = external ? provider.getDisplayName() : endpoint.getDisplayName();
        String endpointUrl = safeEndpointUrl(external ? provider.getBaseUrl() : endpoint.getBaseUrl());
        String protocol = external ? provider.getProviderType().name()
                : endpoint.getRuntimeType() == null ? "LM_STUDIO" : endpoint.getRuntimeType().name();
        boolean hasModelPricing = deployment.getProviderInputPricePerMillion() != null
                || deployment.getProviderOutputPricePerMillion() != null;
        java.math.BigDecimal inputPrice = deployment.getProviderInputPricePerMillion() != null
                ? deployment.getProviderInputPricePerMillion() : external ? null : endpoint.getInputPricePerMillion();
        java.math.BigDecimal outputPrice = deployment.getProviderOutputPricePerMillion() != null
                ? deployment.getProviderOutputPricePerMillion() : external ? null : endpoint.getOutputPricePerMillion();
        String currency = external
                ? deployment.getProviderPriceCurrency() == null ? null : deployment.getProviderPriceCurrency().name()
                : hasModelPricing ? deployment.getProviderPriceCurrency() == null ? null : deployment.getProviderPriceCurrency().name()
                : endpoint.getCurrency() == null ? null : endpoint.getCurrency().name();
        boolean enabled = deployment.isEnabled() && (external ? provider.isEnabled() : endpoint.isEnabled());
        String loadState = modelLoadState(deployment, endpoint, external, liveState);
        String status = !enabled ? "DISABLED" : external ? "HEALTHY" : loadState;
        return new TargetView(deployment.getId(), external ? provider.getId() : endpoint.getId(),
                external ? "EXTERNAL_PROVIDER" : "RUNTIME", providerName, protocol, endpointUrl,
                deployment.getProviderModelId(), deployment.getDisplayName(), status, enabled,
                external || "LOADED".equals(loadState), deployment.getContextLength(), deployment.getMaxConcurrency(),
                capabilities(deployment), inputPrice, outputPrice,
                currency, nodeName, loadState, enabled && (external || "LOADED".equals(loadState)));
    }

    private LiveRuntimeState liveLlamaState(RuntimeEndpoint endpoint) {
        try {
            RuntimeResult response = runtimeClient.listModels(endpoint);
            if (!response.isSuccessful()) return new LiveRuntimeState(false, Map.of());
            Map<String, DiscoveredRuntimeModel> models = new HashMap<>();
            for (DiscoveredRuntimeModel model : modelDiscovery.discover(response.body(), endpoint.getRuntimeType())) {
                models.put(model.providerModelId(), model);
            }
            return new LiveRuntimeState(true, Map.copyOf(models));
        } catch (RuntimeException ignored) {
            return new LiveRuntimeState(false, Map.of());
        }
    }

    private String modelLoadState(ModelDeployment deployment, RuntimeEndpoint endpoint, boolean external,
                                 LiveRuntimeState liveState) {
        if (external) return "LOADED";
        if (endpoint == null) return "UNKNOWN";
        if (endpoint.getRuntimeType() == com.aiconnect.llmgateway.domain.RuntimeType.LLAMA_CPP) {
            try {
                if (liveState == null || !liveState.reachable()) return "UNAVAILABLE";
                DiscoveredRuntimeModel liveModel = liveState.models().get(deployment.getProviderModelId());
                if (liveModel == null) return "NOT_FOUND";
                JsonNode metadata = mapper.readTree(liveModel.metadataJson() == null ? "{}" : liveModel.metadataJson());
                String state = metadata.path("status").path("value").asText("").toLowerCase(Locale.ROOT);
                if (state.equals("loaded") && liveModel.loaded()) return "LOADED";
                if (state.equals("unloaded")) return "UNLOADED";
                if (state.equals("loading")) return "LOADING";
                if (state.equals("downloading")) return "DOWNLOADING";
                if (state.equals("sleeping")) return "SLEEPING";
                if (state.equals("failed")) return "FAILED";
            } catch (Exception ignored) { }
            return "UNKNOWN";
        }
        return deployment.isLoaded() ? "LOADED" : "UNLOADED";
    }

    private void requireLlamaModelLoaded(Resolved resolved) {
        if (resolved.external() || resolved.endpoint().getRuntimeType() != com.aiconnect.llmgateway.domain.RuntimeType.LLAMA_CPP) return;
        try {
            RuntimeResult response = runtimeClient.listModels(resolved.endpoint());
            if (!response.isSuccessful()) throw new ApiException(HttpStatus.BAD_GATEWAY, "PLAYGROUND_RUNTIME_UNAVAILABLE",
                    "llama.cpp 서버에서 현재 모델 상태를 확인하지 못했습니다. 연결을 확인한 뒤 다시 시도하세요.");
            boolean loaded = modelDiscovery.discover(response.body(), resolved.endpoint().getRuntimeType()).stream()
                    .anyMatch(model -> resolved.deployment().getProviderModelId().equals(model.providerModelId()) && model.loaded());
            if (!loaded) throw new ApiException(HttpStatus.CONFLICT, "PLAYGROUND_MODEL_NOT_LOADED",
                    "선택한 모델은 llama.cpp 서버에서 현재 로드된 상태가 아닙니다. 모델을 로드한 뒤 모델 동기화를 실행하고 다시 테스트하세요.");
        } catch (RuntimeUnavailableException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PLAYGROUND_RUNTIME_UNAVAILABLE",
                    "llama.cpp 서버에 연결할 수 없어 현재 모델 상태를 확인하지 못했습니다.");
        }
    }

    private List<String> capabilities(ModelDeployment deployment) {
        String json = deployment.getCapabilityOverridesJson();
        if (json == null || json.isBlank() || "[]".equals(json.trim())) json = deployment.getCapabilitiesJson();
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode parsed = mapper.readTree(json);
            if (!parsed.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            for (JsonNode value : parsed) if (value.isTextual()) result.add(value.asText().toUpperCase(Locale.ROOT));
            return List.copyOf(result);
        } catch (Exception ignored) { return List.of(); }
    }

    private Resolved resolve(UUID organizationId, UUID targetId) {
        ModelDeployment deployment = deployments.findById(targetId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAYGROUND_TARGET_NOT_FOUND", "등록된 모델을 찾을 수 없습니다."));
        if (deployment.isExternal()) {
            ExternalProvider provider = providers.findById(deployment.getExternalProviderId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAYGROUND_PROVIDER_NOT_FOUND", "등록된 Provider를 찾을 수 없습니다."));
            if (!organizationId.equals(provider.getOrganizationId())) throw forbidden();
            return new Resolved(deployment, null, provider, provider.getDisplayName(), safeEndpointUrl(provider.getBaseUrl()),
                    deployment.isEnabled() && provider.isEnabled(), true, capabilities(deployment), cipher.decrypt(provider.getEncryptedApiKey()));
        }
        RuntimeEndpoint endpoint = endpoints.findById(deployment.getRuntimeEndpointId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAYGROUND_RUNTIME_NOT_FOUND", "등록된 Runtime Endpoint를 찾을 수 없습니다."));
        InferenceNode node = nodes.findById(endpoint.getNodeId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAYGROUND_NODE_NOT_FOUND", "등록된 Runtime 노드를 찾을 수 없습니다."));
        if (!organizationId.equals(node.getOrganizationId())) throw forbidden();
        return new Resolved(deployment, endpoint, null, endpoint.getDisplayName(), safeEndpointUrl(endpoint.getBaseUrl()),
                deployment.isEnabled() && endpoint.isEnabled(), false, capabilities(deployment), cipher.decrypt(endpoint.getApiToken()));
    }

    private ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN, "PLAYGROUND_ORGANIZATION_MISMATCH", "해당 모델은 선택한 조직에 속하지 않습니다."); }

    private String safeEndpointUrl(String value) {
        if (value == null) return "";
        String withoutUserInfo = value.replaceAll("(?<=://)[^/@]+@", "[REDACTED]@");
        return withoutUserInfo.replaceAll("(?i)([?&](?:api[_-]?key|access[_-]?token|client[_-]?secret|authorization|token|password|secret|key)=)[^&#]*", "$1[REDACTED]");
    }

    private UUID parseUuid(String value, String code, String message) {
        try { return UUID.fromString(value); }
        catch (Exception ignored) { throw new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    }

    private Integer usage(JsonNode body, String primary, String alternative) {
        return usageValue(body.path("usage"), primary, alternative);
    }

    private Integer usageValue(JsonNode usage, String primary, String alternative) {
        JsonNode value = usage.path(primary);
        if (value.canConvertToInt()) return Math.max(0, value.asInt());
        value = usage.path(alternative);
        return value.canConvertToInt() ? Math.max(0, value.asInt()) : null;
    }

    private List<DiscoveredRuntimeModel> discoverExternalModels(JsonNode body) {
        List<DiscoveredRuntimeModel> result = new ArrayList<>();
        JsonNode data = body.path("data");
        if (!data.isArray()) return result;
        for (JsonNode model : data) {
            String id = model.path("id").asText("").trim();
            if (!id.isBlank()) result.add(new DiscoveredRuntimeModel(id, id, id, null, null,
                    null, true, 1, "[]", null));
        }
        return result;
    }

    private String errorCode(JsonNode body, int status) {
        String code = body.path("error").path("code").asText(null);
        if (code == null || code.isBlank()) code = body.path("code").asText(null);
        return code == null || code.isBlank() ? "HTTP_" + status : code;
    }

    private ObjectNode error(String code, String message, String requestId) {
        ObjectNode error = mapper.createObjectNode().put("message", message).put("type", "playground_error").put("code", code);
        ObjectNode result = mapper.createObjectNode().put("id", requestId);
        result.set("error", error);
        return result;
    }

    private JsonNode readErrorBody(InputStream input, String secret) {
        try (InputStream body = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            body.transferTo(new BoundedOutputStream(bytes, 64 * 1024));
            return sanitize(mapper.readTree(bytes.toByteArray()), secret);
        } catch (Exception exception) {
            return mapper.createObjectNode().put("error", "Provider returned a non-JSON error response.");
        }
    }

    private JsonNode sanitize(JsonNode body, String secret) {
        if (body == null) return mapper.createObjectNode();
        if (body.isTextual()) return mapper.getNodeFactory().textNode(redact(body.asText(), secret));
        if (body.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            body.forEach(item -> copy.add(sanitize(item, secret)));
            return copy;
        }
        if (body.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            body.fields().forEachRemaining(entry -> copy.set(entry.getKey(), sanitize(entry.getValue(), secret)));
            return copy;
        }
        return body.deepCopy();
    }

    private String redact(String text, String secret) {
        if (text == null || secret == null || secret.isBlank()) return text;
        return text.replace(secret, "[REDACTED]");
    }

    private String safeMessage(RuntimeUnavailableException exception) {
        Throwable cause = exception.getCause();
        String detail = cause == null ? null : cause.getMessage();
        if (detail == null || detail.isBlank()) return exception.getMessage() == null ? "상위 AI 서버에 연결할 수 없습니다." : exception.getMessage();
        return (exception.getMessage() == null ? "상위 AI 서버에 연결할 수 없습니다." : exception.getMessage()) + " " + detail;
    }

    private long elapsed(long startedNanos) { return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(); }

    private PlaygroundRequest updateTrace(PlaygroundRequest trace, boolean success, int status, long latency,
                                          Integer input, Integer output, String errorCode) {
        trace.complete(success, status, latency, input, output, errorCode);
        return trace;
    }

    public record TargetView(UUID id, UUID sourceId, String targetType, String providerName, String protocol, String endpointUrl,
                             String modelId, String displayName, String status, boolean enabled, boolean loaded,
                             Integer contextLength, int maxConcurrency, List<String> capabilities,
                             java.math.BigDecimal inputPricePerMillion, java.math.BigDecimal outputPricePerMillion,
                             String currency, String nodeName, String loadState, boolean canChat) { }

    public record ProbeView(boolean reachable, int httpStatus, long latencyMs, boolean modelAvailable,
                            int modelCount, String message) { }

    public record TraceView(String requestId, String source, String targetType, String targetName, String modelId,
                            String endpointUrl, String status, boolean stream, Integer httpStatus, Long latencyMs,
                            Integer inputTokens, Integer outputTokens, String errorCode, Instant startedAt) {
        static TraceView from(PlaygroundRequest item) {
            return new TraceView(item.getRequestId(), item.getSource(), item.getTargetType(), item.getTargetName(),
                    item.getModelId(), item.getEndpointUrl(), item.getStatus(), item.isStream(), item.getHttpStatus(),
                    item.getLatencyMs(), item.getInputTokens(), item.getOutputTokens(), item.getErrorCode(), item.getStartedAt());
        }
    }

    public record ChatResult(int statusCode, String requestId, ObjectNode body, StreamingResponseBody streaming) {
        static ChatResult json(int statusCode, String requestId, JsonNode body) {
            ObjectNode object;
            if (body != null && body.isObject()) object = (ObjectNode) body;
            else { object = new ObjectMapper().createObjectNode(); object.set("response", body); }
            return new ChatResult(statusCode, requestId, object, null);
        }
        static ChatResult stream(int statusCode, String requestId, StreamingResponseBody stream) { return new ChatResult(statusCode, requestId, null, stream); }
    }

    private record Resolved(ModelDeployment deployment, RuntimeEndpoint endpoint, ExternalProvider provider,
                            String providerName, String endpointUrl, boolean enabled, boolean external,
                            List<String> capabilities, String secret) { }

    private record LiveRuntimeState(boolean reachable, Map<String, DiscoveredRuntimeModel> models) { }

    private static final class BoundedOutputStream extends java.io.OutputStream {
        private final ByteArrayOutputStream target;
        private final int maxBytes;
        private int written;
        private BoundedOutputStream(ByteArrayOutputStream target, int maxBytes) { this.target = target; this.maxBytes = maxBytes; }
        @Override public void write(int value) { if (written < maxBytes) { target.write(value); written++; } }
        @Override public void write(byte[] bytes, int offset, int length) {
            int accepted = Math.min(length, maxBytes - written);
            if (accepted > 0) { target.write(bytes, offset, accepted); written += accepted; }
        }
    }
}
