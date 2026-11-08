package io.github.leocb.ls9.bulk;

/**
 * Channel counts and desk variant used when decoding a packed LS9 bulk image.
 *
 * <p>StageMix's bulk handler reads the valid channel ranges from the model, not
 * from the block. LS9-16 and LS9-32 share the same image layout; only the
 * number of mono input channels differs.
 */
public record BulkModel(int deskType, int monoInputs, int stereoInputs,
                        int mixChannels, int matrixChannels, int stereoChannels,
                        int dcaChannels, int geqRacks) {

    public static final BulkModel LS9_16 = new BulkModel(2, 16, 4, 16, 8, 1, 8, 8);
    public static final BulkModel LS9_32 = new BulkModel(2, 32, 4, 16, 8, 1, 8, 8);

    public BulkModel {
        if (deskType < 1 || deskType > 4) {
            throw new IllegalArgumentException("desk type 1..4: " + deskType);
        }
        if (monoInputs < 0 || stereoInputs < 0 || mixChannels < 0 || matrixChannels < 0
                || stereoChannels < 0 || dcaChannels < 0 || geqRacks < 0) {
            throw new IllegalArgumentException("negative channel count");
        }
    }

    /** Number of mono plus stereo input channel strips. */
    public int inputChannels() {
        return monoInputs + stereoInputs * 2;
    }

    /** First stereo input channel number. */
    public int stereoStart() {
        return monoInputs;
    }

    /** Defaults to the 32-channel model. */
    public static BulkModel standard() {
        return LS9_32;
    }
}
