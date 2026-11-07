package io.github.leocb.ls9;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.leocb.ls9.bulk.BulkDump;
import io.github.leocb.ls9.bulk.BulkDumpCodec;
import io.github.leocb.ls9.sysex.Ls9Sysex;

/**
 * Studio-Manager-style synchronization using the public bulk-dump protocol.
 *
 * <p>This transfers complete opaque blocks (current data, setup, scenes,
 * libraries). It does not decode every parameter from the block; individual
 * parameters remain available through {@link Ls9Parameter}.
 */
public final class Ls9Sync {

    public static final int SCENE_MAX = 300;
    public static final int INPUT_EQ_MAX = 199;
    public static final int OUTPUT_EQ_MAX = 199;
    public static final int DYNAMICS_MAX = 199;
    public static final int GEQ_MAX = 199;
    public static final int EFFECT_MAX = 199;

    private final Ls9Console console;

    public Ls9Sync(Ls9Console console) {
        this.console = console;
    }

    public BulkDump currentData() throws IOException {
        return console.bulk().currentData();
    }

    public Map<String, BulkDump> setup() throws IOException {
        Map<String, BulkDump> out = new LinkedHashMap<>();
        for (String module : new String[]{
                BulkDumpCodec.MIXER_SETUP, BulkDumpCodec.OUTPUT_PORT,
                BulkDumpCodec.MONITOR_SETUP, BulkDumpCodec.MIDI_SETUP,
                BulkDumpCodec.LIB_NUMBER, BulkDumpCodec.PROGRAM_CHANGE_TABLE,
                BulkDumpCodec.CONTROL_CHANGE_TABLE}) {
            out.put(module, console.bulk().setup(module));
        }
        return out;
    }

    /** Reads scene metadata (title and date) using LIBRARY ATTRIBUTE. */
    public Ls9SceneInfo sceneInfo(int sceneNumber) throws IOException {
        Ls9LibraryRange range = console.library().exists(Ls9Sysex.MODULE_SCENE, sceneNumber);
        boolean exists = range.valid() && sceneNumber >= range.top() && sceneNumber <= range.end();
        if (!exists) {
            return new Ls9SceneInfo(sceneNumber, false, "", 0, 0, 0, 0, 0, 0);
        }
        String title = console.library().getSceneTitle(sceneNumber);
        int[] date = new int[6];
        for (int i = 0; i < 6; i++) {
            date[i] = (int) console.library().getAttributeRaw(
                    Ls9Sysex.MODULE_SCENE, sceneNumber, 0x0002, 0, i);
        }
        return new Ls9SceneInfo(sceneNumber, true, title,
                date[0], date[1], date[2], date[3], date[4], date[5]);
    }

    /** Downloads every existing scene in the range using LibExist ranges. */
    public List<BulkDump> scenes(int from, int to) throws IOException {
        List<BulkDump> out = new ArrayList<>();
        int n = Math.max(0, from);
        int last = Math.min(SCENE_MAX, to);
        while (n <= last) {
            Ls9LibraryRange range = console.library().exists(Ls9Sysex.MODULE_SCENE, n);
            if (!range.valid()) {
                break;
            }
            int start = Math.max(n, range.top());
            int end = Math.min(last, range.end());
            for (int i = start; i <= end; i++) {
                out.add(console.bulk().scene(i));
            }
            n = range.end() + 1;
        }
        return out;
    }

    /** Downloads every existing entry of one library module. */
    public List<BulkDump> library(String module, int from, int to) throws IOException {
        List<BulkDump> out = new ArrayList<>();
        int n = Math.max(0, from);
        int last = Math.min(199, to);
        while (n <= last) {
            Ls9LibraryRange range = console.library().exists(module, n);
            if (!range.valid()) {
                break;
            }
            int start = Math.max(n, range.top());
            int end = Math.min(last, range.end());
            for (int i = start; i <= end; i++) {
                out.add(console.bulk().library(module, i));
            }
            n = range.end() + 1;
        }
        return out;
    }

    /**
     * Downloads current data, setup blocks and the scene list. Library contents
     * and scene metadata are not included.
     */
    public Ls9Snapshot snapshot() throws IOException {
        Ls9Snapshot s = new Ls9Snapshot();
        s.setCurrentData(currentData());
        s.setup().putAll(setup());
        for (BulkDump d : scenes(0, SCENE_MAX)) {
            s.addScene(d);
        }
        return s;
    }

    /** Like {@link #snapshot()} but also reads each scene title and date. */
    public Ls9Snapshot snapshotWithSceneInfo() throws IOException {
        Ls9Snapshot s = snapshot();
        for (Integer number : s.scenes().keySet()) {
            s.addSceneInfo(sceneInfo(number));
        }
        return s;
    }

    /** Downloads current data, setup, scenes and all six library types. */
    public Ls9Snapshot fullSnapshot() throws IOException {
        Ls9Snapshot s = snapshot();
        addLibraries(s, BulkDumpCodec.INPUT_EQ, 1, INPUT_EQ_MAX);
        addLibraries(s, BulkDumpCodec.OUTPUT_EQ, 1, OUTPUT_EQ_MAX);
        addLibraries(s, BulkDumpCodec.DYNAMICS, 1, DYNAMICS_MAX);
        addLibraries(s, BulkDumpCodec.GEQ, 0, GEQ_MAX);
        addLibraries(s, BulkDumpCodec.EFFECT, 1, EFFECT_MAX);
        return s;
    }

    private void addLibraries(Ls9Snapshot snapshot, String module, int from, int to) throws IOException {
        for (BulkDump d : library(module, from, to)) {
            snapshot.addLibrary(module, d);
        }
    }

    /**
     * Uploads a snapshot. Current data is sent last so that it becomes the
     * active state after setup and library blocks have been written.
     */
    public void upload(Ls9Snapshot snapshot) throws IOException {
        for (BulkDump d : snapshot.setup().values()) {
            console.sendBulkDump(d);
        }
        for (BulkDump d : snapshot.scenes().values()) {
            console.sendBulkDump(d);
        }
        for (Map<Integer, BulkDump> map : snapshot.libraries().values()) {
            for (BulkDump d : map.values()) {
                console.sendBulkDump(d);
            }
        }
        if (snapshot.currentData() != null) {
            console.sendBulkDump(snapshot.currentData());
        }
    }

    public void sendScene(BulkDump dump) throws IOException {
        console.sendBulkDump(dump);
    }

    public void sendLibrary(BulkDump dump) throws IOException {
        console.sendBulkDump(dump);
    }
}
