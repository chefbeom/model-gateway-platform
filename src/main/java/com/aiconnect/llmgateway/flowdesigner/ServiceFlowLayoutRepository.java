package com.aiconnect.llmgateway.flowdesigner;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ServiceFlowLayoutRepository extends JpaRepository<ServiceFlowLayout, UUID> { }
