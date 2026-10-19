package com.aiconnect.llmgateway.scheduling;
import jakarta.persistence.*;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
@Entity @Table(name="service_request_scheduling")
public class ServiceSchedulingPolicy {
    @Id @Column(columnDefinition="char(36)") private UUID serviceId;
    @Column(nullable=false, columnDefinition="text") private String settingsJson;
    protected ServiceSchedulingPolicy() { }
    public ServiceSchedulingPolicy(UUID id, SchedulingSettings settings) {
        serviceId = id; settings.validate();
        try { settingsJson = new ObjectMapper().writeValueAsString(settings); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    public SchedulingSettings settings() {
        try { return new ObjectMapper().readValue(settingsJson, SchedulingSettings.class); }
        catch (Exception e) { throw new IllegalStateException("Invalid persisted scheduling policy", e); }
    }
}
