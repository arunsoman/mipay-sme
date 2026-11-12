package trustplane.rbac.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;
import trustplane.rbac.core.Decisions;
import trustplane.rbac.core.WatchHub;
import trustplane.rbac.grpc.v1.*;

/**
 * Decision plane (gRPC over mTLS) for the gateway and the core. Subjects are core user ids,
 * operations grid cells; see {@link Decisions} for how a check is answered.
 */
@Service
@lombok.RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class RbacGrpcService extends RbacGrpc.RbacImplBase {

    private final Decisions decisions;
    private final WatchHub hub;

    static Decisions.Request req(CheckRequest r) {
        return new Decisions.Request(r.getSubject(), r.getTenant(), r.getOperation(), r.getActiveRolesList(), r.getAttributesMap());
    }

    static CheckResponse resp(Decisions.Decision d) {
        return CheckResponse.newBuilder().setAllowed(d.allowed()).setReason(d.reason()).setTenantVersion(d.tenantVersion())
                .setDecisionId(d.decisionId()).addAllMatchedRoles(d.matchedRoles()).build();
    }

    @Override
    public void check(CheckRequest r, StreamObserver<CheckResponse> rsp) {
        try {
            rsp.onNext(resp(decisions.check(req(r))));
            rsp.onCompleted();
        } catch (Exception e) {
            log.error("[rbac-grpc] check failed", e);
            rsp.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    @Override
    public void checkBatch(CheckBatchRequest r, StreamObserver<CheckBatchResponse> rsp) {
        try {
            if (r.getChecksCount() > 500) {
                rsp.onError(Status.INVALID_ARGUMENT.withDescription("at most 500 checks per batch").asException());
                return;
            }
            CheckBatchResponse.Builder b = CheckBatchResponse.newBuilder();
            for (CheckRequest c : r.getChecksList()) b.addResults(resp(decisions.check(req(c))));
            rsp.onNext(b.build());
            rsp.onCompleted();
        } catch (Exception e) {
            rsp.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    @Override
    public void listPermissions(ListPermissionsRequest r, StreamObserver<ListPermissionsResponse> rsp) {
        try {
            var p = decisions.listPermissions(r.getSubject(), r.getTenant(), r.getActiveRolesList());
            ListPermissionsResponse.Builder b = ListPermissionsResponse.newBuilder().setTenantVersion(p.tenantVersion()).addAllOperations(p.operations());
            p.masks().forEach(b::putMasks);
            rsp.onNext(b.build());
            rsp.onCompleted();
        } catch (Exception e) {
            rsp.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    @Override
    public void listHolders(ListHoldersRequest r, StreamObserver<ListHoldersResponse> rsp) {
        try {
            ListHoldersResponse.Builder b = ListHoldersResponse.newBuilder();
            for (Decisions.Holder h : decisions.holders(r.getRole(), r.getTenant())) {
                b.addHolders(Holder.newBuilder().setSubject(h.subject()).setAssignmentId(h.assignmentId())
                        .setTenant(h.tenant() == null ? "" : h.tenant()).setScope(h.scope()));
            }
            rsp.onNext(b.build());
            rsp.onCompleted();
        } catch (Exception e) {
            rsp.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    @Override
    public void validateActivation(ActivationRequest r, StreamObserver<ActivationResponse> rsp) {
        try {
            String why = decisions.validateActivation(r.getSubject(), r.getRolesList());
            rsp.onNext(ActivationResponse.newBuilder().setOk(why == null).setReason(why == null ? "" : why).build());
            rsp.onCompleted();
        } catch (Exception e) {
            rsp.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    @Override
    public void watch(WatchRequest req, StreamObserver<WatchEvent> rsp) {
        // ends when the client cancels (interrupted take on the virtual-thread executor)
        var sub = hub.subscribe();
        try {
            while (true) {
                rsp.onNext(sub.queue().take());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            hub.unsubscribe(sub.id());
        }
    }

    @Override
    public void ping(PingRequest req, StreamObserver<PingResponse> rsp) {
        rsp.onNext(PingResponse.newBuilder().setVersion("3-grid").build());
        rsp.onCompleted();
    }
}
