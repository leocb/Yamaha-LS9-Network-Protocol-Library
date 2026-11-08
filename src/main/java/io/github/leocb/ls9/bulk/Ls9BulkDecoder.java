package io.github.leocb.ls9.bulk;

import java.util.Map;

/**
 * Full LS9 bulk-image decoder.
 *
 * <p>Decoding is descriptor-driven. LS9 Editor reconstructs every scene,
 * library and setup image by walking {@code _gElementDesc},
 * {@code _gParamDesc}, {@code _gLibraryDesc} and {@code _gCollectionDesc}
 * through {@code CCollectionBase::calcDataSize} and
 * {@code CCollectionBase::getIndParamOffset}. {@link Ls9BulkLayout} carries
 * a generated copy of those tables and {@link Ls9BulkEntry} performs the
 * walk in Java.
 *
 * <p>This replaces the earlier StageMix-template decoder. The StageMix
 * handlers were useful evidence for the byte offsets, but they address some
 * elements with StageMix's own internal numbering; for example StageMix writes
 * the scene effect to element {@code 0x88}, while the published address is
 * {@code 0x100}, and the three output-port attenuator ranges are channels
 * 0..65 of one element ({@code 0x208}) rather than three separate elements.
 * The editor descriptor model matches Yamaha's official parameter list and is
 * therefore authoritative for the returned {@link
 * io.github.leocb.ls9.sysex.ParameterAddress} values.
 *
 * <p>Values are produced for every field the image carries. Short or synthetic
 * images are decoded as far as their bytes reach. The raw bytes remain
 * available from {@link BulkDump#data()}.
 */
public final class Ls9BulkDecoder {

    private Ls9BulkDecoder() {
    }

    /** Decodes one block using the 32-channel model. */
    public static Ls9BulkData decode(BulkDump dump) {
        return decode(dump, BulkModel.standard());
    }

    /** Decodes one block. */
    public static Ls9BulkData decode(BulkDump dump, BulkModel model) {
        if (dump == null) {
            throw new IllegalArgumentException("dump must not be null");
        }
        BulkModel m = model == null ? BulkModel.standard() : model;
        return Ls9BulkData.create(dump, m, BulkDumpParser.typeOf(dump.module()), Map.of());
    }
}
