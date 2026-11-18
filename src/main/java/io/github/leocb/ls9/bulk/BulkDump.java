package io.github.leocb.ls9.bulk;

import java.util.Arrays;
import io.github.leocb.ls9.internal.Text;

/**
 * One Yamaha LS9 bulk-dump SysEx message.
 *
 * <p>Wire format, confirmed by the LS9 manual and Yamaha's AMX example:
 * <pre>
 *   F0 43 0n 3E  byteCountHi byteCountLo  12  module[8]  number[2]  data  checksum  F7
 * </pre>
 * {@code byteCount} covers the model byte, module name, number and encoded
 * data. {@code data} on the wire is the 7-bit form of the logical bytes
 * (seven 8-bit words become eight 7-bit words). The checksum is
 * {@code (-sum(bytes from model through data)) & 0x7F}.
 */
public record BulkDump(String module, int number, byte[] encodedData, byte[] data,
                       int checksum, byte[] raw) {

    public BulkDump {
        encodedData = encodedData == null ? new byte[0] : encodedData.clone();
        data = data == null ? new byte[0] : data.clone();
        raw = raw == null ? new byte[0] : raw.clone();
    }

    @Override
    public byte[] encodedData() {
        return encodedData.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public byte[] raw() {
        return raw.clone();
    }

    /**
     * Internal read-only view used by the decoder. The caller must not mutate
     * the returned array; the dump owns it and all public accessors clone.
     */
    byte[] dataUnsafe() {
        return data;
    }

    /**
     * True when both dumps carry the same module, number and logical image.
     * Used by the change poll to skip a full decode when nothing changed.
     * Compares the owned arrays directly so it does not allocate.
     */
    public boolean contentEquals(BulkDump other) {
        return other != null
                && number == other.number
                && module.equals(other.module)
                && Arrays.equals(data, other.data);
    }

    public int length() {
        return data.length;
    }

    /** Writes the logical image without allocating a defensive copy. */
    public void writeDataTo(java.io.OutputStream out) throws java.io.IOException {
        out.write(data);
    }

    @Override
    public String toString() {
        return "BulkDump{" + Text.trimTrailing(module) + " #" + number
                + ", " + data.length + " bytes, checksum=0x"
                + String.format("%02X", checksum) + "}";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BulkDump other
                && number == other.number
                && module.equals(other.module)
                && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return 31 * module.hashCode() + number + Arrays.hashCode(data);
    }
}
