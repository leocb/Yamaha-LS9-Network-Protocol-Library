package io.github.leocb.ls9.internal;

import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 CSV parser (quoted fields, doubled quotes, CRLF/LF). */
public final class Csv {

    private Csv() {
    }

    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                switch (c) {
                    case '"' -> quoted = true;
                    case ',' -> {
                        row.add(field.toString());
                        field.setLength(0);
                    }
                    case '\n' -> {
                        row.add(field.toString());
                        field.setLength(0);
                        rows.add(row);
                        row = new ArrayList<>();
                    }
                    case '\r' -> {
                        // ignore; LF terminates the record
                    }
                    default -> field.append(c);
                }
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
