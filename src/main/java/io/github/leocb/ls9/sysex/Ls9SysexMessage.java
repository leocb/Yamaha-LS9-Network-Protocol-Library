package io.github.leocb.ls9.sysex;

/**
 * Parsed Yamaha LS9 System Exclusive message.
 */
public final class Ls9SysexMessage {

    public enum Type {
        PARAMETER_CHANGE,
        PARAMETER_REQUEST,
        FUNCTION_CALL,
        OTHER
    }

    private final Type type;
    private final int deviceNumber;
    private final ParameterAddress address;
    private final Long value;
    private final String function;
    private final String module;
    private final int number;
    private final int channel;
    private final byte[] payload;
    private final byte[] raw;

    private Ls9SysexMessage(Type type, int deviceNumber, ParameterAddress address, Long value,
                            String function, String module, int number, int channel,
                            byte[] payload, byte[] raw) {
        this.type = type;
        this.deviceNumber = deviceNumber;
        this.address = address;
        this.value = value;
        this.function = function;
        this.module = module;
        this.number = number;
        this.channel = channel;
        this.payload = payload == null ? new byte[0] : payload.clone();
        this.raw = raw;
    }

    static Ls9SysexMessage parameterChange(int device, ParameterAddress address, long value, byte[] raw) {
        return new Ls9SysexMessage(Type.PARAMETER_CHANGE, device, address, value,
                null, null, 0, 0, null, raw);
    }

    static Ls9SysexMessage parameterRequest(int device, ParameterAddress address, byte[] raw) {
        return new Ls9SysexMessage(Type.PARAMETER_REQUEST, device, address, null,
                null, null, 0, 0, null, raw);
    }

    static Ls9SysexMessage functionCall(int device, String function, String module, int number,
                                        int channel, ParameterAddress address, Long value,
                                        byte[] payload, byte[] raw) {
        return new Ls9SysexMessage(Type.FUNCTION_CALL, device, address, value, function, module,
                number, channel, payload, raw);
    }

    static Ls9SysexMessage other(int device, byte[] raw) {
        return new Ls9SysexMessage(Type.OTHER, device, null, null, null, null, 0, 0, null, raw);
    }

    public Type type() {
        return type;
    }

    public int deviceNumber() {
        return deviceNumber;
    }

    public ParameterAddress address() {
        return address;
    }

    public Long value() {
        return value;
    }

    public String function() {
        return function;
    }

    public String module() {
        return module;
    }

    public int number() {
        return number;
    }

    public int channel() {
        return channel;
    }

    /** Raw bytes after the 8-byte module name (function-specific). */
    public byte[] payload() {
        return payload.clone();
    }

    public byte[] raw() {
        return raw.clone();
    }

    public boolean isParameterChange() {
        return type == Type.PARAMETER_CHANGE;
    }

    public boolean isParameterRequest() {
        return type == Type.PARAMETER_REQUEST;
    }

    public boolean isFunctionCall() {
        return type == Type.FUNCTION_CALL;
    }

    @Override
    public String toString() {
        return switch (type) {
            case PARAMETER_CHANGE -> "ParameterChange{" + address.hex() + "=" + value + "}";
            case PARAMETER_REQUEST -> "ParameterRequest{" + address.hex() + "}";
            case FUNCTION_CALL -> "FunctionCall{" + function + "," + module
                    + ",number=" + number + ",channel=" + channel + "}";
            case OTHER -> "SysEx{" + Ls9Sysex.hex(raw) + "}";
        };
    }
}
