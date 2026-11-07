package io.github.leocb.ls9.meter;

import java.util.Arrays;

/** Encoder/decoder for the LS9 LEVEL METER messages (data category 0x21). */
public final class MeterCodec {

    public static final int CATEGORY = 0x21;

    private MeterCodec() {
    }

    /** Builds a meter subscription request. */
    public static byte[] request(int device, int ul, int lu, int ll, int count) {
        if (ul < 0 || ul > 0x7F || lu < 0 || lu > 0x7F || ll < 0 || ll > 0x7F) {
            throw new IllegalArgumentException("meter address must be 7-bit");
        }
        if (count < 0 || count > 0x3FFF) {
            throw new IllegalArgumentException("meter count must be 0..16383");
        }
        return new byte[]{
                (byte) 0xF0, (byte) 0x43, (byte) (0x30 | (device & 0x0F)), (byte) 0x3E,
                (byte) 0x12, (byte) CATEGORY,
                (byte) ul, (byte) lu, (byte) ll,
                (byte) ((count >>> 7) & 0x7F), (byte) (count & 0x7F),
                (byte) 0xF7
        };
    }

    public static byte[] request(int device, MeterGroup group, int lu, int start, int count) {
        return request(device, group.ul(), lu, start, count);
    }

    /** Stops all meter transmission. */
    public static byte[] stop(int device) {
        return new byte[]{
                (byte) 0xF0, (byte) 0x43, (byte) (0x30 | (device & 0x0F)), (byte) 0x3E,
                (byte) 0x12, (byte) CATEGORY,
                (byte) 0x7F, 0, 0, 0, 0,
                (byte) 0xF7
        };
    }

    /** Parses a meter response; returns {@code null} if it is not one. */
    public static MeterFrame parse(byte[] sysex) {
        if (sysex == null || sysex.length < 11
                || (sysex[0] & 0xFF) != 0xF0
                || (sysex[1] & 0xFF) != 0x43
                || (sysex[3] & 0xFF) != 0x3E
                || (sysex[4] & 0xFF) != 0x12
                || (sysex[5] & 0xFF) != CATEGORY
                || (sysex[sysex.length - 1] & 0xFF) != 0xF7) {
            return null;
        }
        int ul = sysex[6] & 0x7F;
        int lu = sysex[7] & 0x7F;
        int ll = sysex[8] & 0x7F;
        byte[] values = Arrays.copyOfRange(sysex, 9, sysex.length - 1);
        return new MeterFrame(ul, lu, ll, values, sysex);
    }
}
