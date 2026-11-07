package io.github.leocb.ls9.sysex;

/** Static metadata for one generated parameter entry. */
public record ParameterSpec(
        String element,
        String indexName,
        int elementHex,
        int indexHex,
        String min,
        String max,
        String defaultValue,
        int maxChannel,
        String comment,
        int valueTable) {

    public ParameterAddress address(int channel) {
        return ParameterAddress.of(elementHex, indexHex, channel);
    }

    public ParameterAddress address() {
        return address(0);
    }

    public Long minValue() {
        return parseLong(min);
    }

    public Long maxValue() {
        return parseLong(max);
    }

    public Long defaultValueAsLong() {
        return parseLong(defaultValue);
    }

    private static Long parseLong(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.startsWith("'") || t.isEmpty()) {
            return null;
        }
        try {
            if (t.startsWith("0x") || t.startsWith("0X")) {
                return Long.parseLong(t.substring(2), 16);
            }
            return (long) Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return element + "." + indexName + String.format(" [0x%04X/0x%04X]", elementHex, indexHex);
    }
}
