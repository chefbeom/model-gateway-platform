package com.aiconnect.llmgateway.runtime;

import java.io.InputStream;

public record StreamingRuntimeResult(int statusCode, InputStream body) { }
