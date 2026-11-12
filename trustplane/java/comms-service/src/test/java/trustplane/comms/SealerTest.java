package trustplane.comms;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class SealerTest {
    @Test void encryptedPayloadsRoundTripAndAreRandomised() throws Exception {
        Sealer sealer = new Sealer("k".repeat(32).getBytes(StandardCharsets.UTF_8));
        byte[] plain = "secret code".getBytes(StandardCharsets.UTF_8);
        byte[] first = sealer.seal(plain), second = sealer.seal(plain);
        assertArrayEquals(plain, sealer.open(first));
        assertFalse(Arrays.equals(first, second));
        first[first.length - 1] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> sealer.open(first));
    }

    @Test void rejectsShortKeysAndShortCiphertexts() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new Sealer(new byte[31]));
        Sealer sealer = new Sealer(new byte[32]);
        assertThrows(GeneralSecurityException.class, () -> sealer.open(new byte[27]));
    }

    @Test void indexesAreDeterministicAndChannelSpecific() throws Exception {
        Sealer sealer = new Sealer(new byte[32]);
        assertEquals(sealer.blindIndex("sms", "123456"), sealer.blindIndex("sms", "123456"));
        assertNotEquals(sealer.blindIndex("sms", "123456"), sealer.blindIndex("email", "123456"));
    }

    @Test void opensCompatibleNoncePrefixedGcmAndBlindIndexVectors() throws Exception {
        Sealer sealer = new Sealer("k".repeat(32).getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = HexFormat.of().parseHex("000102030405060708090a0b03d24413d766fbb5812159c99f97a5f5cc15a9bfed");
        assertEquals("hello", new String(sealer.open(ciphertext), StandardCharsets.UTF_8));
        assertEquals("c15ff2e7465709cef54072bf6fee7c076467021b061d86eef28f30a39f33de8d",
                sealer.blindIndex("sms", "+264810000042"));
    }
}
