package com.aiconnect.llmgateway.scheduling;

import com.aiconnect.llmgateway.routing.LocalActiveRequestStore;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalSchedulingStoreTest {
    private final LocalActiveRequestStore store=new LocalActiveRequestStore();
    private final UUID service=UUID.randomUUID(),key=UUID.randomUUID(),model=UUID.randomUUID(),runtime=UUID.randomUUID();
    private SchedulingSettings limits(int count,long bytes,int perKey) { return new SchedulingSettings(SchedulingMode.LOCAL_ONLY_QUEUE,0,30000,count,bytes,perKey); }
    private QueueTicket ticket(String id,UUID service,UUID key,long bytes,UUID model,UUID runtime) { long now=System.currentTimeMillis();return new QueueTicket(id,service,key,bytes,now,now+30000,List.of("d:"+model,"r:"+runtime)); }
    @Test void runtimeCapacityIsSharedAcrossModelsAndServices() {
        assertThat(store.tryAcquire(model,10,runtime,1,null)).isTrue();
        assertThat(store.tryAcquire(UUID.randomUUID(),10,runtime,1,null)).isFalse();
        store.release(model,runtime);
        assertThat(store.runtimeCount(runtime)).isZero();
        assertThat(store.tryAcquire(UUID.randomUUID(),10,runtime,1,null)).isTrue();
    }
    @Test void fifoBlocksNewcomersAcrossServicesButSkipsUnrelatedResources() {
        assertThat(store.enqueue(ticket("first",service,key,100,model,runtime),limits(5,10000,5))).isNull();
        assertThat(store.enqueue(ticket("second",UUID.randomUUID(),key,100,model,runtime),limits(5,10000,5))).isNull();
        assertThat(store.tryAcquire(model,1,runtime,1,"second")).isFalse();
        assertThat(store.tryAcquire(model,1,runtime,1,null)).isFalse();
        assertThat(store.tryAcquire(UUID.randomUUID(),1,UUID.randomUUID(),1,null)).isTrue();
        assertThat(store.tryAcquire(model,1,runtime,1,"first")).isTrue();
        store.release(model,runtime);
        assertThat(store.tryAcquire(model,1,runtime,1,"second")).isTrue();
        assertThat(store.snapshot(service).queuedRequests()).isZero();
    }
    @Test void perKeyAndBytesAndCountAreBounded() {
        assertThat(store.enqueue(ticket("first",service,key,800,model,runtime),limits(3,1024,1))).isNull();
        assertThat(store.enqueue(ticket("same-key",service,key,100,model,runtime),limits(3,1024,1))).isEqualTo("QUEUE_KEY_LIMIT");
        assertThat(store.enqueue(ticket("oversize",service,UUID.randomUUID(),300,model,runtime),limits(3,1024,1))).isEqualTo("QUEUE_FULL");
        assertThat(store.snapshot(service).queuedBytes()).isEqualTo(800);
        store.removeTicket("first");
        assertThat(store.snapshot(service).queuedBytes()).isZero();
    }
    @Test void staleMetadataExpiresAndCannotBeClaimed() {
        long past=System.currentTimeMillis()-1000;
        store.enqueue(new QueueTicket("expired",service,key,50,past,past+1,List.of("d:"+model)),limits(3,1024,3));
        assertThat(store.snapshot(service).queuedRequests()).isZero();
        assertThat(store.tryAcquire(model,1,runtime,1,"expired")).isFalse();
        assertThat(store.tryAcquire(model,1,runtime,1,null)).isTrue();
    }
    @Test void concurrentAcquisitionNeverExceedsRuntimeLimit() throws Exception {
        var pool=Executors.newFixedThreadPool(12);CountDownLatch start=new CountDownLatch(1);
        AtomicInteger acquired=new AtomicInteger();
        try {
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<100;i++)tasks.add(pool.submit(()->{start.await();if(store.tryAcquire(UUID.randomUUID(),4,runtime,3,null))acquired.incrementAndGet();return null;}));
            start.countDown();for(var task:tasks)task.get(3,TimeUnit.SECONDS);
            assertThat(acquired).hasValue(3);assertThat(store.runtimeCount(runtime)).isEqualTo(3);
        } finally {pool.shutdownNow();}
    }
    @Test void policiesRejectInconsistentOrUnboundedLimits() {
        assertThatThrownBy(()->new SchedulingSettings(SchedulingMode.LOCAL_FIRST_WAIT,5000,1000,10,1024,5).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SchedulingSettings(SchedulingMode.LOCAL_ONLY_QUEUE,0,60001,10,1024,5).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThat(SchedulingSettings.defaults().mode()).isEqualTo(SchedulingMode.IMMEDIATE);
    }
}
