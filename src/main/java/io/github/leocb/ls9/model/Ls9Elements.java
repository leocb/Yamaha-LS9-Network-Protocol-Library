package io.github.leocb.ls9.model;

import io.github.leocb.ls9.sysex.ParameterAddress;

/**
 * Element and index numbers for the most commonly used LS9 parameters.
 * Values are taken from Yamaha's {@code ParamChangeList_LS9.xls} (data
 * category {@code 0x01}, current scene/setup data).
 */
public final class Ls9Elements {

    private Ls9Elements() {
    }

    // Scene / global
    public static final int SCENE_MUTE_MASTER = 0x0021;
    public static final int SCENE_COMMENT = 0x0001;

    // Setup
    public static final int SETUP_NETWORK = 0x0228;
    public static final int IDX_MAC_0 = 0x000D;

    // Input channels (channel 0..63 = INPUT 1..64; 64..71 = ST IN 1L..4R)
    public static final int INPUT_HA = 0x002A;
    public static final int INPUT_ON = 0x0031;
    public static final int INPUT_PAN = 0x0032;
    public static final int INPUT_FADER = 0x0033;
    public static final int INPUT_ATTENUATOR = 0x0034;
    public static final int INPUT_MUTE = 0x003F;
    public static final int INPUT_TO_STEREO = 0x0041;
    public static final int INPUT_TO_MONO = 0x0042;
    public static final int INPUT_TO_MIX = 0x0043;
    public static final int INPUT_TO_MIX_PAN = 0x0044;
    public static final int INPUT_NAME = 0x0114;

    // Mix channels (channel 0..15 = MIX 1..16)
    public static final int MIX_ON = 0x004C;
    public static final int MIX_BALANCE = 0x004D;
    public static final int MIX_FADER = 0x004E;
    public static final int MIX_ATTENUATOR = 0x004F;
    public static final int MIX_TO_MATRIX = 0x0055;
    public static final int MIX_NAME = 0x011B;

    // Matrix channels (channel 0..7 = MATRIX 1..8)
    public static final int MATRIX_ON = 0x005D;
    public static final int MATRIX_BALANCE = 0x005E;
    public static final int MATRIX_FADER = 0x005F;
    public static final int MATRIX_ATTENUATOR = 0x0060;
    public static final int MATRIX_NAME = 0x011C;

    // Stereo / mono (channel 0=L, 1=R, 2=C)
    public static final int STEREO_ON = 0x006B;
    public static final int STEREO_BALANCE = 0x006C;
    public static final int STEREO_FADER = 0x006D;
    public static final int STEREO_ATTENUATOR = 0x006E;
    public static final int STEREO_NAME = 0x011D;

    // Indexes
    public static final int IDX_FADER = 0x0000;
    public static final int IDX_ON = 0x0000;
    public static final int IDX_PAN = 0x0001;
    public static final int IDX_BALANCE = 0x0000;
    public static final int IDX_ATTENUATOR = 0x0001;
    public static final int IDX_MUTE_MASTER = 0x0000;
    public static final int IDX_HA_PHANTOM = 0x0000;
    public static final int IDX_HA_GAIN = 0x0001;
    public static final int IDX_NAME_1 = 0x0000;
    public static final int IDX_NAME_2 = 0x0001;

    /** {@code kInputToMix} index for a 1-based MIX bus and field. */
    public static int inputToMix(int mix1Based, MixSendField field) {
        if (mix1Based < 1 || mix1Based > 16) {
            throw new IllegalArgumentException("MIX bus must be 1..16: " + mix1Based);
        }
        return 3 + (mix1Based - 1) * 3 + field.ordinal();
    }

    /** {@code kInputToMixPan} index; pan is stored in pairs of MIX buses. */
    public static int inputToMixPan(int mix1Based) {
        if (mix1Based < 1 || mix1Based > 16) {
            throw new IllegalArgumentException("MIX bus must be 1..16: " + mix1Based);
        }
        return (mix1Based - 1) / 2;
    }

    public enum MixSendField {
        ON,
        PRE_POST,
        LEVEL
    }

    /** Builds an address for a current-data parameter. */
    public static ParameterAddress address(int element, int index, int channel) {
        return ParameterAddress.of(element, index, channel);
    }
}
