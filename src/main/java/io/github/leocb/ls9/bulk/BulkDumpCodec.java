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
 * Response:
 * <pre>
 *   F0 43 0n 3E cc cc 12 "SCENE___" dd dd &lt;encoded data&gt; ee F7
 * </pre>
 * The two count bytes come before the model ID. The checksum is calculated
 * over the model byte through the last data byte.
 */
public final class BulkDumpCodec {

    public static final int YAMAHA_ID = 0x43;
    public static final int GROUP_ID = 0x3E;
    public static final int MODEL_ID = 0x12;

    public static final int SUB_DATA = 0x00;
    public static final int SUB_REQUEST = 0x20;

    // Library modules
    public static final String SCENE = "SCENE___";
    public static final String INPUT_EQ = "INEQ____";
    public static final String OUTPUT_EQ = "OUTEQ___";
    public static final String DYNAMICS = "DYNA____";
    public static final String GEQ = "GEQ_____";
    public static final String EFFECT = "EFFECT__";

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

    /** Builds a BULK DUMP DATA message from logical 8-bit bytes. */
    public static byte[] encode(int device, String module, int number, byte[] data) {
        byte[] mod = fixedAscii(module, 8);
        byte[] encoded = convert8to7(data);
        int byteCount = 1 + 8 + 2 + encoded.length;
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

    /** Parses a bulk-dump SysEx message; returns {@code null} if it is not one. */
    public static BulkDump parse(byte[] sysex) {
        if (sysex == null || sysex.length < 8 + 11
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
        int encodedLength = byteCount - 11;
        if (encodedLength < 0) {
            return null;
        }
        int checksumIndex = 17 + encodedLength;
        int expected = (-sum(sysex, 6, checksumIndex - 6)) & 0x7F;
        int checksum = sysex[checksumIndex] & 0x7F;
        if (checksum != expected) {
            return null;
        }
        byte[] encoded = Arrays.copyOfRange(sysex, 17, checksumIndex);
        byte[] data = convert7to8(encoded);
        return new BulkDump(module, number, encoded, data, checksum, sysex);
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
