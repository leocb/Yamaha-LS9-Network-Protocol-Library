package io.github.leocb.ls9.transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.SysexMessage;
import javax.sound.midi.Transmitter;

/**
 * Transport that uses the operating system MIDI stack (CoreMIDI on macOS).
 *
 * <p>This is the zero-reverse-engineering fallback: if Yamaha's Network-MIDI
 * Driver is installed, it exposes ports named like {@code UNIT1 Port1}. Pass
 * that name to this transport and the library works without opening a TCP
 * socket itself.
 */
public final class JavaMidiTransport implements MidiTransport {

    private final String inputPortName;
    private final String outputPortName;

    private MidiDevice inputDevice;
    private MidiDevice outputDevice;
    private Transmitter transmitter;
    private Receiver receiver;
    private volatile Consumer<byte[]> callback;
    private volatile Runnable onClose;

    public JavaMidiTransport(String portName) {
        this(portName, portName);
    }

    public JavaMidiTransport(String inputPortName, String outputPortName) {
        this.inputPortName = Objects.requireNonNull(inputPortName, "inputPortName");
        this.outputPortName = Objects.requireNonNull(outputPortName, "outputPortName");
    }

    /** Lists all MIDI device names visible to the OS. */
    public static List<String> listPorts() {
        List<String> names = new ArrayList<>();
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            names.add(displayName(info));
        }
        return names;
    }

    private static String displayName(MidiDevice.Info info) {
        String name = info.getName();
        String description = info.getDescription();
        if (description == null || description.trim().isEmpty() || description.equals(name)) {
            return name;
        }
        return name + " (" + description + ")";
    }

    @Override
    public void setReceiver(Consumer<byte[]> receiver) {
        this.callback = receiver;
    }

    @Override
    public void setOnClose(Runnable callback) {
        this.onClose = callback;
    }

    @Override
    public void open() throws IOException {
        try {
            inputDevice = findDevice(inputPortName, true);
            outputDevice = findDevice(outputPortName, false);
            inputDevice.open();
            outputDevice.open();
            transmitter = inputDevice.getTransmitter();
            transmitter.setReceiver(new Receiver() {
                @Override
                public void send(MidiMessage message, long timeStamp) {
                    Consumer<byte[]> cb = callback;
                    if (cb == null) {
                        return;
                    }
                    if (message instanceof SysexMessage sysex) {
                        byte[] raw = sysex.getMessage();
                        // Some Java MIDI implementations omit the leading F0 in getMessage().
                        if (raw.length == 0 || (raw[0] & 0xFF) != 0xF0) {
                            byte[] fixed = new byte[raw.length + 1];
                            fixed[0] = (byte) 0xF0;
                            System.arraycopy(raw, 0, fixed, 1, raw.length);
                            raw = fixed;
                        }
                        cb.accept(raw);
                    } else if (message instanceof ShortMessage shortMessage) {
                        cb.accept(shortMessage.getMessage());
                    }
                }

                @Override
                public void close() {
                    // no-op
                }
            });
            receiver = outputDevice.getReceiver();
        } catch (MidiUnavailableException e) {
            close();
            throw new IOException("cannot open MIDI ports '" + inputPortName + "' / '"
                    + outputPortName + "': " + e.getMessage(), e);
        }
    }

    private static MidiDevice findDevice(String wanted, boolean input) throws MidiUnavailableException {
        String needle = wanted.toLowerCase(Locale.ROOT);
        MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
        for (MidiDevice.Info info : infos) {
            String display = displayName(info).toLowerCase(Locale.ROOT);
            if (!display.contains(needle)) {
                continue;
            }
            MidiDevice device = MidiSystem.getMidiDevice(info);
            int max = input ? device.getMaxTransmitters() : device.getMaxReceivers();
            if (max != 0) {
                return device;
            }
        }
        throw new MidiUnavailableException("no " + (input ? "input" : "output")
                + " MIDI port matching '" + wanted + "'. Available: " + listPorts());
    }

    @Override
    public void sendSysex(byte[] message) throws IOException {
        Receiver r = receiver;
        if (r == null) {
            throw new IOException("transport is not open");
        }
        try {
            r.send(new SysexMessage(message, message.length), -1);
        } catch (InvalidMidiDataException e) {
            throw new IOException("invalid SysEx message", e);
        }
    }

    @Override
    public void sendShortMidi(byte[] message) throws IOException {
        Receiver r = receiver;
        if (r == null) {
            throw new IOException("transport is not open");
        }
        try {
            ShortMessage m = new ShortMessage();
            switch (message.length) {
                case 1 -> m.setMessage(message[0] & 0xFF);
                case 2 -> m.setMessage(message[0] & 0xFF, message[1] & 0xFF, 0);
                case 3 -> m.setMessage(message[0] & 0xFF, message[1] & 0xFF, message[2] & 0xFF);
                default -> throw new IOException("short MIDI message must have 1..3 bytes");
            }
            r.send(m, -1);
        } catch (InvalidMidiDataException e) {
            throw new IOException("invalid MIDI message", e);
        }
    }

    @Override
    public void close() {
        if (transmitter != null) {
            transmitter.close();
            transmitter = null;
        }
        if (receiver != null) {
            receiver.close();
            receiver = null;
        }
        if (inputDevice != null) {
            inputDevice.close();
            inputDevice = null;
        }
        if (outputDevice != null) {
            outputDevice.close();
            outputDevice = null;
        }
        Runnable cb = onClose;
        if (cb != null) {
            try {
                cb.run();
            } catch (RuntimeException ignored) {
                // no-op
            }
        }
    }
}
