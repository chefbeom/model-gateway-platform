package com.aiconnect.llmgateway.beta;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="beta_member",uniqueConstraints=@UniqueConstraint(columnNames={"service_id","email"}))
public class BetaMember {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(columnDefinition="char(36)") UUID id;
 @Column(nullable=false,columnDefinition="char(36)") UUID serviceId;
 @Column(nullable=false,unique=true,columnDefinition="char(36)") UUID projectId;
 @Column(nullable=false,length=320) String email;
 @Column(nullable=false) boolean active=true;
 @Column(nullable=false) Instant createdAt=Instant.now();
 protected BetaMember() {}
 public BetaMember(UUID service,UUID project,String email){this.serviceId=service;this.projectId=project;this.email=email;}
 public UUID getId(){return id;} public UUID getServiceId(){return serviceId;} public UUID getProjectId(){return projectId;} public String getEmail(){return email;} public boolean isActive(){return active;} public void suspend(){active=false;}
}
