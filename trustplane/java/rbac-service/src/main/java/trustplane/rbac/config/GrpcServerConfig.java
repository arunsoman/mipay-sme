package trustplane.rbac.config;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import trustplane.rbac.grpc.RbacGrpcService;

import java.io.File;
import java.net.InetSocketAddress;

/**
 * The decision-plane gRPC server: HTTP/2 + mutual TLS, client certificates
 * signed by the platform CA, high stream concurrency for the gateway mesh.
 */
@Component
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class GrpcServerConfig implements SmartLifecycle {

    private final RbacConfig cfg;
    private final RbacGrpcService svc;
    private Server server;
    private volatile boolean running;


    @Override
    public void start() {
        try {
            SslContext ssl = GrpcSslContexts.forServer(new File(cfg.tlsCert()), new File(cfg.tlsKey()))
                    .trustManager(new File(cfg.internalCA()))
                    .clientAuth(ClientAuth.REQUIRE)
                    .build();
            String host = cfg.grpcListen().startsWith(":") ? "0.0.0.0"
                    : cfg.grpcListen().substring(0, cfg.grpcListen().indexOf(':'));
            int port = Integer.parseInt(cfg.grpcListen().substring(cfg.grpcListen().lastIndexOf(':') + 1));
            // gRPC handlers run on virtual threads (blocking watch streams,
            // short Check calls) — cheap to scale per-stream
            server = NettyServerBuilder.forAddress(new InetSocketAddress(host, port))
                    .sslContext(ssl)
                    .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                    .maxConcurrentCallsPerConnection(64)
                    // Go mesh clients keep the channel alive with 15s pings
                    // (incl. streams-less periods); grpc-java's default quota
                    // replies ENHANCE_YOUR_CALM and kills the channel.
                    .permitKeepAliveTime(10, java.util.concurrent.TimeUnit.SECONDS)
                    .permitKeepAliveWithoutCalls(true)
                    .addService(svc)
                    .build()
                    .start();
            running = true;
            log.info("[rbac-grpc] listening {} (mTLS)", cfg.grpcListen());
        } catch (Exception e) {
            throw new IllegalStateException("rbac grpc server: " + e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        if (server != null) server.shutdownNow();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
