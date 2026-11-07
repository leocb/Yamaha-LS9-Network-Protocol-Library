package io.github.leocb.ls9;

import java.io.IOException;
import io.github.leocb.ls9.internal.Text;
import io.github.leocb.ls9.model.Ls9Elements;
import io.github.leocb.ls9.model.Ls9FaderTable;
import io.github.leocb.ls9.sysex.Ls9Sysex;
import io.github.leocb.ls9.sysex.ParameterAddress;

/**
 * INPUT or ST IN channel.
 *
 * <p>Fader values use the official 0..1023 LS9 curve and are exposed in dB.
 * Pan is exposed both as raw 1..127 (64 = center) and as -1.0..+1.0.
 */
public final class Ls9InputChannel {

    private final Ls9Console console;
    private final int channelIndex;
    private final Ls9FaderTable faders;

    Ls9InputChannel(Ls9Console console, int channelIndex) {
        this.console = console;
        this.channelIndex = channelIndex;
        this.faders = console.faderTable();
    }

    public Ls9Console console() {
        return console;
    }

    /** 0-based LS9 channel index (INPUT 1 = 0, ST IN 1L = 64). */
    public int channelIndex() {
        return channelIndex;
    }

    // Fader ------------------------------------------------------------

    public int faderRaw() throws IOException {
        return (int) console.get(address(Ls9Elements.INPUT_FADER, Ls9Elements.IDX_FADER));
    }

    public void setFaderRaw(int raw) throws IOException {
        if (raw < 0 || raw > Ls9FaderTable.MAX_VALUE) {
            throw new IllegalArgumentException("fader raw must be 0..1023: " + raw);
        }
        console.set(address(Ls9Elements.INPUT_FADER, Ls9Elements.IDX_FADER), raw);
    }

    public double faderDb() throws IOException {
        return faders.toDb(faderRaw());
    }

    public void setFaderDb(double db) throws IOException {
        setFaderRaw(faders.toRaw(db));
    }

    // On ---------------------------------------------------------------

    public boolean on() throws IOException {
        return console.get(address(Ls9Elements.INPUT_ON, Ls9Elements.IDX_ON)) != 0;
    }

    public void setOn(boolean on) throws IOException {
        console.set(address(Ls9Elements.INPUT_ON, Ls9Elements.IDX_ON), on ? 1 : 0);
    }

    // Pan --------------------------------------------------------------

    /** Raw pan, 1..127; 64 is center. */
    public int panRaw() throws IOException {
        return (int) console.get(address(Ls9Elements.INPUT_PAN, Ls9Elements.IDX_PAN));
    }

    public void setPanRaw(int raw) throws IOException {
        if (raw < 1 || raw > 127) {
            throw new IllegalArgumentException("pan raw must be 1..127: " + raw);
        }
        console.set(address(Ls9Elements.INPUT_PAN, Ls9Elements.IDX_PAN), raw);
    }

    /** Pan as -1.0 (left) .. +1.0 (right). */
    public double pan() throws IOException {
        return (panRaw() - 64) / 63.0;
    }

    public void setPan(double pan) throws IOException {
        double p = Math.max(-1.0, Math.min(1.0, pan));
        setPanRaw((int) Math.round(p * 63.0) + 64);
    }

    // MIX sends --------------------------------------------------------

    public int mixSendRaw(int mix) throws IOException {
        return (int) console.get(address(Ls9Elements.INPUT_TO_MIX,
                Ls9Elements.inputToMix(mix, Ls9Elements.MixSendField.LEVEL)));
    }

    public void setMixSendRaw(int mix, int raw) throws IOException {
        if (raw < 0 || raw > Ls9FaderTable.MAX_VALUE) {
            throw new IllegalArgumentException("send raw must be 0..1023: " + raw);
        }
        console.set(address(Ls9Elements.INPUT_TO_MIX,
                Ls9Elements.inputToMix(mix, Ls9Elements.MixSendField.LEVEL)), raw);
    }

    public double mixSendDb(int mix) throws IOException {
        return faders.toDb(mixSendRaw(mix));
    }

    public void setMixSendDb(int mix, double db) throws IOException {
        setMixSendRaw(mix, faders.toRaw(db));
    }

    public boolean mixSendOn(int mix) throws IOException {
        return console.get(address(Ls9Elements.INPUT_TO_MIX,
                Ls9Elements.inputToMix(mix, Ls9Elements.MixSendField.ON))) != 0;
    }

    public void setMixSendOn(int mix, boolean on) throws IOException {
        console.set(address(Ls9Elements.INPUT_TO_MIX,
                Ls9Elements.inputToMix(mix, Ls9Elements.MixSendField.ON)), on ? 1 : 0);
    }

    /** {@code true} = pre-fader, {@code false} = post-on. */
    public boolean mixSendPre(int mix) throws IOException {
        return console.get(address(Ls9Elements.INPUT_TO_MIX,
                Ls9Elements.inputToMix(mix, Ls9Elements.MixSendField.PRE_POST))) != 0;
    }

    public void setMixSendPre(int mix, boolean pre) throws IOException {
        console.set(address(Ls9Elements.INPUT_TO_MIX,
                Ls9Elements.inputToMix(mix, Ls9Elements.MixSendField.PRE_POST)), pre ? 1 : 0);
    }

    /** Pan of the send to a MIX bus, 1..127 (64 = center). */
    public int mixSendPanRaw(int mix) throws IOException {
        return (int) console.get(address(Ls9Elements.INPUT_TO_MIX_PAN,
                Ls9Elements.inputToMixPan(mix)));
    }

    public void setMixSendPanRaw(int mix, int raw) throws IOException {
        if (raw < 1 || raw > 127) {
            throw new IllegalArgumentException("pan raw must be 1..127: " + raw);
        }
        console.set(address(Ls9Elements.INPUT_TO_MIX_PAN, Ls9Elements.inputToMixPan(mix)), raw);
    }

    public void setMixSendPan(int mix, double pan) throws IOException {
        double p = Math.max(-1.0, Math.min(1.0, pan));
        setMixSendPanRaw(mix, (int) Math.round(p * 63.0) + 64);
    }

    // Stereo / mono assignment -----------------------------------------

    public boolean toStereo() throws IOException {
        return console.get(address(Ls9Elements.INPUT_TO_STEREO, 0)) != 0;
    }

    public void setToStereo(boolean on) throws IOException {
        console.set(address(Ls9Elements.INPUT_TO_STEREO, 0), on ? 1 : 0);
    }

    public boolean toMono() throws IOException {
        return console.get(address(Ls9Elements.INPUT_TO_MONO, 0)) != 0;
    }

    public void setToMono(boolean on) throws IOException {
        console.set(address(Ls9Elements.INPUT_TO_MONO, 0), on ? 1 : 0);
    }

    // Head amp and attenuator ------------------------------------------

    public boolean phantom() throws IOException {
        return console.get(address(Ls9Elements.INPUT_HA, Ls9Elements.IDX_HA_PHANTOM)) != 0;
    }

    public void setPhantom(boolean on) throws IOException {
        console.set(address(Ls9Elements.INPUT_HA, Ls9Elements.IDX_HA_PHANTOM), on ? 1 : 0);
    }

    /** Raw HA gain, 0..72 (10 = 0 dB, 72 = -62 dB). */
    public int haGainRaw() throws IOException {
        return (int) console.get(address(Ls9Elements.INPUT_HA, Ls9Elements.IDX_HA_GAIN));
    }

    public void setHaGainRaw(int raw) throws IOException {
        if (raw < 0 || raw > 72) {
            throw new IllegalArgumentException("HA gain raw must be 0..72: " + raw);
        }
        console.set(address(Ls9Elements.INPUT_HA, Ls9Elements.IDX_HA_GAIN), raw);
    }

    /** HA gain in dB (-62..+10). */
    public double haGainDb() throws IOException {
        return 10 - haGainRaw();
    }

    public void setHaGainDb(double db) throws IOException {
        int raw = (int) Math.round(10 - db);
        if (raw < 0 || raw > 72) {
            throw new IllegalArgumentException("HA gain must be -62..+10 dB: " + db);
        }
        setHaGainRaw(raw);
    }

    /** Raw attenuator value, 0..1200 (the library assumes 0 = -96 dB). */
    public int attenuatorRaw() throws IOException {
        return (int) console.get(address(Ls9Elements.INPUT_ATTENUATOR, Ls9Elements.IDX_ATTENUATOR));
    }

    public void setAttenuatorRaw(int raw) throws IOException {
        if (raw < 0 || raw > 1200) {
            throw new IllegalArgumentException("attenuator raw must be 0..1200: " + raw);
        }
        console.set(address(Ls9Elements.INPUT_ATTENUATOR, Ls9Elements.IDX_ATTENUATOR), raw);
    }

    /**
     * Attenuator in dB (-96..+24). The LS9 parameter is transmitted as
     * {@code dB*10 + 960} (0..1200).
     */
    public double attenuatorDb() throws IOException {
        return (attenuatorRaw() - 960) / 10.0;
    }

    public void setAttenuatorDb(double db) throws IOException {
        int raw = (int) Math.round(db * 10.0) + 960;
        if (raw < 0 || raw > 1200) {
            throw new IllegalArgumentException("attenuator must be -96..+24 dB: " + db);
        }
        setAttenuatorRaw(raw);
    }

    // Name -------------------------------------------------------------

    public String name() throws IOException {
        long first = console.get(address(Ls9Elements.INPUT_NAME, Ls9Elements.IDX_NAME_1));
        long second = console.get(address(Ls9Elements.INPUT_NAME, Ls9Elements.IDX_NAME_2));
        return Text.trimTrailing(Ls9Sysex.unpackName4(first) + Ls9Sysex.unpackName4(second));
    }

    public void setName(String name) throws IOException {
        String text = name == null ? "" : name;
        if (text.length() > 8) {
            text = text.substring(0, 8);
        }
        long first = Ls9Sysex.packName4(text.length() > 4 ? text.substring(0, 4) : text);
        long second = Ls9Sysex.packName4(text.length() > 4 ? text.substring(4) : "");
        console.set(address(Ls9Elements.INPUT_NAME, Ls9Elements.IDX_NAME_1), first);
        console.set(address(Ls9Elements.INPUT_NAME, Ls9Elements.IDX_NAME_2), second);
    }

    private ParameterAddress address(int element, int index) {
        return ParameterAddress.of(element, index, channelIndex);
    }
}
