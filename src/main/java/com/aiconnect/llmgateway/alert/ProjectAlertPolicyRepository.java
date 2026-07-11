package com.aiconnect.llmgateway.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProjectAlertPolicyRepository extends JpaRepository<ProjectAlertPolicy, UUID> { }
