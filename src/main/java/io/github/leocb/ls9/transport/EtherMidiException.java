package io.github.leocb.ls9.transport;

import java.io.IOException;
import java.util.Arrays;

/**
 * Typed EtherMIDI handshake failure.
 *
 * <p>The console answers an open request with {@link EtherMidiPacket#CMD_OPEN_ACK}
 * or {@link EtherMidiPacket#CMD_OPEN_ERROR}, and a MAC challenge with
 * {@link EtherMidiPacket#CMD_AUTH_ACK} or
 * {@link EtherMidiPacket#CMD_ATTACH_ERROR}. This exception exposes which reply
 * arrived and the raw packet, so a caller can distinguish "the console refused
 * the mode bitmap" from "authentication failed" and react accordingly.
 */
public final class EtherMidiException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int command;
    private final byte[] packet;

    public EtherMidiException(String message, int command, byte[] packet) {
        super(message + " (reply 0x" + String.format("%02X", command & 0xFF) + ")");
        this.command = command & 0xFF;
        this.packet = packet == null ? new byte[0] : Arrays.copyOf(packet, packet.length);
    }

    /** The reply command byte, for example {@code 0x22} or {@code 0x25}. */
    public int command() {
        return command;
    }

    /** Copy of the full reply packet. */
    public byte[] packet() {
        return Arrays.copyOf(packet, packet.length);
    }

    /** True when the console refused the open request ({@code 0x22}). */
    public boolean isOpenRefused() {
        return command == EtherMidiPacket.CMD_OPEN_ERROR;
    }

    /** True when the console rejected the MAC challenge ({@code 0x25}). */
    public boolean isAttachRejected() {
        return command == EtherMidiPacket.CMD_ATTACH_ERROR;
    }
}