package io.github.leocb.ls9;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.leocb.ls9.bulk.BulkDump;

/**
 * A Studio-Manager-style session snapshot: current mixer data, setup blocks,
 * scene memories and optional library blocks.
 */
public final class Ls9Snapshot {

    private BulkDump currentData;
    private BulkDump currentDataRecallSafe;
    private final Map<String, BulkDump> setup = new LinkedHashMap<>();
    private final Map<Integer, BulkDump> scenes = new LinkedHashMap<>();
    private final Map<Integer, Ls9SceneInfo> sceneInfo = new LinkedHashMap<>();
    private final Map<String, Map<Integer, BulkDump>> libraries = new LinkedHashMap<>();

    public BulkDump currentData() {
        return currentData;
    }

    public void setCurrentData(BulkDump currentData) {
        this.currentData = currentData;
    }

    public BulkDump currentDataRecallSafe() {
        return currentDataRecallSafe;
    }

    public void setCurrentDataRecallSafe(BulkDump currentDataRecallSafe) {
        this.currentDataRecallSafe = currentDataRecallSafe;
    }

    public Map<String, BulkDump> setup() {
        return setup;
    }

    public Map<Integer, BulkDump> scenes() {
        return scenes;
    }

    public Map<Integer, Ls9SceneInfo> sceneInfo() {
        return sceneInfo;
    }

    public Map<String, Map<Integer, BulkDump>> libraries() {
        return libraries;
    }

    public void addScene(BulkDump dump) {
        scenes.put(dump.number(), dump);
    }

    public void addSceneInfo(Ls9SceneInfo info) {
        sceneInfo.put(info.number(), info);
    }

    public void addLibrary(String module, BulkDump dump) {
        libraries.computeIfAbsent(module, k -> new LinkedHashMap<>()).put(dump.number(), dump);
    }

    public List<BulkDump> allBlocks() {
        List<BulkDump> out = new ArrayList<>();
        if (currentData != null) {
            out.add(currentData);
        }
        if (currentDataRecallSafe != null) {
            out.add(currentDataRecallSafe);
        }
        out.addAll(setup.values());
        out.addAll(scenes.values());
        for (Map<Integer, BulkDump> map : libraries.values()) {
            out.addAll(map.values());
        }
        return out;
    }

    public int blockCount() {
        return allBlocks().size();
    }

    @Override
    public String toString() {
        return "Ls9Snapshot{current=" + (currentData != null)
                + ", setup=" + setup.size() + ", scenes=" + scenes.size()
                + ", libraries=" + libraries.size() + "}";
    }
}
