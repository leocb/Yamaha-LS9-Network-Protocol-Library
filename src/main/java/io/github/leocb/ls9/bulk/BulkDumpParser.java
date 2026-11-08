package io.github.leocb.ls9.bulk;

/**
 * Classifies LS9 bulk-dump blocks and exposes typed views.
 *
 * <p>The module table is the LS9 row of the StageMix 6.0.7
 * {@code GetBulkDataTypeFromSysex} table (0x100649668, desk types 2..4):
 * {@code SCENE___}, {@code MIXERSET}, {@code INCHNNL_}, {@code OUTCHNNL},
 * {@code GEQ_____}, {@code EFFECT__} and {@code OUT_PORT}. Several aliases
 * point at the same type because the console uses the same memory layout for
 * the three output-channel library classes.
 *
 * <p>LS9 Editor additionally speaks {@code INEQ____}, {@code OUTEQ___} and
 * {@code DYNA____} for its library windows. Those are accepted by
 * {@link BulkDumpCodec} but are not part of the StageMix bulk type table.
 */
public final class BulkDumpParser {

    /** Logical block type used by the console. */
    public enum Type {
        SCENE,
        MIXER_SETUP,
        INPUT_CHANNEL,
        OUTPUT_CHANNEL,
        INPUT_EQ,
        OUTPUT_EQ,
        DYNAMICS,
        GEQ,
        EFFECT,
        OUTPUT_PORT,
        MONITOR_SETUP,
        MIDI_SETUP,
        LIBRARY_NUMBER,
        PROGRAM_CHANGE_TABLE,
        CONTROL_CHANGE_TABLE,
        USER_LEVELS,
        USER_KEYS,
        PREFERENCES,
        CUSTOM_FADER,
        UNKNOWN
    }

    private BulkDumpParser() {
    }

    /** Classifies a module name exactly as the LS9 StageMix bulk table does. */
    public static Type typeOf(String module) {
        if (module == null) {
            return Type.UNKNOWN;
        }
        String m = module.length() >= 8 ? module.substring(0, 8) : pad(module);
        return switch (m) {
            case BulkDumpCodec.SCENE -> Type.SCENE;
            case BulkDumpCodec.MIXER_SETUP -> Type.MIXER_SETUP;
            case BulkDumpCodec.INPUT_CHANNEL -> Type.INPUT_CHANNEL;
            case BulkDumpCodec.OUTPUT_CHANNEL -> Type.OUTPUT_CHANNEL;
            case BulkDumpCodec.INPUT_EQ -> Type.INPUT_EQ;
            case BulkDumpCodec.OUTPUT_EQ -> Type.OUTPUT_EQ;
            case BulkDumpCodec.DYNAMICS -> Type.DYNAMICS;
            case BulkDumpCodec.GEQ -> Type.GEQ;
            case BulkDumpCodec.EFFECT -> Type.EFFECT;
            case BulkDumpCodec.OUTPUT_PORT -> Type.OUTPUT_PORT;
            case BulkDumpCodec.MONITOR_SETUP -> Type.MONITOR_SETUP;
            case BulkDumpCodec.MIDI_SETUP -> Type.MIDI_SETUP;
            case BulkDumpCodec.LIB_NUMBER -> Type.LIBRARY_NUMBER;
            case BulkDumpCodec.PROGRAM_CHANGE_TABLE -> Type.PROGRAM_CHANGE_TABLE;
            case BulkDumpCodec.CONTROL_CHANGE_TABLE -> Type.CONTROL_CHANGE_TABLE;
            case BulkDumpCodec.USER_LEVEL_CURRENT, BulkDumpCodec.USER_LEVEL_GUEST ->
                    Type.USER_LEVELS;
            case BulkDumpCodec.USER_KEYS_CURRENT, BulkDumpCodec.USER_KEYS_ADMIN,
                    BulkDumpCodec.USER_KEYS_GUEST -> Type.USER_KEYS;
            case BulkDumpCodec.PREFERENCE_CURRENT, BulkDumpCodec.PREFERENCE_ADMIN,
                    BulkDumpCodec.PREFERENCE_GUEST -> Type.PREFERENCES;
            case BulkDumpCodec.CUSTOM_FADER_CURRENT, BulkDumpCodec.CUSTOM_FADER_ADMIN,
                    BulkDumpCodec.CUSTOM_FADER_GUEST -> Type.CUSTOM_FADER;
            default -> Type.UNKNOWN;
        };
    }

    /** Parses a block as a scene header. */
    public static BulkScene scene(BulkDump dump) {
        return BulkScene.from(dump);
    }

    /** Fully decodes a block using the 32-channel model. */
    public static Ls9BulkData decode(BulkDump dump) {
        return Ls9BulkDecoder.decode(dump);
    }

    /** Fully decodes a block using explicit channel counts. */
    public static Ls9BulkData decode(BulkDump dump, BulkModel model) {
        return Ls9BulkDecoder.decode(dump, model);
    }

    /**
     * Validates that a block has the minimum size of an LS9 scene image
     * (through the timestamp at 0x5D).
     */
    public static boolean isSceneImage(BulkDump dump) {
        return dump != null && dump.length() >= BulkScene.DATE_OFFSET + BulkScene.DATE_BYTES;
    }

    private static String pad(String module) {
        StringBuilder sb = new StringBuilder(module);
        while (sb.length() < 8) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
