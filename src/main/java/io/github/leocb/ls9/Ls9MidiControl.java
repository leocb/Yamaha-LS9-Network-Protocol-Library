package io.github.leocb.ls9;

import java.io.IOException;

/**
 * MIDI channel messages and MMC supported by the LS9.
 *
 * <p>Studio Manager itself always uses SysEx, but the console also supports
 * Program Change (scene/library recall via its assignment table), Control
 * Change / NRPN and MMC for the USB recorder.
 */
public final class Ls9MidiControl {

    private final Ls9Console console;
    private final int channel;

    Ls9MidiControl(Ls9Console console, int channel) {
        this.console = console;
        this.channel = channel & 0x0F;
    }

    public int channel() {
        return channel;
    }

    public void programChange(int program) throws IOException {
        check7(program, "program");
        console.sendShortMidi(new byte[]{(byte) (0xC0 | channel), (byte) program});
    }

    public void controlChange(int controller, int value) throws IOException {
        check7(controller, "controller");
        check7(value, "value");
        console.sendShortMidi(new byte[]{(byte) (0xB0 | channel), (byte) controller, (byte) value});
    }

    /**
     * Sends an NRPN using the standard CC 99/98 (number) and 6/38 (data)
     * sequence. {@code value} is 0..16383.
     */
    public void nrpn(int nrpn, int value) throws IOException {
        if (nrpn < 0 || nrpn > 0x3FFF) {
            throw new IllegalArgumentException("NRPN number must be 0..16383");
        }
        if (value < 0 || value > 0x3FFF) {
            throw new IllegalArgumentException("NRPN value must be 0..16383");
        }
        controlChange(99, (nrpn >>> 7) & 0x7F);
        controlChange(98, nrpn & 0x7F);
        controlChange(6, (value >>> 7) & 0x7F);
        controlChange(38, value & 0x7F);
    }

    // MMC --------------------------------------------------------------

    public void mmcStop() throws IOException {
        mmc(0x01);
    }

    public void mmcPlay() throws IOException {
        mmc(0x02);
    }

    public void mmcDeferredPlay() throws IOException {
        mmc(0x03);
    }

    public void mmcRecordStrobe() throws IOException {
        mmc(0x06);
    }

    public void mmcPause() throws IOException {
        mmc(0x09);
    }

    /** Sends an MMC command (sub-id 0x06) to the given device (0x7F = all). */
    public void mmc(int command) throws IOException {
        mmc(0x7F, command);
    }

    public void mmc(int device, int command) throws IOException {
        check7(command, "MMC command");
        console.sendSysex(new byte[]{(byte) 0xF0, 0x7F, (byte) (device & 0x7F),
                0x06, (byte) command, (byte) 0xF7});
    }

    /** Sends System Reset (FF) and Active Sensing (FE). */
    public void systemReset() throws IOException {
        console.sendShortMidi(new byte[]{(byte) 0xFF});
    }

    public void activeSensing() throws IOException {
        console.sendShortMidi(new byte[]{(byte) 0xFE});
    }

    private static void check7(int value, String name) {
        if (value < 0 || value > 0x7F) {
            throw new IllegalArgumentException(name + " must be 0..127: " + value);
        }
    }
}
