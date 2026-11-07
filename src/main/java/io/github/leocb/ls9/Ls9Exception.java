package io.github.leocb.ls9;

/** Thrown when the LS9 connection or protocol operation fails. */
public class Ls9Exception extends java.io.IOException {
    private static final long serialVersionUID = 1L;

    public Ls9Exception(String message) {
        super(message);
    }

    public Ls9Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
