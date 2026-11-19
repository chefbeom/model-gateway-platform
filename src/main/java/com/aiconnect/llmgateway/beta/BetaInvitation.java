package com.aiconnect.llmgateway.beta;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="beta_invitation")
public class BetaInvitation {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(columnDefinition="char(36)") UUID id;
 @Column(nullable=false,columnDefinition="char(36)") UUID serviceId;
 @Column(nullable=false,length=320) String normalizedEmail;
 @Column(nullable=false,unique=true,length=64) String tokenHash;
 @Column(nullable=false) Instant expiresAt;
 Instant consumedAt; Instant revokedAt;
 protected BetaInvitation() {}
 public BetaInvitation(UUID service,String email,String hash,Instant expires){serviceId=service;normalizedEmail=email;tokenHash=hash;expiresAt=expires;}
}
