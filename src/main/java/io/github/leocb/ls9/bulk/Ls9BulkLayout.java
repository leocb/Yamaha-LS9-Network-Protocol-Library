package io.github.leocb.ls9.bulk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import io.github.leocb.ls9.internal.Csv;
import io.github.leocb.ls9.internal.Io;

/**
 * Layouts of LS9 bulk-block payloads, reconstructed from LS9 Editor's private
 * memory descriptor tables.
 *
 * <p>The console transfers a bulk block as the raw memory image of one scene,
 * one library entry, or one current-data area. That image is laid out by LS9
 * Editor's {@code CCollectionBase::calcDataSize} / {@code getIndParamOffset}
 * walk over {@code _gLibraryDesc}, {@code _gCollectionDesc},
 * {@code _gElementDesc} and {@code _gParamDesc}. The resource
 * {@code ls9-bulk-layouts.csv} is generated from those tables by
 * {@code tools/generate_bulk_layouts.py}.
 *
 * <p>Library numbers are the editor's {@code CLibraryBase} array indexes:
 * <ol start="0">
 *   <li>{@code SCENE___} scene memories</li>
 *   <li>input patch</li>
 *   <li>output patch</li>
 *   <li>{@code INCHNNL_} input channel</li>
 *   <li>{@code OUTCHNNL} mix / matrix / stereo (three variants)</li>
 *   <li>{@code INEQ____} input EQ</li>
 *   <li>{@code OUTEQ___} output EQ (three variants)</li>
 *   <li>{@code DYNA____} dynamics (five variants)</li>
 *   <li>{@code GEQ_____} GEQ / PEQ (two variants)</li>
 *   <li>{@code EFFECT__} effect</li>
 *   <li>HA (head amp)</li>
 * </ol>
 *
 * <p>Collection numbers are the editor's {@code CCollectionBase} array
 * indexes (0..38), which correspond to the current-data areas such as
 * {@code MIXERSET}, {@code OUT_PORT}, {@code MONITOR_}, {@code MIDI_SET},
 * user levels, preferences and the MIDI program/control tables.
 *
 * <p>Element numbers in the resource are the published wire numbers used by
 * {@link io.github.leocb.ls9.sysex.ParameterAddress}. LS9 Editor stores
 * elements densely; {@link #encodeElement(int)} converts a dense editor
 * element to its wire number. The conversion is the inverse of the editor's
 * own element encoding and is confirmed by Yamaha's parameter list
 * (dense 128 = {@code kEffect} = 0x0100, dense 163 = {@code kAD824} = 0x0123,
 * dense 256 = {@code kSetupPGMChangeTable} = 0x0200).
 */
public final class Ls9BulkLayout {

    private static final String RESOURCE = "/io/github/leocb/ls9/ls9-bulk-layouts.csv";
    private static volatile Ls9BulkLayout instance;

    /** Which editor descriptor table the layout came from. */
    public enum AreaKind {
        LIBRARY,
        COLLECTION
    }

    /** Semantic content of one library number. */
    public enum Library {
        SCENE(0, "scene"),
        INPUT_PATCH(1, "input patch"),
        OUTPUT_PATCH(2, "output patch"),
        INPUT_CHANNEL(3, "input channel"),
        OUTPUT_CHANNEL(4, "output channel"),
        INPUT_EQ(5, "input EQ"),
        OUTPUT_EQ(6, "output EQ"),
        DYNAMICS(7, "dynamics"),
        GEQ(8, "GEQ/PEQ"),
        EFFECT(9, "effect"),
        HA(10, "head amp");

        private final int index;
        private final String label;

        Library(int index, String label) {
            this.index = index;
            this.label = label;
        }

        public int index() {
            return index;
        }

        public String label() {
            return label;
        }

        public static Optional<Library> of(int index) {
            for (Library value : values()) {
                if (value.index == index) {
                    return Optional.of(value);
                }
            }
            return Optional.empty();
        }
    }

    /** Value kind from {@code _gParamDesc} byte +0x0C. */
    public enum Kind {
        BIT("bit", 0, false),
        U8("u8", 1, false),
        S8("s8", 1, true),
        U16("u16", 2, false),
        S16("s16", 2, true),
        S16BE("s16be", 2, true),
        U32("u32", 4, false),
        S32("s32", 4, true),
        S32BE("s32be", 4, true);

        private final String token;
        private final int bytes;
        private final boolean signed;

        Kind(String token, int bytes, boolean signed) {
            this.token = token;
            this.bytes = bytes;
            this.signed = signed;
        }

        public int bytes() {
            return bytes;
        }

        public boolean signed() {
            return signed;
        }

        public static Kind from(String token) {
            for (Kind value : values()) {
                if (value.token.equals(token)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("unknown layout field kind: " + token);
        }
    }

    /** Which part of the image a field belongs to. */
    public enum Region {
        TITLE,
        VERSION,
        FLAG,
        VARIANT,
        RESERVED,
        CHECKSUM,
        PARAM,
        META
    }

    /**
     * One value carried by a bulk image.
     *
     * @param offset  byte offset in the image
     * @param bit     bit position 0..7 for {@link Kind#BIT}, otherwise -1
     * @param channel channel / repetition index
     * @param element published wire element for {@link Region#PARAM} /
     *                {@link Region#META}; unused for header regions
     * @param index   published index within the element, or position for meta
     * @param min     {@code _gParamDesc} minimum
     * @param max     {@code _gParamDesc} maximum
     * @param defaultValue {@code _gParamDesc} default (unsigned 32-bit)
     * @param paramDescriptor index into {@code _gParamDesc}
     */
    public record Field(Region region, int offset, int bit, Kind kind, int element,
                        int index, int channel, long min, long max, long defaultValue,
                        int paramDescriptor) {
    }

    /** Immutable layout for one area variant. */
    public static final class Layout {
        private final AreaKind kind;
        private final int area;
        private final int variant;
        private final int dataSize;
        private final int collection;
        private final int version;
        private final List<Field> fields;
        private final List<Field> parameters;

        private Layout(AreaKind kind, int area, int variant, int dataSize,
                       int collection, int version, List<Field> fields) {
            this.kind = kind;
            this.area = area;
            this.variant = variant;
            this.dataSize = dataSize;
            this.collection = collection;
            this.version = version;
            this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
            List<Field> params = new ArrayList<>();
            for (Field field : fields) {
                if (field.region() == Region.PARAM || field.region() == Region.META) {
                    params.add(field);
                }
            }
            this.parameters = Collections.unmodifiableList(params);
        }

        public AreaKind areaKind() {
            return kind;
        }

        public int area() {
            return area;
        }

        public int variant() {
            return variant;
        }

        /** Size of the image in bytes, including the trailing checksum. */
        public int dataSize() {
            return dataSize;
        }

        public int collection() {
            return collection;
        }

        public int version() {
            return version;
        }

        /** Every field in layout order, including header and checksum. */
        public List<Field> fields() {
            return fields;
        }

        /** Parameter and meta fields only. */
        public List<Field> parameters() {
            return parameters;
        }

        @Override
        public String toString() {
            return "Layout{" + kind + " " + area + " variant " + variant
                    + ", size=" + dataSize + ", fields=" + fields.size() + "}";
        }
    }

    /** A layout selection for one module name. */
    public record Selection(AreaKind areaKind, int area, Layout layout) {
        public boolean isLibrary() {
            return areaKind == AreaKind.LIBRARY;
        }
    }

    private final Map<AreaKind, Map<Integer, Map<Integer, Layout>>> layouts;

    private Ls9BulkLayout(Map<AreaKind, Map<Integer, Map<Integer, Layout>>> layouts) {
        this.layouts = Collections.unmodifiableMap(layouts);
    }

    public static Ls9BulkLayout get() {
        Ls9BulkLayout local = instance;
        if (local == null) {
            synchronized (Ls9BulkLayout.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    private static Ls9BulkLayout load() {
        try (InputStream in = Ls9BulkLayout.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            String text = new String(Io.readAll(in), StandardCharsets.UTF_8);
            List<List<String>> rows = Csv.parse(text);
            Map<Integer, List<Field>> fields = new LinkedHashMap<>();
            Map<Integer, int[]> meta = new LinkedHashMap<>();
            for (int i = 1; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                if (row.size() < 18 || row.get(0).trim().isEmpty()) {
                    continue;
                }
                AreaKind kind = AreaKind.valueOf(
                        row.get(0).trim().toUpperCase(Locale.ROOT));
                int area = Integer.parseInt(row.get(1).trim());
                int variant = Integer.parseInt(row.get(2).trim());
                int dataSize = Integer.parseInt(row.get(3).trim());
                int collection = Integer.parseInt(row.get(4).trim());
                int version = Integer.parseInt(row.get(5).trim());
                Field field = new Field(
                        Region.valueOf(row.get(6).trim().toUpperCase(Locale.ROOT)),
                        Integer.parseInt(row.get(7).trim()),
                        Integer.parseInt(row.get(8).trim()),
                        Kind.from(row.get(10).trim()),
                        Integer.parseInt(row.get(11).trim()),
                        Integer.parseInt(row.get(12).trim()),
                        Integer.parseInt(row.get(13).trim()),
                        Long.parseLong(row.get(14).trim()),
                        Long.parseLong(row.get(15).trim()),
                        Long.parseLong(row.get(16).trim()),
                        Integer.parseInt(row.get(17).trim()));
                int key = (kind.ordinal() * 1000000) + area * 100 + variant;
                fields.computeIfAbsent(key, k -> new ArrayList<>()).add(field);
                meta.putIfAbsent(key, new int[] {kind.ordinal(), area, variant,
                        dataSize, collection, version});
            }
            Map<AreaKind, Map<Integer, Map<Integer, Layout>>> byKind = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<Field>> entry : fields.entrySet()) {
                int[] m = meta.get(entry.getKey());
                if (m == null) {
                    continue;
                }
                AreaKind kind = AreaKind.values()[m[0]];
                Layout layout = new Layout(kind, m[1], m[2], m[3], m[4], m[5],
                        entry.getValue());
                byKind.computeIfAbsent(kind, k -> new LinkedHashMap<>())
                        .computeIfAbsent(m[1], k -> new LinkedHashMap<>())
                        .put(m[2], layout);
            }
            return new Ls9BulkLayout(byKind);
        } catch (IOException e) {
            throw new IllegalStateException("cannot load " + RESOURCE, e);
        }
    }

    /** Number of layout variants known for an area. */
    public int variantCount(AreaKind kind, int area) {
        Map<Integer, Layout> variants = layouts.getOrDefault(kind, Collections.emptyMap()).get(area);
        return variants == null ? 0 : variants.size();
    }

    /** Layout lookup with a fallback to variant 0 for unknown variants. */
    public Optional<Layout> find(AreaKind kind, int area, int variant) {
        Map<Integer, Layout> variants = layouts.getOrDefault(kind, Collections.emptyMap()).get(area);
        if (variants == null) {
            return Optional.empty();
        }
        Layout layout = variants.get(variant);
        if (layout == null) {
            layout = variants.get(0);
        }
        return Optional.ofNullable(layout);
    }

    /** Whether the resource contains a layout for this area. */
    public boolean has(AreaKind kind, int area) {
        return layouts.getOrDefault(kind, Collections.emptyMap()).containsKey(area);
    }

    /**
     * Selects the layout for a bulk module and payload.
     *
     * <p>The variant byte is payload offset 0x13
     * ({@code getVariantFromBuffer}). Unknown modules return empty.
     */
    public Optional<Selection> select(String module, byte[] data) {
        OptionalInt area = areaForModule(module);
        if (area.isEmpty()) {
            return Optional.empty();
        }
        int number = area.getAsInt();
        AreaKind kind = kindForModule(module);
        int variant = data.length > 0x13 ? data[0x13] & 0xFF : 0;
        Optional<Layout> layout = find(kind, number, variant);
        return layout.map(value -> new Selection(kind, number, value));
    }

    /** Area number for a module name, ignoring whether it is a library. */
    public static OptionalInt areaForModule(String module) {
        OptionalInt library = libraryForModule(module);
        if (library.isPresent()) {
            return library;
        }
        if (module == null) {
            return OptionalInt.empty();
        }
        String m = module.length() >= 8 ? module.substring(0, 8) : module;
        return switch (m) {
            case BulkDumpCodec.MIXER_SETUP -> OptionalInt.of(22);
            case BulkDumpCodec.OUTPUT_PORT -> OptionalInt.of(23);
            case BulkDumpCodec.MONITOR_SETUP -> OptionalInt.of(24);
            case BulkDumpCodec.MIDI_SETUP -> OptionalInt.of(25);
            case BulkDumpCodec.LIB_NUMBER -> OptionalInt.of(28);
            case BulkDumpCodec.PROGRAM_CHANGE_TABLE -> OptionalInt.of(4);
            case BulkDumpCodec.CONTROL_CHANGE_TABLE -> OptionalInt.of(5);
            case BulkDumpCodec.USER_LEVEL_CURRENT -> OptionalInt.of(29);
            case BulkDumpCodec.USER_LEVEL_GUEST -> OptionalInt.of(31);
            case BulkDumpCodec.PREFERENCE_CURRENT -> OptionalInt.of(26);
            case BulkDumpCodec.PREFERENCE_ADMIN -> OptionalInt.of(32);
            case BulkDumpCodec.PREFERENCE_GUEST -> OptionalInt.of(33);
            case BulkDumpCodec.USER_KEYS_CURRENT -> OptionalInt.of(1);
            case BulkDumpCodec.USER_KEYS_ADMIN -> OptionalInt.of(34);
            case BulkDumpCodec.USER_KEYS_GUEST -> OptionalInt.of(35);
            case BulkDumpCodec.CUSTOM_FADER_CURRENT -> OptionalInt.of(38);
            case BulkDumpCodec.CUSTOM_FADER_ADMIN -> OptionalInt.of(36);
            case BulkDumpCodec.CUSTOM_FADER_GUEST -> OptionalInt.of(37);
            default -> OptionalInt.empty();
        };
    }

    /** Which descriptor table contains the module. */
    public static AreaKind kindForModule(String module) {
        return libraryForModule(module).isPresent()
                ? AreaKind.LIBRARY : AreaKind.COLLECTION;
    }

    /**
     * Maps a bulk-dump module name to an LS9 Editor library number.
     *
     * <p>{@code INCHNNL_}, {@code OUTCHNNL}, {@code GEQ_____} and
     * {@code EFFECT__} are the four library module names in StageMix's bulk
     * table. {@code INEQ____}, {@code OUTEQ___} and {@code DYNA____} are LS9
     * Editor's component-library module names.
     */
    public static OptionalInt libraryForModule(String module) {
        if (module == null) {
            return OptionalInt.empty();
        }
        String m = module.length() >= 8 ? module.substring(0, 8) : module;
        int library;
        switch (m) {
            case BulkDumpCodec.SCENE -> library = 0;
            case BulkDumpCodec.INPUT_CHANNEL -> library = 3;
            case BulkDumpCodec.OUTPUT_CHANNEL -> library = 4;
            case BulkDumpCodec.INPUT_EQ -> library = 5;
            case BulkDumpCodec.OUTPUT_EQ -> library = 6;
            case BulkDumpCodec.DYNAMICS -> library = 7;
            case BulkDumpCodec.GEQ -> library = 8;
            case BulkDumpCodec.EFFECT -> library = 9;
            default -> {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.of(library);
    }

    /**
     * Converts a dense LS9 Editor element index to the published wire element.
     *
     * <p>Editor elements are dense; the wire address uses 7-bit offsets with
     * one page per 128 dense entries. Examples from Yamaha's parameter list:
     * dense 128 ({@code kEffect}) = 0x0100, dense 163 ({@code kAD824}) =
     * 0x0123, dense 256 ({@code kSetupPGMChangeTable}) = 0x0200.
     */
    public static int encodeElement(int dense) {
        return ((dense >> 7) << 8) | (dense & 0x7F);
    }
}
