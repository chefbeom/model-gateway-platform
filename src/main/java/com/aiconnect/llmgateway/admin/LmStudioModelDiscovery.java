package com.aiconnect.llmgateway.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class LmStudioModelDiscovery {
    private final ObjectMapper objectMapper;

    public LmStudioModelDiscovery(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<DiscoveredRuntimeModel> discover(JsonNode body) {
        if (body == null) return List.of();
        if (body.path("models").isArray()) return discoverNativeV1(body.path("models"));
        if (body.path("data").isArray()) return discoverCompatibleList(body.path("data"));
        return List.of();
    }

    private List<DiscoveredRuntimeModel> discoverNativeV1(JsonNode models) {
        List<DiscoveredRuntimeModel> discovered = new ArrayList<>();
        for (JsonNode model : models) {
            String key = text(model, "key", null);
            if (key == null || key.isBlank()) continue;
            String displayName = text(model, "display_name", key);
            String family = text(model, "architecture", null);
            String quantization = text(model.path("quantization"), "name", text(model, "selected_variant", null));
            Set<String> capabilities = nativeCapabilities(model);
            JsonNode instances = model.path("loaded_instances");
            if (!instances.isArray() || instances.isEmpty()) {
                discovered.add(new DiscoveredRuntimeModel(key, key, displayName, family, quantization,
                        positiveInt(model.path("max_context_length"), null), false, 1,
                        json(capabilities), json(model)));
                continue;
            }
            for (JsonNode instance : instances) {
                String instanceId = text(instance, "id", key);
                JsonNode config = instance.path("config");
                discovered.add(new DiscoveredRuntimeModel(instanceId, key, displayName, family, quantization,
                        positiveInt(config.path("context_length"), positiveInt(model.path("max_context_length"), null)),
                        true, positiveInt(config.path("parallel"), 1), json(capabilities), json(model)));
            }
        }
        return discovered;
    }

    private List<DiscoveredRuntimeModel> discoverCompatibleList(JsonNode data) {
        List<DiscoveredRuntimeModel> discovered = new ArrayList<>();
        for (JsonNode model : data) {
            String id = text(model, "id", null);
            if (id == null || id.isBlank()) continue;
            String state = text(model, "state", "loaded");
            boolean loaded = !"not-loaded".equalsIgnoreCase(state) && !"unloaded".equalsIgnoreCase(state);
            Set<String> capabilities = compatibleCapabilities(model);
            discovered.add(new DiscoveredRuntimeModel(id, id, text(model, "display_name", id),
                    text(model, "arch", null), text(model, "quantization", null),
                    positiveInt(model.path("max_context_length"), null), loaded, 1,
                    json(capabilities), json(model)));
        }
        return discovered;
    }

    private Set<String> nativeCapabilities(JsonNode model) {
        Set<String> result = new LinkedHashSet<>();
        if ("embedding".equalsIgnoreCase(text(model, "type", ""))) {
            result.add("EMBEDDING");
            return result;
        }
        result.add("CHAT_COMPLETION");
        result.add("STREAMING");
        JsonNode capabilities = model.path("capabilities");
        if (capabilities.path("vision").asBoolean(false)) result.add("VISION");
        if (capabilities.path("trained_for_tool_use").asBoolean(false)) result.add("TOOL_CALLING");
        if (capabilities.hasNonNull("reasoning")) result.add("REASONING");
        return result;
    }

    private Set<String> compatibleCapabilities(JsonNode model) {
        Set<String> result = new LinkedHashSet<>();
        String type = text(model, "type", "llm");
        if (type.toLowerCase().contains("embed")) result.add("EMBEDDING");
        else {
            result.add("CHAT_COMPLETION");
            result.add("STREAMING");
        }
        if (type.equalsIgnoreCase("vlm")) result.add("VISION");
        JsonNode values = model.path("capabilities");
        if (values.isArray()) {
            for (JsonNode value : values) {
                String capability = value.asText("");
                if (capability.equalsIgnoreCase("tool_use") || capability.equalsIgnoreCase("tool_calling")) result.add("TOOL_CALLING");
                if (capability.equalsIgnoreCase("vision")) result.add("VISION");
                if (capability.equalsIgnoreCase("reasoning")) result.add("REASONING");
            }
        }
        return result;
    }

    private String text(JsonNode node, String field, String fallback) {
        return node.hasNonNull(field) ? node.path(field).asText() : fallback;
    }

    private Integer positiveInt(JsonNode node, Integer fallback) {
        int value = node.asInt(0);
        return value > 0 ? value : fallback;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { return value instanceof Set<?> ? "[]" : null; }
    }
}
