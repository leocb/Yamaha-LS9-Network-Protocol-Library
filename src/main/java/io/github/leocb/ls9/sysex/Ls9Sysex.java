package io.github.leocb.ls9.sysex;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Encoder/decoder for the Yamaha LS9 System Exclusive protocol.
 *
 * <p>Model ID is {@code 0x12}; group ID is {@code 0x3E}. Device numbers are
 * 0-based (MIDI channel 1 = device 0). A parameter change uses sub-status
 * {@code 0x10 | device}, a parameter request uses {@code 0x30 | device}.
 */
public final class Ls9Sysex {

    public static final int YAMAHA_ID = 0x43;
    public static final int GROUP_ID = 0x3E;
    public static final int MODEL_ID = 0x12;

    public static final int SUB_PARAMETER_CHANGE = 0x10;
    public static final int SUB_PARAMETER_REQUEST = 0x30;
    public static final int DATA_OTHER = 0x00;

    public static final int VALUE_BYTES = 5;

    public static final String MODULE_SCENE = "SCENE___";
    public static final String MODULE_INPUT_EQ = "INEQ____";
    public static final String MODULE_OUTPUT_EQ = "OUTEQ___";
    public static final String MODULE_DYNAMICS = "DYNA____";
    public static final String MODULE_GEQ = "GEQ_____";
    public static final String MODULE_EFFECT = "EFFECT__";

    public static final String FN_LIBRARY_STORE = "LibStr__";
    public static final String FN_LIBRARY_RECALL = "LibRcl__";
    public static final String FN_LIBRARY_UNKNOWN_STORE = "LibUnStr";
    public static final String FN_LIBRARY_UNKNOWN_RECALL = "LibUnRcl";
    public static final String FN_LIBRARY_UNDO_STORE = "LibStrUd";
    public static final String FN_LIBRARY_UNDO_RECALL = "LibRclUd";
    public static final String FN_LIBRARY_ATTRIBUTE = "LibAttrbt";
    public static final String FN_LIBRARY_COPY = "LibCpy__";
    public static final String FN_LIBRARY_PASTE = "LibPst__";
    public static final String FN_LIBRARY_CLEAR = "LibClr__";
    public static final String FN_LIBRARY_CUT = "LibCut__";
    public static final String FN_LIBRARY_INSERT = "LibIns__";
    public static final String FN_LIBRARY_EDIT_UNDO = "LibEdtUd";
    public static final String FN_LIBRARY_EXISTS = "LibExist";
    public static final String FN_COLLECTION_STORE = "ColUnStr";
    public static final String FN_MODULE_TRIGGER = "ModFxTrg";
    public static final String FN_CHANNEL_PAIR = "ChlPirCp";
    public static final String PAIR_ON_COPY = "PAIRONCP";
    public static final String PAIR_ON_RESET = "PAIRONRS";
    public static final String PAIR_OFF = "PAIROFF_";

    private Ls9Sysex() {
    }

    /** Builds a PARAMETER CHANGE for an unsigned value. */
    public static byte[] parameterChange(int device, ParameterAddress address, long value) {
        return parameterChange(device, address, value, false);
    }

    /**
     * Builds a PARAMETER CHANGE. Negative values are encoded as a 35-bit
     * two's-complement number when {@code signed} is true.
     */
    public static byte[] parameterChange(int device, ParameterAddress address, long value, boolean signed) {
        byte[] valueBytes = signed ? encodeSigned7(value, VALUE_BYTES) : encode7(value, VALUE_BYTES);
        return paramMessage(SUB_PARAMETER_CHANGE | device, address, valueBytes);
    }

    /** Builds a PARAMETER REQUEST (no value bytes). */
    public static byte[] parameterRequest(int device, ParameterAddress address) {
        return paramMessage(SUB_PARAMETER_REQUEST | device, address, new byte[0]);
    }

    private static byte[] paramMessage(int subStatus, ParameterAddress address, byte[] value) {
        int length = 5 + 1 + 6 + value.length + 1;
        byte[] b = new byte[length];
        int i = 0;
        b[i++] = (byte) 0xF0;
        b[i++] = (byte) YAMAHA_ID;
        b[i++] = (byte) subStatus;
        b[i++] = (byte) GROUP_ID;
        b[i++] = (byte) MODEL_ID;
        b[i++] = (byte) address.dataCategory();
        b[i++] = (byte) (address.element() >>> 7);
        b[i++] = (byte) (address.element() & 0x7F);
        b[i++] = (byte) (address.index() >>> 7);
        b[i++] = (byte) (address.index() & 0x7F);
        b[i++] = (byte) (address.channel() >>> 7);
        b[i++] = (byte) (address.channel() & 0x7F);
        System.arraycopy(value, 0, b, i, value.length);
        i += value.length;
        b[i] = (byte) 0xF7;
        return b;
    }

    /** Builds a scene/library store function call. */
    public static byte[] libraryStore(int device, String module, int number, int channel) {
        return functionCall(device, FN_LIBRARY_STORE, module, encode14(number), encode14(channel));
    }

    /** Builds a scene/library recall function call. */
    public static byte[] libraryRecall(int device, String module, int number, int channel) {
        return functionCall(device, FN_LIBRARY_RECALL, module, encode14(number), encode14(channel));
    }

    /** Unknown-factor store ({@code LibUnStr}). */
    public static byte[] libraryUnknownStore(int device, String module, int number) {
        return functionCall(device, FN_LIBRARY_UNKNOWN_STORE, module, encode14(number), encode14(0));
    }

    /** Unknown-factor recall ({@code LibUnRcl}). */
    public static byte[] libraryUnknownRecall(int device, String module, int number) {
        return functionCall(device, FN_LIBRARY_UNKNOWN_RECALL, module, encode14(number), encode14(0));
    }

    /** Library edit: copy/paste/clear/cut/insert/edit-undo. */
    public static byte[] libraryEdit(int device, String function, String module,
                                     int sourceStart, int sourceEnd, int destinationStart) {
        return functionCall(device, function, module,
                encode14(sourceStart), encode14(sourceEnd), encode14(destinationStart));
    }

    public static byte[] libraryCopy(int device, String module, int source) {
        return libraryEdit(device, FN_LIBRARY_COPY, module, source, 0, 0);
    }

    public static byte[] libraryPaste(int device, String module, int destination) {
        return libraryEdit(device, FN_LIBRARY_PASTE, module, 0, 0, destination);
    }

    public static byte[] libraryClear(int device, String module, int number) {
        return libraryEdit(device, FN_LIBRARY_CLEAR, module, number, 0, 0);
    }

    public static byte[] libraryCut(int device, String module, int number) {
        return libraryEdit(device, FN_LIBRARY_CUT, module, number, 0, 0);
    }

    public static byte[] libraryInsert(int device, String module, int destination) {
        return libraryEdit(device, FN_LIBRARY_INSERT, module, 0, 0, destination);
    }

    public static byte[] libraryEditUndo(int device, String module) {
        return libraryEdit(device, FN_LIBRARY_EDIT_UNDO, module, 0, 0, 0);
    }

    /** LIBRARY ATTRIBUTE request (no value). */
    public static byte[] libraryAttributeRequest(int device, String module, int number,
                                                 ParameterAddress parameter) {
        return functionRequest(device, FN_LIBRARY_ATTRIBUTE, module,
                encode14(number), encode14(parameter.element()), encode14(parameter.index()),
                encode14(parameter.channel()));
    }

    /** EXIST LIBRARY RANGE request. */
    public static byte[] libraryExistsRequest(int device, String module, int number) {
        return functionRequest(device, FN_LIBRARY_EXISTS, module, encode14(number));
    }

    /** Collection store ({@code ColUnStr}). */
    public static byte[] collectionStore(int device, String function, String module,
                                         int number, int channel) {
        return functionCall(device, function, module, encode14(number), encode14(channel));
    }

    /** Effect/module trigger ({@code ModFxTrg}). */
    public static byte[] moduleTrigger(int device, String module, int effect, boolean press) {
        return functionCall(device, FN_MODULE_TRIGGER, module,
                new byte[]{(byte) (effect & 0x7F), (byte) (press ? 1 : 0)});
    }

    /** Channel pair operation; {@code pairModule} is PAIRONCP, PAIRONRS or PAIROFF_. */
    public static byte[] channelPair(int device, String pairModule, int sourceChannel, int destinationChannel) {
        return functionCall(device, FN_CHANNEL_PAIR, pairModule,
                encode14(sourceChannel), encode14(destinationChannel));
    }

    public static byte[] libraryStoreUndo(int device) {
        return functionCall(device, FN_LIBRARY_UNDO_STORE, MODULE_SCENE, encode14(0), encode14(0));
    }

    public static byte[] libraryRecallUndo(int device) {
        return functionCall(device, FN_LIBRARY_UNDO_RECALL, MODULE_SCENE, encode14(0), encode14(0));
    }

    /**
     * Builds a LIBRARY ATTRIBUTE function call (used to set a scene/library
     * title element).
     */
    public static byte[] libraryAttribute(int device, String module, int number,
                                          ParameterAddress parameter, long value) {
        byte[] data = new byte[2 + 2 + 2 + 2 + VALUE_BYTES];
        int i = 0;
        System.arraycopy(encode14(number), 0, data, i, 2);
        i += 2;
        System.arraycopy(encode14(parameter.element()), 0, data, i, 2);
        i += 2;
        System.arraycopy(encode14(parameter.index()), 0, data, i, 2);
        i += 2;
        System.arraycopy(encode14(parameter.channel()), 0, data, i, 2);
        i += 2;
        System.arraycopy(encode7(value, VALUE_BYTES), 0, data, i, VALUE_BYTES);
        return functionCall(device, FN_LIBRARY_ATTRIBUTE, module, data);
    }

    /** Builds a generic function call with raw payload bytes. */
    public static byte[] functionCall(int device, String function, String module, byte[]... parts) {
        return functionMessage(SUB_PARAMETER_CHANGE | device, function, module, parts);
    }

    /** Builds a function-call request (no value), e.g. LibAttrbt/LibExist. */
    public static byte[] functionRequest(int device, String function, String module, byte[]... parts) {
        return functionMessage(SUB_PARAMETER_REQUEST | device, function, module, parts);
    }

    private static byte[] functionMessage(int subStatus, String function, String module, byte[]... parts) {
        byte[] fn = fixedAscii(function, 8);
        byte[] mod = fixedAscii(module, 8);
        int payload = 0;
        for (byte[] p : parts) {
            payload += p.length;
        }
        byte[] b = new byte[6 + fn.length + mod.length + payload + 1];
        int i = 0;
        b[i++] = (byte) 0xF0;
        b[i++] = (byte) YAMAHA_ID;
        b[i++] = (byte) subStatus;
        b[i++] = (byte) GROUP_ID;
        b[i++] = (byte) MODEL_ID;
        b[i++] = (byte) DATA_OTHER;
        System.arraycopy(fn, 0, b, i, fn.length);
        i += fn.length;
        System.arraycopy(mod, 0, b, i, mod.length);
        i += mod.length;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, b, i, p.length);
            i += p.length;
        }
        b[i] = (byte) 0xF7;
        return b;
    }

    /** Parses a complete SysEx message; returns {@code null} when it is not an LS9 message. */
    public static Ls9SysexMessage parse(byte[] message) {
        if (message == null || message.length < 7
                || (message[0] & 0xFF) != 0xF0
                || (message[message.length - 1] & 0xFF) != 0xF7
                || (message[1] & 0xFF) != YAMAHA_ID
                || (message[3] & 0xFF) != GROUP_ID
                || (message[4] & 0xFF) != MODEL_ID) {
            return null;
        }
        int sub = message[2] & 0xFF;
        int device = sub & 0x0F;
        if ((sub & 0xF0) == SUB_PARAMETER_REQUEST
                && message.length >= 12
                && (message[5] & 0xFF) == ParameterAddress.CURRENT_DATA) {
            int element = decode14(message, 6);
            int index = decode14(message, 8);
            int channel = decode14(message, 10);
            ParameterAddress address = new ParameterAddress(ParameterAddress.CURRENT_DATA, element, index, channel);
            return Ls9SysexMessage.parameterRequest(device, address, message);
        }
        if ((sub & 0xF0) == SUB_PARAMETER_CHANGE
                && message.length >= 12
                && (message[5] & 0xFF) == ParameterAddress.CURRENT_DATA) {
            int element = decode14(message, 6);
            int index = decode14(message, 8);
            int channel = decode14(message, 10);
            ParameterAddress address = new ParameterAddress(ParameterAddress.CURRENT_DATA, element, index, channel);
            int dataLength = Math.min(VALUE_BYTES, message.length - 13);
            if (dataLength < 0) {
                dataLength = 0;
            }
            long value = decode7(message, 12, dataLength);
            return Ls9SysexMessage.parameterChange(device, address, value, message);
        }
        if (((sub & 0xF0) == SUB_PARAMETER_CHANGE || (sub & 0xF0) == SUB_PARAMETER_REQUEST)
                && message.length >= 21
                && (message[5] & 0xFF) == DATA_OTHER) {
            String function = ascii(message, 6, 8);
            String module = ascii(message, 14, 8);
            int payloadOffset = 22;
            int number = 0;
            int channel = 0;
            ParameterAddress address = null;
            Long value = null;
            if (message.length >= payloadOffset + 1) {
                if (function.equals(FN_LIBRARY_ATTRIBUTE) && message.length >= payloadOffset + 10) {
                    number = decode14(message, payloadOffset);
                    int element = decode14(message, payloadOffset + 2);
                    int index = decode14(message, payloadOffset + 4);
                    channel = decode14(message, payloadOffset + 6);
                    address = new ParameterAddress(DATA_OTHER, element, index, channel);
                    value = decode7(message, payloadOffset + 8, VALUE_BYTES);
                } else if (function.equals(FN_LIBRARY_EXISTS) && message.length >= payloadOffset + 7) {
                    // status, request number, top number, end number
                    number = decode14(message, payloadOffset + 1);
                } else if (message.length >= payloadOffset + 4) {
                    number = decode14(message, payloadOffset);
                    channel = decode14(message, payloadOffset + 2);
                }
            }
            byte[] payload = Arrays.copyOfRange(message, payloadOffset, message.length - 1);
            return Ls9SysexMessage.functionCall(device, function, module, number, channel,
                    address, value, payload, message);
        }
        return Ls9SysexMessage.other(device, message);
    }

    /** Encodes a non-negative value into {@code bytes} 7-bit bytes, big-endian. */
    public static byte[] encode7(long value, int bytes) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative");
        }
        long limit = 1L << (7 * bytes);
        if (value >= limit) {
            throw new IllegalArgumentException("value " + value + " does not fit in " + bytes + " 7-bit bytes");
        }
        byte[] out = new byte[bytes];
        long v = value;
        for (int i = bytes - 1; i >= 0; i--) {
            out[i] = (byte) (v & 0x7F);
            v >>>= 7;
        }
        return out;
    }

    /** Encodes a signed value as two's complement in {@code bytes} 7-bit bytes. */
    public static byte[] encodeSigned7(long value, int bytes) {
        int bits = 7 * bytes;
        long mask = (1L << bits) - 1;
        return encode7(value & mask, bytes);
    }

    public static long decode7(byte[] b, int offset, int bytes) {
        long v = 0;
        for (int i = 0; i < bytes; i++) {
            v = (v << 7) | (b[offset + i] & 0x7F);
        }
        return v;
    }

    public static byte[] encode14(int value) {
        return new byte[]{(byte) ((value >>> 7) & 0x7F), (byte) (value & 0x7F)};
    }

    public static int decode14(byte[] b, int offset) {
        return ((b[offset] & 0x7F) << 7) | (b[offset + 1] & 0x7F);
    }

    /**
     * Packs four 7-bit characters into one parameter value (LS9 name chunks).
     * Character 0 is the most significant group.
     */
    public static long packName4(String text) {
        byte[] b = fixedAscii(text, 4);
        long v = 0;
        for (int i = 0; i < 4; i++) {
            v = (v << 7) | (b[i] & 0x7F);
        }
        return v;
    }

    /** Unpacks four 7-bit characters from one parameter value. */
    public static String unpackName4(long value) {
        char[] chars = new char[4];
        long v = value;
        for (int i = 3; i >= 0; i--) {
            chars[i] = (char) (v & 0x7F);
            v >>>= 7;
        }
        return new String(chars);
    }

    private static byte[] fixedAscii(String text, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) ' ');
        if (text != null) {
            byte[] src = text.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(src, 0, out, 0, Math.min(src.length, length));
        }
        for (int i = 0; i < out.length; i++) {
            out[i] &= 0x7F;
        }
        return out;
    }

    private static String ascii(byte[] b, int offset, int length) {
        byte[] tmp = Arrays.copyOfRange(b, offset, offset + length);
        for (int i = 0; i < tmp.length; i++) {
            tmp[i] &= 0x7F;
        }
        return new String(tmp, StandardCharsets.US_ASCII);
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }
}
