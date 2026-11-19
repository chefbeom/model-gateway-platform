package com.aiconnect.llmgateway.beta;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface BetaMemberRepository extends JpaRepository<BetaMember,UUID> {
 Optional<BetaMember> findByProjectId(UUID projectId);
 List<BetaMember> findByServiceId(UUID serviceId);
 boolean existsByServiceIdAndEmail(UUID serviceId,String email);
}
