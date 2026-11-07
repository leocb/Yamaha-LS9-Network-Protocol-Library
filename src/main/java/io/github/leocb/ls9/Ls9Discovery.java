package io.github.leocb.ls9;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import io.github.leocb.ls9.transport.EtherMidiTransport;

/**
 * Simple subnet probe for EtherMIDI devices. This is a client-side convenience,
 * not a replacement for Yamaha's driver auto-detect (which also retrieves the
 * device name/MAC before a session exists).
 */
public final class Ls9Discovery {

    public record Probe(String host, int port, boolean reachable, boolean handshake, String error) {
    }

    private Ls9Discovery() {
    }

    /** Probes one address and performs the EtherMIDI handshake. */
    public static Probe probe(String host, int port, int timeoutMs, byte[] mac) {
        EtherMidiTransport transport = new EtherMidiTransport(host, port)
                .connectTimeoutMs(timeoutMs)
                .authData(mac);
        try {
            transport.open();
            transport.close();
            return new Probe(host, port, true, true, "");
        } catch (IOException e) {
            boolean reachable = e.getMessage() != null
                    && !e.getMessage().toLowerCase().contains("connection refused")
                    && !e.getMessage().toLowerCase().contains("timed out");
            return new Probe(host, port, reachable, false, e.getMessage());
        }
    }

    /**
     * Scans an inclusive IPv4 range. Only addresses that complete the
     * EtherMIDI handshake are returned when {@code onlyHandshakes} is true.
     */
    public static List<Probe> scan(String startIp, String endIp, int port, int timeoutMs,
                                   byte[] mac, boolean onlyHandshakes) throws IOException {
        long start = ipv4ToLong(startIp);
        long end = ipv4ToLong(endIp);
        if (end < start) {
            throw new IllegalArgumentException("end address is before start address");
        }
        if (end - start > 4096) {
            throw new IllegalArgumentException("range too large (max 4096 addresses)");
        }
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Probe>> futures = new ArrayList<>();
            for (long ip = start; ip <= end; ip++) {
                String host = longToIpv4(ip);
                futures.add(pool.submit((Callable<Probe>) () -> probe(host, port, timeoutMs, mac)));
            }
            List<Probe> out = new ArrayList<>();
            for (Future<Probe> f : futures) {
                try {
                    Probe p = f.get();
                    if (!onlyHandshakes || p.handshake()) {
                        out.add(p);
                    }
                } catch (Exception e) {
                    // ignore individual failures
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long ipv4ToLong(String ip) throws IOException {
        InetAddress address = InetAddress.getByName(ip);
        byte[] b = address.getAddress();
        if (b.length != 4) {
            throw new IllegalArgumentException("only IPv4 is supported: " + ip);
        }
        return ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16)
                | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
    }

    private static String longToIpv4(long value) {
        return ((value >>> 24) & 0xFF) + "." + ((value >>> 16) & 0xFF) + "."
                + ((value >>> 8) & 0xFF) + "." + (value & 0xFF);
    }
}
