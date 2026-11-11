package com.mipay.sme.security.rbac;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * mTLS client context for the trustplane mesh, from PEM files (pki-init output):
 * MESH_CERT, MESH_KEY (PKCS#8, EC or RSA), MESH_CA. Defaults to /pki/mipay-mesh*.pem.
 */
final class MeshTls {
    private MeshTls() { }

    static SSLContext clientContext() throws Exception {
        String cert = env("MESH_CERT", "/pki/mipay-mesh.pem");
        String key = env("MESH_KEY", "/pki/mipay-mesh-key.pem");
        String ca = env("MESH_CA", "/pki/platform-ca.pem");
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate leaf;
        try (var in = new FileInputStream(cert)) { leaf = cf.generateCertificate(in); }
        String pem = Files.readString(Path.of(key)).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        PrivateKey pk;
        try { pk = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der)); }
        catch (Exception e) { pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der)); }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("mesh", pk, new char[0], new Certificate[]{leaf});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        KeyStore ts = KeyStore.getInstance("PKCS12");
        ts.load(null, null);
        try (var in = new FileInputStream(ca)) { ts.setCertificateEntry("ca", cf.generateCertificate(in)); }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}
