package com.mipay.sme.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.IdentityLink;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.repository.IdentityLinkRepository;
import com.mipay.sme.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ingest endpoint against the real components: a JWS signed with the IdP's REAL ES256
 * signing key (from the mesh PKI volume), verified by the core against the REAL IdP JWKS
 * fetched over real TLS. CREDENTIAL_SET activates the link + user through the
 * UserLifecycleService exactly once (auth_events_seen dedup); forgeries, wrong audiences,
 * stale events and unknown kids fail closed.
 */
@ExtendWith(AuthOutboxProcessorRealIdpIT.RealInfra.class)
@SpringBootTest(properties = {
        "spring.profiles.active=postgres",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:25433/auth_it_events",
        "spring.datasource.username=t",
        "spring.datasource.password=t",
        "app.identity-mode=gateway",
        "app.auth-provider=trustplane",
        "app.auth-url=https://127.0.0.1:18443",
        "app.outbox.poll-millis=3600000",
        "app.gateway-context-secret-file=build/test-pki/_vol/ctx-secret"
})
@AutoConfigureMockMvc
class AuthEventIngestRealIdpIT {
    private static final String ISSUER = "https://idp.fapi.local:8443";
    private static final String KID = "es256";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static PrivateKey signerKey;

    @Autowired MockMvc mvc;
    @Autowired IdentityLinkRepository links;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthOutboxProcessor processor;
    @Autowired AuthOutboxService outbox;

    @BeforeAll
    static void provision() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:25433/postgres", "t", "t");
             var s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS auth_it_events WITH (FORCE)");
            s.execute("CREATE DATABASE auth_it_events");
        }
        String sec1 = Files.readString(Path.of("build/test-pki/_vol/signing/es256.pem"))
                .replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] sec1Der = Base64.getDecoder().decode(sec1);
        try {
            signerKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(sec1Der));
        } catch (Exception incompat) {
            signerKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(wrapSec1(sec1Der)));
        }
    }

    /** Wraps an RFC 5915 EC key into PKCS#8: SEQUENCE { INTEGER 0, AlgorithmIdentifier, OCTET STRING(sec1) }. */
    private static byte[] wrapSec1(byte[] sec1) {
        return tlv(0x30, concat(concat(tlv(0x02, new byte[]{0}),
                tlv(0x30, concat(tlv(0x06, hex("2a8648ce3d0201")), tlv(0x06, hex("2a8648ce3d030107"))))),
                tlv(0x04, sec1)));
    }

    private static byte[] tlv(int tag, byte[] content) {
        byte[] len = lengthHeader(content.length);
        byte[] out = new byte[1 + len.length + content.length];
        out[0] = (byte) tag;
        System.arraycopy(len, 0, out, 1, len.length);
        System.arraycopy(content, 0, out, 1 + len.length, content.length);
        return out;
    }

    private static byte[] lengthHeader(int len) {
        if (len < 128) return new byte[]{(byte) len};
        if (len < 256) return new byte[]{(byte) 0x81, (byte) len};
        return new byte[]{(byte) 0x82, (byte) (len >> 8), (byte) len};
    }

    private static byte[] hex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private String sign(Map<String, Object> event, long iat) throws Exception {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(
                Map.of("alg", "ES256", "typ", "auth-event+jwt", "kid", KID)));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(
                Map.of("iss", ISSUER, "aud", "mipay-core", "iat", iat, "exp", iat + 300,
                        "jti", event.get("id"), "event", event)));
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(signerKey);
        sig.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        String mac = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        return header + "." + payload + "." + mac;
    }

    private String pendingEnrolledUser() {
        String uid = "ev-" + UUID.randomUUID().toString().substring(0, 8);
        String rootId = jdbc.queryForObject("SELECT id FROM organizations WHERE org_type='PLATFORM' LIMIT 1", String.class);
        User user = new User();
        user.setId(uid);
        user.setName("Event IT");
        user.setPhone("+264810" + System.nanoTime() % 100000000);
        user.setRole(Enums.UserRole.TPTS_OPS);
        user.setStatus(Enums.UserStatus.PENDING);
        user.setOrgId(rootId);
        users.save(user);
        outbox.enqueue(uid, "trustplane", "ENROL",
                Map.of("user_id", uid, "login_name", "ev-" + uid, "display_name", "Event IT"), "enrol:" + uid);
        processor.tick();
        IdentityLink link = linkOf(uid);
        assertTrue(link.getSubject() != null && !link.getSubject().isBlank(), "real IdP subject on the link");
        return uid;
    }

    private IdentityLink linkOf(String uid) {
        return links.findByUserId(uid).stream().filter(l -> "trustplane".equals(l.getProvider())).findFirst().orElseThrow();
    }

    @Test
    void realSignedCredentialSetActivatesLinkAndUserExactlyOnce() throws Exception {
        String uid = pendingEnrolledUser();
        String subject = linkOf(uid).getSubject();
        String jwt = sign(event("CREDENTIAL_SET", subject, uid), Instant.now().getEpochSecond());

        mvc.perform(post("/internal/auth/events").contentType("application/jwt").content(jwt))
                .andExpect(status().isOk());
        assertEquals(IdentityLink.Status.ACTIVE, linkOf(uid).getStatus());
        assertTrue(linkOf(uid).isIdpEnabled());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class, uid));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM auth_events_seen WHERE subject=?",
                Integer.class, subject));

        // redelivery of the same signed event is idempotent
        mvc.perform(post("/internal/auth/events").contentType("application/jwt").content(jwt))
                .andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM auth_events_seen WHERE subject=?",
                Integer.class, subject));
        assertEquals(IdentityLink.Status.ACTIVE, linkOf(uid).getStatus());
    }

    private Map<String, Object> event(String type, String subject, String uid) {
        return Map.of("id", "evt_" + UUID.randomUUID().toString().replace("-", ""),
                "provider", "trustplane", "type", type, "subject", subject, "uid", uid,
                "at", Instant.now().toString());
    }

    @Test
    void forgedSignatureFailsClosed() throws Exception {
        String jwt = sign(event("CREDENTIAL_SET", "no-such", "no-such"), Instant.now().getEpochSecond());
        String[] parts = jwt.split("\\.");
        String forged = parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 4) + "AAAA";
        mvc.perform(post("/internal/auth/events").contentType("application/jwt").content(forged))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongAudienceAndStaleEventsFailClosed() throws Exception {
        String good = sign(event("LOCKED", "s", "u"), Instant.now().getEpochSecond());
        String[] parts = good.split("\\.");
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("mipay-core", "mipay-something-else");
        String wrongAud = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                payloadJson.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        mvc.perform(post("/internal/auth/events").contentType("application/jwt").content(wrongAud))
                .andExpect(status().isBadRequest());

        long stale = Instant.now().getEpochSecond() - 3600;
        String staleJwt = sign(event("LOCKED", "s", "u"), stale);
        mvc.perform(post("/internal/auth/events").contentType("application/jwt").content(staleJwt))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownKidFailsClosed() throws Exception {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(
                Map.of("alg", "ES256", "typ", "auth-event+jwt", "kid", "not-a-real-kid")));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(
                Map.of("iss", ISSUER, "aud", "mipay-core", "iat", Instant.now().getEpochSecond(),
                        "exp", Instant.now().getEpochSecond() + 300, "jti", "evt_unknown",
                        "event", event("LOCKED", "s", "u"))));
        String jwt = header + "." + payload + "." + "A".repeat(21);
        mvc.perform(post("/internal/auth/events").contentType("application/jwt").content(jwt))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lockedEventWritesAuditNotState() throws Exception {
        String uid = pendingEnrolledUser();
        String subject = linkOf(uid).getSubject();
        String jwt = sign(event("LOCKED", subject, uid), Instant.now().getEpochSecond());
        mvc.perform(post("/internal/auth/events").contentType("application/jwt").content(jwt))
                .andExpect(status().isOk());
        assertEquals(IdentityLink.Status.PENDING, linkOf(uid).getStatus(), "LOCKED must not touch PENDING links");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action='CREDENTIAL_LOCKED' AND entity_id=?",
                Integer.class, subject));
        assertNotEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class, uid));
    }

    @AfterAll
    static void dropDb() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:25433/postgres", "t", "t");
             var s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS auth_it_events WITH (FORCE)");
        } catch (Exception ignored) { }
    }
}
