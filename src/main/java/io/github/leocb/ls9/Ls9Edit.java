package io.github.leocb.ls9;

import java.io.IOException;

/** A block of live edits, used for recall-edit-store workflows. */
@FunctionalInterface
public interface Ls9Edit {
    void apply(Ls9Console console) throws IOException;
}
