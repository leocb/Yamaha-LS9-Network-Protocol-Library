package io.github.leocb.ls9;

import java.io.IOException;
import io.github.leocb.ls9.internal.Text;
import io.github.leocb.ls9.model.Ls9Elements;
import io.github.leocb.ls9.sysex.Ls9Sysex;
import io.github.leocb.ls9.sysex.ParameterAddress;

/**
 * Scene memory operations, implemented with Yamaha's LIBRARY STORE/RECALL
 * function calls (section 4.2 of the LS9 MIDI implementation).
 */
public final class Ls9SceneControl {

    private final Ls9Console console;

    Ls9SceneControl(Ls9Console console) {
        this.console = console;
    }

    /**
     * Recalls scene 0..300. Scene library calls use channel 512, as shown by
     * Yamaha's own AMX example ("LibRcl__SCENE___" followed by 04 00).
     */
    public void recall(int sceneNumber) throws IOException {
        recall(sceneNumber, 512);
    }

    /** Recalls a scene with an explicit channel field. */
    public void recall(int sceneNumber, int channel) throws IOException {
        checkScene(sceneNumber, 0);
        console.sendSysex(Ls9Sysex.libraryRecall(console.deviceNumber(),
                Ls9Sysex.MODULE_SCENE, sceneNumber, channel));
    }

    /** Stores the current mix settings into scene 1..300. */
    public void store(int sceneNumber) throws IOException {
        store(sceneNumber, 512);
    }

    /** Stores a scene with an explicit channel field. */
    public void store(int sceneNumber, int channel) throws IOException {
        checkScene(sceneNumber, 1);
        console.sendSysex(Ls9Sysex.libraryStore(console.deviceNumber(),
                Ls9Sysex.MODULE_SCENE, sceneNumber, channel));
    }

    /**
     * Stores the current mix settings and sets a scene title (max 16 chars).
     * The four LS9 title elements each hold four 7-bit characters.
     */
    public void store(int sceneNumber, String title) throws IOException {
        store(sceneNumber);
        setTitle(sceneNumber, title);
    }

    /**
     * Recalls a scene, applies live edits, then stores it back. This is the
     * console workflow and does not require decoding the bulk-dump block.
     */
    public void edit(int sceneNumber, Ls9Edit edits) throws IOException {
        recall(sceneNumber);
        edits.apply(console);
        store(sceneNumber);
    }

    /** Undo the most recent scene store. */
    public void storeUndo() throws IOException {
        console.sendSysex(Ls9Sysex.libraryStoreUndo(console.deviceNumber()));
    }

    /** Undo the most recent scene recall. */
    public void recallUndo() throws IOException {
        console.sendSysex(Ls9Sysex.libraryRecallUndo(console.deviceNumber()));
    }

    /**
     * Changes the stored title of a scene through LIBRARY ATTRIBUTE.
     * The title is limited to 16 characters.
     */
    public void setTitle(int sceneNumber, String title) throws IOException {
        checkScene(sceneNumber, 1);
        String text = title == null ? "" : title;
        if (text.length() > 16) {
            text = text.substring(0, 16);
        }
        for (int i = 0; i < 4; i++) {
            String part = i * 4 < text.length() ? text.substring(i * 4, Math.min(i * 4 + 4, text.length())) : "";
            ParameterAddress address = ParameterAddress.of(Ls9Elements.SCENE_COMMENT, i, 0);
            console.sendSysex(Ls9Sysex.libraryAttribute(console.deviceNumber(),
                    Ls9Sysex.MODULE_SCENE, sceneNumber, address, Ls9Sysex.packName4(part)));
        }
    }

    /**
     * Reads the title of the <em>current</em> scene. For an arbitrary stored
     * scene, request the scene library with a BULK DUMP REQUEST instead.
     */
    public String currentTitle() throws IOException {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 4; i++) {
            long value = console.get(ParameterAddress.of(Ls9Elements.SCENE_COMMENT, i, 0));
            sb.append(Ls9Sysex.unpackName4(value));
        }
        return Text.trimTrailing(sb.toString());
    }

    private static void checkScene(int scene, int min) {
        if (scene < min || scene > 300) {
            throw new IllegalArgumentException("scene number must be " + min + "..300: " + scene);
        }
    }
}
