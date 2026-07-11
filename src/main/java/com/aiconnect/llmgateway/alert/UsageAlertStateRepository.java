package com.aiconnect.llmgateway.alert;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageAlertStateRepository extends JpaRepository<UsageAlertState, UsageAlertStateId> { }
