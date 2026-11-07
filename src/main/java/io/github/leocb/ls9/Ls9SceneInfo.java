package io.github.leocb.ls9;

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
}
