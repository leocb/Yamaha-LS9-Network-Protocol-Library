package io.github.leocb.ls9.bulk;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import io.github.leocb.ls9.internal.Text;
import io.github.leocb.ls9.sysex.Ls9ParameterRegistry;
import io.github.leocb.ls9.sysex.ParameterAddress;
import io.github.leocb.ls9.sysex.ParameterDefinition;

/**
 * Decoded bulk image: one scene, one library entry, or one current-data area.
 *
 * <p>The payload is the memory image of one entry. Library and setup entries
 * share the scene header but have no comment or timestamp:
 * <pre>
 *   0x00  16-byte title (ASCII)
 *   0x10  version, big-endian 16-bit   (getVersionFromBuffer, 0x1000b1330)
 *   0x12  one-byte flag
 *   0x13  variant, 0..4                (getVariantFromBuffer, 0x1000b1320)
 *   0x14  32-bit reserved value
 *   0x18  32-bit reserved value
 *   0x1C  layout fields, see {@link Ls9BulkLayout}
 *   end-4 trailing checksum, little-endian 32-bit
 * </pre>
 *
 * <p>The trailing checksum is produced by
 * {@code CCollectionBase::setCheckSumToBuffer} (0x1000b2030) using
 * {@code mem_CalcCheckSumLong} (0x1000d73a0): sum every 32-bit word except the
 * last, byte-swapping each word, then complement and byte-swap.
 *
 * <p>Parameter values use the published wire addresses from
 * {@link Ls9BulkLayout}; title words are exposed at element {@code 0x0193}
 * indices 0..3, matching the scene decoder and StageMix. Names, value tables
 * and display ranges come from {@link Ls9ParameterRegistry}.
 */
public final class Ls9BulkEntry {

    public static final int TITLE_OFFSET = 0x00;
    public static final int TITLE_BYTES = 16;
    public static final int TITLE_WORDS = 4;
    public static final int VERSION_OFFSET = 0x10;
    public static final int FLAG_OFFSET = 0x12;
    public static final int VARIANT_OFFSET = 0x13;
    public static final int RESERVED0_OFFSET = 0x14;
    public static final int RESERVED1_OFFSET = 0x18;
    public static final int PAYLOAD_OFFSET = 0x1C;
    public static final int TITLE_ELEMENT = 0x0193;

    /** One decoded parameter value. */
    public record Value(ParameterAddress address, long value, Ls9BulkLayout.Field field,
                        ParameterDefinition definition) {
        public String name() {
            if (definition != null) {
                return definition.element() + "." + definition.indexName();
            }
            return String.format("0x%04X[%d]", address.element(), address.index());
        }
    }

    private final BulkDump dump;
    private final byte[] data;
    private final Ls9BulkLayout.AreaKind areaKind;
    private final int area;
    private final Ls9BulkLayout.Layout layout;
    private final int declaredVariant;
    private final Map<ParameterAddress, Long> parameters;
    private final List<Value> values;

    private Ls9BulkEntry(BulkDump dump, Ls9BulkLayout.AreaKind areaKind,
                            int area, Ls9BulkLayout.Layout layout) {
        this.dump = dump;
        this.data = dump.data();
        this.areaKind = areaKind;
        this.area = area;
        this.layout = layout;
        this.declaredVariant = u8(VARIANT_OFFSET);
        Map<ParameterAddress, Long> map = new LinkedHashMap<>();
        List<Value> list = new ArrayList<>();
        Ls9ParameterRegistry registry = Ls9ParameterRegistry.get();
        for (Ls9BulkLayout.Field field : layout.parameters()) {
            Long value = read(field);
            if (value == null) {
                continue;
            }
            ParameterAddress address = ParameterAddress.of(
                    field.element(), field.index(), field.channel());
            map.put(address, value);
            ParameterDefinition definition = registry.find(field.element(), field.index())
                    .orElse(null);
            list.add(new Value(address, value, field, definition));
        }
        for (int i = 0; i < TITLE_WORDS; i++) {
            int offset = TITLE_OFFSET + i * 4;
            if (data.length < offset + 4) {
                break;
            }
            long word = ((data[offset] & 0xFFL) << 24)
                    | ((data[offset + 1] & 0xFFL) << 16)
                    | ((data[offset + 2] & 0xFFL) << 8)
                    | (data[offset + 3] & 0xFFL);
            ParameterAddress address = ParameterAddress.of(TITLE_ELEMENT, i, 0);
            map.put(address, word);
            ParameterDefinition definition = registry.find(TITLE_ELEMENT, i).orElse(null);
            list.add(new Value(address, word, null, definition));
        }
        if (isScene()) {
            ParameterAddress address = ParameterAddress.of(0x0195, 0, 0);
            long value = u8(FLAG_OFFSET);
            map.put(address, value);
            ParameterDefinition definition = registry.find(0x0195, 0).orElse(null);
            list.add(new Value(address, value, null, definition));
        }
        this.parameters = Collections.unmodifiableMap(map);
        this.values = Collections.unmodifiableList(list);
    }

    private boolean isScene() {
        return areaKind == Ls9BulkLayout.AreaKind.LIBRARY && area == 0;
    }

    /**
     * Wraps a library block. The library number comes from the module name;
     * the layout variant comes from byte 0x13 of the payload.
     */
    public static Ls9BulkEntry from(BulkDump dump) {
        if (dump == null) {
            throw new IllegalArgumentException("dump must not be null");
        }
        Ls9BulkLayout.Selection selection = Ls9BulkLayout.get()
                .select(dump.module(), dump.data())
                .orElseThrow(() -> new IllegalArgumentException(
                        "no layout for block: " + Text.trimTrailing(dump.module())));
        return new Ls9BulkEntry(dump, selection.areaKind(), selection.area(),
                selection.layout());
    }

    /** Wraps a block as an explicit library number (0..10). */
    public static Ls9BulkEntry from(BulkDump dump, int library) {
        if (dump == null) {
            throw new IllegalArgumentException("dump must not be null");
        }
        Ls9BulkLayout layouts = Ls9BulkLayout.get();
        int variant = dump.length() > VARIANT_OFFSET ? dump.data()[VARIANT_OFFSET] & 0xFF : 0;
        Ls9BulkLayout.Layout layout = layouts.find(
                Ls9BulkLayout.AreaKind.LIBRARY, library, variant).orElseThrow(
                () -> new IllegalArgumentException("no library layout for " + library
                        + " variant " + variant));
        return new Ls9BulkEntry(dump, Ls9BulkLayout.AreaKind.LIBRARY, library, layout);
    }

    /** Whether this module name carries a library entry. */
    public static boolean isLibraryModule(String module) {
        return Ls9BulkLayout.libraryForModule(module).isPresent();
    }

    /**
     * Whether this block is a library entry. The scene lives in the editor's
     * library array as number 0 but is exposed as a scene, not as a library.
     */
    public boolean isLibrary() {
        return areaKind == Ls9BulkLayout.AreaKind.LIBRARY && area != 0;
    }

    public BulkDump dump() {
        return dump;
    }

    /** LS9 Editor library number, or the collection number for setup areas. */
    public int area() {
        return area;
    }

    public Ls9BulkLayout.AreaKind areaKind() {
        return areaKind;
    }

    /** Library number, or -1 for a current-data collection. */
    public int library() {
        return isLibrary() ? area : -1;
    }

    public Optional<Ls9BulkLayout.Library> libraryKind() {
        return isLibrary() ? Ls9BulkLayout.Library.of(area) : Optional.empty();
    }

    /** Lay out selected by the entry's variant byte. */
    public Ls9BulkLayout.Layout layout() {
        return layout;
    }

    /** Variant byte stored at 0x13. */
    public int declaredVariant() {
        return declaredVariant;
    }

    /** Big-endian 16-bit version at 0x10. */
    public int version() {
        return be16(VERSION_OFFSET);
    }

    /** One-byte flag at 0x12. */
    public int flag() {
        return u8(FLAG_OFFSET);
    }

    /** First reserved 32-bit value at 0x14. */
    public long reserved0() {
        return be32(RESERVED0_OFFSET);
    }

    /** Second reserved 32-bit value at 0x18. */
    public long reserved1() {
        return be32(RESERVED1_OFFSET);
    }

    /** Size in bytes of the library image for the selected variant. */
    public int dataSize() {
        return layout.dataSize();
    }

    /** Whether the block carries at least the complete image. */
    public boolean isComplete() {
        return data.length >= layout.dataSize();
    }

    /** 16-character ASCII title at 0x00. */
    public String title() {
        int length = Math.min(TITLE_BYTES, data.length - TITLE_OFFSET);
        if (length <= 0) {
            return "";
        }
        byte[] text = new byte[length];
        for (int i = 0; i < length; i++) {
            text[i] = (byte) (data[TITLE_OFFSET + i] & 0x7F);
        }
        int end = text.length;
        while (end > 0) {
            int c = text[end - 1] & 0xFF;
            if (c == 0 || c == 0x7F || Character.isWhitespace((char) c)) {
                end--;
            } else {
                break;
            }
        }
        return Text.trimTrailing(new String(text, 0, end, StandardCharsets.US_ASCII));
    }

    /** Trailing 32-bit checksum, read native little-endian like {@code getCheckSum}. */
    public long checksum() {
        int size = Math.min(layout.dataSize(), data.length);
        if (size < 4) {
            return 0xFFFFFFFFL;
        }
        int offset = size - 4;
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    /** Recomputes the checksum over the selected layout size. */
    public boolean checksumValid() {
        int size = layout.dataSize();
        if (data.length < size) {
            return false;
        }
        long stored = (data[size - 4] & 0xFFL)
                | ((data[size - 3] & 0xFFL) << 8)
                | ((data[size - 2] & 0xFFL) << 16)
                | ((data[size - 1] & 0xFFL) << 24);
        return stored == calcCheckSumLong(data, size);
    }

    /** Every decoded parameter, including title words. */
    public Map<ParameterAddress, Long> parameters() {
        return parameters;
    }

    /** Every decoded value with field metadata and the published definition. */
    public List<Value> values() {
        return values;
    }

    public Long get(int element, int index, int channel) {
        return parameters.get(ParameterAddress.of(element, index, channel));
    }

    public long getOrDefault(int element, int index, int channel, long fallback) {
        Long value = parameters.get(ParameterAddress.of(element, index, channel));
        return value == null ? fallback : value;
    }

    /** All values for one element, ordered as laid out in the image. */
    public List<Value> valuesForElement(int element) {
        List<Value> out = new ArrayList<>();
        for (Value value : values) {
            if (value.address().element() == element) {
                out.add(value);
            }
        }
        return out;
    }

    /**
     * Checksum algorithm from {@code mem_CalcCheckSumLong} (0x1000d73a0).
     * Sums every 32-bit word before the last, byte-swapping each one, then
     * returns the complemented, byte-swapped result.
     */
    static long calcCheckSumLong(byte[] data, int size) {
        int words = (size & ~3);
        if (words <= 4) {
            return 0xFFFFFFFFL;
        }
        int sum = 0;
        for (int offset = 0; offset < words - 4; offset += 4) {
            int word = (data[offset] & 0xFF)
                    | ((data[offset + 1] & 0xFF) << 8)
                    | ((data[offset + 2] & 0xFF) << 16)
                    | ((data[offset + 3] & 0xFF) << 24);
            sum += Integer.reverseBytes(word);
        }
        int result = ~sum;
        return Integer.toUnsignedLong(Integer.reverseBytes(result));
    }

    private Long read(Ls9BulkLayout.Field field) {
        int offset = field.offset();
        Ls9BulkLayout.Kind kind = field.kind();
        if (kind == Ls9BulkLayout.Kind.BIT) {
            if (offset < 0 || offset >= data.length) {
                return null;
            }
            return (long) ((data[offset] >> field.bit()) & 1);
        }
        int size = kind.bytes();
        if (offset < 0 || offset + size > data.length) {
            return null;
        }
        long value;
        switch (kind) {
            case U8 -> value = data[offset] & 0xFFL;
            case S8 -> value = data[offset];
            case U16, S16 -> {
                value = ((data[offset] & 0xFFL) << 8) | (data[offset + 1] & 0xFFL);
                if (kind.signed() && (value & 0x8000L) != 0) {
                    value |= 0xFFFFFFFFFFFF0000L;
                }
            }
            case S16BE -> {
                value = ((data[offset] & 0xFFL) << 8) | (data[offset + 1] & 0xFFL);
                if ((value & 0x8000L) != 0) {
                    value |= 0xFFFFFFFFFFFF0000L;
                }
            }
            case U32, S32, S32BE -> {
                value = ((data[offset] & 0xFFL) << 24)
                        | ((data[offset + 1] & 0xFFL) << 16)
                        | ((data[offset + 2] & 0xFFL) << 8)
                        | (data[offset + 3] & 0xFFL);
                if (kind.signed()) {
                    value = (int) value;
                }
            }
            default -> {
                return null;
            }
        }
        return value;
    }

    private int u8(int offset) {
        return offset < data.length ? data[offset] & 0xFF : 0;
    }

    private int be16(int offset) {
        if (offset + 1 >= data.length) {
            return 0;
        }
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private long be32(int offset) {
        if (offset + 3 >= data.length) {
            return 0;
        }
        return ((data[offset] & 0xFFL) << 24)
                | ((data[offset + 1] & 0xFFL) << 16)
                | ((data[offset + 2] & 0xFFL) << 8)
                | (data[offset + 3] & 0xFFL);
    }

    @Override
    public String toString() {
        return "Ls9BulkEntry{" + libraryKind().map(Ls9BulkLayout.Library::label)
                .orElse(areaKind + " " + area) + " #" + dump.number()
                + ", variant " + declaredVariant + ", title=\"" + title() + "\""
                + ", " + parameters.size() + " parameters"
                + (isComplete() && checksumValid() ? ", checksum OK" : "") + "}";
    }
}
