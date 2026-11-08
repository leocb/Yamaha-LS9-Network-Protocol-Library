package io.github.leocb.ls9.bulk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import io.github.leocb.ls9.sysex.ParameterAddress;

/**
 * Semantically decoded parameters from one bulk-dump block.
 *
 * <p>Decoding follows LS9 Editor's descriptor walk
 * ({@code calcDataSize}/{@code getIndParamOffset} over
 * {@code _gElementDesc}, {@code _gParamDesc}, {@code _gLibraryDesc} and
 * {@code _gCollectionDesc}), so every field in the image is produced with its
 * published {@link ParameterAddress}. Only parameters present in the block are
 * produced; a short or synthetic block is decoded as far as its bytes reach.
 */
public final class Ls9BulkData {

    private final BulkDump dump;
    private final BulkModel model;
    private final BulkDumpParser.Type type;
    private final Map<ParameterAddress, Long> parameters;
    private final BulkScene scene;
    private final Ls9BulkEntry entry;

    private Ls9BulkData(BulkDump dump, BulkModel model, BulkDumpParser.Type type,
                        Map<ParameterAddress, Long> parameters, BulkScene scene,
                        Ls9BulkEntry entry) {
        this.dump = dump;
        this.model = model;
        this.type = type;
        this.parameters = parameters;
        this.scene = scene;
        this.entry = entry;
    }

    static Ls9BulkData create(BulkDump dump, BulkModel model,
                              BulkDumpParser.Type type, Map<ParameterAddress, Long> parameters) {
        BulkScene scene = type == BulkDumpParser.Type.SCENE ? BulkScene.from(dump) : null;
        Ls9BulkEntry entry = Ls9BulkLayout.get()
                .select(dump.module(), dump.data())
                .map(selection -> Ls9BulkEntry.from(dump))
                .orElse(null);
        Map<ParameterAddress, Long> all = new LinkedHashMap<>(parameters);
        if (entry != null) {
            all.putAll(entry.parameters());
        }
        return new Ls9BulkData(dump, model, type,
                Collections.unmodifiableMap(all), scene, entry);
    }

    /** The source block. */
    public BulkDump dump() {
        return dump;
    }

    public BulkModel model() {
        return model;
    }

    public BulkDumpParser.Type type() {
        return type;
    }

    /** Scene header view for {@code SCENE___} blocks, otherwise {@code null}. */
    public BulkScene scene() {
        return scene;
    }

    /**
     * Library-entry view for library blocks, otherwise {@code null}.
     * Library entries share the 16-byte title with scene memories but carry
     * no comment or timestamp.
     */
    public Ls9BulkEntry library() {
        return entry != null && entry.isLibrary() ? entry : null;
    }

    /**
     * Decoded descriptor view for any block with a known layout (scene,
     * current-data areas and libraries), otherwise {@code null}.
     */
    public Ls9BulkEntry entry() {
        return entry;
    }

    /** Decoded parameter values keyed by element, index and channel. */
    public Map<ParameterAddress, Long> parameters() {
        return parameters;
    }

    public Long get(int element, int index, int channel) {
        return parameters.get(ParameterAddress.of(element, index, channel));
    }

    public long getOrDefault(int element, int index, int channel, long defaultValue) {
        Long value = parameters.get(ParameterAddress.of(element, index, channel));
        return value == null ? defaultValue : value;
    }

    public int size() {
        return parameters.size();
    }

    @Override
    public String toString() {
        return "Ls9BulkData{" + type + " " + dump.module().trim() + " #" + dump.number()
                + ", " + parameters.size() + " parameters}";
    }
}
