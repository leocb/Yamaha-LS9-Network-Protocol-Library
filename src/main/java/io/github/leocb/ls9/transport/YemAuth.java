package io.github.leocb.ls9.transport;

import java.nio.charset.StandardCharsets;

/**
 * MAC/authentication challenge used by the Yamaha EtherMIDI handshake.
 *
 * <p>When the console's open acknowledgement has bit 31 set in its capability
 * word, the client must answer with command {@code 0x23}. The 16-bit value is
 * computed by the reverse-engineered routine in the Network-MIDI driver:
 * a CRC-32 (polynomial {@code 0x04C11DB7}) over
 * <pre>
 *   ~seed, authData[0..5], "COPYRIGHT (C) YAMAHA Corp."
 * </pre>
 * followed by the driver's 16-bit folding step. {@code seed} is byte 10 of the
 * {@code 0x21} acknowledgement and {@code authData} is the six-byte MAC
 * address configured for the console.
 */
public final class YemAuth {

    private static final int POLY = 0x04C11DB7;
    private static final int[] TABLE = new int[256];
    private static final byte[] COPYRIGHT = "COPYRIGHT (C) YAMAHA Corp.".getBytes(StandardCharsets.US_ASCII);

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ POLY : crc << 1;
            }
            TABLE[i] = crc;
        }
    }

    private YemAuth() {
    }

    /**
     * @param seed     byte 10 of the console's {@code 0x21} reply (0..255)
     * @param authData six-byte MAC address
     * @return 16-bit value for bytes 8..9 of the {@code 0x23} request
     */
    public static int checksum(int seed, byte[] authData) {
        if (authData == null || authData.length != 6) {
            throw new IllegalArgumentException("authData must be exactly 6 bytes (a MAC address)");
        }
        int crc = TABLE[(~seed) & 0xFF] ^ 0xFFFFFF00;
        int last = crc;
        for (byte b : authData) {
            last = TABLE[((crc >>> 24) ^ (b & 0xFF)) & 0xFF];
            crc = last ^ (crc << 8);
        }
        for (byte b : COPYRIGHT) {
            last = TABLE[((crc >>> 24) ^ (b & 0xFF)) & 0xFF];
            crc = last ^ (crc << 8);
        }
        int low = (last & 0xFF) << 8;
        int high = TABLE[(crc >>> 24) & 0xFF] & 0xFFFF;
        return (high ^ low) & 0xFFFF;
    }

    /** Parses {@code AA:BB:CC:DD:EE:FF}, {@code AA-BB-...} or 12 hex digits. */
    public static byte[] parseMac(String mac) {
        if (mac == null) {
            throw new IllegalArgumentException("MAC address is null");
        }
        String s = mac.replace(":", "").replace("-", "").replace(".", "").trim();
        if (s.length() != 12) {
            throw new IllegalArgumentException("MAC address must contain 12 hex digits: " + mac);
        }
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String formatMac(byte[] mac) {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02X", mac[i] & 0xFF));
        }
        return sb.toString();
    }
}
