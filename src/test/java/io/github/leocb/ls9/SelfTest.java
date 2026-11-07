package io.github.leocb.ls9;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;
import io.github.leocb.ls9.bulk.BulkDump;
import io.github.leocb.ls9.bulk.BulkDumpCodec;
import io.github.leocb.ls9.meter.MeterCodec;
import io.github.leocb.ls9.meter.MeterFrame;
import io.github.leocb.ls9.meter.MeterGroup;
import io.github.leocb.ls9.model.Ls9FaderTable;
import io.github.leocb.ls9.sysex.Ls9Sysex;
import io.github.leocb.ls9.sysex.Ls9SysexMessage;
import io.github.leocb.ls9.sysex.ParameterAddress;
import io.github.leocb.ls9.transport.EtherMidiPacket;
import io.github.leocb.ls9.transport.YemAuth;

/**
 * Dependency-free self test. Run with:
 * <pre>
 *   javac -d out $(find src/main/java src/test/java -name '*.java')
 *   java -cp out io.github.leocb.ls9.SelfTest
 * </pre>
 */
public final class SelfTest {

    private static int checks;

    public static void main(String[] args) throws Exception {
        testSysexVectors();
        testBulkDump();
        testMeters();
        testNames();
        testFaderTable();
        testParameterRegistry();
        testSnapshotIo();
        testTransportAgainstFakeConsole(false);
        testTransportAgainstFakeConsole(true);
        System.out.println("OK: " + checks + " checks passed");
    }

    private static void testSysexVectors() {
        ParameterAddress fader56 = ParameterAddress.of(0x0033, 0x0000, 55);
        byte[] change = Ls9Sysex.parameterChange(0, fader56, 823);
        eq("F0 43 10 3E 12 01 00 33 00 00 00 37 00 00 00 06 37 F7",
                Ls9Sysex.hex(change), "fader 56 = 0 dB");

        ParameterAddress input48Mix1 = ParameterAddress.of(0x0043, 0x0005, 47);
        byte[] request = Ls9Sysex.parameterRequest(0, input48Mix1);
        eq("F0 43 30 3E 12 01 00 43 00 05 00 2F F7",
                Ls9Sysex.hex(request), "request input 48 -> MIX 1 level");

        byte[] recall = Ls9Sysex.libraryRecall(0, Ls9Sysex.MODULE_SCENE, 12, 512);
        eq("F0 43 10 3E 12 00 4C 69 62 52 63 6C 5F 5F 53 43 45 4E 45 5F 5F 5F 00 0C 04 00 F7",
                Ls9Sysex.hex(recall), "scene 12 recall");

        eq("F0 43 10 3E 12 00 4C 69 62 43 70 79 5F 5F 53 43 45 4E 45 5F 5F 5F 00 05 00 00 00 00 F7",
                Ls9Sysex.hex(Ls9Sysex.libraryCopy(0, Ls9Sysex.MODULE_SCENE, 5)), "library copy");
        eq("F0 43 30 3E 12 00 4C 69 62 45 78 69 73 74 53 43 45 4E 45 5F 5F 5F 00 0A F7",
                Ls9Sysex.hex(Ls9Sysex.libraryExistsRequest(0, Ls9Sysex.MODULE_SCENE, 10)),
                "library exists request");
        eq("F0 43 10 3E 12 00 43 68 6C 50 69 72 43 70 50 41 49 52 4F 4E 43 50 00 00 00 01 F7",
                Ls9Sysex.hex(Ls9Sysex.channelPair(0, Ls9Sysex.PAIR_ON_COPY, 0, 1)), "channel pair");
        eq("F0 43 10 3E 12 00 4D 6F 64 46 78 54 72 67 46 52 5A 50 4C 41 59 5F 00 01 F7",
                Ls9Sysex.hex(Ls9Sysex.moduleTrigger(0, "FRZPLAY_", 0, true)), "module trigger");

        Ls9SysexMessage parsed = Ls9Sysex.parse(change);
        check(parsed != null && parsed.isParameterChange(), "parse parameter change");
        eq("0033/0000/0037", String.format("%04X/%04X/%04X", parsed.address().element(),
                parsed.address().index(), parsed.address().channel()), "parsed address");
        eq(823L, parsed.value(), "parsed value");

        // 14-bit values split into 7-bit groups, big-endian.
        eq(823L, Ls9Sysex.decode7(Ls9Sysex.encode7(823, 5), 0, 5), "7-bit round trip");
    }

    private static void testBulkDump() {
        byte[] request = BulkDumpCodec.request(0, BulkDumpCodec.SCENE, 45);
        eq("F0 43 20 3E 12 53 43 45 4E 45 5F 5F 5F 00 2D F7",
                Ls9Sysex.hex(request), "bulk dump request scene 45");

        for (int length = 0; length <= 100; length++) {
            byte[] data = new byte[length];
            for (int i = 0; i < length; i++) {
                data[i] = (byte) (i * 37 + 0x80);
            }
            byte[] encoded = BulkDumpCodec.encode(0, BulkDumpCodec.SCENE, 3, data);
            BulkDump parsed = BulkDumpCodec.parse(encoded);
            check(parsed != null, "bulk dump parse length " + length);
            eq("SCENE___", parsed.module(), "bulk dump module length " + length);
            eq(3, parsed.number(), "bulk dump number length " + length);
            check(java.util.Arrays.equals(data, parsed.data()),
                    "bulk dump data round trip length " + length);
        }
    }

    private static void testMeters() {
        byte[] request = MeterCodec.request(0, MeterGroup.INPUT, MeterGroup.POINT_PRE_FADER, 0, 8);
        eq("F0 43 30 3E 12 21 00 06 00 00 08 F7",
                Ls9Sysex.hex(request), "input meter request");
        byte[] stop = MeterCodec.stop(0);
        eq("F0 43 30 3E 12 21 7F 00 00 00 00 F7",
                Ls9Sysex.hex(stop), "stop meters");
        byte[] response = new byte[]{(byte) 0xF0, 0x43, 0x10, 0x3E, 0x12, 0x21,
                0x00, 0x06, 0x00, 0x10, 0x20, 0x30, (byte) 0xF7};
        MeterFrame frame = MeterCodec.parse(response);
        check(frame != null, "meter response parse");
        eq(0x10, frame.valueAt(0), "meter value 0");
        eq(0x30, frame.valueAt(2), "meter value 2");
        eq(MeterGroup.INPUT, frame.group(), "meter group");
    }

    private static void testNames() {
        eq("Vocal 1 ", Ls9Sysex.unpackName4(Ls9Sysex.packName4("Voca"))
                + Ls9Sysex.unpackName4(Ls9Sysex.packName4("l 1 ")), "name packing");
    }

    private static void testFaderTable() {
        Ls9FaderTable t = Ls9FaderTable.get();
        eq(0.0, t.toDb(823), "raw 823 is 0 dB");
        check(t.toRaw(0.0) == 823, "0 dB maps to 823, got " + t.toRaw(0.0));
        check(t.toDb(0) == Double.NEGATIVE_INFINITY, "-Infinity at raw 0");
        check(t.toRaw(Double.NEGATIVE_INFINITY) == 0, "-Infinity -> raw 0");
        check(t.toRaw(-6.0) >= 0 && t.toDb(t.toRaw(-6.0)) <= -5.9, "-6 dB round trip");
    }

    private static void testParameterRegistry() {
        eq(1033, io.github.leocb.ls9.sysex.Ls9ParameterRegistry.get().all().size(),
                "parameter registry size");
        eq(1024, io.github.leocb.ls9.sysex.Ls9ValueTableRegistry.get().get(3).orElseThrow()
                .values().size(), "fader value table size");
        eq("0.00dB", io.github.leocb.ls9.sysex.Ls9ValueTableRegistry.get().get(3).orElseThrow()
                .display(823).replace(" ", ""), "fader display");
        eq(0x0033, io.github.leocb.ls9.sysex.Ls9Parameters.kInputFader.kFader.elementHex(),
                "generated fader element");
        eq(0x0000, io.github.leocb.ls9.sysex.Ls9Parameters.kInputFader.kFader.indexHex(),
                "generated fader index");
        Ls9Parameter on = new Ls9Parameter(null, io.github.leocb.ls9.sysex.Ls9Parameters.kInputOn.kChannelOn, 0);
        eq("On", on.display(1), "inline enum display");
        eq(0L, on.parseDisplay("Off"), "inline enum parse");
    }

    private static void testSnapshotIo() throws Exception {
        byte[] data = new byte[]{1, 2, 3, (byte) 0x80, 5};
        BulkDump scene = BulkDumpCodec.parse(BulkDumpCodec.encode(0, BulkDumpCodec.SCENE, 7, data));
        BulkDump current = BulkDumpCodec.parse(BulkDumpCodec.encode(0, BulkDumpCodec.SCENE,
                BulkDumpCodec.SCENE_CURRENT, data));
        Ls9Snapshot snapshot = new Ls9Snapshot();
        snapshot.addScene(scene);
        snapshot.setCurrentData(current);

        java.io.File path = java.io.File.createTempFile("ls9-snapshot", ".bin");
        try {
            Ls9SnapshotIO.save(snapshot, path);
            Ls9Snapshot loaded = Ls9SnapshotIO.load(path);
            eq(2, loaded.blockCount(), "snapshot block count");
            check(java.util.Arrays.equals(data, loaded.scenes().get(7).data()),
                    "snapshot scene data");
            check(java.util.Arrays.equals(data, loaded.currentData().data()),
                    "snapshot current data");
        } finally {
            path.delete();
        }
    }

    private static void testTransportAgainstFakeConsole(boolean authenticate) throws Exception {
        byte[] mac = YemAuth.parseMac("00:1A:2B:3C:4D:5E");
        try (ServerSocket server = new ServerSocket(0)) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread serverThread = new Thread(() -> {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    OutputStream out = s.getOutputStream();

                    byte[] open = readPacket(in);
                    eq(0x20, open[4] & 0xFF, "open command");
                    eq(0, open[6] & 0xFF, "port index");
                    eq(1, open[7] & 0xFF, "device id");
                    eq(0x20000000, EtherMidiPacket.getInt(open, 12), "open mode bitmap");

                    byte[] ack = open.clone();
                    ack[4] = 0x21;
                    ack[8] = 1;
                    ack[9] = 0;
                    ack[10] = 0x42; // challenge seed
                    EtherMidiPacket.putInt(ack, 12, authenticate ? 0x80000000 : 0);
                    out.write(ack);
                    out.flush();

                    if (authenticate) {
                        byte[] auth = readPacket(in);
                        eq(0x23, auth[4] & 0xFF, "auth command");
                        int expected = YemAuth.checksum(0x42, mac);
                        eq(expected, ((auth[8] & 0xFF) << 8) | (auth[9] & 0xFF), "auth checksum");
                        byte[] authAck = new byte[16];
                        EtherMidiPacket.putInt(authAck, 0, 16);
                        authAck[4] = 0x24;
                        out.write(authAck);
                        out.flush();
                    }

                    byte[] data = readPacket(in);
                    eq(0x16, data[4] & 0xFF, "sysex command");
                    EtherMidiPacket.Frame frame = EtherMidiPacket.decode(data);
                    eq("F0 43 10 3E 12 01 00 33 00 00 00 00 00 00 00 06 37 F7",
                            Ls9Sysex.hex(frame.payload()), "fader change on the wire");

                    byte[] request = readPacket(in);
                    eq(0x16, request[4] & 0xFF, "request sysex command");
                    EtherMidiPacket.Frame requestFrame = EtherMidiPacket.decode(request);
                    eq("F0 43 30 3E 12 01 00 33 00 00 00 00 F7",
                            Ls9Sysex.hex(requestFrame.payload()), "fader request on the wire");

                    byte[] reply = EtherMidiPacket.sysex(0, 1,
                            new byte[]{(byte) 0xF0, 0x43, 0x10, 0x3E, 0x12, 0x01, 0x00, 0x33,
                                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x06, 0x37,
                                    (byte) 0xF7});
                    out.write(reply);
                    out.flush();

                    byte[] bulkRequest = readPacket(in);
                    eq(0x16, bulkRequest[4] & 0xFF, "bulk request sysex command");
                    eq("F0 43 20 3E 12 53 43 45 4E 45 5F 5F 5F 00 07 F7",
                            Ls9Sysex.hex(EtherMidiPacket.decode(bulkRequest).payload()),
                            "bulk request on the wire");
                    byte[] bulkData = new byte[]{9, 8, 7, (byte) 0x80, 6};
                    byte[] bulkSysex = BulkDumpCodec.encode(0, BulkDumpCodec.SCENE, 7, bulkData);
                    out.write(EtherMidiPacket.sysex(0, 1, bulkSysex));
                    out.flush();

                    // Wait for the client to close so the socket is not reset mid-test.
                    try {
                        readPacket(in);
                    } catch (IOException ignored) {
                        // expected EOF or close packet
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "fake-ls9");
            serverThread.start();

            try (Ls9Console console = Ls9Console.builder()
                    .host("127.0.0.1")
                    .tcpPort(server.getLocalPort())
                    .authData(authenticate ? mac : null)
                    .timeoutMs(2000)
                    .open()) {
                console.input(1).setFaderDb(0.0);
                eq(823, console.input(1).faderRaw(), "fader request round trip");

                BulkDump dump = console.requestBulkDump(BulkDumpCodec.SCENE, 7);
                eq(7, dump.number(), "bulk dump number");
                check(java.util.Arrays.equals(new byte[]{9, 8, 7, (byte) 0x80, 6}, dump.data()),
                        "bulk dump data");
            }

            serverThread.join(3000);
            if (failure.get() != null) {
                throw new AssertionError("fake console failed", failure.get());
            }
        }
    }

    private static byte[] readPacket(DataInputStream in) throws IOException {
        byte[] head = new byte[4];
        in.readFully(head);
        int length = EtherMidiPacket.getInt(head, 0);
        byte[] packet = new byte[length];
        System.arraycopy(head, 0, packet, 0, 4);
        in.readFully(packet, 4, length - 4);
        return packet;
    }

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
