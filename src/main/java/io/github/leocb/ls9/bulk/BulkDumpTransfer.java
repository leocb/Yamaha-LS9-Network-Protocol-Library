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

    /** Requests one scene memory (0..300) and decodes its scene header. */
    public BulkScene sceneView(int sceneNumber) throws IOException {
        return BulkScene.from(scene(sceneNumber));
    }

    /** Requests one scene memory (0..300) and reads title/date from the block. */
    public io.github.leocb.ls9.Ls9SceneInfo sceneInfo(int sceneNumber) throws IOException {
        return io.github.leocb.ls9.Ls9SceneInfo.fromBulk(scene(sceneNumber));
    }

    /** Requests one scene memory and decodes every parameter the block carries. */
    public Ls9BulkData sceneData(int sceneNumber) throws IOException {
        return Ls9BulkDecoder.decode(scene(sceneNumber));
    }

    /** Requests one scene memory and decodes it for an explicit model. */
    public Ls9BulkData sceneData(int sceneNumber, BulkModel model) throws IOException {
        return Ls9BulkDecoder.decode(scene(sceneNumber), model);
    }

    /** The complete current mixer state with every decodable parameter. */
    public Ls9BulkData currentDataDecoded() throws IOException {
        return Ls9BulkDecoder.decode(currentData());
    }

    /** The complete current mixer state decoded for an explicit model. */
    public Ls9BulkData currentDataDecoded(BulkModel model) throws IOException {
        return Ls9BulkDecoder.decode(currentData(), model);
    }

    /** The complete current mixer state with the current scene header decoded. */
    public BulkScene currentSceneView() throws IOException {
        return BulkScene.from(currentData());
    }

    /** Requests one library entry. */
    public BulkDump library(String module, int number) throws IOException {
        return console.requestBulkDump(module, number);
    }

    /** Requests one block and decodes every field with a known layout. */
    public Ls9BulkData data(String module, int number) throws IOException {
        return Ls9BulkDecoder.decode(library(module, number));
    }

    /** Requests one block and decodes it for an explicit model. */
    public Ls9BulkData data(String module, int number, BulkModel model) throws IOException {
        return Ls9BulkDecoder.decode(library(module, number), model);
    }

    /** Requests one block and returns its descriptor view (header + values). */
    public Ls9BulkEntry entry(String module, int number) throws IOException {
        return Ls9BulkEntry.from(library(module, number));
    }

    /** Requests one library entry and decodes it. */
    public Ls9BulkData libraryData(String module, int number) throws IOException {
        return data(module, number);
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
