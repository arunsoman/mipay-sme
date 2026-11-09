package com.mipay.sme.service;

import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.ledger.AccountMapping;
import com.mipay.sme.domain.ledger.Connector;
import com.mipay.sme.repository.AccountMappingRepository;
import com.mipay.sme.repository.ConnectorRepository;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

/**
 * Connector management (E01-E07, BC p.29): bank/issuer, lender, IPS/card/QR/KYC/
 * notification connectors with capability matrices (native IPP B2B excluded at
 * go-live, BC p.9/p.17), health checks and account mappings.
 */
@Service
@RequiredArgsConstructor
public class ConnectorService {

    @Value("${app.environment}")
    private String environment;

    private final ConnectorRepository connectorRepo;
    private final AccountMappingRepository mappingRepo;
    private final AuditService auditService;

    private final SecureRandom random = new SecureRandom();

    @Transactional(readOnly = true)
    public List<Dtos.ConnectorView> list() {
        return connectorRepo.findAllByOrderByConnectorType().stream()
                .map(this::view)
                .toList();
    }

    @Transactional
    public Dtos.ConnectorView upsert(Dtos.ConnectorUpsert req, String actorId, String actorRole) {
        var existing = connectorRepo.findAllByOrderByConnectorType().stream()
                .filter(c -> c.getConnectorType() == req.connectorType() && c.getName().equals(req.name()))
                .findFirst();
        Connector c = existing.orElseGet(Connector::new);
        String before = existing.isEmpty() ? null : c.getStatus().name();
        c.setConnectorType(req.connectorType());
        c.setName(req.name());
        c.setEnvironment(req.environment());
        c.setBaseUrl(req.baseUrl());
        c.setAuthType(req.authType() == null ? Enums.AuthType.OAUTH2 : req.authType());
        c.setCredentialsRef(req.credentialsRef() == null ? "vault://" + req.connectorType().name().toLowerCase() : req.credentialsRef());
        c.setCapabilities(req.capabilities() == null ? "{}" : req.capabilities());
        c.setStatus(Enums.ConnectorStatus.ACTIVE);
        connectorRepo.save(c);
        auditService.record(actorId, actorRole, "connector_saved", "connector", c.getId(),
                before, c.getStatus().name(), "capabilities=" + c.getCapabilities(), false, null);
        return view(c);
    }

    /** Health check with simulated latency + auth result (E07). */
    @Transactional
    public Dtos.ConnectorTestResult test(String connectorId, String actorId, String actorRole) {
        Connector c = connectorRepo.findById(connectorId)
                .orElseThrow(() -> new NotFoundException("Connector not found"));
        boolean ok = c.getBaseUrl() != null && !c.getBaseUrl().isBlank();
        int latency = ok ? 80 + random.nextInt(120) : -1;
        c.setLastHealthCheck(Instant.now());
        c.setHealthLatencyMs(latency);
        c.setStatus(ok ? Enums.ConnectorStatus.ACTIVE : Enums.ConnectorStatus.FAILED);
        connectorRepo.save(c);
        auditService.sim(actorId, actorRole, "connector_health_check", "connector", c.getId(),
                "ok=" + ok + " latency=" + latency + "ms", null);
        return new Dtos.ConnectorTestResult(c.getId(), c.getName(), ok, latency,
                ok ? "Auth OK (OAuth2 client credentials) - sandbox" : "Connection failed - no base URL",
                "SIMULATED health check");
    }

    @Transactional
    public Dtos.ConnectorTestResult rotateCredentials(String connectorId, String actorId, String actorRole) {
        Connector c = connectorRepo.findById(connectorId)
                .orElseThrow(() -> new NotFoundException("Connector not found"));
        String newRef = "vault://" + c.getConnectorType().name().toLowerCase() + "/" + randomHex();
        String before = c.getCredentialsRef();
        c.setCredentialsRef(newRef);
        connectorRepo.save(c);
        auditService.record(actorId, actorRole, "credentials_rotated", "connector", c.getId(),
                before, newRef, "old credentials revoked (TC-SET-04)", false, null);
        return new Dtos.ConnectorTestResult(c.getId(), c.getName(), true, null,
                "Credentials rotated; old secret revoked; connection re-established", "SIMULATED rotation");
    }

    @Transactional(readOnly = true)
    public List<Dtos.ConnectorView> byType(Enums.ConnectorType type) {
        return connectorRepo.findByConnectorType(type).stream().map(this::view).toList();
    }

    private Dtos.ConnectorView view(Connector c) {
        return new Dtos.ConnectorView(c.getId(), c.getConnectorType().name(), c.getName(),
                c.getEnvironment().name(), c.getBaseUrl(), c.getAuthType() == null ? null : c.getAuthType().name(),
                c.getCredentialsRef() == null ? null : mask(c.getCredentialsRef()),
                c.getCapabilities(), c.getStatus().name(), c.getLastHealthCheck(), c.getHealthLatencyMs());
    }

    private String mask(String credentialsRef) {
        // vault refs are only identifiers - shown in full; real secrets never leave the vault
        return credentialsRef;
    }

    private String randomHex() {
        return String.format("%08X", random.nextLong() & 0xFFFFFFFFL);
    }
}
