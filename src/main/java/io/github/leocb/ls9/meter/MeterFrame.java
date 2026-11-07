package io.github.leocb.ls9.meter;

import java.util.Arrays;

/** One 0x21 LEVEL METER response. */
public record MeterFrame(int ul, int lu, int ll, byte[] values, byte[] raw) {

    public MeterFrame {
        values = values == null ? new byte[0] : values.clone();
        raw = raw == null ? new byte[0] : raw.clone();
    }

    @Override
    public byte[] values() {
        return values.clone();
    }

    @Override
    public byte[] raw() {
        return raw.clone();
    }

    public int count() {
        return values.length;
    }

    /** 0..127 meter value for the channel at {@code start + index}. */
    public int valueAt(int index) {
        if (index < 0 || index >= values.length) {
            throw new IndexOutOfBoundsException(index);
        }
        return values[index] & 0x7F;
    }

    public MeterGroup group() {
        for (MeterGroup g : MeterGroup.values()) {
            if (g.ul() == ul) {
                return g;
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MeterFrame other && ul == other.ul && lu == other.lu
                && ll == other.ll && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return ((ul * 31 + lu) * 31 + ll) * 31 + Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "MeterFrame{ul=" + ul + ",lu=" + lu + ",ll=" + ll + ",count=" + values.length + "}";
    }
}
