package io.github.leocb.ls9.bulk;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import io.github.leocb.ls9.internal.Text;
import io.github.leocb.ls9.sysex.ParameterAddress;

/**
 * Read-only view of the LS9 scene memory image carried by a {@code SCENE___}
 * bulk dump.
 *
 * <p>The field offsets are taken from the LS9 StageMix 6.0.7 decoder
 * ({@code SceneBulkHandlerBase::DecodeCurSceneData<TLS9_CurSceneBulkDump*>},
 * 0x1003a3b24) and cross-checked against LS9 Editor's {@code _SceneDefault}
 * image, whose 16-byte title, 32-byte comment and six-byte date sit at the same
 * offsets. The title is four four-character values at 0x00..0x0F, the comment
 * is eight four-character values at 0x38..0x57, and the timestamp is six bytes
 * at 0x58. The title words are also the four values mapped to element
 * {@code 0x0193}; the byte at 0x12 is mapped to element {@code 0x0195}.
 *
 * <p>This class only covers the scene header. The rest of the block is the
 * packed mixer image; use {@link #raw()} to retain it losslessly.
 */
public final class BulkScene {

    /** Offset of the first title word. */
    public static final int TITLE_OFFSET = 0x00;
    /** Number of four-character title words. */
    public static final int TITLE_WORDS = 4;
    /** Offset of the first comment word. */
    public static final int COMMENT_OFFSET = 0x38;
    /** Number of four-character comment words. */
    public static final int COMMENT_WORDS = 8;
    /** Offset of the first timestamp byte (year). */
    public static final int DATE_OFFSET = 0x58;
    /** Number of timestamp bytes: year, month, day, hour, minute, second. */
    public static final int DATE_BYTES = 6;
    /** Offset of the undocumented scene byte mapped to element {@code 0x195}. */
    public static final int SCENE_BYTE_OFFSET = 0x12;
    /** Offset of the title words, also element {@code 0x193} indices 0..3. */
    public static final int SCENE_WORD_OFFSET = 0x00;

    private final BulkDump dump;
    private final byte[] data;

    private BulkScene(BulkDump dump) {
        this.dump = dump;
        this.data = dump.data();
    }

    /** Wraps a bulk dump. The module is not checked so current data (512/768) works too. */
    public static BulkScene from(BulkDump dump) {
        if (dump == null) {
            throw new IllegalArgumentException("dump must not be null");
        }
        return new BulkScene(dump);
    }

    /** The block number (0..300 for stored scenes, 512/768 for current data). */
    public int number() {
        return dump.number();
    }

    /** The underlying dump. */
    public BulkDump dump() {
        return dump;
    }

    /** The decoded 8-bit mixer image, including any 7-bit padding. */
    public byte[] raw() {
        return data.clone();
    }

    /** Length of the decoded image in bytes. */
    public int length() {
        return data.length;
    }

    public boolean hasTitle() {
        return data.length >= TITLE_OFFSET + TITLE_WORDS * 4;
    }

    /** The 16-character scene title (element {@code 0x0193}, indices 0..3). */
    public String title() {
        if (!hasTitle()) {
            return "";
        }
        return ascii(TITLE_OFFSET, TITLE_WORDS * 4);
    }

    public boolean hasComment() {
        return data.length >= COMMENT_OFFSET + COMMENT_WORDS * 4;
    }

    /** The 32-character scene comment (element {@code 0x0001}, indices 0..7). */
    public String comment() {
        if (!hasComment()) {
            return "";
        }
        return ascii(COMMENT_OFFSET, COMMENT_WORDS * 4);
    }

    private String ascii(int offset, int length) {
        byte[] text = new byte[length];
        for (int i = 0; i < length; i++) {
            text[i] = (byte) (data[offset + i] & 0x7F);
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

    public boolean hasDate() {
        return data.length >= DATE_OFFSET + DATE_BYTES;
    }

    /** Two-digit year offset (0..255, add 2000). */
    public int year() {
        return byteAt(DATE_OFFSET);
    }

    /** Month 0..11. */
    public int month() {
        return byteAt(DATE_OFFSET + 1);
    }

    /** Day 0..30. */
    public int day() {
        return byteAt(DATE_OFFSET + 2);
    }

    /** Hour 0..23. */
    public int hour() {
        return byteAt(DATE_OFFSET + 3);
    }

    /** Minute 0..59. */
    public int minute() {
        return byteAt(DATE_OFFSET + 4);
    }

    /** Second 0..59. */
    public int second() {
        return byteAt(DATE_OFFSET + 5);
    }

    /** Formatted timestamp, or an empty string when the block has no date. */
    public String dateTime() {
        if (!hasDate()) {
            return "";
        }
        return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d",
                2000 + year(), month() + 1, day() + 1, hour(), minute(), second());
    }

    /**
     * One of the four title words, also mapped to element {@code 0x193}
     * indices 0..3. Words are read little-endian, matching the ARM64 decoder's
     * native load.
     */
    public long sceneWord(int index) {
        if (index < 0 || index > 3) {
            throw new IndexOutOfBoundsException("scene word index 0..3: " + index);
        }
        int offset = SCENE_WORD_OFFSET + index * 4;
        if (data.length < offset + 4) {
            return 0;
        }
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    /** The single undocumented scene byte mapped to element {@code 0x195}. */
    public int sceneByte() {
        return byteAt(SCENE_BYTE_OFFSET);
    }

    /**
     * Scene-header parameters keyed exactly as the StageMix decoder publishes
     * them: elements {@code 0x0193}, {@code 0x0001}, {@code 0x0002} and
     * {@code 0x0195}. Values are the raw numbers carried by the image; display
     * conversion still comes from the parameter/value tables.
     *
     * <p>Title and comment words are read little-endian here, matching
     * StageMix's native loads. {@link Ls9BulkData#parameters()} uses the
     * editor's descriptor kinds instead, so its 32-bit text words are
     * big-endian logical values.
     */
    public Map<ParameterAddress, Long> headerParameters() {
        Map<ParameterAddress, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) {
            out.put(ParameterAddress.of(0x0193, i, 0), sceneWord(i));
        }
        if (hasComment()) {
            for (int i = 0; i < COMMENT_WORDS; i++) {
                out.put(ParameterAddress.of(0x0001, i, 0),
                        littleEndian32(COMMENT_OFFSET + i * 4));
            }
        }
        if (hasDate()) {
            for (int i = 0; i < DATE_BYTES; i++) {
                out.put(ParameterAddress.of(0x0002, 0, i), (long) byteAt(DATE_OFFSET + i));
            }
        }
        out.put(ParameterAddress.of(0x0195, 0, 0), (long) sceneByte());
        return Collections.unmodifiableMap(out);
    }

    private long littleEndian32(int offset) {
        if (data.length < offset + 4) {
            return 0;
        }
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    private int byteAt(int offset) {
        if (offset < 0 || offset >= data.length) {
            return 0;
        }
        return data[offset] & 0xFF;
    }

    @Override
    public String toString() {
        return "BulkScene{#" + number() + ", title=\"" + title() + "\"," +
                " date=\"" + dateTime() + "\", " + data.length + " bytes}";
    }
}
