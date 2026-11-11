package trustplane.shared;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Loads the platform PEM material (server certs, mesh client certs, CA certs)
 * into JDK keystores / SSL machinery. All trustplane keys are unencrypted
 * PKCS#8 ("BEGIN PRIVATE KEY"), certs are standard PEM chains.
 */
public final class PemKeyStore {

    private PemKeyStore() {}

    public static PrivateKey loadPrivateKey(String pemPath) throws Exception {
        String pem = java.nio.file.Files.readString(java.nio.file.Path.of(pemPath), StandardCharsets.US_ASCII);
        String b64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                        .replaceAll("-----END [A-Z ]+-----", "")
                        .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String alg : new String[]{"RSA", "EC"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (Exception ignore) { /* try next */ }
        }
        throw new IllegalStateException("unsupported private key in " + pemPath);
    }

    public static List<Certificate> loadCertificates(String pemPath) throws Exception {
        String pem = java.nio.file.Files.readString(java.nio.file.Path.of(pemPath), StandardCharsets.US_ASCII);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<Certificate> out = new ArrayList<>();
        for (String block : pem.split("(?=-----BEGIN CERTIFICATE-----)")) {
            if (!block.contains("BEGIN CERTIFICATE")) continue;
            try (InputStream in = new ByteArrayInputStream(block.getBytes(StandardCharsets.US_ASCII))) {
                out.add(cf.generateCertificate(in));
            }
        }
        return out;
    }

    /** PKCS12 keystore holding the private key + its certificate chain. */
    public static KeyStore keyStore(String certPem, String keyPem) throws Exception {
        PrivateKey key = loadPrivateKey(keyPem);
        List<Certificate> chain = loadCertificates(certPem);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("mesh", key, new char[0], chain.toArray(new Certificate[0]));
        return ks;
    }

    /** Truststore with the platform CA (and any additional root in the file). */
    public static KeyStore trustStore(String caPem) throws Exception {
        KeyStore ts = KeyStore.getInstance("PKCS12");
        ts.load(null, null);
        List<Certificate> cas = loadCertificates(caPem);
        for (int i = 0; i < cas.size(); i++) {
            ts.setCertificateEntry("ca-" + i, cas.get(i));
        }
        return ts;
    }

    public static KeyManagerFactory kmf(String certPem, String keyPem) throws Exception {
        KeyManagerFactory f = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        f.init(keyStore(certPem, keyPem), new char[0]);
        return f;
    }

    public static TrustManagerFactory tmf(String caPem) throws Exception {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(trustStore(caPem));
        return f;
    }

    /** Client SSLContext presenting our mesh certificate (mutual TLS). */
    public static SSLContext clientContext(String certPem, String keyPem, String caPem) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf(certPem, keyPem).getKeyManagers(), tmf(caPem).getTrustManagers(), new java.security.SecureRandom());
        // allow TLS 1.2+ only (platform floor)
        ctx.getDefaultSSLParameters().setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        return ctx;
    }
}
