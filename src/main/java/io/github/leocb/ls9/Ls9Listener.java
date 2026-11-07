package io.github.leocb.ls9;

import io.github.leocb.ls9.bulk.BulkDump;
import io.github.leocb.ls9.meter.MeterFrame;
import io.github.leocb.ls9.sysex.Ls9SysexMessage;

/** Receives asynchronous events from an LS9. */
public interface Ls9Listener {

    /** A parameter changed on the console (or was echoed after a request). */
    default void onParameterChange(Ls9ParameterEvent event) {
    }

    /** A complete BULK DUMP block arrived (requested or unsolicited). */
    default void onBulkDump(BulkDump dump) {
    }

    /** A LEVEL METER response arrived after a subscription request. */
    default void onMeter(MeterFrame frame) {
    }

    /** A function call (scene/library recall, store, ...) was reported. */
    default void onFunctionCall(Ls9SysexMessage message) {
    }

    /** The transport has been closed or the console dropped the connection. */
    default void onDisconnected() {
    }
}
