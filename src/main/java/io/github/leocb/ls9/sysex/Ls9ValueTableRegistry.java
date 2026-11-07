package io.github.leocb.ls9.sysex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import io.github.leocb.ls9.internal.Csv;
import io.github.leocb.ls9.internal.Io;
import io.github.leocb.ls9.internal.Text;

/** Registry of all PRM TABLE display tables extracted from Yamaha's XLS. */
public final class Ls9ValueTableRegistry {

    private static final String RESOURCE = "/io/github/leocb/ls9/value-tables.csv";
    private static volatile Ls9ValueTableRegistry instance;

    private final Map<Integer, Ls9ValueTable> tables;

    private Ls9ValueTableRegistry(Map<Integer, Ls9ValueTable> tables) {
        this.tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
    }

    public static Ls9ValueTableRegistry get() {
        Ls9ValueTableRegistry local = instance;
        if (local == null) {
            synchronized (Ls9ValueTableRegistry.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    private static Ls9ValueTableRegistry load() {
        try (InputStream in = Ls9ValueTableRegistry.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            List<List<String>> rows = Csv.parse(new String(Io.readAll(in), StandardCharsets.UTF_8));
            Map<Integer, Map<Integer, String>> values = new LinkedHashMap<>();
            Map<Integer, String> names = new LinkedHashMap<>();
            for (int i = 1; i < rows.size(); i++) {
                List<String> r = rows.get(i);
                if (r.size() < 4 || r.get(0).trim().isEmpty()) {
                    continue;
                }
                int id = Integer.parseInt(r.get(0));
                values.computeIfAbsent(id, k -> new LinkedHashMap<>())
                        .putIfAbsent(Integer.parseInt(r.get(2)), r.get(3));
                names.putIfAbsent(id, r.get(1));
            }
            Map<Integer, Ls9ValueTable> tables = new LinkedHashMap<>();
            for (Map.Entry<Integer, Map<Integer, String>> e : values.entrySet()) {
                tables.put(e.getKey(), new Ls9ValueTable(e.getKey(),
                        names.getOrDefault(e.getKey(), ""), e.getValue()));
            }
            return new Ls9ValueTableRegistry(tables);
        } catch (IOException e) {
            throw new IllegalStateException("cannot load " + RESOURCE, e);
        }
    }

    public Optional<Ls9ValueTable> get(int id) {
        return Optional.ofNullable(tables.get(id));
    }

    public Map<Integer, Ls9ValueTable> tables() {
        return tables;
    }
}
