package com.aiconnect.llmgateway.scheduling;

import com.aiconnect.llmgateway.repository.LlmRequestRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.UUID;

@Service
public class SchedulingTraceService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SchedulingTraceService.class);
    private final RequestSchedulingRepository traces;
    private final LlmRequestRepository requests;
    public SchedulingTraceService(RequestSchedulingRepository traces,LlmRequestRepository requests) { this.traces=traces; this.requests=requests; }
    public void queued(String id,UUID service,SchedulingMode mode,long now,long deadline) { traces.save(new RequestSchedulingTrace(id,service,mode,now,deadline)); }
    public void dispatched(String id,UUID model,String reason,long now) { traces.findById(id).ifPresent(t->{t.dispatch(model,reason,now); traces.save(t);}); }
    public void finish(String id,String phase,String code) {try {traces.findById(id).ifPresent(t->{t.finish(phase,code,System.currentTimeMillis()); traces.save(t);});}catch(RuntimeException error){log.warn("Scheduling completion metadata could not be saved for requestId={}",id,error);} }
    public RequestSchedulingTrace.View view(String id) { return traces.findById(id).map(RequestSchedulingTrace::view).orElse(null); }
    // An owner restart never replays an inference. Only abandoned *waiting* requests are expired.
    @Scheduled(fixedDelay=30000)
    public void expireOrphans() {
        for(var trace:traces.findByPhaseAndDeadlineBefore("QUEUED", Instant.now().minusSeconds(30))) {
            trace.finish("EXPIRED","QUEUE_WAIT_TIMEOUT",System.currentTimeMillis()); traces.save(trace);
            // Safe metadata-only repair after an API owner disappears.
            requests.findByRequestId(trace.getRequestId()).filter(r->r.getStatus()==com.aiconnect.llmgateway.domain.RequestStatus.IN_PROGRESS).ifPresent(r->{
                r.fail("QUEUE_WAIT_TIMEOUT",503,java.time.Duration.between(r.getStartedAt(),Instant.now()).toMillis(),0);requests.save(r);
            });
        }
    }
}
