package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> { }
