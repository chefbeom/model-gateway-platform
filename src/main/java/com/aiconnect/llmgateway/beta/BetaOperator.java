package com.aiconnect.llmgateway.beta;
import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
@Entity @Table(name="beta_operator") @IdClass(BetaOperator.Key.class)
public class BetaOperator {
 @Id @Column(columnDefinition="char(36)") UUID serviceId;
 @Id @Column(columnDefinition="char(36)") UUID userId;
 protected BetaOperator() {}
 public BetaOperator(UUID serviceId,UUID userId){this.serviceId=serviceId;this.userId=userId;}
 public static class Key implements Serializable {
  public UUID serviceId; public UUID userId;
  public Key(){} public Key(UUID serviceId,UUID userId){this.serviceId=serviceId;this.userId=userId;}
  @Override public boolean equals(Object other){return other instanceof Key key && Objects.equals(serviceId,key.serviceId)&&Objects.equals(userId,key.userId);}
  @Override public int hashCode(){return Objects.hash(serviceId,userId);}
 }
}
