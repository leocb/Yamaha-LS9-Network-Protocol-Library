package io.github.leocb.ls9.sysex;

/**
 * One row of Yamaha's official LS9 Parameter Change List.
 *
 * @param category      "Scene" or "Setup"
 * @param element       symbolic element name, e.g. {@code kInputFader}
 * @param elementHex    element number
 * @param indexName     symbolic index name, e.g. {@code kFader}
 * @param indexHex      index number
 * @param min           minimum value as printed in the spreadsheet
 * @param max           maximum value as printed in the spreadsheet
 * @param defaultValue  default value as printed in the spreadsheet
 * @param maxChannel    highest channel index for this element
 * @param comment       descriptive comment / value table reference
 * @param valueTable    PRM TABLE number, or 0
 * @param template      parameter-change template from the spreadsheet
 */
public record ParameterDefinition(
        String category,
        String element,
        int elementHex,
        String indexName,
        int indexHex,
        String min,
        String max,
        String defaultValue,
        int maxChannel,
        String comment,
        int valueTable,
        String template) {

    public ParameterAddress address(int channel) {
        return ParameterAddress.of(elementHex, indexHex, channel);
    }

    public ParameterAddress address() {
        return address(0);
    }
}
