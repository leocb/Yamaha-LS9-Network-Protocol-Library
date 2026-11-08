package io.github.leocb.ls9;

import io.github.leocb.ls9.bulk.BulkDump;
import io.github.leocb.ls9.bulk.BulkScene;

/** Metadata for one scene memory entry. */
public record Ls9SceneInfo(int number, boolean exists, String title,
                           int year, int month, int day, int hour, int minute, int second) {

    public String dateTime() {
        if (!exists) {
            return "";
        }
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d",
                2000 + year, month + 1, day + 1, hour, minute, second);
    }

    /**
     * Reads title and timestamp from a {@code SCENE___} bulk dump, avoiding
     * separate {@code LibAtrbt} round trips. The comment and date are part of
     * the scene image (see {@link BulkScene}).
     */
    public static Ls9SceneInfo fromBulk(BulkDump dump) {
        BulkScene scene = BulkScene.from(dump);
        return new Ls9SceneInfo(dump.number(), true, scene.title(),
                scene.year(), scene.month(), scene.day(),
                scene.hour(), scene.minute(), scene.second());
    }
}
