package io.github.leocb.ls9;

import io.github.leocb.ls9.sysex.ParameterAddress;

/** Parameter change notification from the console. */
public record Ls9ParameterEvent(ParameterAddress address, long value, byte[] raw) {
    public Ls9ParameterEvent {
        raw = raw == null ? new byte[0] : raw.clone();
    }

    @Override
    public byte[] raw() {
        return raw.clone();
    }
}
