package io.github.leocb.ls9;

import java.io.IOException;
import io.github.leocb.ls9.internal.Text;
import io.github.leocb.ls9.model.Ls9Elements;
import io.github.leocb.ls9.model.Ls9FaderTable;
import io.github.leocb.ls9.sysex.Ls9Sysex;
import io.github.leocb.ls9.sysex.ParameterAddress;

/** MIX, MATRIX or STEREO/MONO output channel. */
public final class Ls9OutputChannel {

    public enum Kind {
        MIX,
        MATRIX,
        STEREO
    }

    private final Ls9Console console;
    private final Kind kind;
    private final int channelIndex;
    private final Ls9FaderTable faders;

    private Ls9OutputChannel(Ls9Console console, Kind kind, int channelIndex) {
        this.console = console;
        this.kind = kind;
        this.channelIndex = channelIndex;
        this.faders = console.faderTable();
    }

    static Ls9OutputChannel mix(Ls9Console console, int index) {
        return new Ls9OutputChannel(console, Kind.MIX, index);
    }

    static Ls9OutputChannel matrix(Ls9Console console, int index) {
        return new Ls9OutputChannel(console, Kind.MATRIX, index);
    }

    static Ls9OutputChannel stereo(Ls9Console console, int side) {
        return new Ls9OutputChannel(console, Kind.STEREO, side);
    }

    public Ls9Console console() {
        return console;
    }

    public Kind kind() {
        return kind;
    }

    public int channelIndex() {
        return channelIndex;
    }

    public int faderRaw() throws IOException {
        return (int) console.get(ParameterAddress.of(faderElement(), Ls9Elements.IDX_FADER, channelIndex));
    }

    public void setFaderRaw(int raw) throws IOException {
        if (raw < 0 || raw > Ls9FaderTable.MAX_VALUE) {
            throw new IllegalArgumentException("fader raw must be 0..1023: " + raw);
        }
        console.set(ParameterAddress.of(faderElement(), Ls9Elements.IDX_FADER, channelIndex), raw);
    }

    public double faderDb() throws IOException {
        return faders.toDb(faderRaw());
    }

    public void setFaderDb(double db) throws IOException {
        setFaderRaw(faders.toRaw(db));
    }

    public boolean on() throws IOException {
        return console.get(ParameterAddress.of(onElement(), Ls9Elements.IDX_ON, channelIndex)) != 0;
    }

    public void setOn(boolean on) throws IOException {
        console.set(ParameterAddress.of(onElement(), Ls9Elements.IDX_ON, channelIndex), on ? 1 : 0);
    }

    /** Raw balance, 1..127; 64 is center. */
    public int balanceRaw() throws IOException {
        return (int) console.get(ParameterAddress.of(balanceElement(), Ls9Elements.IDX_BALANCE, channelIndex));
    }

    public void setBalanceRaw(int raw) throws IOException {
        if (raw < 1 || raw > 127) {
            throw new IllegalArgumentException("balance raw must be 1..127: " + raw);
        }
        console.set(ParameterAddress.of(balanceElement(), Ls9Elements.IDX_BALANCE, channelIndex), raw);
    }

    /** Balance as -1.0 (left) .. +1.0 (right). */
    public double balance() throws IOException {
        return (balanceRaw() - 64) / 63.0;
    }

    public void setBalance(double balance) throws IOException {
        double b = Math.max(-1.0, Math.min(1.0, balance));
        setBalanceRaw((int) Math.round(b * 63.0) + 64);
    }

    /** Raw attenuator value, 0..1200 (the library assumes 0 = -96 dB). */
    public int attenuatorRaw() throws IOException {
        return (int) console.get(ParameterAddress.of(attenuatorElement(),
                Ls9Elements.IDX_ATTENUATOR, channelIndex));
    }

    public void setAttenuatorRaw(int raw) throws IOException {
        if (raw < 0 || raw > 1200) {
            throw new IllegalArgumentException("attenuator raw must be 0..1200: " + raw);
        }
        console.set(ParameterAddress.of(attenuatorElement(), Ls9Elements.IDX_ATTENUATOR, channelIndex), raw);
    }

    /** Attenuator in dB (-96..+24), transmitted as {@code dB*10 + 960}. */
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

    public String name() throws IOException {
        long first = console.get(ParameterAddress.of(nameElement(), Ls9Elements.IDX_NAME_1, channelIndex));
        long second = console.get(ParameterAddress.of(nameElement(), Ls9Elements.IDX_NAME_2, channelIndex));
        return Text.trimTrailing(Ls9Sysex.unpackName4(first) + Ls9Sysex.unpackName4(second));
    }

    public void setName(String name) throws IOException {
        String text = name == null ? "" : name;
        if (text.length() > 8) {
            text = text.substring(0, 8);
        }
        long first = Ls9Sysex.packName4(text.length() > 4 ? text.substring(0, 4) : text);
        long second = Ls9Sysex.packName4(text.length() > 4 ? text.substring(4) : "");
        console.set(ParameterAddress.of(nameElement(), Ls9Elements.IDX_NAME_1, channelIndex), first);
        console.set(ParameterAddress.of(nameElement(), Ls9Elements.IDX_NAME_2, channelIndex), second);
    }

    private int faderElement() {
        return switch (kind) {
            case MIX -> Ls9Elements.MIX_FADER;
            case MATRIX -> Ls9Elements.MATRIX_FADER;
            case STEREO -> Ls9Elements.STEREO_FADER;
        };
    }

    private int onElement() {
        return switch (kind) {
            case MIX -> Ls9Elements.MIX_ON;
            case MATRIX -> Ls9Elements.MATRIX_ON;
            case STEREO -> Ls9Elements.STEREO_ON;
        };
    }

    private int balanceElement() {
        return switch (kind) {
            case MIX -> Ls9Elements.MIX_BALANCE;
            case MATRIX -> Ls9Elements.MATRIX_BALANCE;
            case STEREO -> Ls9Elements.STEREO_BALANCE;
        };
    }

    private int attenuatorElement() {
        return switch (kind) {
            case MIX -> Ls9Elements.MIX_ATTENUATOR;
            case STEREO -> Ls9Elements.STEREO_ATTENUATOR;
            case MATRIX -> Ls9Elements.MATRIX_ATTENUATOR;
        };
    }

    private int nameElement() {
        return switch (kind) {
            case MIX -> Ls9Elements.MIX_NAME;
            case MATRIX -> Ls9Elements.MATRIX_NAME;
            case STEREO -> Ls9Elements.STEREO_NAME;
        };
    }
}
