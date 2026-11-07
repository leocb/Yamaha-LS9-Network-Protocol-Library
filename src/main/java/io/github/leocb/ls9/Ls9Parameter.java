package io.github.leocb.ls9;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import io.github.leocb.ls9.internal.Text;
import io.github.leocb.ls9.sysex.Ls9ValueTable;
import io.github.leocb.ls9.sysex.Ls9ValueTableRegistry;
import io.github.leocb.ls9.sysex.ParameterAddress;
import io.github.leocb.ls9.sysex.ParameterSpec;

/**
 * A parameter bound to a console and a channel. This is the generic,
 * fully-generated access path for every entry in Yamaha's parameter list.
 */
public final class Ls9Parameter {

    private final Ls9Console console;
    private final ParameterSpec spec;
    private final int channel;
    private final Map<String, Integer> inlineEnum;

    Ls9Parameter(Ls9Console console, ParameterSpec spec, int channel) {
        this.console = console;
        this.spec = spec;
        this.channel = channel;
        this.inlineEnum = buildInlineEnum(spec.comment());
    }

    public ParameterSpec spec() {
        return spec;
    }

    public ParameterAddress address() {
        return spec.address(channel);
    }

    public int channel() {
        return channel;
    }

    public long getRaw() throws IOException {
        return console.get(address());
    }

    public void setRaw(long value) throws IOException {
        console.set(address(), value);
    }

    public long get() throws IOException {
        return getRaw();
    }

    public void set(long value) throws IOException {
        setRaw(value);
    }

    public Long min() {
        return spec.minValue();
    }

    public Long max() {
        return spec.maxValue();
    }

    public Long defaultValue() {
        return spec.defaultValueAsLong();
    }

    /** Human-readable value using the PRM TABLE or inline enum, if available. */
    public String display(long raw) {
        if (spec.valueTable() > 0) {
            Ls9ValueTable table = Ls9ValueTableRegistry.get().get(spec.valueTable()).orElse(null);
            if (table != null) {
                return table.display((int) raw);
            }
        }
        if (!inlineEnum.isEmpty()) {
            Integer key = (int) raw;
            String value = inlineEnumByValue.get(key);
            if (value != null) {
                return value;
            }
        }
        return Long.toString(raw);
    }

    /** Reads the current value and renders it through {@link #display(long)}. */
    public String getDisplay() throws IOException {
        return display(getRaw());
    }

    /** Parses a display string and sends the corresponding raw value. */
    public void setDisplay(String text) throws IOException {
        Long raw = parseDisplay(text);
        if (raw == null) {
            throw new IllegalArgumentException("cannot parse '" + text + "' for " + spec);
        }
        setRaw(raw);
    }

    public Long parseDisplay(String text) {
        if (text == null) {
            return null;
        }
        if (spec.valueTable() > 0) {
            Ls9ValueTable table = Ls9ValueTableRegistry.get().get(spec.valueTable()).orElse(null);
            if (table != null) {
                Integer v = table.parse(text);
                if (v != null) {
                    return (long) v;
                }
            }
        }
        String key = normalize(text);
        Integer v = inlineEnum.get(key);
        if (v != null) {
            return (long) v;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private final Map<Integer, String> inlineEnumByValue = new LinkedHashMap<>();

    private Map<String, Integer> buildInlineEnum(String comment) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (comment == null || Text.isBlank(comment) || comment.contains("default:")) {
            return out;
        }
        String[] parts = comment.split(",");
        if (parts.length < 2 || parts.length > 24) {
            return out;
        }
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty() || p.equals("-") || p.toLowerCase(Locale.ROOT).contains("db")) {
                return Collections.emptyMap();
            }
            if (!p.matches("[A-Za-z][A-Za-z0-9 _/()+.\\-]*")) {
                return Collections.emptyMap();
            }
            out.putIfAbsent(normalize(p), i);
            inlineEnumByValue.putIfAbsent(i, p);
        }
        return out;
    }

    private static String normalize(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return spec.toString() + " channel=" + channel;
    }
}
