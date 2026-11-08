package io.github.leocb.ls9.bulk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reassembles multi-packet LS9 bulk dumps.
 *
 * <p>StageMix accumulates each packet's encoded chunk in unit order and only
 * decodes after the packet whose index equals the last-packet index arrives
 * ({@code BulkDatStore::HandleBulkDumpPacket}). The assembler follows the same
 * model, but accepts out-of-order packets and ignores duplicate indices.
 */
public final class BulkDumpAssembler {

    private final Map<String, Pending> pending = new HashMap<>();

    /** Adds one packet; returns the complete block or {@code null}. */
    public synchronized BulkDump add(BulkDumpPacket packet) {
        if (packet == null) {
            return null;
        }
        String key = key(packet.module(), packet.number());
        Pending p = pending.get(key);
        if (p == null || p.lastPacket != packet.lastPacket()) {
            p = new Pending(packet.lastPacket());
            pending.put(key, p);
        } else if (p.chunks.containsKey(packet.packetIndex())) {
            // Duplicate or restarted packet; keep the first copy.
            return null;
        }
        p.chunks.put(packet.packetIndex(), packet.encodedData());
        p.lastChecksum = packet.checksum();
        p.lastRaw = packet.raw();
        if (p.chunks.size() != p.lastPacket + 1) {
            return null;
        }
        pending.remove(key);
        List<byte[]> ordered = new ArrayList<>(p.chunks.size());
        int total = 0;
        for (int i = 0; i <= p.lastPacket; i++) {
            byte[] chunk = p.chunks.get(i);
            if (chunk == null) {
                return null;
            }
            ordered.add(chunk);
            total += chunk.length;
        }
        byte[] encoded = new byte[total];
        int offset = 0;
        for (byte[] chunk : ordered) {
            System.arraycopy(chunk, 0, encoded, offset, chunk.length);
            offset += chunk.length;
        }
        byte[] data = BulkDumpCodec.convert7to8(encoded);
        return new BulkDump(packet.module(), packet.number(), encoded, data,
                p.lastChecksum, p.lastRaw);
    }

    /** Drops any partial block for this module/number. */
    public synchronized void reset(String module, int number) {
        pending.remove(key(module, number));
    }

    /** Drops every partial block. */
    public synchronized void clear() {
        pending.clear();
    }

    private static String key(String module, int number) {
        return module + "\u0000" + number;
    }

    private static final class Pending {
        final int lastPacket;
        final Map<Integer, byte[]> chunks = new HashMap<>();
        int lastChecksum;
        byte[] lastRaw = new byte[0];

        Pending(int lastPacket) {
            this.lastPacket = lastPacket;
        }
    }
}
