package io.github.leocb.ls9.sysex;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One display table from Yamaha's PRM TABLE sheets. */
public final class Ls9ValueTable {

    private final int id;
    private final String name;
    private final Map<Integer, String> byValue;
    private final Map<String, Integer> byDisplay;

    Ls9ValueTable(int id, String name, Map<Integer, String> values) {
        this.id = id;
        this.name = name;
        this.byValue = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        Map<String, Integer> reverse = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : values.entrySet()) {
            reverse.putIfAbsent(normalize(e.getValue()), e.getKey());
        }
        this.byDisplay = Collections.unmodifiableMap(reverse);
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Map<Integer, String> values() {
        return byValue;
    }

    public String display(int value) {
        return byValue.getOrDefault(value, Integer.toString(value)).trim();
    }

    public Integer parse(String text) {
        if (text == null) {
            return null;
        }
        String key = normalize(text);
        Integer exact = byDisplay.get(key);
        if (exact != null) {
            return exact;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalize(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public String toString() {
        return "ValueTable#" + id + "{" + name + ", " + byValue.size() + " entries}";
    }
}
