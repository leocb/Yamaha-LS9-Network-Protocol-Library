package io.github.leocb.ls9.bulk;

import java.util.Arrays;

/**
 * One on-wire LS9 bulk-dump data packet.
 *
 * <p>LS9 data messages carry two packet bytes after the block number:
 * {@code lastPacket} (zero-based index of the final packet) and
 * {@code packetIndex} (zero-based index of this packet). A complete block is
 * the concatenation of every packet's encoded data. The StageMix
 * {@code BulkDatStore::Configure} table puts the data at offset 19, the last
 * packet index at 17, the packet index at 18, the checksum start at the model
 * byte (6), and the block number at 15.
 *
 * @param module ASCII module name (eight bytes)
 * @param number 14-bit block number
 * @param lastPacket zero-based index of the last packet
 * @param packetIndex zero-based index of this packet
 * @param encodedData 7-bit encoded payload chunk
 * @param checksum packet checksum
 * @param raw complete SysEx packet
 */
public record BulkDumpPacket(String module, int number, int lastPacket,
                             int packetIndex, byte[] encodedData,
                             int checksum, byte[] raw) {

    public BulkDumpPacket {
        module = module == null ? "" : module;
        encodedData = encodedData == null ? new byte[0] : encodedData.clone();
        raw = raw == null ? new byte[0] : raw.clone();
        if (lastPacket < 0 || lastPacket > 0x7F
                || packetIndex < 0 || packetIndex > lastPacket) {
            throw new IllegalArgumentException("invalid packet " + packetIndex
                    + " of last " + lastPacket);
        }
    }

    @Override
    public byte[] encodedData() {
        return encodedData.clone();
    }

    @Override
    public byte[] raw() {
        return raw.clone();
    }

    /** Number of packets in the complete block. */
    public int packetCount() {
        return lastPacket + 1;
    }

    public boolean isLast() {
        return packetIndex == lastPacket;
    }

    @Override
    public String toString() {
        return "BulkDumpPacket{" + module.trim() + " #" + number
                + ", packet " + (packetIndex + 1) + "/" + packetCount()
                + ", " + encodedData.length + " encoded bytes}";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BulkDumpPacket other
                && number == other.number
                && lastPacket == other.lastPacket
                && packetIndex == other.packetIndex
                && module.equals(other.module)
                && Arrays.equals(encodedData, other.encodedData);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * module.hashCode() + number)
                + Arrays.hashCode(encodedData) + packetIndex;
    }
}
