package com.mipay.sme.security.rbac;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Discovers every {@link Perm} / {@link PublicEndpoint} handler at startup and registers the
 * permission catalogue with trustplane RBAC (its existing registry:
 * POST /rbac/internal/permissions/register, body {service, permissions:[{id, description}]}).
 * The endpoint list travels in the same body (extra field, ignored by today's registry, used by
 * the gateway route compilation of design Appendix F).
 *
 * Unannotated handlers are reported; with app.rbac.require-annotations=true startup fails.
 * Registration is skipped when app.rbac.registry-url is empty (local runs).
 */
@Component
@Slf4j
public class EndpointManifestPublisher {

    private final RequestMappingHandlerMapping mappings;
    private final ObjectMapper mapper;

    public EndpointManifestPublisher(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings,
                                     ObjectMapper mapper) {
        this.mappings = mappings;
        this.mapper = mapper;
    }

    @Value("${app.rbac.require-annotations:false}") private boolean requireAnnotations;
    @Value("${app.rbac.registry-url:}") private String registryUrl;
    @Value("${app.rbac.service-name:mipay}") private String service;

    public record Endpoint(String method, String path, String permission, String tenantSource,
                           String lookup, boolean isPublic, String reason, String handler) { }

    @EventListener(ApplicationReadyEvent.class)
    public void publish() {
        List<Endpoint> endpoints = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Map<String, String> catalogue = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod hm = e.getValue();
            if (!hm.getBeanType().getName().startsWith("com.mipay.sme.web")) continue;
            String handler = hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName();
            Perm perm = hm.getMethodAnnotation(Perm.class);
            PublicEndpoint pub = hm.getMethodAnnotation(PublicEndpoint.class);
            Set<String> paths = e.getKey().getPatternValues();
            Set<String> methods = new TreeSet<>();
            e.getKey().getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
            for (String path : paths) for (String method : methods.isEmpty() ? Set.of("ANY") : methods) {
                if (perm != null) {
                    String id = perm.resource() + ":" + perm.verb().id();
                    catalogue.putIfAbsent(id, perm.description().isBlank()
                            ? perm.resource().replace('.', ' ') + " " + perm.verb().id() : perm.description());
                    endpoints.add(new Endpoint(method, path, id, perm.tenantSource().name(), perm.lookup(),
                            false, null, handler));
                } else if (pub != null) {
                    endpoints.add(new Endpoint(method, path, null, null, null, true, pub.reason(), handler));
                } else {
                    missing.add(method + " " + path + " (" + handler + ")");
                }
            }
        }
        log.info("[rbac] manifest: {} endpoints, {} permission cells, {} without @Perm/@PublicEndpoint",
                endpoints.size(), catalogue.size(), missing.size());
        if (!missing.isEmpty()) {
            missing.stream().sorted().limit(20).forEach(m -> log.warn("[rbac] unannotated: {}", m));
            if (requireAnnotations) {
                throw new IllegalStateException(missing.size() + " handler(s) lack @Perm or @PublicEndpoint");
            }
        }
        if (registryUrl.isBlank()) {
            log.info("[rbac] app.rbac.registry-url not set - catalogue not registered");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", service);
        List<Map<String, String>> perms = new ArrayList<>();
        catalogue.forEach((id, d) -> perms.add(Map.of("id", id, "description", d)));
        body.put("permissions", perms);
        body.put("endpoints", endpoints);
        Thread.ofVirtual().start(() -> register(body));
    }

    /** Retries in the background; RBAC may start after the backend. */
    private void register(Map<String, Object> body) {
        for (int attempt = 1; attempt <= 20; attempt++) {
            try {
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                        .sslContext(MeshTls.clientContext()).build();
                HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(registryUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("X-Mesh-Service", service)
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() / 100 == 2) {
                    log.info("[rbac] catalogue registered with RBAC: {}", r.body());
                    return;
                }
                log.warn("[rbac] registration attempt {} refused: {} {}", attempt, r.statusCode(), r.body());
            } catch (Exception ex) {
                log.warn("[rbac] registration attempt {} failed: {}", attempt, ex.getMessage());
            }
            try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
        log.error("[rbac] catalogue NOT registered after 20 attempts");
    }
}
