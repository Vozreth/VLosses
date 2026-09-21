package me.vlosses.vLosses;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeCodecTest {
    private static final String SECRET = "test-secret-32-characters-or-more-1234567890";
    private static final long NOW = 1_800_000_000_000L;
    private final BridgeCodec codec = new BridgeCodec(SECRET);

    @Test
    void authenticatedPreferenceRoundTripsWithoutChangingIdentity() {
        BridgeCodec.Preference preference = preference(NOW, "pt_BR");
        byte[] payload = codec.encode(preference);
        assertTrue(payload.length <= BridgeCodec.MAX_PAYLOAD);
        assertEquals(preference, codec.decode(payload).orElseThrow());
    }

    @Test
    void everyTamperedByteInvalidatesAuthentication() {
        byte[] payload = codec.encode(preference(NOW, "tr"));
        for (int index = 0; index < payload.length; index++) {
            byte[] changed = payload.clone();
            changed[index] ^= 1;
            assertTrue(codec.decode(changed).isEmpty(), "Accepted modified byte " + index);
        }
    }

    @Test
    void anotherNetworkSecretCannotAuthenticateTheMessage() {
        BridgeCodec other = new BridgeCodec("another-independent-network-secret-1234567890");
        assertTrue(other.decode(codec.encode(preference(NOW, "de"))).isEmpty());
    }

    @Test
    void truncatedOversizedAndNullMessagesAreRejected() {
        byte[] payload = codec.encode(preference(NOW, "en"));
        for (int length = 0; length < payload.length; length++) {
            assertTrue(codec.decode(Arrays.copyOf(payload, length)).isEmpty());
        }
        assertTrue(codec.decode(new byte[BridgeCodec.MAX_PAYLOAD + 1]).isEmpty());
        assertTrue(codec.decode(null).isEmpty());
    }

    @Test
    void evenAuthenticatedUnknownVersionsAndTrailingBytesAreRejected() throws Exception {
        byte[] payload = codec.encode(preference(NOW, "en"));
        byte[] body = Arrays.copyOf(payload, payload.length - 32);
        body[0] = 2;
        assertTrue(codec.decode(signed(body)).isEmpty());
        body[0] = 1;
        assertTrue(codec.decode(signed(Arrays.copyOf(body, body.length + 1))).isEmpty());
    }

    @Test
    void authenticatedMalformedStringsAndInvalidValuesAreRejected() throws Exception {
        assertTrue(codec.decode(signed(rawBody("lobby", "en", true))).isEmpty());
        assertTrue(codec.decode(signed(rawBody("bad/server", "en", false))).isEmpty());
        assertTrue(codec.decode(signed(rawBody("lobby", "<red>en", false))).isEmpty());
        assertTrue(codec.decode(signed(rawBody("x".repeat(65), "en", false))).isEmpty());
    }

    @Test
    void secretAndOutgoingIdentifiersAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new BridgeCodec("too-short"));
        assertThrows(IllegalArgumentException.class, () -> new BridgeCodec(" ".repeat(32)));
        assertThrows(IllegalArgumentException.class, () -> new BridgeCodec("x".repeat(4097)));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(preference(NOW, "en\ncommand")));
        assertFalse(BridgeCodec.validServer("survival;stop"));
        assertFalse(BridgeCodec.validServer(null));
        assertTrue(BridgeCodec.validServer("survival-1.eu"));
    }

    @Test
    void sameNonceIsAcceptedOnlyOnceIncludingAcrossPlayerIdentities() {
        BridgeCodec.ReplayGuard replay = new BridgeCodec.ReplayGuard(10);
        BridgeCodec.Preference preference = preference(NOW, "en");
        assertTrue(replay.accept(preference, NOW));
        assertFalse(replay.accept(preference, NOW));
        assertFalse(replay.accept(new BridgeCodec.Preference(UUID.randomUUID(), "de", "other",
                NOW, preference.nonce()), NOW));
    }

    @Test
    void timestampsOutsideWindowAreRejectedWithoutConsumingCapacity() {
        BridgeCodec.ReplayGuard replay = new BridgeCodec.ReplayGuard(10);
        assertFalse(replay.accept(preference(NOW - 60_001, "en"), NOW));
        assertFalse(replay.accept(preference(NOW + 5_001, "en"), NOW));
        assertFalse(replay.accept(preference(Long.MIN_VALUE, "en"), NOW));
        assertFalse(replay.accept(preference(Long.MAX_VALUE, "en"), NOW));
        assertEquals(0, replay.size());
        assertTrue(replay.accept(preference(NOW - 60_000, "en"), NOW));
        assertTrue(replay.accept(preference(NOW + 5_000, "en"), NOW));
    }

    @Test
    void fullReplayCacheFailsClosedWithoutEvictingLiveNonces() {
        BridgeCodec.ReplayGuard replay = new BridgeCodec.ReplayGuard(2);
        BridgeCodec.Preference first = preference(NOW, "en");
        assertTrue(replay.accept(first, NOW));
        assertTrue(replay.accept(preference(NOW, "tr"), NOW));
        assertFalse(replay.accept(preference(NOW, "de"), NOW));
        assertFalse(replay.accept(first, NOW));
        assertEquals(2, replay.size());
        assertTrue(replay.accept(preference(NOW + 60_001, "de"), NOW + 60_001));
        assertEquals(1, replay.size());
        assertFalse(replay.accept(first, NOW + 60_001));
    }

    @Test
    void futureDatedMessageRetainsReplayProtectionThroughoutItsValidity() {
        BridgeCodec.ReplayGuard replay = new BridgeCodec.ReplayGuard(2);
        BridgeCodec.Preference future = preference(NOW + 5_000, "en");
        assertTrue(replay.accept(future, NOW));
        assertFalse(replay.accept(future, NOW + 60_001));
        assertFalse(replay.accept(future, NOW + 65_000));
        assertFalse(replay.accept(future, NOW + 65_001));
    }

    private static BridgeCodec.Preference preference(long timestamp, String language) {
        return new BridgeCodec.Preference(UUID.randomUUID(), language, "lobby-1", timestamp, UUID.randomUUID());
    }

    private static byte[] signed(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] result = Arrays.copyOf(body, body.length + 32);
        System.arraycopy(mac.doFinal(body), 0, result, body.length, 32);
        return result;
    }

    private static byte[] rawBody(String server, String language, boolean malformed) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(1);
        output.writeLong(NOW);
        for (int part = 0; part < 4; part++) {
            output.writeLong(part);
        }
        if (malformed) {
            output.writeShort(60_000);
            output.writeUTF(server);
        } else {
            output.writeUTF(server);
        }
        output.writeUTF(language);
        return bytes.toByteArray();
    }
}
