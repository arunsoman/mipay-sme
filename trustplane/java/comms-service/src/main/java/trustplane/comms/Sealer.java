package trustplane.comms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Go B0-compatible encryption for contact addresses and queued payloads. */
public final class Sealer {
    private static final int NONCE_BYTES = 12;
    private final byte[] encryptionKey;
    private final byte[] indexKey;
    private final SecureRandom random = new SecureRandom();

    public static Sealer load(Path path) throws IOException, GeneralSecurityException {
        byte[] raw = Files.readAllBytes(path);
        // The existing Go service trims whitespace, then accepts either hex or raw bytes.
        byte[] material = new String(raw, StandardCharsets.UTF_8).strip().getBytes(StandardCharsets.UTF_8);
        try {
            material = HexFormat.of().parseHex(new String(material, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ignored) {
            // Not hex: use the trimmed raw key, matching the existing implementation.
        }
        return new Sealer(material);
    }

    public Sealer(byte[] material) throws GeneralSecurityException {
        if (material.length < 32) throw new IllegalArgumentException("comms data key must hold at least 32 bytes");
        encryptionKey = hmac(material, "comms/aes-gcm/v1");
        indexKey = hmac(material, "comms/blind-index/v1");
    }

    public byte[] seal(byte[] plaintext) throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] result = Arrays.copyOf(nonce, nonce.length + encrypted.length);
        System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
        return result;
    }

    public byte[] open(byte[] ciphertext) throws GeneralSecurityException {
        if (ciphertext.length < NONCE_BYTES + 16) throw new GeneralSecurityException("ciphertext too short");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                new GCMParameterSpec(128, ciphertext, 0, NONCE_BYTES));
        return cipher.doFinal(ciphertext, NONCE_BYTES, ciphertext.length - NONCE_BYTES);
    }

    public String blindIndex(String channel, String address) throws GeneralSecurityException {
        return HexFormat.of().formatHex(hmac(indexKey, channel + ":" + address));
    }

    private static byte[] hmac(byte[] key, String message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }
}
