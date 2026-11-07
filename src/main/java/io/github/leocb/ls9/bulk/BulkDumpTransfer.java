package io.github.leocb.ls9.bulk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import io.github.leocb.ls9.Ls9Console;

/**
 * High-level bulk-dump operations: whole-console snapshots, scene/library
 * download and upload.
 */
public final class BulkDumpTransfer {

    private final Ls9Console console;

    public BulkDumpTransfer(Ls9Console console) {
        this.console = console;
    }

    /** The complete current mixer state (scene number 512). */
    public BulkDump currentData() throws IOException {
        return console.requestBulkDump(BulkDumpCodec.SCENE, BulkDumpCodec.SCENE_CURRENT);
    }

    /** The complete current mixer state including recall-safe data (768). */
    public BulkDump currentDataWithRecallSafe() throws IOException {
        return console.requestBulkDump(BulkDumpCodec.SCENE, BulkDumpCodec.SCENE_CURRENT_RECALL_SAFE);
    }

    /** Sends a complete current-state block to the console. */
    public void sendCurrentData(BulkDump dump) throws IOException {
        console.sendBulkDump(dump);
    }

    /** Requests one scene memory (0..300). */
    public BulkDump scene(int sceneNumber) throws IOException {
        return console.requestBulkDump(BulkDumpCodec.SCENE, sceneNumber);
    }

    /** Requests one library entry. */
    public BulkDump library(String module, int number) throws IOException {
        return console.requestBulkDump(module, number);
    }

    /** Requests a setup block such as {@code MIXERSET} or {@code MIDI_SET}. */
    public BulkDump setup(String module) throws IOException {
        return console.requestBulkDump(module, 512);
    }

    /** Downloads a contiguous scene range; invalid/empty scenes may time out. */
    public List<BulkDump> downloadScenes(int from, int to) throws IOException {
        List<BulkDump> out = new ArrayList<>();
        for (int n = from; n <= to; n++) {
            out.add(scene(n));
        }
        return out;
    }

    /** Downloads a contiguous library range. */
    public List<BulkDump> downloadLibrary(String module, int from, int to) throws IOException {
        List<BulkDump> out = new ArrayList<>();
        for (int n = from; n <= to; n++) {
            out.add(library(module, n));
        }
        return out;
    }

    /** Uploads scene blocks in list order. */
    public void uploadScenes(List<BulkDump> scenes) throws IOException {
        for (BulkDump d : scenes) {
            console.sendBulkDump(d);
        }
    }

    /** Uploads library blocks in list order. */
    public void uploadLibraries(List<BulkDump> blocks) throws IOException {
        for (BulkDump d : blocks) {
            console.sendBulkDump(d);
        }
    }

    /**
     * Downloads the six setup blocks that Studio Manager uses for a full
     * session snapshot. Library lists are not included; request them
     * separately with the numbered ranges.
     */
    public List<BulkDump> downloadSetup() throws IOException {
        List<BulkDump> out = new ArrayList<>();
        out.add(setup(BulkDumpCodec.MIXER_SETUP));
        out.add(setup(BulkDumpCodec.OUTPUT_PORT));
        out.add(setup(BulkDumpCodec.MONITOR_SETUP));
        out.add(setup(BulkDumpCodec.MIDI_SETUP));
        out.add(setup(BulkDumpCodec.LIB_NUMBER));
        out.add(setup(BulkDumpCodec.PROGRAM_CHANGE_TABLE));
        out.add(setup(BulkDumpCodec.CONTROL_CHANGE_TABLE));
        return out;
    }

    /** Uploads setup blocks. */
    public void uploadSetup(List<BulkDump> blocks) throws IOException {
        for (BulkDump d : blocks) {
            console.sendBulkDump(d);
        }
    }
}
