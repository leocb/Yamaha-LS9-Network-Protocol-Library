package io.github.leocb.ls9.meter;

/** Meter address groups from Yamaha's "Meter ID Assign" sheet. */
public enum MeterGroup {
    INPUT(0x00, 0x00, 72),
    MIX(0x01, 0x00, 16),
    MATRIX(0x02, 0x00, 8),
    STEREO(0x03, 0x00, 3),
    MONITOR(0x04, 0x00, 1),
    CUE(0x05, 0x00, 1),
    OSCILLATOR(0x06, 0x00, 1),
    TALKBACK(0x07, 0x00, 1),
    HA_IN(0x08, 0x00, 72),
    MS_IN(0x09, 0x00, 72);

    private final int ul;
    private final int defaultLu;
    private final int channels;

    MeterGroup(int ul, int defaultLu, int channels) {
        this.ul = ul;
        this.defaultLu = defaultLu;
        this.channels = channels;
    }

    public int ul() {
        return ul;
    }

    public int defaultLu() {
        return defaultLu;
    }

    public int channels() {
        return channels;
    }

    // Common LU (measurement point) values.
    public static final int POINT_PRE_HPF = 0x00;
    public static final int POINT_PRE_ATT = 0x01;
    public static final int POINT_POST_EQ = 0x02;
    public static final int POINT_POST_DYN1 = 0x03;
    public static final int POINT_POST_DYN2 = 0x04;
    public static final int POINT_POST_ON = 0x05;
    public static final int POINT_PRE_FADER = 0x06;
    public static final int POINT_DYN1_GR = 0x07;
    public static final int POINT_DYN2_GR = 0x08;
    public static final int POINT_ALL = 0x7F;
}
