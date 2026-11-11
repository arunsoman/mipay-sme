package trustplane.rbac.core;

import org.springframework.stereotype.Component;
import trustplane.rbac.grpc.v1.WatchEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * In-process fan-out hub backing the gRPC Watch stream. Mutations
 * (grant revocations, assignments, user/supplier status changes, role edits)
 * publish here; the gateway consumes the stream and purges its decision
 * cache, so revocations take effect in near-real-time.
 */
@Component
@lombok.extern.slf4j.Slf4j
public class WatchHub {

    private static final int QUEUE_CAP = 256;

    private final Map<Long, LinkedBlockingQueue<WatchEvent>> watchers = new ConcurrentHashMap<>();
    private long nextId = 1;

    public record Subscription(long id, LinkedBlockingQueue<WatchEvent> queue) {}

    public synchronized Subscription subscribe() {
        long id = nextId++;
        LinkedBlockingQueue<WatchEvent> q = new LinkedBlockingQueue<>(QUEUE_CAP);
        watchers.put(id, q);
        return new Subscription(id, q);
    }

    public synchronized void unsubscribe(long id) {
        watchers.remove(id);
    }

    public void publish(String tenant, long version, List<String> subjects) {
        if (subjects == null) subjects = List.of();
        WatchEvent ev = WatchEvent.newBuilder()
                .setTenant(tenant == null ? "" : tenant)
                .setVersion(version)
                .addAllSubjects(subjects)
                .build();
        List<Long> dead = new ArrayList<>();
        for (var e : watchers.entrySet()) {
            if (!e.getValue().offer(ev)) {
                // watcher too slow: drop oldest to keep the invalidation path live
                e.getValue().poll();
                if (!e.getValue().offer(ev)) dead.add(e.getKey());
            }
        }
        for (Long id : dead) unsubscribe(id);
        log.info("[rbac-watch] tenant={} subjects={} version={} watchers={}", tenant, subjects.size(), version, watchers.size());
    }
}
