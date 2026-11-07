package io.github.leocb.ls9.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import io.github.leocb.ls9.internal.Csv;
import io.github.leocb.ls9.internal.Io;
import io.github.leocb.ls9.internal.Text;

/**
 * LS9 fader curve: raw 0..1023 to dB, taken from {@code PRM TABLE #03} of
 * Yamaha's official parameter list.
 */
public final class Ls9FaderTable {

    public static final int MAX_VALUE = 1023;
    private static final String RESOURCE = "/io/github/leocb/ls9/fader-table.csv";
    private static volatile Ls9FaderTable instance;

    private final double[] db = new double[1024];

    private Ls9FaderTable(double[] db) {
        System.arraycopy(db, 0, this.db, 0, db.length);
    }

    public static Ls9FaderTable get() {
        Ls9FaderTable local = instance;
        if (local == null) {
            synchronized (Ls9FaderTable.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    private static Ls9FaderTable load() {
        try (InputStream in = Ls9FaderTable.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            List<List<String>> rows = Csv.parse(new String(Io.readAll(in), StandardCharsets.UTF_8));
            double[] values = new double[1024];
            boolean[] seen = new boolean[1024];
            for (int i = 1; i < rows.size(); i++) {
                List<String> r = rows.get(i);
                if (r.size() < 2 || r.get(0).trim().isEmpty()) {
                    continue;
                }
                int raw = (int) Double.parseDouble(r.get(0));
                values[raw] = Double.parseDouble(r.get(1));
                seen[raw] = true;
            }
            for (int i = 0; i < 1024; i++) {
                if (!seen[i]) {
                    throw new IllegalStateException("fader table incomplete at " + i);
                }
            }
            return new Ls9FaderTable(values);
        } catch (IOException e) {
            throw new IllegalStateException("cannot load " + RESOURCE, e);
        }
    }

    /** Converts a raw fader value (0..1023) to dB. */
    public double toDb(int raw) {
        checkRaw(raw);
        return db[raw];
    }

    /**
     * Converts dB to the nearest raw fader value (0..1023).
     * Values at or below the first finite step map to 0 ({@code -Infinity}).
     */
    public int toRaw(double valueDb) {
        if (Double.isNaN(valueDb)) {
            throw new IllegalArgumentException("NaN is not a valid level");
        }
        if (valueDb == Double.NEGATIVE_INFINITY || valueDb <= db[1]) {
            return 0;
        }
        if (valueDb >= db[MAX_VALUE]) {
            return MAX_VALUE;
        }
        int lo = 1;
        int hi = MAX_VALUE;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (db[mid] < valueDb) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        // lo is the first entry >= value; choose the closer of lo and lo-1.
        if (lo > 1 && Math.abs(db[lo - 1] - valueDb) <= Math.abs(db[lo] - valueDb)) {
            return lo - 1;
        }
        return lo;
    }

    /** Returns a short display string, e.g. {@code -Infinity} or {@code -6.0 dB}. */
    public String format(int raw) {
        double v = toDb(raw);
        if (v == Double.NEGATIVE_INFINITY) {
            return "-Infinity";
        }
        return String.format(java.util.Locale.ROOT, "%.2f dB", v);
    }

    private static void checkRaw(int raw) {
        if (raw < 0 || raw > MAX_VALUE) {
            throw new IllegalArgumentException("raw fader value out of range 0..1023: " + raw);
        }
    }
}
