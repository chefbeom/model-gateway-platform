package com.aiconnect.llmgateway.beta;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="beta_service")
public class BetaService {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(columnDefinition="char(36)") UUID id;
 @Column(nullable=false,columnDefinition="char(36)") UUID organizationId;
 @Column(nullable=false,unique=true,columnDefinition="char(36)") UUID logicalServiceId;
 @Column(nullable=false,length=24) String mode="PAUSED";
 @Column(nullable=false) Instant createdAt=Instant.now();
 protected BetaService() {}
 public BetaService(UUID org,UUID logical){organizationId=org;logicalServiceId=logical;}
 public UUID getId(){return id;} public UUID getOrganizationId(){return organizationId;} public UUID getLogicalServiceId(){return logicalServiceId;} public String getMode(){return mode;}
 public void setMode(String value){mode=value;}
}
