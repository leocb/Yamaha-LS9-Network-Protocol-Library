package io.github.leocb.ls9.transport;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Byte-oriented MIDI transport used by the LS9 protocol layer.
 *
 * <p>Implementations deliver complete MIDI messages: a System Exclusive message
 * always starts with {@code F0} and ends with {@code F7}; short channel messages
 * are delivered as their complete bytes.
 */
public interface MidiTransport extends AutoCloseable {

    /** Opens the underlying connection and starts receiving messages. */
    void open() throws IOException;

    /**
     * Sets the callback that receives every complete MIDI message from the
     * console. May be called before {@link #open()}.
     */
    void setReceiver(Consumer<byte[]> receiver);

    /**
     * Sets a callback invoked once when the transport stops receiving (console
     * closed the socket, cable removed, or {@link #close()} was called).
     */
    default void setOnClose(Runnable callback) {
    }

    /** Sends one complete System Exclusive message ({@code F0 ... F7}). */
    void sendSysex(byte[] message) throws IOException;

    /**
     * Sends a short MIDI message (1..3 bytes, e.g. a Control Change). The Yamaha
     * EtherMIDI transport carries these in a smaller packet; the CoreMIDI
     * transport sends them as ordinary MIDI messages.
     */
    void sendShortMidi(byte[] message) throws IOException;

    @Override
    void close() throws IOException;
}
