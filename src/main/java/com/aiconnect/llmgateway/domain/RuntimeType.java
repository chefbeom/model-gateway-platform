package com.aiconnect.llmgateway.domain;

public enum RuntimeType {
    LM_STUDIO("LM Studio", 1234, true),
    OLLAMA("Ollama", 11434, false),
    LLAMA_CPP("llama.cpp", 8080, false),
    OPENAI_COMPATIBLE("OpenAI Compatible", null, false);

    private final String displayName;
    private final Integer defaultPort;
    private final boolean nativeModelManagement;

    RuntimeType(String displayName, Integer defaultPort, boolean nativeModelManagement) {
        this.displayName = displayName;
        this.defaultPort = defaultPort;
        this.nativeModelManagement = nativeModelManagement;
    }

    public String displayName() { return displayName; }
    public Integer defaultPort() { return defaultPort; }
    public boolean nativeModelManagement() { return nativeModelManagement; }
}
