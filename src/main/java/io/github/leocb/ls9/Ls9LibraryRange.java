package io.github.leocb.ls9;

/** Result of an EXIST LIBRARY RANGE request. */
public record Ls9LibraryRange(boolean valid, int requested, int top, int end) {
}
