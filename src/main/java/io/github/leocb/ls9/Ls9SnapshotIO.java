package io.github.leocb.ls9;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import io.github.leocb.ls9.bulk.BulkDump;
import io.github.leocb.ls9.bulk.BulkDumpCodec;

/**
 * Portable binary snapshot format (not Yamaha's proprietary .yse format).
 * Stores bulk-dump blocks losslessly.
 */
public final class Ls9SnapshotIO {

    private static final byte[] MAGIC = "LS9SNAP1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int VERSION = 1;

    private Ls9SnapshotIO() {
    }

    public static void save(Ls9Snapshot snapshot, File file) throws IOException {
        try (OutputStream raw = new FileOutputStream(file);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw))) {
            out.write(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(snapshot.blockCount());
            for (BulkDump d : snapshot.allBlocks()) {
                out.writeUTF(d.module());
                out.writeInt(d.number());
                byte[] data = d.data();
                out.writeInt(data.length);
                out.write(data);
            }
        }
    }

    public static Ls9Snapshot load(File file) throws IOException {
        try (InputStream raw = new FileInputStream(file);
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw))) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new IOException("not an LS9 snapshot file");
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("unsupported snapshot version " + version);
            }
            int count = in.readInt();
            Ls9Snapshot snapshot = new Ls9Snapshot();
            for (int i = 0; i < count; i++) {
                String module = in.readUTF();
                int number = in.readInt();
                int length = in.readInt();
                if (length < 0 || length > 16 * 1024 * 1024) {
                    throw new IOException("invalid block length " + length);
                }
                byte[] data = new byte[length];
                in.readFully(data);
                BulkDump dump = new BulkDump(module, number,
                        BulkDumpCodec.convert8to7(data), data, 0, new byte[0]);
                classify(snapshot, dump);
            }
            return snapshot;
        }
    }

    private static void classify(Ls9Snapshot snapshot, BulkDump dump) {
        String module = dump.module();
        int number = dump.number();
        if (module.equals(BulkDumpCodec.SCENE)) {
            if (number == BulkDumpCodec.SCENE_CURRENT) {
                snapshot.setCurrentData(dump);
            } else if (number == BulkDumpCodec.SCENE_CURRENT_RECALL_SAFE) {
                snapshot.setCurrentDataRecallSafe(dump);
            } else {
                snapshot.addScene(dump);
            }
        } else if (isSetup(module)) {
            snapshot.setup().put(module, dump);
        } else {
            snapshot.addLibrary(module, dump);
        }
    }

    private static boolean isSetup(String module) {
        return module.equals(BulkDumpCodec.MIXER_SETUP)
                || module.equals(BulkDumpCodec.OUTPUT_PORT)
                || module.equals(BulkDumpCodec.MONITOR_SETUP)
                || module.equals(BulkDumpCodec.MIDI_SETUP)
                || module.equals(BulkDumpCodec.LIB_NUMBER)
                || module.equals(BulkDumpCodec.PROGRAM_CHANGE_TABLE)
                || module.equals(BulkDumpCodec.CONTROL_CHANGE_TABLE)
                || module.equals(BulkDumpCodec.PREFERENCE_CURRENT)
                || module.equals(BulkDumpCodec.PREFERENCE_ADMIN)
                || module.equals(BulkDumpCodec.PREFERENCE_GUEST)
                || module.equals(BulkDumpCodec.USER_KEYS_CURRENT)
                || module.equals(BulkDumpCodec.USER_KEYS_ADMIN)
                || module.equals(BulkDumpCodec.USER_KEYS_GUEST)
                || module.equals(BulkDumpCodec.CUSTOM_FADER_CURRENT)
                || module.equals(BulkDumpCodec.CUSTOM_FADER_ADMIN)
                || module.equals(BulkDumpCodec.CUSTOM_FADER_GUEST)
                || module.equals(BulkDumpCodec.USER_LEVEL_CURRENT)
                || module.equals(BulkDumpCodec.USER_LEVEL_GUEST);
    }
}
