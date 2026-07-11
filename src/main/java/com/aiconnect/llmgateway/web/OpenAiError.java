package com.aiconnect.llmgateway.web;

public record OpenAiError(ErrorBody error) {
    public record ErrorBody(String message, String type, String code, String request_id) { }
    public static OpenAiError of(String message, String type, String code, String requestId) {
        return new OpenAiError(new ErrorBody(message, type, code, requestId));
    }
}
