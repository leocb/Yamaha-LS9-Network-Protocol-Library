package io.github.leocb.ls9.transport;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Direct Ethernet transport to a Yamaha LS9.
 *
 * <p>The LS9 speaks Yamaha's proprietary "EtherMIDI" protocol on TCP port
 * 12300. This class implements the connection handshake and packet framing
 * reverse-engineered from Yamaha's Network-MIDI Driver 2.0.0
 * ({@code EtherMIDIDriver-2.0.0f4}); the MIDI payload is the standard Yamaha
 * LS9 System Exclusive protocol.
 *
 * <p>Typical use:
 * <pre>{@code
 * EtherMidiTransport t = new EtherMidiTransport("192.168.0.128");
 * t.setAuthData("00:1A:2B:3C:4D:5E"); // only needed if the console challenges
 * t.open();
 * t.sendSysex(sysexBytes);
 * }</pre>
 */
public final class EtherMidiTransport implements MidiTransport {

    public static final int DEFAULT_TCP_PORT = 12300;
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;

    private static final Logger LOG = Logger.getLogger(EtherMidiTransport.class.getName());

    private final String host;
    private final int tcpPort;

    private int portIndex = 0;      // header[6]: 0-based "Port1" == 0
    private int deviceId = 1;       // header[7]: "Device Port No.", 1 for LS9
    private int modeBitmap = 0;     // header[12..15] in the open request
    private byte[] authData;        // six-byte MAC, only used if challenged
    private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private boolean autoReplyToOpen = true;

    private volatile Socket socket;
    private volatile InputStream input;
    private volatile OutputStream output;
    private volatile Thread reader;
    private volatile boolean running;
    private volatile Consumer<byte[]> receiver;
    private volatile Runnable onClose;
    private final Object writeLock = new Object();

    public EtherMidiTransport(String host) {
        this(host, DEFAULT_TCP_PORT);
    }

    public EtherMidiTransport(String host, int tcpPort) {
        this.host = Objects.requireNonNull(host, "host");
        this.tcpPort = tcpPort;
    }

    public EtherMidiTransport portIndex(int portIndex) {
        this.portIndex = portIndex;
        return this;
    }

    public EtherMidiTransport deviceId(int deviceId) {
        this.deviceId = deviceId;
        return this;
    }

    public EtherMidiTransport modeBitmap(int modeBitmap) {
        this.modeBitmap = modeBitmap;
        return this;
    }

    public EtherMidiTransport authData(byte[] mac) {
        if (mac != null && mac.length != 6) {
            throw new IllegalArgumentException("authData/MAC must be 6 bytes");
        }
        this.authData = mac == null ? null : Arrays.copyOf(mac, mac.length);
        return this;
    }

    public EtherMidiTransport authMac(String mac) {
        return authData(YemAuth.parseMac(mac));
    }

    public EtherMidiTransport connectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        return this;
    }

    public EtherMidiTransport autoReplyToOpen(boolean autoReplyToOpen) {
        this.autoReplyToOpen = autoReplyToOpen;
        return this;
    }

    public String host() {
        return host;
    }

    public int tcpPort() {
        return tcpPort;
    }

    public boolean isOpen() {
        return running;
    }

    @Override
    public void setReceiver(Consumer<byte[]> receiver) {
        this.receiver = receiver;
    }

    @Override
    public void setOnClose(Runnable callback) {
        this.onClose = callback;
    }

    @Override
    public void open() throws IOException {
        if (running) {
            throw new IllegalStateException("transport already open");
        }
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, tcpPort), connectTimeoutMs);
        s.setSoTimeout(connectTimeoutMs);
        this.socket = s;
        this.input = new BufferedInputStream(s.getInputStream());
        this.output = new BufferedOutputStream(s.getOutputStream());

        try {
            handshake();
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }

        s.setSoTimeout(0);
        running = true;
        Thread t = new Thread(this::readLoop, "ls9-ethermidi-reader");
        t.setDaemon(true);
        this.reader = t;
        t.start();
    }

    private void handshake() throws IOException {
        byte[] request = EtherMidiPacket.openRequest(portIndex, deviceId, modeBitmap);
        writeFully(request);
        byte[] reply = readPacket();
        if (reply.length < EtherMidiPacket.HEADER_SIZE) {
            throw new IOException("short open acknowledgement from console");
        }
        int command = reply[4] & 0xFF;
        if (command != EtherMidiPacket.CMD_OPEN_ACK) {
            throw new IOException(String.format(
                    "unexpected handshake reply 0x%02X (expected 0x21): %s",
                    command, EtherMidiPacket.hex(reply)));
        }
        int capabilities = EtherMidiPacket.getInt(reply, 12);
        LOG.log(Level.FINE, "EtherMIDI open acknowledged, capabilities=0x{0}",
                String.format("%08X", capabilities));

        if ((capabilities & 0x80000000) != 0) {
            if (authData == null) {
                throw new IOException(
                        "console requested MAC authentication but no authData/MAC was configured. "
                                + "Pass the console MAC address, e.g. authMac(\"00:1A:2B:3C:4D:5E\")");
            }
            int checksum = YemAuth.checksum(reply[10] & 0xFF, authData);
            byte[] auth = EtherMidiPacket.authRequest(portIndex, deviceId, checksum);
            writeFully(auth);
            byte[] authReply = readPacket();
            int authCommand = authReply[4] & 0xFF;
            if (authCommand != EtherMidiPacket.CMD_AUTH_ACK
                    && authCommand != EtherMidiPacket.CMD_OPEN_ACK) {
                throw new IOException(String.format(
                        "authentication rejected, reply 0x%02X: %s",
                        authCommand, EtherMidiPacket.hex(authReply)));
            }
            LOG.log(Level.FINE, "EtherMIDI authentication accepted");
        }
    }

    @Override
    public void sendSysex(byte[] message) throws IOException {
        ensureOpen();
        writePacket(EtherMidiPacket.sysex(portIndex, deviceId, message));
    }

    @Override
    public void sendShortMidi(byte[] message) throws IOException {
        ensureOpen();
        writePacket(EtherMidiPacket.shortMidi(portIndex, deviceId, message));
    }

    private void writePacket(byte[] packet) throws IOException {
        synchronized (writeLock) {
            OutputStream out = output;
            if (out == null) {
                throw new IOException("transport is closed");
            }
            out.write(packet);
            out.flush();
        }
    }

    private void writeFully(byte[] packet) throws IOException {
        synchronized (writeLock) {
            OutputStream out = output;
            if (out == null) {
                throw new IOException("transport is closed");
            }
            out.write(packet);
            out.flush();
        }
    }

    private void readLoop() {
        try {
            while (running) {
                byte[] packet;
                try {
                    packet = readPacket();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                handlePacket(packet);
            }
        } catch (EOFException | SocketException e) {
            if (running) {
                LOG.log(Level.FINE, "EtherMIDI connection closed: {0}", e.toString());
            }
        } catch (IOException e) {
            if (running) {
                LOG.log(Level.WARNING, "EtherMIDI read failed", e);
            }
        } finally {
            running = false;
            Runnable cb = onClose;
            if (cb != null) {
                try {
                    cb.run();
                } catch (RuntimeException ignored) {
                    // never let a callback kill the reader thread
                }
            }
        }
    }

    private void handlePacket(byte[] packet) {
        EtherMidiPacket.Frame frame;
        try {
            frame = EtherMidiPacket.decode(packet);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "discarding malformed EtherMIDI packet: " + EtherMidiPacket.hex(packet), e);
            return;
        }
        switch (frame.command()) {
            case EtherMidiPacket.CMD_SYSEX, EtherMidiPacket.CMD_MIDI_1,
                    EtherMidiPacket.CMD_MIDI_2, EtherMidiPacket.CMD_MIDI_3 -> {
                Consumer<byte[]> r = receiver;
                if (r != null) {
                    r.accept(frame.payload());
                }
            }
            case EtherMidiPacket.CMD_OPEN -> {
                if (autoReplyToOpen) {
                    replyToOpen(packet);
                }
            }
            case EtherMidiPacket.CMD_CLOSE -> {
                LOG.log(Level.FINE, "console closed the EtherMIDI session");
                running = false;
                closeQuietly(socket);
            }
            case EtherMidiPacket.CMD_RESET -> LOG.log(Level.FINE, "console requested an EtherMIDI reset");
            default -> LOG.log(Level.FINE, "ignoring EtherMIDI command 0x{0}",
                    String.format("%02X", frame.command()));
        }
    }

    private void replyToOpen(byte[] request) {
        try {
            byte[] reply = Arrays.copyOf(request, EtherMidiPacket.HEADER_SIZE);
            reply[4] = (byte) EtherMidiPacket.CMD_OPEN_ACK;
            reply[8] = 1;
            reply[9] = 0;
            EtherMidiPacket.putInt(reply, 12,
                    EtherMidiPacket.getInt(reply, 12) & 0x03000020);
            writePacket(reply);
        } catch (IOException e) {
            LOG.log(Level.FINE, "could not reply to console open request", e);
        }
    }

    private byte[] readPacket() throws IOException {
        byte[] header = new byte[4];
        readFully(header, 0, 4);
        int length = EtherMidiPacket.getInt(header, 0);
        if (length < EtherMidiPacket.HEADER_SIZE || length > EtherMidiPacket.MAX_PACKET_SIZE) {
            throw new IOException("invalid EtherMIDI packet length " + length);
        }
        byte[] packet = new byte[length];
        System.arraycopy(header, 0, packet, 0, 4);
        readFully(packet, 4, length - 4);
        return packet;
    }

    private void readFully(byte[] buffer, int offset, int length) throws IOException {
        InputStream in = input;
        if (in == null) {
            throw new IOException("transport is closed");
        }
        while (length > 0) {
            int n = in.read(buffer, offset, length);
            if (n < 0) {
                throw new EOFException("connection closed while reading EtherMIDI packet");
            }
            offset += n;
            length -= n;
        }
    }

    private void ensureOpen() throws IOException {
        if (!running || socket == null || socket.isClosed()) {
            throw new IOException("transport is not open");
        }
    }

    @Override
    public void close() throws IOException {
        boolean wasRunning = running;
        running = false;
        Socket s = socket;
        if (s != null && !s.isClosed()) {
            if (wasRunning) {
                try {
                    writePacket(EtherMidiPacket.closeRequest(portIndex, deviceId));
                } catch (IOException ignored) {
                    // best effort
                }
            }
            closeQuietly(s);
        }
        Thread t = reader;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        input = null;
        output = null;
        socket = null;
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
