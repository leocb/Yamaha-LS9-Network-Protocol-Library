package io.github.leocb.ls9.meter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import io.github.leocb.ls9.internal.Csv;
import io.github.leocb.ls9.internal.Io;
import io.github.leocb.ls9.internal.Text;

/** Meter value 0..127 to dB conversion from Yamaha's meter_steps table. */
public final class MeterScale {

    private static final String RESOURCE = "/io/github/leocb/ls9/meter-table.csv";
    private static volatile MeterScale instance;

    private final double[] db = new double[128];

    private MeterScale(double[] db) {
        System.arraycopy(db, 0, this.db, 0, db.length);
    }

    public static MeterScale get() {
        MeterScale local = instance;
        if (local == null) {
            synchronized (MeterScale.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    private static MeterScale load() {
        try (InputStream in = MeterScale.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            List<List<String>> rows = Csv.parse(new String(Io.readAll(in), StandardCharsets.UTF_8));
            double[] values = new double[128];
            for (int i = 0; i < values.length; i++) {
                values[i] = Double.NaN;
            }
            for (int i = 1; i < rows.size(); i++) {
                List<String> r = rows.get(i);
                if (r.size() < 2 || r.get(0).trim().isEmpty()) {
                    continue;
                }
                int index = Integer.parseInt(r.get(0));
                if (index < 0 || index > 127) {
                    continue;
                }
                try {
                    values[index] = Double.parseDouble(r.get(1));
                } catch (NumberFormatException e) {
                    values[index] = Double.NaN;
                }
            }
            return new MeterScale(values);
        } catch (IOException e) {
            throw new IllegalStateException("cannot load " + RESOURCE, e);
        }
    }

    public double toDb(int meterValue) {
        if (meterValue < 0 || meterValue > 127) {
            throw new IllegalArgumentException("meter value must be 0..127");
        }
        return db[meterValue];
    }

    /** Returns the nearest meter step for a dB value, or 0 for silence. */
    public int fromDb(double valueDb) {
        int best = 0;
        double bestDelta = Double.POSITIVE_INFINITY;
        for (int i = 0; i < db.length; i++) {
            if (Double.isNaN(db[i])) {
                continue;
            }
            double delta = Math.abs(db[i] - valueDb);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = i;
            }
        }
        return best;
    }
}
