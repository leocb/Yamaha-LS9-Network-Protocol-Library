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

    /**
     * True when the string contains only printable ASCII. LS9 names and titles
     * are 7-bit ASCII; control bytes mean a word was read from the wrong place
     * or the console answered with a placeholder.
     */
    public static boolean isPrintable(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }
}
