package io.github.leocb.ls9.sysex;

import java.util.Objects;

/**
 * Address of one LS9 parameter inside a PARAMETER CHANGE / REQUEST message.
 *
 * <p>Wire layout (after the {@code 12} model ID):
 * <pre>
 *   data category (1 byte)
 *   element number (2 x 7-bit, big-endian)
 *   index number   (2 x 7-bit, big-endian)
 *   channel number (2 x 7-bit, big-endian)
 * </pre>
 * For "current scene/setup" parameters the data category is {@code 0x01}.
 */
public record ParameterAddress(int dataCategory, int element, int index, int channel) {

    public static final int CURRENT_DATA = 0x01;

    public ParameterAddress {
        require14(element, "element");
        require14(index, "index");
        require14(channel, "channel");
        if (dataCategory < 0 || dataCategory > 0x7F) {
            throw new IllegalArgumentException("dataCategory out of range: " + dataCategory);
        }
    }

    public static ParameterAddress of(int element, int index, int channel) {
        return new ParameterAddress(CURRENT_DATA, element, index, channel);
    }

    public ParameterAddress withChannel(int newChannel) {
        return new ParameterAddress(dataCategory, element, index, newChannel);
    }

    public ParameterAddress withIndex(int newIndex) {
        return new ParameterAddress(dataCategory, element, newIndex, channel);
    }

    private static void require14(int value, String name) {
        if (value < 0 || value > 0x3FFF) {
            throw new IllegalArgumentException(name + " out of 14-bit range: " + value);
        }
    }

    public String hex() {
        return String.format("%02X/%04X/%04X/%04X", dataCategory, element, index, channel);
    }

    @Override
    public String toString() {
        return "ParameterAddress[" + hex() + "]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ParameterAddress other
                && dataCategory == other.dataCategory
                && element == other.element
                && index == other.index
                && channel == other.channel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(dataCategory, element, index, channel);
    }
}
