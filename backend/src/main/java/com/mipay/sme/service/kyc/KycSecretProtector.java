package com.mipay.sme.service.kyc;

import com.mipay.sme.domain.kyc.KycField;
import com.mipay.sme.repository.KycSearchIndexRepository;
import com.mipay.sme.service.kyc.KycMasterService.ProfileBundle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SECRET values are encrypted where they live, inside the JSON (§8): the
 * plaintext is replaced by an {"enc": "v1:<iv>:<ct>"} object before the
 * document is stored, and restored when it is read. SECRET and PII values get
 * an HMAC blind index row for duplicate checks and ops search. Key comes from
 * the environment (never from the database); the default is sandbox-only.
 */
@Service
@Slf4j
public class KycSecretProtector {

    private static final String ENC_PREFIX = "v1:";
    private static final int IV_BYTES = 12;

    private final byte[] key;
    private final KycSearchIndexRepository searchIndex;
    private final String keyId;

    private final SecureRandom random = new SecureRandom();

    public KycSecretProtector(KycSearchIndexRepository searchIndex,
                              @Value("${app.kyc.encryption-key:sandbox-kyc-key-change-me-0123456789abcdef}") String keyHex,
                              @Value("${app.kyc.encryption-key-id:k1}") String keyId) {
        this.searchIndex = searchIndex;
        this.keyId = keyId;
        this.key = deriveKey(keyHex);
        log.info("KYC sensitive-data protection initialised (key {} bits, "
                        + "sandbox default only — set app.kyc.encryption-key in production)",
                this.key.length * 8);
    }

    private static byte[] deriveKey(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("KYC encryption unavailable", e);
        }
    }

    // ---------------------------------------------------------------- document transforms

    /** Replaces SECRET values (inside OBJECT sections and LIST items) with encrypted objects. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> encryptSecrets(Map<String, Object> doc, ProfileBundle bundle, String applicationId) {
        for (var e : bundle.allFieldsBySectionKey().entrySet()) {
            KycField field = e.getValue();
            if (field.getSensitivity() != KycField.Sensitivity.SECRET) continue;
            String[] parts = e.getKey().split("\\.", 2);
            if (parts.length < 2) continue;
            Object body = doc.get(parts[0]);
            if (body instanceof Map && ((Map<String, Object>) body).get(parts[1]) != null) {
                encryptPut((Map<String, Object>) body, parts[1]);
            }
            if (body instanceof List<?> l) {
                for (Object item : l) {
                    if (item instanceof Map && ((Map<String, Object>) item).get(parts[1]) != null) {
                        encryptPut((Map<String, Object>) item, parts[1]);
                    }
                }
            }
        }
        if (applicationId != null) {
            indexSecrets(applicationId, bundle, doc);
        }
        return doc;
    }

    /** Rebuilds the blind index for one application (§8): rows are cleared per tag, never duplicated. */
    public void indexSecrets(String applicationId, ProfileBundle bundle, Map<String, Object> encryptedDoc) {
        if (applicationId == null) return;
        for (var e : bundle.allFieldsBySectionKey().entrySet()) {
            KycField field = e.getValue();
            if (field.getSensitivity() != KycField.Sensitivity.SECRET
                    || field.getCanonicalTag() == null) continue;
            String[] parts = e.getKey().split("\\.", 2);
            if (parts.length < 2) continue;
            List<String> plaintexts = secretPlaintexts(encryptedDoc, parts[0], parts[1]);
            try {
                searchIndex.deleteByApplicationIdAndCanonicalTag(applicationId, field.getCanonicalTag());
            } catch (Exception ex) {
                log.warn("blind index clear failed for {}: {}", field.getCanonicalTag(), ex.getMessage());
            }
            for (String v : plaintexts) {
                indexSecret(applicationId, field.getCanonicalTag(), v);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void encryptPut(Map<String, Object> holder, String key) {
        Object value = holder.get(key);
        if (value instanceof Map && ((Map<String, Object>) value).containsKey("enc")) return;
        if (value != null && !isEncrypted(value.toString())) {
            holder.put(key, java.util.Map.of("enc", encrypt(value.toString())));
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> secretPlaintexts(Map<String, Object> doc, String section, String key) {
        List<String> values = new ArrayList<>();
        Object body = doc.get(section);
        if (body instanceof Map) {
            Object v = ((Map<String, Object>) body).get(key);
            if (v != null) values.add(decrypt(ciphertextOf(v)));
        }
        if (body instanceof List<?> l) {
            for (Object item : l) {
                if (item instanceof Map) {
                    Object v = ((Map<String, Object>) item).get(key);
                    if (v != null) values.add(decrypt(ciphertextOf(v)));
                }
            }
        }
        return values;
    }

    /** Restores SECRET plaintext for readers who may see it. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> decryptSecrets(Map<String, Object> doc, ProfileBundle bundle) {
        for (var e : bundle.allFieldsBySectionKey().entrySet()) {
            KycField field = e.getValue();
            if (field.getSensitivity() != KycField.Sensitivity.SECRET) continue;
            String[] parts = e.getKey().split("\\.", 2);
            if (parts.length < 2) continue;
            Object body = doc.get(parts[0]);
            if (body instanceof Map && ((Map<String, Object>) body).get(parts[1]) != null) {
                decryptPut((Map<String, Object>) body, parts[1]);
            }
            if (body instanceof List<?> l) {
                for (Object item : l) {
                    if (item instanceof Map && ((Map<String, Object>) item).get(parts[1]) != null) {
                        decryptPut((Map<String, Object>) item, parts[1]);
                    }
                }
            }
        }
        return doc;
    }

    @SuppressWarnings("unchecked")
    private void decryptPut(Map<String, Object> holder, String key) {
        Object value = holder.get(key);
        if (value == null) return;
        String ct = ciphertextOf(value);
        if (ct != null && isEncrypted(ct)) {
            holder.put(key, decrypt(ct));
        }
    }

    /** Masks PII/SECRET fields per the field's mask rule (ancestors never see plaintext). */
    public Map<String, Object> maskForAncestor(Map<String, Object> doc, ProfileBundle bundle) {
        Map<String, Object> out = new LinkedHashMap<>(decryptSecrets(doc, bundle));
        for (var e : bundle.allFieldsBySectionKey().entrySet()) {
            KycField field = e.getValue();
            if (field.getSensitivity() == KycField.Sensitivity.NONE) continue;
            KycField.Mask effective = field.getMask() != null
                    ? field.getMask()
                    : (field.getSensitivity() == KycField.Sensitivity.PII ? KycField.Mask.HIDE : null);
            if (effective == null) continue;
            KycField.Mask maskKind = effective;
            String[] parts = e.getKey().split("\\.", 2);
            if (parts.length < 2) continue;
            Object body = out.get(parts[0]);
            if (body instanceof Map) {
                maskPut((Map<String, Object>) body, parts[1], maskKind);
            }
            if (body instanceof List<?> l) {
                for (Object item : l) {
                    if (item instanceof Map) {
                        maskPut((Map<String, Object>) item, parts[1], maskKind);
                    }
                }
            }
        }
        return out;
    }

    /** Applies the master's mask rule to a plaintext value (§8). */
    private String mask(String plaintext, KycField.Mask maskKind) {
        if (plaintext == null) return null;
        return switch (maskKind) {
            case LAST4 -> plaintext.length() <= 4 ? "****" : "****" + plaintext.substring(plaintext.length() - 4);
            case HIDE -> "••••••";
            case YEAR_ONLY -> plaintext.length() >= 4 ? plaintext.substring(plaintext.length() - 4) : "••••";
        };
    }

    @SuppressWarnings("unchecked")
    private void maskPut(Map<String, Object> holder, String key, KycField.Mask maskKind) {
        Object value = holder.get(key);
        if (value == null) return;
        String ct = ciphertextOf(value);
        holder.put(key, mask(isEncrypted(ct) ? decrypt(ct) : ct, maskKind));
    }

    // ---------------------------------------------------------------- crypto

    public synchronized String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // the payload carries its key id for rotation (§8)
            return ENC_PREFIX + keyId + ":" + HexFormat.of().formatHex(iv) + ":" + HexFormat.of().formatHex(ct);
        } catch (Exception e) {
            throw new IllegalStateException("KYC value encryption failed", e);
        }
    }

    /** @return the ciphertext of a cell: either the {"enc": ...} envelope or the legacy v1: string. */
    @SuppressWarnings("unchecked")
    public String ciphertextOf(Object cell) {
        if (cell instanceof Map && ((Map<String, Object>) cell).get("enc") != null) {
            return String.valueOf(((Map<String, Object>) cell).get("enc"));
        }
        return cell == null ? null : cell.toString();
    }

    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(ENC_PREFIX);
    }

    public String decrypt(String stored) {
        if (!isEncrypted(stored)) return stored;
        try {
            String rest = stored.substring(ENC_PREFIX.length());
            String ivHex;
            String ctHex;
            if (rest.contains(":") && rest.indexOf(":") <= 4) {   // v1:<keyId>:<iv>:<ct>
                rest = rest.substring(rest.indexOf(":") + 1);
            }   // legacy v1:<iv>:<ct> falls through with the configured key
            ivHex = rest.substring(0, IV_BYTES * 2);
            ctHex = rest.substring(IV_BYTES * 2 + 1);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, HexFormat.of().parseHex(ivHex)));
            return new String(cipher.doFinal(HexFormat.of().parseHex(ctHex)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("KYC value decryption failed", e);
        }
    }

    private String plaintextOf(String stored) {
        return isEncrypted(stored) ? decrypt(stored) : stored;
    }
    /** HMAC blind index row; duplicate checks and ops search never decrypt (§8). */
    public String hmac(String canonicalTag, String plaintext) {        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] tagKey = deriveKey(new String(HexFormat.of().formatHex(key))
                    + ":" + canonicalTag);
            mac.init(new SecretKeySpec(tagKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("KYC blind index failed", e);
        }
    }

    private void indexSecret(String applicationId, String canonicalTag, String plaintext) {
        if (applicationId == null || plaintext == null || canonicalTag == null) return;
        try {
            searchIndex.save(new com.mipay.sme.domain.kyc.KycSearchIndexRow(
                    applicationId, canonicalTag, hmac(canonicalTag, plaintext)));
        } catch (Exception e) {
            log.warn("blind index insert skipped: {}", e.getMessage());
        }
    }
}
