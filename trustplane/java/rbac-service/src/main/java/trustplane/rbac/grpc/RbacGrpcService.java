package trustplane.rbac.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;
import trustplane.rbac.core.ApiExceptionCodes;
import trustplane.rbac.core.RbacStore;
import trustplane.rbac.grpc.v1.CheckRequest;
import trustplane.rbac.grpc.v1.CheckResponse;
import trustplane.rbac.grpc.v1.ListPermissionsRequest;
import trustplane.rbac.grpc.v1.ListPermissionsResponse;
import trustplane.rbac.grpc.v1.PingRequest;
import trustplane.rbac.grpc.v1.PingResponse;
import trustplane.rbac.grpc.v1.RbacGrpc;
import trustplane.rbac.grpc.v1.WatchEvent;
import trustplane.rbac.grpc.v1.WatchRequest;

/**
 * Decision plane implementation (gRPC over mTLS). Wire-compatible with the
 * Go gateway client: Check returns the tenant version + supplier org; Watch
 * streams invalidation events (tenant, version, affected subjects).
 */
@Service
@lombok.RequiredArgsConstructor
public class RbacGrpcService extends RbacGrpc.RbacImplBase {

    private final RbacStore store;
    private final trustplane.rbac.core.WatchHub hub;

    @Override
    public void check(CheckRequest req, StreamObserver<CheckResponse> rsp) {
        try {
            var d = store.checkDecision(req.getSubject(), req.getTenant(), req.getOperation());
            rsp.onNext(CheckResponse.newBuilder()
                    .setAllowed(d.allowed())
                    .setReason(d.reason())
                    .setTenantVersion(d.tenantVersion())
                    .setDecisionId("d-" + System.nanoTime())
                    .setSupplierId(d.supplierId() == null ? "" : d.supplierId())
                    .build());
            rsp.onCompleted();
        } catch (Exception e) {
            rsp.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    @Override
    public void listPermissions(ListPermissionsRequest req, StreamObserver<ListPermissionsResponse> rsp) {
        try {
            var p = store.listPermissions(req.getSubject(), req.getTenant());
            rsp.onNext(ListPermissionsResponse.newBuilder()
                    .addAllOperations(p.operations())
                    .setTenantVersion(p.tenantVersion())
                    .setSupplierId(p.supplierId() == null ? "" : p.supplierId())
                    .build());
            rsp.onCompleted();
        } catch (Exception e) {
            rsp.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    @Override
    public void watch(WatchRequest req, StreamObserver<WatchEvent> rsp) {
        // stream lifecycle: ends when the client cancels (interrupted take on
        // the virtual-thread executor) — no isCancelled probe needed
        var sub = hub.subscribe();
        try {
            while (true) {
                WatchEvent ev = sub.queue().take();
                rsp.onNext(ev);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            hub.unsubscribe(sub.id());
            return;
        }
    }

    @Override
    public void ping(PingRequest req, StreamObserver<PingResponse> rsp) {
        rsp.onNext(PingResponse.newBuilder().setVersion("2-java").build());
        rsp.onCompleted();
    }
}
