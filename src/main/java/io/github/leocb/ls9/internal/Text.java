package io.github.leocb.ls9.internal;

/** Small compatibility helpers for API levels without Java 11 String methods. */
public final class Text {

    private Text() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static String trimTrailing(String s) {
        if (s == null) {
            return "";
        }
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
