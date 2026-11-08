package io.github.leocb.ls9.bulk;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Encoder/decoder for the LS9 BULK DUMP and BULK DUMP REQUEST messages.
 *
 * <p>Request (confirmed by an actual LS9 example):
 * <pre>
 *   F0 43 2n 3E 12 "SCENE___" 00 2D F7
 * </pre>
 * Data packet:
 * <pre>
 *   F0 43 0n 3E cc cc 12 "SCENE___" dd dd  ll ii &lt;encoded data&gt; ee F7
 * </pre>
 * The two count bytes come before the model ID. {@code ll} is the zero-based
 * last-packet index and {@code ii} is the zero-based packet index; both are 0
 * for a single-packet block. The checksum is calculated over the model byte
 * through the last data byte.
 */
public final class BulkDumpCodec {

    public static final int YAMAHA_ID = 0x43;
    public static final int GROUP_ID = 0x3E;
    public static final int MODEL_ID = 0x12;

    public static final int SUB_DATA = 0x00;
    public static final int SUB_REQUEST = 0x20;

    /** Offset of the encoded payload in an LS9 data packet. */
    public static final int DATA_OFFSET = 19;
    /** Offset of the zero-based last-packet index. */
    public static final int LAST_PACKET_OFFSET = 17;
    /** Offset of the zero-based packet index. */
    public static final int PACKET_INDEX_OFFSET = 18;
    /** Bytes covered by the byte count before encoded data: model..packet index. */
    public static final int HEADER_DATA_BYTES = 13;
    /** Largest encoded chunk that fits in the 14-bit count. */
    public static final int MAX_ENCODED_CHUNK = ((0x3FFF - HEADER_DATA_BYTES) / 8) * 8;

    // Library modules
    public static final String SCENE = "SCENE___";
    public static final String INPUT_EQ = "INEQ____";
    public static final String OUTPUT_EQ = "OUTEQ___";
    public static final String DYNAMICS = "DYNA____";
    public static final String GEQ = "GEQ_____";
    public static final String EFFECT = "EFFECT__";

    // Channel-library modules used by the LS9 bulk type table
    public static final String INPUT_CHANNEL = "INCHNNL_";
    public static final String OUTPUT_CHANNEL = "OUTCHNNL";

    // Setup / table modules
    public static final String MIXER_SETUP = "MIXERSET";
    public static final String OUTPUT_PORT = "OUT_PORT";
    public static final String MONITOR_SETUP = "MONITOR_";
    public static final String MIDI_SETUP = "MIDI_SET";
    public static final String LIB_NUMBER = "LIB_NUM_";
    public static final String PROGRAM_CHANGE_TABLE = "PRGMCHG_";
    public static final String CONTROL_CHANGE_TABLE = "CTRLCHG_";
    public static final String PREFERENCE_CURRENT = "PREF_CUR";
    public static final String PREFERENCE_ADMIN = "PREF_ADM";
    public static final String PREFERENCE_GUEST = "PREF_GST";
    public static final String USER_KEYS_CURRENT = "UDEF_CUR";
    public static final String USER_KEYS_ADMIN = "UDEF_ADM";
    public static final String USER_KEYS_GUEST = "UDEF_GST";
    public static final String CUSTOM_FADER_CURRENT = "CFAD_CUR";
    public static final String CUSTOM_FADER_ADMIN = "CFAD_ADM";
    public static final String CUSTOM_FADER_GUEST = "CFAD_GST";
    public static final String USER_LEVEL_CURRENT = "UKEY_CUR";
    public static final String USER_LEVEL_GUEST = "UKEY_GST";

    /** Scene number 512 = current data; 768 = current data with recall safe. */
    public static final int SCENE_CURRENT = 512;
    public static final int SCENE_CURRENT_RECALL_SAFE = 768;

    private BulkDumpCodec() {
    }

    /** Builds a BULK DUMP REQUEST. */
    public static byte[] request(int device, String module, int number) {
        byte[] mod = fixedAscii(module, 8);
        byte[] b = new byte[4 + 1 + 8 + 2 + 1];
        int i = 0;
        b[i++] = (byte) 0xF0;
        b[i++] = (byte) YAMAHA_ID;
        b[i++] = (byte) (SUB_REQUEST | (device & 0x0F));
        b[i++] = (byte) GROUP_ID;
        b[i++] = (byte) MODEL_ID;
        System.arraycopy(mod, 0, b, i, 8);
        i += 8;
        b[i++] = (byte) ((number >>> 7) & 0x7F);
        b[i++] = (byte) (number & 0x7F);
        b[i] = (byte) 0xF7;
        return b;
    }

    /** Builds a single-packet BULK DUMP DATA message from logical 8-bit bytes. */
    public static byte[] encode(int device, String module, int number, byte[] data) {
        return encodePacket(device, module, number, 0, 0, convert8to7(data));
    }

    /** Builds one BULK DUMP DATA packet from already 7-bit encoded bytes. */
    public static byte[] encodePacket(int device, String module, int number,
                                      int lastPacket, int packetIndex, byte[] encodedData) {
        if (lastPacket < 0 || lastPacket > 0x7F
                || packetIndex < 0 || packetIndex > lastPacket) {
            throw new IllegalArgumentException("invalid packet " + packetIndex
                    + " of last " + lastPacket);
        }
        byte[] mod = fixedAscii(module, 8);
        byte[] encoded = encodedData == null ? new byte[0] : encodedData;
        int byteCount = HEADER_DATA_BYTES + encoded.length;
        if (byteCount > 0x3FFF) {
            throw new IllegalArgumentException("bulk packet too large: " + byteCount);
        }
        byte[] b = new byte[6 + byteCount + 2];
        int i = 0;
        b[i++] = (byte) 0xF0;
        b[i++] = (byte) YAMAHA_ID;
        b[i++] = (byte) (SUB_DATA | (device & 0x0F));
        b[i++] = (byte) GROUP_ID;
        b[i++] = (byte) ((byteCount >>> 7) & 0x7F);
        b[i++] = (byte) (byteCount & 0x7F);
        b[i++] = (byte) MODEL_ID;
        System.arraycopy(mod, 0, b, i, 8);
        i += 8;
        b[i++] = (byte) ((number >>> 7) & 0x7F);
        b[i++] = (byte) (number & 0x7F);
        b[i++] = (byte) (lastPacket & 0x7F);
        b[i++] = (byte) (packetIndex & 0x7F);
        System.arraycopy(encoded, 0, b, i, encoded.length);
        i += encoded.length;
        int sum = 0;
        for (int k = 6; k < i; k++) {
            sum += b[k] & 0x7F;
        }
        b[i++] = (byte) ((-sum) & 0x7F);
        b[i] = (byte) 0xF7;
        return b;
    }

    /**
     * Splits a logical image into padded 7-bit chunks and returns the complete
     * packet list. Chunks are multiples of eight encoded bytes except possibly
     * the final one.
     */
    public static java.util.List<byte[]> encodePackets(int device, String module,
                                                       int number, byte[] data) {
        byte[] logical = data == null ? new byte[0] : data;
        int paddedLength = ((logical.length + 6) / 7) * 7;
        if (paddedLength != logical.length) {
            logical = Arrays.copyOf(logical, paddedLength);
        }
        return encodeEncodedPackets(device, module, number, convert8to7(logical));
    }

    /** Packetizes an existing encoded stream without re-encoding it. */
    public static java.util.List<byte[]> encodeEncodedPackets(int device, String module,
                                                              int number, byte[] encoded) {
        byte[] stream = encoded == null ? new byte[0] : encoded;
        int count = stream.length == 0 ? 1
                : (stream.length + MAX_ENCODED_CHUNK - 1) / MAX_ENCODED_CHUNK;
        java.util.List<byte[]> out = new java.util.ArrayList<>(count);
        for (int packet = 0; packet < count; packet++) {
            int from = packet * MAX_ENCODED_CHUNK;
            int to = Math.min(stream.length, from + MAX_ENCODED_CHUNK);
            byte[] chunk = Arrays.copyOfRange(stream, from, to);
            out.add(encodePacket(device, module, number, count - 1, packet, chunk));
        }
        return out;
    }

    /**
     * Builds a BULK DUMP DATA message using the console's padded grouping:
     * the logical image is zero-padded to a multiple of seven bytes, so the
     * encoded data is a multiple of eight bytes. StageMix asserts both
     * properties in {@code BulkDatStore.cpp}.
     */
    public static byte[] encodePadded(int device, String module, int number, byte[] data) {
        byte[] logical = data == null ? new byte[0] : data;
        int paddedLength = ((logical.length + 6) / 7) * 7;
        if (paddedLength != logical.length) {
            logical = Arrays.copyOf(logical, paddedLength);
        }
        return encode(device, module, number, logical);
    }

    /**
     * Parses one LS9 bulk data packet. Multi-packet blocks need
     * {@link BulkDumpAssembler} because one message carries only one chunk.
     */
    public static BulkDumpPacket parsePacket(byte[] sysex) {
        if (sysex == null || sysex.length < DATA_OFFSET + 2
                || (sysex[0] & 0xFF) != 0xF0
                || (sysex[1] & 0xFF) != YAMAHA_ID
                || (sysex[3] & 0xFF) != GROUP_ID
                || (sysex[2] & 0xF0) != SUB_DATA
                || (sysex[sysex.length - 1] & 0xFF) != 0xF7) {
            return null;
        }
        int byteCount = ((sysex[4] & 0x7F) << 7) | (sysex[5] & 0x7F);
        if (sysex.length != 8 + byteCount) {
            return null;
        }
        if ((sysex[6] & 0xFF) != MODEL_ID) {
            return null;
        }
        String module = ascii(sysex, 7, 8);
        int number = ((sysex[15] & 0x7F) << 7) | (sysex[16] & 0x7F);
        int lastPacket = sysex[LAST_PACKET_OFFSET] & 0x7F;
        int packetIndex = sysex[PACKET_INDEX_OFFSET] & 0x7F;
        if (packetIndex > lastPacket) {
            return null;
        }
        int encodedLength = byteCount - HEADER_DATA_BYTES;
        if (encodedLength < 0) {
            return null;
        }
        int checksumIndex = DATA_OFFSET + encodedLength;
        int expected = (-sum(sysex, 6, checksumIndex - 6)) & 0x7F;
        int checksum = sysex[checksumIndex] & 0x7F;
        if (checksum != expected) {
            return null;
        }
        byte[] encoded = Arrays.copyOfRange(sysex, DATA_OFFSET, checksumIndex);
        return new BulkDumpPacket(module, number, lastPacket, packetIndex,
                encoded, checksum, sysex);
    }

    /**
     * Parses a complete single-packet bulk-dump SysEx message. For a
     * multi-packet block this returns {@code null}; use {@link #parsePacket}
     * with {@link BulkDumpAssembler}.
     */
    public static BulkDump parse(byte[] sysex) {
        BulkDumpPacket packet = parsePacket(sysex);
        if (packet == null || !packet.isLast()) {
            return null;
        }
        if (packet.packetIndex() != 0) {
            return null;
        }
        byte[] encoded = packet.encodedData();
        byte[] data = convert7to8(encoded);
        return new BulkDump(packet.module(), packet.number(), encoded, data,
                packet.checksum(), packet.raw());
    }

    /**
     * Converts logical 8-bit data to the on-wire 7-bit form. Full groups of
     * seven bytes become eight bytes; a final partial group of k bytes becomes
     * 1 + k bytes.
     */
    public static byte[] convert8to7(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        int last = data.length % 7;
        int encodedLength = (data.length / 7) * 8 + (last == 0 ? 0 : last + 1);
        byte[] out = new byte[encodedLength];
        int src = 0;
        int dst = 0;
        while (src < data.length) {
            int n = Math.min(7, data.length - src);
            int flags = 0;
            for (int i = 0; i < n; i++) {
                int v = data[src + i] & 0xFF;
                if ((v & 0x80) != 0) {
                    flags |= 1 << (6 - i);
                }
                out[dst + 1 + i] = (byte) (v & 0x7F);
            }
            out[dst] = (byte) flags;
            dst += n + 1;
            src += n;
        }
        return out;
    }

    /**
     * Converts on-wire 7-bit data back to logical 8-bit data. Full eight-byte
     * groups become seven bytes; a final partial group of 1+k bytes becomes k
     * bytes.
     */
    public static byte[] convert7to8(byte[] encoded) {
        if (encoded == null || encoded.length == 0) {
            return new byte[0];
        }
        int full = encoded.length / 8;
        int rem = encoded.length % 8;
        int rawLength = full * 7 + (rem > 0 ? rem - 1 : 0);
        byte[] out = new byte[rawLength];
        int src = 0;
        int dst = 0;
        for (int g = 0; g < full; g++) {
            decodeGroup(encoded, src, 8, out, dst, 7);
            src += 8;
            dst += 7;
        }
        if (rem > 0) {
            decodeGroup(encoded, src, rem, out, dst, rem - 1);
        }
        return out;
    }

    private static void decodeGroup(byte[] in, int src, int encodedCount, byte[] out, int dst, int rawCount) {
        int flags = in[src] & 0x7F;
        for (int i = 0; i < rawCount; i++) {
            int v = in[src + 1 + i] & 0x7F;
            if (((flags << (i + 1)) & 0x80) != 0) {
                v |= 0x80;
            }
            out[dst + i] = (byte) v;
        }
    }

    public static int checksum(byte[] data, int offset, int length) {
        return (-sum(data, offset, length)) & 0x7F;
    }

    private static int sum(byte[] data, int offset, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += data[offset + i] & 0x7F;
        }
        return sum;
    }

    private static byte[] fixedAscii(String text, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) ' ');
        if (text != null) {
            byte[] src = text.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(src, 0, out, 0, Math.min(src.length, length));
        }
        for (int i = 0; i < out.length; i++) {
            out[i] &= 0x7F;
        }
        return out;
    }

    private static String ascii(byte[] b, int offset, int length) {
        byte[] tmp = Arrays.copyOfRange(b, offset, offset + length);
        for (int i = 0; i < tmp.length; i++) {
            tmp[i] &= 0x7F;
        }
        return new String(tmp, StandardCharsets.US_ASCII);
    }
}
