package io.github.leocb.ls9;

import java.io.IOException;
import io.github.leocb.ls9.internal.Text;
import io.github.leocb.ls9.sysex.Ls9Sysex;
import io.github.leocb.ls9.sysex.Ls9SysexMessage;
import io.github.leocb.ls9.sysex.ParameterAddress;

/**
 * Library management through Yamaha's function calls: store, recall, undo,
 * copy/paste/clear/cut/insert, attributes and existence ranges.
 *
 * <p>Module names are in {@link Ls9Sysex} and in
 * {@link io.github.leocb.ls9.bulk.BulkDumpCodec}.
 */
public final class Ls9Library {

    private final Ls9Console console;

    Ls9Library(Ls9Console console) {
        this.console = console;
    }

    public void store(String module, int number, int channel) throws IOException {
        console.sendSysex(Ls9Sysex.libraryStore(console.deviceNumber(), module, number, channel));
    }

    public void recall(String module, int number, int channel) throws IOException {
        console.sendSysex(Ls9Sysex.libraryRecall(console.deviceNumber(), module, number, channel));
    }

    public void unknownStore(String module, int number) throws IOException {
        console.sendSysex(Ls9Sysex.libraryUnknownStore(console.deviceNumber(), module, number));
    }

    public void unknownRecall(String module, int number) throws IOException {
        console.sendSysex(Ls9Sysex.libraryUnknownRecall(console.deviceNumber(), module, number));
    }

    public void storeUndo() throws IOException {
        console.sendSysex(Ls9Sysex.libraryStoreUndo(console.deviceNumber()));
    }

    public void recallUndo() throws IOException {
        console.sendSysex(Ls9Sysex.libraryRecallUndo(console.deviceNumber()));
    }

    /**
     * Recalls a library entry into a channel/rack, applies live edits and
     * stores it back. This is the console workflow and does not require
     * decoding the bulk-dump block.
     */
    public void edit(String module, int number, int channel, Ls9Edit edits) throws IOException {
        recall(module, number, channel);
        edits.apply(console);
        store(module, number, channel);
    }

    public void copy(String module, int source) throws IOException {
        console.sendSysex(Ls9Sysex.libraryCopy(console.deviceNumber(), module, source));
    }

    public void paste(String module, int destination) throws IOException {
        console.sendSysex(Ls9Sysex.libraryPaste(console.deviceNumber(), module, destination));
    }

    public void clear(String module, int number) throws IOException {
        console.sendSysex(Ls9Sysex.libraryClear(console.deviceNumber(), module, number));
    }

    public void cut(String module, int number) throws IOException {
        console.sendSysex(Ls9Sysex.libraryCut(console.deviceNumber(), module, number));
    }

    public void insert(String module, int destination) throws IOException {
        console.sendSysex(Ls9Sysex.libraryInsert(console.deviceNumber(), module, destination));
    }

    public void editUndo(String module) throws IOException {
        console.sendSysex(Ls9Sysex.libraryEditUndo(console.deviceNumber(), module));
    }

    /** Collection store ({@code ColUnStr}); used for setup/user-key snapshots. */
    public void collectionStore(String function, String module, int number, int channel)
            throws IOException {
        console.sendSysex(Ls9Sysex.collectionStore(console.deviceNumber(), function, module,
                number, channel));
    }

    /** Effect/module trigger ({@code ModFxTrg}). */
    public void moduleTrigger(String module, int effect, boolean press) throws IOException {
        console.sendSysex(Ls9Sysex.moduleTrigger(console.deviceNumber(), module, effect, press));
    }

    /** Channel pair operation ({@code PAIRONCP}, {@code PAIRONRS}, {@code PAIROFF_}). */
    public void channelPair(String pairModule, int sourceChannel, int destinationChannel)
            throws IOException {
        console.sendSysex(Ls9Sysex.channelPair(console.deviceNumber(), pairModule,
                sourceChannel, destinationChannel));
    }

    /**
     * Sets one 4-character title element. The scene module uses element
     * {@code 0x0001} with indexes 0..3.
     */
    public void setAttribute(String module, int number, int element, int index,
                             int channel, String text) throws IOException {
        ParameterAddress address = ParameterAddress.of(element, index, channel);
        console.sendSysex(Ls9Sysex.libraryAttribute(console.deviceNumber(), module, number,
                address, Ls9Sysex.packName4(text)));
    }

    /** Sets a scene title (max 16 characters) through four attribute calls. */
    public void setSceneTitle(int sceneNumber, String title) throws IOException {
        String text = title == null ? "" : title;
        if (text.length() > 16) {
            text = text.substring(0, 16);
        }
        for (int i = 0; i < 4; i++) {
            String part = i * 4 < text.length()
                    ? text.substring(i * 4, Math.min(i * 4 + 4, text.length())) : "";
            setAttribute(Ls9Sysex.MODULE_SCENE, sceneNumber, 0x0001, i, 0, part);
        }
    }

    /** Reads one raw attribute value. */
    public long getAttributeRaw(String module, int number, int element, int index, int channel)
            throws IOException {
        ParameterAddress address = ParameterAddress.of(element, index, channel);
        Ls9SysexMessage reply = console.requestFunction(
                Ls9Sysex.libraryAttributeRequest(console.deviceNumber(), module, number, address),
                Ls9Sysex.FN_LIBRARY_ATTRIBUTE, module, number);
        return reply.value() == null ? 0 : reply.value();
    }

    /** Reads one 4-character attribute element. */
    public String getAttribute(String module, int number, int element, int index, int channel)
            throws IOException {
        return Ls9Sysex.unpackName4(getAttributeRaw(module, number, element, index, channel));
    }

    /** Reads a scene title. */
    public String getSceneTitle(int sceneNumber) throws IOException {
        return getLibraryTitle(Ls9Sysex.MODULE_SCENE, sceneNumber);
    }

    /**
     * Reads a library title through four LIBRARY ATTRIBUTE reads.
     * Most LS9 libraries use element {@code 0x0001} for the title.
     */
    public String getLibraryTitle(String module, int number) throws IOException {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 4; i++) {
            sb.append(getAttribute(module, number, 0x0001, i, 0));
        }
        return Text.trimTrailing(sb.toString());
    }

    /** Writes a library title through four LIBRARY ATTRIBUTE writes. */
    public void setLibraryTitle(String module, int number, String title) throws IOException {
        String text = title == null ? "" : title;
        if (text.length() > 16) {
            text = text.substring(0, 16);
        }
        for (int i = 0; i < 4; i++) {
            String part = i * 4 < text.length()
                    ? text.substring(i * 4, Math.min(i * 4 + 4, text.length())) : "";
            setAttribute(module, number, 0x0001, i, 0, part);
        }
    }

    /** Asks for the first/last non-empty, non-read-only library entry. */
    public Ls9LibraryRange exists(String module, int number) throws IOException {
        Ls9SysexMessage reply = console.requestFunction(
                Ls9Sysex.libraryExistsRequest(console.deviceNumber(), module, number),
                Ls9Sysex.FN_LIBRARY_EXISTS, module, number);
        byte[] p = reply.payload();
        if (p.length < 7) {
            return new Ls9LibraryRange(false, number, 0, 0);
        }
        int requested = ((p[1] & 0x7F) << 7) | (p[2] & 0x7F);
        int top = ((p[3] & 0x7F) << 7) | (p[4] & 0x7F);
        int end = ((p[5] & 0x7F) << 7) | (p[6] & 0x7F);
        return new Ls9LibraryRange((p[0] & 0x7F) != 0, requested, top, end);
    }
}
