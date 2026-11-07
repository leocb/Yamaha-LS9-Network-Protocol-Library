package io.github.leocb.ls9;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import io.github.leocb.ls9.bulk.BulkDump;
import io.github.leocb.ls9.bulk.BulkDumpCodec;
import io.github.leocb.ls9.internal.Text;
import io.github.leocb.ls9.meter.MeterCodec;
import io.github.leocb.ls9.meter.MeterFrame;
import io.github.leocb.ls9.meter.MeterGroup;
import io.github.leocb.ls9.model.Ls9Elements;
import io.github.leocb.ls9.model.Ls9FaderTable;
import io.github.leocb.ls9.sysex.Ls9Sysex;
import io.github.leocb.ls9.sysex.Ls9SysexMessage;
import io.github.leocb.ls9.sysex.ParameterAddress;
import io.github.leocb.ls9.transport.EtherMidiTransport;
import io.github.leocb.ls9.transport.MidiTransport;
import io.github.leocb.ls9.transport.YemAuth;

/**
 * High-level connection to a Yamaha LS9 console.
 *
 * <pre>{@code
 * try (Ls9Console ls9 = Ls9Console.connect("192.168.0.128")) {
 *     ls9.input(1).setFaderDb(0.0);
 *     ls9.input(1).setOn(true);
 *     ls9.scene().recall(12);
 * }
 * }</pre>
 *
 * <p>By default the console is reached directly over TCP port 12300 using the
 * reverse-engineered EtherMIDI transport. A {@link io.github.leocb.ls9.transport.JavaMidiTransport}
 * can be supplied instead to use Yamaha's installed Network-MIDI Driver.
 */
public final class Ls9Console implements AutoCloseable {

    public static final int DEFAULT_DEVICE_NUMBER = 0; // MIDI channel 1
    public static final int DEFAULT_TIMEOUT_MS = 1500;

    private final MidiTransport transport;
    private final int deviceNumber;
    private final int timeoutMs;
    private final Ls9FaderTable faders = Ls9FaderTable.get();

    private final ConcurrentHashMap<ParameterAddress, CompletableFuture<Long>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<BulkDump>> pendingBulk = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Ls9SysexMessage>> pendingFunction = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ParameterAddress, Long> lastValues = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Ls9Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean disconnectNotified = new AtomicBoolean();
    private volatile boolean closed;

    private Ls9Console(MidiTransport transport, int deviceNumber, int timeoutMs) {
        this.transport = transport;
        this.deviceNumber = deviceNumber;
        this.timeoutMs = timeoutMs;
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    /** Connects directly to {@code host} on the default EtherMIDI TCP port. */
    public static Ls9Console connect(String host) throws IOException {
        return builder().host(host).open();
    }

    /** Connects directly to {@code host:port}. */
    public static Ls9Console connect(String host, int tcpPort) throws IOException {
        return builder().host(host).tcpPort(tcpPort).open();
    }

    /** Connects directly, using the console MAC if the handshake challenges. */
    public static Ls9Console connect(String host, String macAddress) throws IOException {
        return builder().host(host).authMac(macAddress).open();
    }

    /** A fluent builder for both EtherMIDI and OS-MIDI transports. */
    public static final class Builder {
        private String host = "192.168.0.128";
        private int tcpPort = EtherMidiTransport.DEFAULT_TCP_PORT;
        private byte[] authData;
        private int portIndex = 0;
        private int deviceId = 1;
        private int modeBitmap = 0;
        private int deviceNumber = DEFAULT_DEVICE_NUMBER;
        private int timeoutMs = DEFAULT_TIMEOUT_MS;
        private int connectTimeoutMs = EtherMidiTransport.DEFAULT_CONNECT_TIMEOUT_MS;
        private MidiTransport transport;

        public Builder host(String host) {
            this.host = Objects.requireNonNull(host, "host");
            return this;
        }

        public Builder tcpPort(int tcpPort) {
            this.tcpPort = tcpPort;
            return this;
        }

        /** Six-byte MAC address used for the EtherMIDI authentication challenge. */
        public Builder authData(byte[] mac) {
            this.authData = mac == null ? null : mac.clone();
            return this;
        }

        public Builder authMac(String mac) {
            this.authData = YemAuth.parseMac(mac);
            return this;
        }

        /** EtherMIDI MIDI port index; Port1 is 0. */
        public Builder portIndex(int portIndex) {
            this.portIndex = portIndex;
            return this;
        }

        /** EtherMIDI device/port number; an LS9 uses 1. */
        public Builder deviceId(int deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        public Builder modeBitmap(int modeBitmap) {
            this.modeBitmap = modeBitmap;
            return this;
        }

        /** LS9 SysEx device number, 0..15 (MIDI channel 1 = 0). */
        public Builder deviceNumber(int deviceNumber) {
            if (deviceNumber < 0 || deviceNumber > 15) {
                throw new IllegalArgumentException("deviceNumber must be 0..15");
            }
            this.deviceNumber = deviceNumber;
            return this;
        }

        public Builder timeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public Builder connectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        /** Uses an already-configured transport instead of EtherMIDI. */
        public Builder transport(MidiTransport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            return this;
        }

        public Ls9Console open() throws IOException {
            MidiTransport t = transport;
            if (t == null) {
                t = new EtherMidiTransport(host, tcpPort)
                        .portIndex(portIndex)
                        .deviceId(deviceId)
                        .modeBitmap(modeBitmap)
                        .authData(authData)
                        .connectTimeoutMs(connectTimeoutMs);
            }
            Ls9Console console = new Ls9Console(t, deviceNumber, timeoutMs);
            t.setReceiver(console::handleMidi);
            t.setOnClose(console::handleTransportClosed);
            t.open();
            return console;
        }
    }

    // ------------------------------------------------------------------
    // Low-level parameter access
    // ------------------------------------------------------------------

    /** Sends a PARAMETER CHANGE with an unsigned value. */
    public void set(ParameterAddress address, long value) throws IOException {
        ensureOpen();
        transport.sendSysex(Ls9Sysex.parameterChange(deviceNumber, address, value));
        lastValues.put(address, value);
    }

    /** Sends a PARAMETER CHANGE, encoding negative values as two's complement. */
    public void setSigned(ParameterAddress address, long value) throws IOException {
        ensureOpen();
        transport.sendSysex(Ls9Sysex.parameterChange(deviceNumber, address, value, true));
        lastValues.put(address, value);
    }

    /** Sends an arbitrary complete SysEx message. */
    public void sendSysex(byte[] message) throws IOException {
        ensureOpen();
        transport.sendSysex(message);
    }

    /** Sends an arbitrary short MIDI message (1..3 bytes). */
    public void sendShortMidi(byte[] message) throws IOException {
        ensureOpen();
        transport.sendShortMidi(message);
    }

    /**
     * Sends a PARAMETER REQUEST and waits for the matching PARAMETER CHANGE.
     *
     * @throws Ls9Exception if the console does not answer within the timeout
     */
    public long get(ParameterAddress address) throws IOException {
        ensureOpen();
        CompletableFuture<Long> future = new CompletableFuture<>();
        pending.put(address, future);
        try {
            transport.sendSysex(Ls9Sysex.parameterRequest(deviceNumber, address));
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new Ls9Exception("no reply from console for parameter " + address.hex()
                    + " within " + timeoutMs + " ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Ls9Exception("interrupted while waiting for parameter " + address.hex(), e);
        } catch (ExecutionException e) {
            throw new Ls9Exception("parameter request failed for " + address.hex(), e.getCause());
        } finally {
            pending.remove(address, future);
        }
    }

    /** Last value seen for this address, if any. */
    public Optional<Long> lastValue(ParameterAddress address) {
        return Optional.ofNullable(lastValues.get(address));
    }

    // ------------------------------------------------------------------
    // Bulk dump transfer
    // ------------------------------------------------------------------

    /** Requests one bulk-dump block and waits for the matching response. */
    public BulkDump requestBulkDump(String module, int number) throws IOException {
        ensureOpen();
        String key = bulkKey(module, number);
        CompletableFuture<BulkDump> future = new CompletableFuture<>();
        pendingBulk.put(key, future);
        try {
            transport.sendSysex(BulkDumpCodec.request(deviceNumber, module, number));
            int wait = Math.max(timeoutMs, 5000);
            return future.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new Ls9Exception("no bulk dump for " + Text.trimTrailing(module)
                    + " #" + number + " within " + Math.max(timeoutMs, 5000) + " ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Ls9Exception("interrupted while waiting for bulk dump", e);
        } catch (ExecutionException e) {
            throw new Ls9Exception("bulk dump request failed", e.getCause());
        } finally {
            pendingBulk.remove(key, future);
        }
    }

    /** Sends a bulk-dump block built from logical 8-bit data. */
    public void sendBulkDump(String module, int number, byte[] data) throws IOException {
        ensureOpen();
        transport.sendSysex(BulkDumpCodec.encode(deviceNumber, module, number, data));
    }

    /** Sends a previously received block back to the console. */
    public void sendBulkDump(BulkDump dump) throws IOException {
        ensureOpen();
        transport.sendSysex(BulkDumpCodec.encode(deviceNumber, dump.module(), dump.number(), dump.data()));
    }

    private static String bulkKey(String module, int number) {
        return module + "\u0000" + number;
    }

    // ------------------------------------------------------------------
    // Function-call requests (library attribute, exist range, ...)
    // ------------------------------------------------------------------

    /** Sends a function-call request and waits for the matching response. */
    public Ls9SysexMessage requestFunction(byte[] request, String function,
                                           String module, int number) throws IOException {
        ensureOpen();
        String key = functionKey(function, module, number);
        CompletableFuture<Ls9SysexMessage> future = new CompletableFuture<>();
        pendingFunction.put(key, future);
        try {
            transport.sendSysex(request);
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new Ls9Exception("no reply for " + Text.trimTrailing(function)
                    + " " + Text.trimTrailing(module) + " #" + number
                    + " within " + timeoutMs + " ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Ls9Exception("interrupted while waiting for function reply", e);
        } catch (ExecutionException e) {
            throw new Ls9Exception("function request failed", e.getCause());
        } finally {
            pendingFunction.remove(key, future);
        }
    }

    private static String functionKey(String function, String module, int number) {
        return function + "\u0000" + module + "\u0000" + number;
    }

    // ------------------------------------------------------------------
    // Level meters
    // ------------------------------------------------------------------

    /**
     * Subscribes to a meter range. The console then sends responses through
     * {@link Ls9Listener#onMeter(MeterFrame)} roughly every 50 ms.
     */
    public void subscribeMeters(MeterGroup group, int point, int start, int count) throws IOException {
        ensureOpen();
        transport.sendSysex(MeterCodec.request(deviceNumber, group, point, start, count));
    }

    public void subscribeMeters(int ul, int lu, int ll, int count) throws IOException {
        ensureOpen();
        transport.sendSysex(MeterCodec.request(deviceNumber, ul, lu, ll, count));
    }

    /** Stops all meter transmission. */
    public void stopMeters() throws IOException {
        ensureOpen();
        transport.sendSysex(MeterCodec.stop(deviceNumber));
    }

    public Ls9FaderTable faderTable() {
        return faders;
    }

    public int deviceNumber() {
        return deviceNumber;
    }

    public MidiTransport transport() {
        return transport;
    }

    public void addListener(Ls9Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Ls9Listener listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------
    // High-level mixer objects
    // ------------------------------------------------------------------

    /** INPUT channel, 1..64. */
    public Ls9InputChannel input(int channel) {
        if (channel < 1 || channel > 64) {
            throw new IllegalArgumentException("INPUT channel must be 1..64: " + channel);
        }
        return new Ls9InputChannel(this, channel - 1);
    }

    /** Stereo input channel, 1..4; {@code right} selects the R side. */
    public Ls9InputChannel stereoInput(int channel, boolean right) {
        if (channel < 1 || channel > 4) {
            throw new IllegalArgumentException("ST IN channel must be 1..4: " + channel);
        }
        return new Ls9InputChannel(this, 64 + (channel - 1) * 2 + (right ? 1 : 0));
    }

    /** MIX channel, 1..16. */
    public Ls9OutputChannel mix(int channel) {
        if (channel < 1 || channel > 16) {
            throw new IllegalArgumentException("MIX channel must be 1..16: " + channel);
        }
        return Ls9OutputChannel.mix(this, channel - 1);
    }

    /** MATRIX channel, 1..8. */
    public Ls9OutputChannel matrix(int channel) {
        if (channel < 1 || channel > 8) {
            throw new IllegalArgumentException("MATRIX channel must be 1..8: " + channel);
        }
        return Ls9OutputChannel.matrix(this, channel - 1);
    }

    /** STEREO L (side 0), STEREO R (side 1) or MONO/C (side 2). */
    public Ls9OutputChannel stereo(int side) {
        if (side < 0 || side > 2) {
            throw new IllegalArgumentException("stereo side must be 0=L, 1=R, 2=C");
        }
        return Ls9OutputChannel.stereo(this, side);
    }

    public Ls9OutputChannel stereoLeft() {
        return stereo(0);
    }

    public Ls9OutputChannel stereoRight() {
        return stereo(1);
    }

    public Ls9OutputChannel mono() {
        return stereo(2);
    }

    /** Scene memory operations. */
    public Ls9SceneControl scene() {
        return new Ls9SceneControl(this);
    }

    /** Whole-console snapshot and library bulk transfer operations. */
    public io.github.leocb.ls9.bulk.BulkDumpTransfer bulk() {
        return new io.github.leocb.ls9.bulk.BulkDumpTransfer(this);
    }

    /** Library store/recall/edit/attribute operations. */
    public Ls9Library library() {
        return new Ls9Library(this);
    }

    /** Binds any generated parameter specification to a channel. */
    public Ls9Parameter parameter(io.github.leocb.ls9.sysex.ParameterSpec spec, int channel) {
        if (channel < 0 || (spec.maxChannel() > 0 && channel > spec.maxChannel())) {
            throw new IllegalArgumentException("channel " + channel + " out of range for " + spec);
        }
        return new Ls9Parameter(this, spec, channel);
    }

    /** Looks a parameter up by Yamaha's element and index names. */
    public Ls9Parameter parameter(String element, String indexName, int channel) {
        var def = io.github.leocb.ls9.sysex.Ls9ParameterRegistry.get()
                .find(element, indexName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown parameter " + element + "." + indexName));
        var spec = new io.github.leocb.ls9.sysex.ParameterSpec(def.element(), def.indexName(),
                def.elementHex(), def.indexHex(), def.min(), def.max(), def.defaultValue(),
                def.maxChannel(), def.comment(), def.valueTable());
        return parameter(spec, channel);
    }

    /** Studio-Manager-style full/session synchronization. */
    public Ls9Sync sync() {
        return new Ls9Sync(this);
    }

    /** MIDI channel messages and MMC using the console's MIDI channel. */
    public Ls9MidiControl midi() {
        return new Ls9MidiControl(this, deviceNumber);
    }

    public Ls9MidiControl midi(int channel) {
        if (channel < 0 || channel > 15) {
            throw new IllegalArgumentException("MIDI channel must be 0..15");
        }
        return new Ls9MidiControl(this, channel);
    }

    /**
     * Sends one read request (INPUT 1 fader) to verify that the session is
     * actually routed to the console. Useful during bring-up.
     */
    public boolean probe() {
        try {
            input(1).faderRaw();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Reads the console MAC address through {@code kSetupNetwork}. Useful for
     * a subsequent authenticated direct connection.
     */
    public byte[] readMacAddress() throws IOException {
        byte[] mac = new byte[6];
        for (int i = 0; i < 6; i++) {
            mac[i] = (byte) get(ParameterAddress.of(Ls9Elements.SETUP_NETWORK,
                    Ls9Elements.IDX_MAC_0 + i, 0));
        }
        return mac;
    }

    /** DCA mute master, 1..8. */
    public void setMuteMaster(int dca, boolean on) throws IOException {
        if (dca < 1 || dca > 8) {
            throw new IllegalArgumentException("DCA mute master must be 1..8: " + dca);
        }
        set(ParameterAddress.of(Ls9Elements.SCENE_MUTE_MASTER, Ls9Elements.IDX_MUTE_MASTER, dca - 1),
                on ? 1 : 0);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            transport.close();
        } finally {
            notifyDisconnected();
        }
    }

    private void handleTransportClosed() {
        notifyDisconnected();
    }

    private void notifyDisconnected() {
        if (!disconnectNotified.compareAndSet(false, true)) {
            return;
        }
        for (Ls9Listener l : listeners) {
            try {
                l.onDisconnected();
            } catch (RuntimeException ignored) {
                // listener errors must not prevent shutdown
            }
        }
    }

    private void handleMidi(byte[] message) {
        MeterFrame meter = null;
        try {
            meter = MeterCodec.parse(message);
        } catch (RuntimeException ignored) {
            // not a meter frame
        }
        if (meter != null) {
            for (Ls9Listener l : listeners) {
                try {
                    l.onMeter(meter);
                } catch (RuntimeException ignored) {
                    // keep the reader thread alive
                }
            }
            return;
        }

        BulkDump dump;
        try {
            dump = BulkDumpCodec.parse(message);
        } catch (RuntimeException e) {
            dump = null;
        }
        if (dump != null) {
            CompletableFuture<BulkDump> future = pendingBulk.get(bulkKey(dump.module(), dump.number()));
            if (future != null) {
                future.complete(dump);
            }
            for (Ls9Listener l : listeners) {
                try {
                    l.onBulkDump(dump);
                } catch (RuntimeException ignored) {
                    // keep the reader thread alive
                }
            }
            return;
        }

        Ls9SysexMessage parsed;
        try {
            parsed = Ls9Sysex.parse(message);
        } catch (RuntimeException e) {
            return;
        }
        if (parsed == null) {
            return;
        }
        if (parsed.isParameterChange()) {
            ParameterAddress address = parsed.address();
            long value = parsed.value() == null ? 0 : parsed.value();
            lastValues.put(address, value);
            CompletableFuture<Long> future = pending.get(address);
            if (future != null) {
                future.complete(value);
            }
            Ls9ParameterEvent event = new Ls9ParameterEvent(address, value, parsed.raw());
            for (Ls9Listener l : listeners) {
                try {
                    l.onParameterChange(event);
                } catch (RuntimeException ignored) {
                    // keep the reader thread alive
                }
            }
        } else if (parsed.isFunctionCall()) {
            // Ignore echoes of our own 0x3n requests; only 0x1n replies
            // complete a pending request.
            byte[] raw = parsed.raw();
            boolean isReply = raw.length > 2 && (raw[2] & 0xF0) == Ls9Sysex.SUB_PARAMETER_CHANGE;
            if (isReply) {
                CompletableFuture<Ls9SysexMessage> future = pendingFunction.get(
                        functionKey(parsed.function(), parsed.module(), parsed.number()));
                if (future != null) {
                    future.complete(parsed);
                }
            }
            for (Ls9Listener l : listeners) {
                try {
                    l.onFunctionCall(parsed);
                } catch (RuntimeException ignored) {
                    // keep the reader thread alive
                }
            }
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("console connection is closed");
        }
    }
}
