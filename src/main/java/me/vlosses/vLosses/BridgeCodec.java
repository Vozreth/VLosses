package me.vlosses.vLosses;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

final class BridgeCodec {
    static final int MAX_PAYLOAD = 256;
    private static final int SIGNATURE_LENGTH = 32;
    private static final int VERSION = 1;
    private static final Pattern SERVER = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern LANGUAGE = Pattern.compile("[a-zA-Z]{2,3}(?:[-_][a-zA-Z]{2,4})?");
    private final Mac mac;

    BridgeCodec(String secret) {
        if (secret == null || secret.length() < 32 || secret.length() > 4096 || secret.isBlank()) {
            throw new IllegalArgumentException("Bridge secret must contain 32 to 4096 characters");
        }
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 unavailable", exception);
        }
    }

    byte[] encode(Preference preference) {
        if (preference == null || preference.playerId() == null || preference.nonce() == null
                || !validServer(preference.source()) || !validLanguage(preference.language())) {
            throw new IllegalArgumentException("Invalid bridge preference");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(160);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeByte(VERSION);
            output.writeLong(preference.timestamp());
            writeUuid(output, preference.nonce());
            writeUuid(output, preference.playerId());
            output.writeUTF(preference.source());
            output.writeUTF(preference.language());
            byte[] body = bytes.toByteArray();
            output.write(sign(body, body.length));
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    Optional<Preference> decode(byte[] payload) {
        if (payload == null || payload.length < 80 || payload.length > MAX_PAYLOAD) {
            return Optional.empty();
        }
        int bodyLength = payload.length - SIGNATURE_LENGTH;
        byte[] receivedSignature = Arrays.copyOfRange(payload, bodyLength, payload.length);
        if (!MessageDigest.isEqual(sign(payload, bodyLength), receivedSignature)) {
            return Optional.empty();
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload, 0, bodyLength))) {
            if (input.readUnsignedByte() != VERSION) {
                return Optional.empty();
            }
            long timestamp = input.readLong();
            UUID nonce = readUuid(input);
            UUID playerId = readUuid(input);
            String source = input.readUTF();
            String language = input.readUTF();
            if (input.available() != 0 || !validServer(source) || !validLanguage(language)) {
                return Optional.empty();
            }
            return Optional.of(new Preference(playerId, language, source, timestamp, nonce));
        } catch (IOException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    static boolean validServer(String server) {
        return server != null && SERVER.matcher(server).matches();
    }

    private static boolean validLanguage(String language) {
        return language != null && LANGUAGE.matcher(language).matches();
    }

    private synchronized byte[] sign(byte[] data, int length) {
        mac.update(data, 0, length);
        return mac.doFinal();
    }

    private static void writeUuid(DataOutputStream output, UUID uuid) throws IOException {
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    record Preference(UUID playerId, String language, String source, long timestamp, UUID nonce) { }

    static final class ReplayGuard {
        static final long WINDOW_MILLIS = 60_000;
        static final long FUTURE_MILLIS = 5_000;
        private final int capacity;
        private final Map<UUID, Long> seen = new HashMap<>();
        private long lastCleanup;

        ReplayGuard(int capacity) {
            if (capacity < 1 || capacity > 65_536) {
                throw new IllegalArgumentException("Invalid replay cache capacity");
            }
            this.capacity = capacity;
        }

        boolean accept(Preference preference, long now) {
            long timestamp = preference.timestamp();
            if (timestamp < now - WINDOW_MILLIS || timestamp > now + FUTURE_MILLIS) {
                return false;
            }
            if (now < lastCleanup || now - lastCleanup >= 1_000 || seen.size() >= capacity) {
                seen.values().removeIf(expiry -> expiry < now);
                lastCleanup = now;
            }
            if (seen.containsKey(preference.nonce()) || seen.size() >= capacity) {
                return false;
            }
            seen.put(preference.nonce(), timestamp + WINDOW_MILLIS);
            return true;
        }

        int size() {
            return seen.size();
        }

        void clear() {
            seen.clear();
        }
    }
}
