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

    /**
     * Compares two images of the same block and emits only changed values.
     * This is used by the safety poll: a missed edit usually changes one or
     * two fields, so there is no reason to walk the 20k-entry map or allocate
     * an address for every unchanged parameter.
     *
     * @return false when the block has no known layout or the dumps do not
     *         describe the same block, in which case the caller should fall
     *         back to {@link #decode(BulkDump)}
     */
    public static boolean diff(BulkDump before, BulkDump after,
                               Ls9BulkEntry.ParameterConsumer consumer) {
        if (before == null || after == null || consumer == null
                || !before.module().equals(after.module())
                || before.number() != after.number()) {
            return false;
        }
        Ls9BulkLayout.Selection selection = Ls9BulkLayout.get()
                .select(after.module(), after.dataUnsafe())
                .orElse(null);
        if (selection == null) {
            return false;
        }
        byte[] a = before.dataUnsafe();
        byte[] b = after.dataUnsafe();
        for (Ls9BulkLayout.Field field : selection.layout().parameters()) {
            Long va = Ls9BulkEntry.read(a, field);
            Long vb = Ls9BulkEntry.read(b, field);
            if (va == null ? vb != null : !va.equals(vb)) {
                consumer.accept(field.element(), field.index(), field.channel(),
                        vb == null ? 0 : vb);
            }
        }
        // Title words are not part of Layout.parameters(), but they are
        // published at element 0x0193 and can change between scenes.
        for (int i = 0; i < BulkScene.TITLE_WORDS; i++) {
            int offset = BulkScene.TITLE_OFFSET + i * 4;
            if (offset + 4 > a.length || offset + 4 > b.length) {
                break;
            }
            long va = be32(a, offset);
            long vb = be32(b, offset);
            if (va != vb) {
                consumer.accept(Ls9BulkEntry.TITLE_ELEMENT, i, 0, vb);
            }
        }
        if (selection.areaKind() == Ls9BulkLayout.AreaKind.LIBRARY && selection.area() == 0) {
            int offset = BulkScene.SCENE_BYTE_OFFSET;
            if (offset < a.length && offset < b.length) {
                long va = a[offset] & 0xFFL;
                long vb = b[offset] & 0xFFL;
                if (va != vb) {
                    consumer.accept(0x0195, 0, 0, vb);
                }
            }
        }
        return true;
    }

    private static long be32(byte[] data, int offset) {
        return ((data[offset] & 0xFFL) << 24)
                | ((data[offset + 1] & 0xFFL) << 16)
                | ((data[offset + 2] & 0xFFL) << 8)
                | (data[offset + 3] & 0xFFL);
    }
}
