package io.github.leocb.ls9.transport;

import java.util.Arrays;

/**
 * Yamaha EtherMIDI packet framing, as used by the Network-MIDI driver
 * ({@code EtherMIDIDriver-2.0.0}, {@code com.yamaha.ethermidi}).
 *
 * <p>A packet is a 16-byte big-endian header whose first 32-bit word is the
 * total packet length. The command byte lives at offset 4. For System
 * Exclusive data (command {@code 0x16}) the header is followed by a 4-byte
 * big-endian copy of the SysEx length and then the SysEx bytes, so the total
 * length is {@code 20 + sysex.length}. Short MIDI messages (commands
 * {@code 0x11..0x13}) fit in the 16-byte header itself.
 */
public final class EtherMidiPacket {

    public static final int HEADER_SIZE = 16;
    public static final int SYSEX_HEADER_SIZE = 20;
    public static final int MAX_PACKET_SIZE = 5000;

    public static final int CMD_MIDI_1 = 0x11;
    public static final int CMD_MIDI_2 = 0x12;
    public static final int CMD_MIDI_3 = 0x13;
    public static final int CMD_SYSEX = 0x16;
    public static final int CMD_OPEN = 0x20;
    public static final int CMD_OPEN_ACK = 0x21;
    public static final int CMD_AUTH = 0x23;
    public static final int CMD_AUTH_ACK = 0x24;
    public static final int CMD_CLOSE = 0x30;
    public static final int CMD_RESET = 0x40;

    private EtherMidiPacket() {
    }

    /** A decoded EtherMIDI frame. */
    public record Frame(int command, int port, int rxDevice, byte[] payload) {
        public boolean isSysex() {
            return command == CMD_SYSEX;
        }

        public boolean isShortMidi() {
            return command >= CMD_MIDI_1 && command <= CMD_MIDI_3;
        }
    }

    /**
     * Builds a packet carrying a complete System Exclusive message.
     *
     * @param port      MIDI port index (0-based; "Port1" is 0)
     * @param rxDevice  device/MIDI port number (1 for an LS9)
     * @param sysex     complete {@code F0 ... F7} message
     */
    public static byte[] sysex(int port, int rxDevice, byte[] sysex) {
        requireSysex(sysex);
        int total = SYSEX_HEADER_SIZE + sysex.length;
        if (total > MAX_PACKET_SIZE) {
            throw new IllegalArgumentException(
                    "SysEx message too large for one EtherMIDI packet: " + total + " > " + MAX_PACKET_SIZE);
        }
        byte[] p = new byte[total];
        putInt(p, 0, total);
        p[4] = (byte) CMD_SYSEX;
        p[5] = 0;
        p[6] = (byte) port;
        p[7] = (byte) rxDevice;
        putInt(p, 8, sysex.length);
        putInt(p, 12, 0xFFFFFFFF);
        putInt(p, 16, sysex.length);
        System.arraycopy(sysex, 0, p, SYSEX_HEADER_SIZE, sysex.length);
        return p;
    }

    /**
     * Builds a packet carrying a short MIDI message (1..3 bytes).
     */
    public static byte[] shortMidi(int port, int rxDevice, byte[] midi) {
        if (midi == null || midi.length < 1 || midi.length > 3) {
            throw new IllegalArgumentException("short MIDI message must have 1..3 bytes");
        }
        byte[] p = new byte[HEADER_SIZE];
        putInt(p, 0, HEADER_SIZE);
        p[4] = (byte) (0x10 + midi.length);
        p[5] = 0;
        p[6] = (byte) port;
        p[7] = (byte) rxDevice;
        System.arraycopy(midi, 0, p, 8, midi.length);
        putInt(p, 12, 0xFFFFFFFF);
        return p;
    }

    /** Builds the 16-byte open request used by the Yamaha driver. */
    public static byte[] openRequest(int port, int rxDevice, int modeBitmap) {
        byte[] p = new byte[HEADER_SIZE];
        putInt(p, 0, HEADER_SIZE);
        p[4] = (byte) CMD_OPEN;
        p[5] = 0;
        p[6] = (byte) port;
        p[7] = (byte) rxDevice;
        p[8] = 1;
        p[9] = 0;
        putInt(p, 12, modeBitmap | 0x20000000);
        return p;
    }

    /** Builds the 16-byte authentication request (MAC challenge response). */
    public static byte[] authRequest(int port, int rxDevice, int checksum) {
        byte[] p = new byte[HEADER_SIZE];
        putInt(p, 0, HEADER_SIZE);
        p[4] = (byte) CMD_AUTH;
        p[5] = 0;
        p[6] = (byte) port;
        p[7] = (byte) rxDevice;
        p[8] = (byte) (checksum >>> 8);
        p[9] = (byte) checksum;
        return p;
    }

    /** Builds the 16-byte close notification. */
    public static byte[] closeRequest(int port, int rxDevice) {
        byte[] p = new byte[HEADER_SIZE];
        putInt(p, 0, HEADER_SIZE);
        p[4] = (byte) CMD_CLOSE;
        p[5] = 0;
        p[6] = (byte) port;
        p[7] = (byte) rxDevice;
        putInt(p, 12, 0xFFFFFFFF);
        return p;
    }

    /** Decodes a packet whose first 4 bytes have already been validated. */
    public static Frame decode(byte[] packet) {
        if (packet.length < HEADER_SIZE) {
            throw new IllegalArgumentException("packet shorter than 16 bytes");
        }
        int length = getInt(packet, 0);
        if (length != packet.length) {
            throw new IllegalArgumentException("length field " + length + " != actual " + packet.length);
        }
        int command = packet[4] & 0xFF;
        int port = packet[6] & 0xFF;
        int rxDevice = packet[7] & 0xFF;
        byte[] payload;
        if (command == CMD_SYSEX) {
            if (packet.length < SYSEX_HEADER_SIZE) {
                throw new IllegalArgumentException("SysEx packet shorter than 20 bytes");
            }
            int sysexLength = getInt(packet, 8);
            // The driver duplicates the length at offset 16, but the receive
            // state machine ignores those four bytes. Do the same and trust the
            // header length when it is sane.
            if (sysexLength <= 0 || SYSEX_HEADER_SIZE + sysexLength != packet.length) {
                sysexLength = packet.length - SYSEX_HEADER_SIZE;
            }
            payload = Arrays.copyOfRange(packet, SYSEX_HEADER_SIZE,
                    SYSEX_HEADER_SIZE + sysexLength);
        } else if (command >= CMD_MIDI_1 && command <= CMD_MIDI_3) {
            payload = Arrays.copyOfRange(packet, 8, 8 + (command - 0x10));
        } else {
            payload = Arrays.copyOfRange(packet, 8, packet.length);
        }
        return new Frame(command, port, rxDevice, payload);
    }

    public static void putInt(byte[] b, int off, int value) {
        b[off] = (byte) (value >>> 24);
        b[off + 1] = (byte) (value >>> 16);
        b[off + 2] = (byte) (value >>> 8);
        b[off + 3] = (byte) value;
    }

    public static int getInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    public static String hex(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private static void requireSysex(byte[] sysex) {
        if (sysex == null || sysex.length < 2 || (sysex[0] & 0xFF) != 0xF0
                || (sysex[sysex.length - 1] & 0xFF) != 0xF7) {
            throw new IllegalArgumentException("not a complete SysEx message (F0 ... F7)");
        }
    }
}
