package com.aiconnect.llmgateway.scheduling;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
public interface RequestSchedulingRepository extends JpaRepository<RequestSchedulingTrace,String> {
    List<RequestSchedulingTrace> findByPhaseAndDeadlineBefore(String phase, Instant now);
}
