package io.github.leocb.ls9;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;
import io.github.leocb.ls9.bulk.BulkDump;
import io.github.leocb.ls9.bulk.BulkDumpAssembler;
import io.github.leocb.ls9.bulk.BulkDumpCodec;
import io.github.leocb.ls9.bulk.BulkDumpPacket;
import io.github.leocb.ls9.bulk.BulkDumpParser;
import io.github.leocb.ls9.bulk.BulkModel;
import io.github.leocb.ls9.bulk.BulkScene;
import io.github.leocb.ls9.bulk.Ls9BulkData;
import io.github.leocb.ls9.bulk.Ls9BulkDecoder;
import io.github.leocb.ls9.bulk.Ls9BulkEntry;
import io.github.leocb.ls9.bulk.Ls9BulkLayout;
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

        testBulkSceneParser();
        testBulkDecode();
        testLibraryLayouts();

        byte[] corrupt = BulkDumpCodec.encode(0, BulkDumpCodec.SCENE, 3,
                new byte[]{1, 2, 3});
        corrupt[corrupt.length - 2] ^= 0x01;
        check(BulkDumpCodec.parse(corrupt) == null, "bulk dump rejects bad checksum");

        byte[] wrongModel = BulkDumpCodec.encode(0, BulkDumpCodec.SCENE, 3,
                new byte[]{1, 2, 3});
        wrongModel[6] = 0x13;
        check(BulkDumpCodec.parse(wrongModel) == null, "bulk dump rejects bad model");

        // StageMix pads the logical image to seven bytes before 8-to-7
        // conversion, so the on-wire data length is a multiple of eight.
        byte[] shortImage = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        byte[] padded = BulkDumpCodec.encodePadded(0, BulkDumpCodec.SCENE, 4, shortImage);
        BulkDump paddedDump = BulkDumpCodec.parse(padded);
        check(paddedDump != null, "padded bulk dump parse");
        eq(14, paddedDump.length(), "padded logical length");
        eq(0, paddedDump.data()[10] & 0xFF, "padded byte 10");
        eq(0, paddedDump.data()[13] & 0xFF, "padded byte 13");
        check(paddedDump.encodedData().length % 8 == 0, "padded encoded length");

        // Multi-packet block: StageMix accumulates encoded chunks and decodes
        // only after the packet whose index equals the last-packet index.
        byte[] logical14 = new byte[14];
        for (int i = 0; i < logical14.length; i++) {
            logical14[i] = (byte) (0x81 + i);
        }
        byte[] encoded16 = BulkDumpCodec.convert8to7(logical14);
        byte[] p0 = BulkDumpCodec.encodePacket(0, BulkDumpCodec.SCENE, 9,
                1, 0, java.util.Arrays.copyOfRange(encoded16, 0, 8));
        byte[] p1 = BulkDumpCodec.encodePacket(0, BulkDumpCodec.SCENE, 9,
                1, 1, java.util.Arrays.copyOfRange(encoded16, 8, 16));
        BulkDumpPacket packet0 = BulkDumpCodec.parsePacket(p0);
        BulkDumpPacket packet1 = BulkDumpCodec.parsePacket(p1);
        check(packet0 != null && packet1 != null, "multi-packet parse");
        eq(0, packet0.packetIndex(), "packet 0 index");
        eq(1, packet1.lastPacket(), "packet 1 last index");
        check(BulkDumpCodec.parse(p0) == null, "partial block is not complete");
        BulkDumpAssembler assembler = new BulkDumpAssembler();
        check(assembler.add(packet0) == null, "first packet is partial");
        BulkDump complete = assembler.add(packet1);
        check(complete != null, "multi-packet completes");
        eq(9, complete.number(), "multi-packet number");
        check(java.util.Arrays.equals(logical14, complete.data()), "multi-packet data");

        byte[] longEncoded = new byte[BulkDumpCodec.MAX_ENCODED_CHUNK + 8];
        for (int i = 0; i < longEncoded.length; i++) {
            longEncoded[i] = (byte) (i & 0x7F);
        }
        java.util.List<byte[]> longPackets = BulkDumpCodec.encodeEncodedPackets(
                0, BulkDumpCodec.SCENE, 10, longEncoded);
        eq(2, longPackets.size(), "long block packet count");
        BulkDumpAssembler longAssembler = new BulkDumpAssembler();
        BulkDump longComplete = null;
        for (byte[] p : longPackets) {
            BulkDumpPacket parsedPacket = BulkDumpCodec.parsePacket(p);
            check(parsedPacket != null, "long block packet parse");
            longComplete = longAssembler.add(parsedPacket);
        }
        check(longComplete != null, "long block completes");
        check(java.util.Arrays.equals(BulkDumpCodec.convert7to8(longEncoded),
                longComplete.data()), "long block data");
    }

    private static void testBulkSceneParser() {
        // Offsets validated against LS9 StageMix 6.0.7
        // SceneBulkHandlerBase::DecodeCurSceneData<TLS9_CurSceneBulkDump*>.
        byte[] image = new byte[0x5E];
        java.util.Arrays.fill(image, (byte) ' ');
        putAscii(image, 0x00, "Title Words Here");
        image[0x12] = (byte) 0x42;
        putAscii(image, 0x38, "This is the scene comment       ");
        image[0x58] = 24;
        image[0x59] = 10;
        image[0x5A] = 6;
        image[0x5B] = 13;
        image[0x5C] = 45;
        image[0x5D] = 20;

        BulkDump dump = BulkDumpCodec.parse(BulkDumpCodec.encode(0,
                BulkDumpCodec.SCENE, 7, image));
        BulkScene scene = BulkDumpParser.scene(dump);
        eq("Title Words Here", scene.title(), "scene title");
        eq("This is the scene comment", scene.comment(), "scene comment");
        eq("2024-11-07 13:45:20", scene.dateTime(), "scene date");
        eq(le32(image, 0x00), scene.sceneWord(0), "scene word 0");
        eq(le32(image, 0x0C), scene.sceneWord(3), "scene word 3");
        eq(0x42, scene.sceneByte(), "scene byte");
        eq(7, scene.number(), "scene number");
        eq(le32(image, 0x00), scene.headerParameters().get(
                io.github.leocb.ls9.sysex.ParameterAddress.of(0x0193, 0, 0)),
                "scene header parameter 0x193/0");
        eq(20L, scene.headerParameters().get(
                io.github.leocb.ls9.sysex.ParameterAddress.of(0x0002, 0, 5)),
                "scene header parameter date seconds");
        Ls9SceneInfo info = Ls9SceneInfo.fromBulk(dump);
        eq("Title Words Here", info.title(), "scene info title");
        eq("2024-11-07 13:45:20", info.dateTime(), "scene info date");

        eq(BulkDumpParser.Type.SCENE, BulkDumpParser.typeOf("SCENE___"), "scene type");
        eq(BulkDumpParser.Type.MIXER_SETUP, BulkDumpParser.typeOf("MIXERSET"), "mixer type");
        eq(BulkDumpParser.Type.INPUT_CHANNEL, BulkDumpParser.typeOf("INCHNNL_"), "input library type");
        eq(BulkDumpParser.Type.OUTPUT_CHANNEL, BulkDumpParser.typeOf("OUTCHNNL"), "output library type");
        eq(BulkDumpParser.Type.GEQ, BulkDumpParser.typeOf("GEQ_____"), "geq type");
        eq(BulkDumpParser.Type.EFFECT, BulkDumpParser.typeOf("EFFECT__"), "effect type");
        eq(BulkDumpParser.Type.OUTPUT_PORT, BulkDumpParser.typeOf("OUT_PORT"), "out port type");
        eq(BulkDumpParser.Type.UNKNOWN, BulkDumpParser.typeOf("NOPE____"), "unknown type");
        eq(BulkDumpParser.Type.MONITOR_SETUP, BulkDumpParser.typeOf("MONITOR_"), "monitor type");
        eq(BulkDumpParser.Type.MIDI_SETUP, BulkDumpParser.typeOf("MIDI_SET"), "midi setup type");
        eq(BulkDumpParser.Type.LIBRARY_NUMBER, BulkDumpParser.typeOf("LIB_NUM_"), "library number type");
        eq(BulkDumpParser.Type.PROGRAM_CHANGE_TABLE,
                BulkDumpParser.typeOf("PRGMCHG_"), "program change type");
        eq(BulkDumpParser.Type.CONTROL_CHANGE_TABLE,
                BulkDumpParser.typeOf("CTRLCHG_"), "control change type");
        eq(BulkDumpParser.Type.USER_LEVELS, BulkDumpParser.typeOf("UKEY_CUR"), "user levels type");
        eq(BulkDumpParser.Type.USER_KEYS, BulkDumpParser.typeOf("UDEF_CUR"), "user keys type");
        eq(BulkDumpParser.Type.PREFERENCES, BulkDumpParser.typeOf("PREF_CUR"), "preferences type");
        eq(BulkDumpParser.Type.CUSTOM_FADER, BulkDumpParser.typeOf("CFAD_CUR"), "custom fader type");

        BulkScene shortScene = BulkDumpParser.scene(BulkDumpCodec.parse(
                BulkDumpCodec.encode(0, BulkDumpCodec.SCENE, 8, new byte[]{1, 2, 3})));
        eq("", shortScene.title(), "short scene title");
        eq("", shortScene.comment(), "short scene comment");
        eq("", shortScene.dateTime(), "short scene date");
        eq(0L, shortScene.sceneWord(0), "short scene word");

        // Exact first 0x5E bytes of LS9 Editor's _SceneDefault image
        // (0x1003dfd30). It uses the same header layout as the StageMix decoder.
        byte[] editorDefault = new byte[]{
            (byte)0x49,(byte)0x6E,(byte)0x69,(byte)0x74,(byte)0x69,(byte)0x61,(byte)0x6C,(byte)0x20,
            (byte)0x44,(byte)0x61,(byte)0x74,(byte)0x61,(byte)0x20,(byte)0x20,(byte)0x20,(byte)0x20,
            (byte)0x00,(byte)0x01,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,
            (byte)0x00,(byte)0x00,(byte)0x02,(byte)0x69,(byte)0x00,(byte)0x48,(byte)0x00,(byte)0x00,
            (byte)0x00,(byte)0x00,(byte)0x00,(byte)0x10,(byte)0x00,(byte)0x08,(byte)0x00,(byte)0x03,
            (byte)0x00,(byte)0x08,(byte)0x00,(byte)0x04,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,
            (byte)0x00,(byte)0x04,(byte)0x00,(byte)0x08,(byte)0x00,(byte)0x08,(byte)0x00,(byte)0x00,
            (byte)0x49,(byte)0x6E,(byte)0x69,(byte)0x74,(byte)0x69,(byte)0x61,(byte)0x6C,(byte)0x20,
            (byte)0x53,(byte)0x65,(byte)0x74,(byte)0x74,(byte)0x69,(byte)0x6E,(byte)0x67,(byte)0x20,
            (byte)0x44,(byte)0x61,(byte)0x74,(byte)0x61,(byte)0x20,(byte)0x20,(byte)0x20,(byte)0x20,
            (byte)0x20,(byte)0x20,(byte)0x20,(byte)0x20,(byte)0x20,(byte)0x20,(byte)0x20,(byte)0x20,
            (byte)0x06,(byte)0x08,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00
        };
        BulkDump defaultDump = BulkDumpCodec.parse(
                BulkDumpCodec.encode(0, BulkDumpCodec.SCENE, 0, editorDefault));
        BulkScene defaultScene = BulkScene.from(defaultDump);
        eq("Initial Data", defaultScene.title(), "editor default title");
        eq("Initial Setting Data", defaultScene.comment(), "editor default comment");
        eq("2006-09-01 00:00:00", defaultScene.dateTime(), "editor default date");
        Ls9BulkData defaultData = Ls9BulkDecoder.decode(defaultDump);
        eq("Initial Data", defaultData.entry().title(), "descriptor default title");
        eq(be32(editorDefault, 0x38), defaultData.getOrDefault(0x0001, 0, 0, -1),
                "descriptor default comment word");
        eq(0x06L, defaultData.getOrDefault(0x0002, 0, 0, -1), "descriptor default year");
    }

    private static void testBulkDecode() {
        byte[] data = new byte[0x4500];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 13 + 7);
        }
        BulkDump scene = new BulkDump(BulkDumpCodec.SCENE, 7,
                BulkDumpCodec.convert8to7(data), data, 0, new byte[0]);
        Ls9BulkData decoded = Ls9BulkDecoder.decode(scene, BulkModel.LS9_32);
        check(decoded.size() > 1000, "scene decode parameter count " + decoded.size());
        eq((long) (data[0x5f3] & 1), decoded.getOrDefault(0x31, 0, 0, -1),
                "input switch 0x31 channel 0");
        eq((long) (data[0x10d3] & 0xFF), decoded.getOrDefault(0x3c, 1, 0, -1),
                "input send 0x3c/1");
        eq((long) ((data[0x2f2a] & 0xFF)), decoded.getOrDefault(0x54, 3, 0, -1),
                "mix control 0x54/3");
        eq((long) (short) ((data[0x3536] & 0xFF) << 8 | (data[0x3537] & 0xFF)),
                decoded.getOrDefault(0x7f, 9, 0, -1), "GEQ band 9");
        eq((long) (data[0x12] & 0xFF), decoded.getOrDefault(0x195, 0, 0, -1),
                "scene number 0x195");
        eq(be32(data, 0x38), decoded.getOrDefault(0x0001, 0, 0, -1),
                "scene comment word 0");
        eq((long) (data[0x58] & 0xFF), decoded.getOrDefault(0x0002, 0, 0, -1),
                "scene date year");
        check(decoded.scene() != null, "scene view");
        check(decoded.entry() != null && !decoded.entry().isLibrary(),
                "scene descriptor entry");
        check(decoded.library() == null, "scene is not a library");

        BulkDump mix = new BulkDump(BulkDumpCodec.MIXER_SETUP, 512,
                BulkDumpCodec.convert8to7(data), data, 0, new byte[0]);
        Ls9BulkData mixData = Ls9BulkDecoder.decode(mix, BulkModel.LS9_32);
        eq((long) ((data[0xbc] & 0xFF) << 8 | (data[0xbd] & 0xFF)),
                mixData.getOrDefault(0x209, 1, 0, -1), "mixer setup 0x209/1");
        eq((long) (data[0x98] & 1), mixData.getOrDefault(0x173, 0, 0, -1), "mute group membership bit");

        BulkDump out = new BulkDump(BulkDumpCodec.OUTPUT_PORT, 512,
                BulkDumpCodec.convert8to7(data), data, 0, new byte[0]);
        Ls9BulkData outData = Ls9BulkDecoder.decode(out, BulkModel.LS9_32);
        eq((long) (short) ((data[0xbc] & 0xFF) << 8 | (data[0xbd] & 0xFF)),
                outData.getOrDefault(0x208, 0, 16, -1), "out port 0x208/0/16");
        eq((long) ((data[0x134] & 0xFF) << 8 | (data[0x135] & 0xFF)),
                outData.getOrDefault(0x21e, 2, 0, -1), "out port 0x21e/2");

        // Library image: 316 bytes for the input-channel library (layout from
        // LS9 Editor's descriptor tables, variant 0). Header: title, version,
        // flag, variant. Field offsets are from ls9-bulk-layouts.csv.
        byte[] lib = new byte[316];
        putAscii(lib, 0, "Test Channel    ");
        lib[0x10] = 0x01;
        lib[0x11] = 0x02;
        lib[0x12] = 1;
        lib[0x13] = 0;
        lib[28] = 1;                       // element 0x2B / index 0, bit 0
        lib[30] = 77;                      // element 0x2C / index 1, u8
        lib[32] = 0x12;                    // element 0x2D / index 1, be16
        lib[33] = 0x34;
        lib[40] = 0x01;                    // element 0x2F / index 2, be16
        lib[41] = 0x23;
        putChecksum(lib);
        BulkDump library = new BulkDump(BulkDumpCodec.INPUT_CHANNEL, 1,
                BulkDumpCodec.convert8to7(lib), lib, 0, new byte[0]);
        Ls9BulkData libraryData = Ls9BulkDecoder.decode(library, BulkModel.LS9_32);
        check(libraryData.library() != null, "library view");
        eq("Test Channel", libraryData.library().title(), "library title");
        eq(0x0102, libraryData.library().version(), "library version");
        eq(0, libraryData.library().declaredVariant(), "library variant");
        eq(0, libraryData.library().layout().variant(), "library layout variant");
        check(libraryData.library().isComplete(), "library complete");
        check(libraryData.library().checksumValid(), "library checksum");
        eq(1L, libraryData.getOrDefault(0x2B, 0, 0, -1), "library bit field");
        eq(77L, libraryData.getOrDefault(0x2C, 1, 0, -1), "library byte field");
        eq(0x1234L, libraryData.getOrDefault(0x2D, 1, 0, -1), "library word field");
        eq(0x0123L, libraryData.getOrDefault(0x2F, 2, 0, -1), "library word field 2");
        eq(be32(lib, 0), libraryData.getOrDefault(0x193, 0, 0, -1),
                "library title word 0");

        // GEQ library (160 bytes, variant 0): element 0x7F, signed 16-bit gains.
        byte[] geq = new byte[160];
        putAscii(geq, 0, "GEQ             ");
        geq[0x13] = 0;
        geq[28] = 1;                       // kGEQType
        geq[29] = 2;                       // kGEQLRLink
        geq[30] = (byte) 0xFF;             // kGEQGainL1 = -1
        geq[31] = (byte) 0xF1;             // -15
        putChecksum(geq);
        Ls9BulkEntry geqEntry = Ls9BulkEntry.from(new BulkDump(
                BulkDumpCodec.GEQ, 1, BulkDumpCodec.convert8to7(geq), geq, 0, new byte[0]));
        eq(160, geqEntry.layout().dataSize(), "GEQ layout size");
        eq(1L, geqEntry.getOrDefault(0x7F, 0, 0, -1), "GEQ type");
        eq(2L, geqEntry.getOrDefault(0x7F, 2, 0, -1), "GEQ LR link");
        eq(-15L, geqEntry.getOrDefault(0x7F, 9, 0, -1), "GEQ gain 1");

        // Effect library (164 bytes, variant 0): element 0x100.
        byte[] effect = new byte[164];
        putAscii(effect, 0, "Effect          ");
        effect[0x13] = 0;
        effect[28] = 88;                   // kEffectMix, signed byte
        effect[29] = 3;                    // kEffectType
        effect[32] = 0x00;                 // kEffectParam1 = 12345, signed 32-bit
        effect[33] = 0x00;
        effect[34] = 0x30;
        effect[35] = 0x39;
        putChecksum(effect);
        Ls9BulkEntry effectEntry = Ls9BulkEntry.from(new BulkDump(
                BulkDumpCodec.EFFECT, 1, BulkDumpCodec.convert8to7(effect), effect, 0, new byte[0]));
        eq(0x100, effectEntry.valuesForElement(0x100).get(0).address().element(),
                "effect published element");
        eq(88L, effectEntry.getOrDefault(0x100, 9, 0, -1), "effect mix");
        eq(3L, effectEntry.getOrDefault(0x100, 10, 0, -1), "effect type");
        eq(12345L, effectEntry.getOrDefault(0x100, 11, 0, -1), "effect param 1");
        check(!effectEntry.values().isEmpty(), "effect values list");
        check(effectEntry.valuesForElement(0x100).get(0).name().contains("kEffect"),
                "effect value name");

        // OUTCHNNL variant 1 selects the matrix-channel layout.
        byte[] outLib = new byte[112];
        outLib[0x13] = 1;
        putChecksum(outLib);
        Ls9BulkEntry outEntry = Ls9BulkEntry.from(new BulkDump(
                BulkDumpCodec.OUTPUT_CHANNEL, 1, BulkDumpCodec.convert8to7(outLib), outLib, 0, new byte[0]));
        eq(1, outEntry.layout().variant(), "output channel variant");
        eq(112, outEntry.layout().dataSize(), "output channel size");
    }

    private static void testLibraryLayouts() {
        Ls9BulkLayout layouts = Ls9BulkLayout.get();
        check(layouts.has(Ls9BulkLayout.AreaKind.LIBRARY, 0), "scene layout exists");
        check(layouts.has(Ls9BulkLayout.AreaKind.LIBRARY, 10), "HA layout exists");
        eq(17548, layouts.find(Ls9BulkLayout.AreaKind.LIBRARY, 0, 0).orElseThrow()
                .dataSize(), "scene layout size");
        eq(952, layouts.find(Ls9BulkLayout.AreaKind.LIBRARY, 1, 0).orElseThrow()
                .dataSize(), "input patch size");
        eq(372, layouts.find(Ls9BulkLayout.AreaKind.LIBRARY, 2, 0).orElseThrow()
                .dataSize(), "output patch size");
        eq(128, layouts.find(Ls9BulkLayout.AreaKind.LIBRARY, 10, 0).orElseThrow()
                .dataSize(), "HA size");
        eq(3, layouts.variantCount(Ls9BulkLayout.AreaKind.LIBRARY, 4), "output channel variants");
        eq(5, layouts.variantCount(Ls9BulkLayout.AreaKind.LIBRARY, 7), "dynamics variants");
        eq(2, layouts.variantCount(Ls9BulkLayout.AreaKind.LIBRARY, 8), "GEQ variants");
        eq(1644, layouts.find(Ls9BulkLayout.AreaKind.COLLECTION, 0, 0).orElseThrow()
                .dataSize(), "setup collection size");
        eq(15328, layouts.find(Ls9BulkLayout.AreaKind.COLLECTION, 2, 0).orElseThrow()
                .dataSize(), "mixer setting size");
        eq(408, layouts.find(Ls9BulkLayout.AreaKind.COLLECTION, 22, 0).orElseThrow()
                .dataSize(), "MIXERSET size");
        eq(428, layouts.find(Ls9BulkLayout.AreaKind.COLLECTION, 23, 0).orElseThrow()
                .dataSize(), "OUT_PORT size");

        // Module routing follows LS9 Editor's findLibInfo table.
        eq(3, Ls9BulkLayout.areaForModule("INCHNNL_").orElse(-1), "INCHNNL area");
        eq(4, Ls9BulkLayout.areaForModule("OUTCHNNL").orElse(-1), "OUTCHNNL area");
        eq(5, Ls9BulkLayout.areaForModule("INEQ____").orElse(-1), "INEQ area");
        eq(6, Ls9BulkLayout.areaForModule("OUTEQ___").orElse(-1), "OUTEQ area");
        eq(7, Ls9BulkLayout.areaForModule("DYNA____").orElse(-1), "DYNA area");
        eq(8, Ls9BulkLayout.areaForModule("GEQ_____").orElse(-1), "GEQ area");
        eq(9, Ls9BulkLayout.areaForModule("EFFECT__").orElse(-1), "EFFECT area");
        eq(22, Ls9BulkLayout.areaForModule("MIXERSET").orElse(-1), "MIXERSET area");
        eq(23, Ls9BulkLayout.areaForModule("OUT_PORT").orElse(-1), "OUT_PORT area");
        eq(24, Ls9BulkLayout.areaForModule("MONITOR_").orElse(-1), "MONITOR area");
        eq(25, Ls9BulkLayout.areaForModule("MIDI_SET").orElse(-1), "MIDI_SET area");
        eq(28, Ls9BulkLayout.areaForModule("LIB_NUM_").orElse(-1), "LIB_NUM area");
        eq(4, Ls9BulkLayout.areaForModule("PRGMCHG_").orElse(-1), "PRGMCHG area");
        eq(5, Ls9BulkLayout.areaForModule("CTRLCHG_").orElse(-1), "CTRLCHG area");

        // Every library module variant can be selected from the payload byte.
        eq(0, Ls9BulkLayout.kindForModule("INCHNNL_").ordinal(), "INCHNNL kind");
        eq(1, Ls9BulkLayout.kindForModule("MIXERSET").ordinal(), "MIXERSET kind");
        eq(0x0100, Ls9BulkLayout.encodeElement(128), "effect element encoding");
        eq(0x0123, Ls9BulkLayout.encodeElement(163), "AD824 element encoding");
        eq(0x0200, Ls9BulkLayout.encodeElement(256), "setup element encoding");
        eq(0x0209, Ls9BulkLayout.encodeElement(265), "recall-safe element encoding");

        // Setup blocks decode through the collection descriptors.
        byte[] mix = new byte[408];
        mix[0xbc] = 0x12;
        mix[0xbd] = 0x34;
        putChecksum(mix);
        Ls9BulkData mixData = Ls9BulkDecoder.decode(new BulkDump(
                BulkDumpCodec.MIXER_SETUP, 512, BulkDumpCodec.convert8to7(mix),
                mix, 0, new byte[0]));
        eq(0x1234L, mixData.getOrDefault(0x209, 1, 0, -1), "MIXERSET recall-safe value");
        check(mixData.entry() != null, "MIXERSET descriptor entry");
        check(mixData.library() == null, "MIXERSET is not a library");
        check(mixData.entry().checksumValid(), "MIXERSET checksum");

        byte[] outPort = new byte[428];
        outPort[0xbc] = 0x43;
        outPort[0xbd] = 0x21;
        putChecksum(outPort);
        Ls9BulkData outPortData = Ls9BulkDecoder.decode(new BulkDump(
                BulkDumpCodec.OUTPUT_PORT, 512, BulkDumpCodec.convert8to7(outPort),
                outPort, 0, new byte[0]));
        eq(0x4321L, outPortData.getOrDefault(0x208, 0, 16, -1),
                "OUT_PORT attenuator channel 16");
        check(outPortData.entry() != null, "OUT_PORT descriptor entry");
        check(outPortData.entry().checksumValid(), "OUT_PORT checksum");
    }

    private static void putChecksum(byte[] data) {
        int size = data.length;
        int sum = 0;
        for (int offset = 0; offset < size - 4; offset += 4) {
            int word = (data[offset] & 0xFF)
                    | ((data[offset + 1] & 0xFF) << 8)
                    | ((data[offset + 2] & 0xFF) << 16)
                    | ((data[offset + 3] & 0xFF) << 24);
            sum += Integer.reverseBytes(word);
        }
        int value = Integer.reverseBytes(~sum);
        data[size - 4] = (byte) value;
        data[size - 3] = (byte) (value >>> 8);
        data[size - 2] = (byte) (value >>> 16);
        data[size - 1] = (byte) (value >>> 24);
    }

    private static long be32(byte[] data, int offset) {
        return ((data[offset] & 0xFFL) << 24)
                | ((data[offset + 1] & 0xFFL) << 16)
                | ((data[offset + 2] & 0xFFL) << 8)
                | (data[offset + 3] & 0xFFL);
    }

    private static long le32(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    private static void putAscii(byte[] data, int offset, String text) {
        for (int i = 0; i < text.length() && offset + i < data.length; i++) {
            data[offset + i] = (byte) (text.charAt(i) & 0x7F);
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
