package com.aiconnect.llmgateway.service;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.Project;

public record ApiKeyCredentials(ApiKey apiKey, Project project) { }
