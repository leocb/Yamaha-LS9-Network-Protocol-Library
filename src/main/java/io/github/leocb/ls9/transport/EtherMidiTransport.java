package io.github.leocb.ls9.transport;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
    private int deviceId = 0;       // header[7]: StageMix opens with 0
    private int modeBitmap = 3;     // header[12..15]; StageMix uses bits 0+1
    private int sessionPort;        // port/rx adopted from the console's 0x21 reply
    private int sessionRx;
    private byte[] authData;        // six-byte MAC, only used if challenged
    private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private boolean autoReplyToOpen = true;
    private boolean reverseListen = true;
    private int listenPort = DEFAULT_TCP_PORT;

    private volatile Socket socket;
    private volatile InputStream input;
    private volatile OutputStream output;
    private volatile Thread reader;
    private volatile boolean running;
    private volatile Consumer<byte[]> receiver;
    private volatile Runnable onClose;
    private volatile ServerSocket listener;
    private volatile Thread acceptThread;
    private final java.util.List<Socket> reverseSockets =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
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

    /**
     * Listens on {@link #listenPort} for the console's reverse connection.
     * The console sends parameter-change replies and meter data on that
     * connection, not on the outbound socket.
     */
    public EtherMidiTransport reverseListen(boolean reverseListen) {
        this.reverseListen = reverseListen;
        return this;
    }

    public EtherMidiTransport listenPort(int listenPort) {
        if (listenPort < 0 || listenPort > 65535) {
            throw new IllegalArgumentException("listenPort must be 0..65535");
        }
        this.listenPort = listenPort;
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
        try {
            s.connect(new InetSocketAddress(host, tcpPort), connectTimeoutMs);
            s.setSoTimeout(connectTimeoutMs);
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }

        ServerSocket ls = null;
        if (reverseListen) {
            ls = new ServerSocket();
            ls.setReuseAddress(true);
            // The console always dials back to port 12300 on the address it
            // saw as the client. Binding the local address of this socket
            // (rather than every interface) lets a virtual console share the
            // machine on another loopback address, and is what the console
            // targets anyway.
            InetAddress local = s.getLocalAddress();
            try {
                ls.bind(new InetSocketAddress(local, listenPort));
            } catch (IOException e) {
                closeQuietly(ls);
                closeQuietly(s);
                throw new IOException("cannot listen on TCP " + listenPort
                        + " for the console's reply connection; close any other LS9 client: "
                        + e.getMessage(), e);
            }
            this.listener = ls;
        }
        try {
            this.socket = s;
            this.input = new BufferedInputStream(s.getInputStream());
            this.output = new BufferedOutputStream(s.getOutputStream());
            this.sessionPort = portIndex;
            this.sessionRx = deviceId;
            handshake();
        } catch (IOException e) {
            closeQuietly(s);
            closeQuietly(ls);
            this.listener = null;
            throw e;
        }

        s.setSoTimeout(0);
        running = true;
        Thread t = new Thread(this::readLoop, "ls9-ethermidi-reader");
        t.setDaemon(true);
        this.reader = t;
        t.start();
        if (ls != null) {
            Thread at = new Thread(this::acceptLoop, "ls9-ethermidi-accept");
            at.setDaemon(true);
            this.acceptThread = at;
            at.start();
        }
    }

    private void acceptLoop() {
        ServerSocket ls = listener;
        while (running && ls != null && !ls.isClosed()) {
            try {
                Socket r = ls.accept();
                Socket outbound = socket;
                if (outbound != null && outbound.getInetAddress() != null
                        && !outbound.getInetAddress().equals(r.getInetAddress())) {
                    LOG.log(Level.FINE, "ignoring reverse connection from {0}", r.getInetAddress());
                    closeQuietly(r);
                    continue;
                }
                r.setTcpNoDelay(true);
                r.setSoTimeout(0);
                reverseSockets.add(r);
                Thread rt = new Thread(() -> reverseReadLoop(r), "ls9-ethermidi-reverse");
                rt.setDaemon(true);
                rt.start();
                LOG.log(Level.FINE, "accepted console reply connection from {0}", r.getInetAddress());
            } catch (IOException e) {
                if (running) {
                    LOG.log(Level.FINE, "EtherMIDI accept loop ended: {0}", e.toString());
                }
                break;
            }
        }
    }

    private void reverseReadLoop(Socket r) {
        try {
            // Wait for the first packet with the request timeout: the console
            // can open the reverse connection well before it sends the reply,
            // especially while meter streams are active. After the first
            // packet, use a short idle timeout to drain any burst and close.
            r.setSoTimeout(2500);
            InputStream in = new BufferedInputStream(r.getInputStream());
            OutputStream out = new BufferedOutputStream(r.getOutputStream());
            while (running) {
                byte[] packet;
                try {
                    packet = readPacket(in);
                } catch (SocketTimeoutException e) {
                    break;
                }
                handlePacket(packet, out);
                // The console opens a reverse connection per meter frame and
                // waits for the client to close it before sending the next
                // frame. Waiting for an idle timeout here throttles meters to
                // about two frames per second.
                if (isMeterPacket(packet)) {
                    break;
                }
                r.setSoTimeout(40);
            }
        } catch (EOFException | SocketException e) {
            LOG.log(Level.FINE, "console reply connection closed: {0}", e.toString());
        } catch (IOException e) {
            if (running) {
                LOG.log(Level.FINE, "console reply connection ended: {0}", e.toString());
            }
        } finally {
            reverseSockets.remove(r);
            closeQuietly(r);
        }
    }

    /** LEVEL METER frames are SysEx category 0x21. */
    private static boolean isMeterPacket(byte[] packet) {
        try {
            byte[] payload = EtherMidiPacket.decode(packet).payload();
            return payload.length > 5
                    && (payload[0] & 0xFF) == 0xF0
                    && (payload[1] & 0xFF) == 0x43
                    && (payload[5] & 0xFF) == 0x21;
        } catch (RuntimeException e) {
            return false;
        }
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
        // StageMix uses the port and rx fields echoed in the open reply for the
        // rest of the session; the open request itself uses the configured
        // portIndex/deviceId (0/0).
        this.sessionPort = reply[6] & 0xFF;
        this.sessionRx = reply[7] & 0xFF;
        LOG.log(Level.FINE, "EtherMIDI open acknowledged, capabilities=0x{0}, port={1}, rx={2}",
                new Object[]{String.format("%08X", capabilities), sessionPort, sessionRx});

        if ((capabilities & 0x80000000) != 0) {
            if (authData == null) {
                throw new IOException(
                        "console requested MAC authentication but no authData/MAC was configured. "
                                + "Pass the console MAC address, e.g. authMac(\"00:1A:2B:3C:4D:5E\")");
            }
            int seedWord = ((reply[10] & 0xFF) << 8) | (reply[11] & 0xFF);
            int checksum = YemAuth.checksum(seedWord, authData);
            byte[] auth = EtherMidiPacket.authRequest(sessionPort, sessionRx, checksum);
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
        writePacket(EtherMidiPacket.sysex(sessionPort, sessionRx, message));
    }

    @Override
    public void sendShortMidi(byte[] message) throws IOException {
        ensureOpen();
        writePacket(EtherMidiPacket.shortMidi(sessionPort, sessionRx, message));
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
            ServerSocket ls = listener;
            listener = null;
            closeQuietly(ls);
            closeReverseSockets();
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
        handlePacket(packet, null);
    }

    private void handlePacket(byte[] packet, OutputStream replyOut) {
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
                    replyToOpen(packet, replyOut);
                }
            }
            case EtherMidiPacket.CMD_CLOSE -> {
                LOG.log(Level.FINE, "console closed the EtherMIDI session");
                running = false;
                closeQuietly(socket);
                closeReverseSockets();
            }
            case EtherMidiPacket.CMD_RESET -> LOG.log(Level.FINE, "console requested an EtherMIDI reset");
            default -> LOG.log(Level.FINE, "ignoring EtherMIDI command 0x{0}",
                    String.format("%02X", frame.command()));
        }
    }

    private void replyToOpen(byte[] request, OutputStream replyOut) {
        try {
            byte[] reply = Arrays.copyOf(request, EtherMidiPacket.HEADER_SIZE);
            reply[4] = (byte) EtherMidiPacket.CMD_OPEN_ACK;
            reply[8] = 1;
            reply[9] = 0;
            EtherMidiPacket.putInt(reply, 12,
                    EtherMidiPacket.getInt(reply, 12) & 0x03000020);
            if (replyOut != null) {
                synchronized (replyOut) {
                    replyOut.write(reply);
                    replyOut.flush();
                }
            } else {
                writePacket(reply);
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "could not reply to console open request", e);
        }
    }

    private byte[] readPacket() throws IOException {
        return readPacket(input);
    }

    private byte[] readPacket(InputStream in) throws IOException {
        byte[] header = new byte[4];
        readFully(in, header, 0, 4);
        int length = EtherMidiPacket.getInt(header, 0);
        if (length < EtherMidiPacket.HEADER_SIZE || length > EtherMidiPacket.MAX_PACKET_SIZE) {
            throw new IOException("invalid EtherMIDI packet length " + length);
        }
        byte[] packet = new byte[length];
        System.arraycopy(header, 0, packet, 0, 4);
        readFully(in, packet, 4, length - 4);
        return packet;
    }

    private void readFully(byte[] buffer, int offset, int length) throws IOException {
        readFully(input, buffer, offset, length);
    }

    private void readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
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
        ServerSocket ls = listener;
        listener = null;
        if (ls != null) {
            try {
                ls.close();
            } catch (IOException ignored) {
            }
        }
        closeReverseSockets();
        Socket s = socket;
        if (s != null && !s.isClosed()) {
            if (wasRunning) {
                try {
                    writePacket(EtherMidiPacket.closeRequest(sessionPort, sessionRx));
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
        Thread at = acceptThread;
        if (at != null && at != Thread.currentThread()) {
            try {
                at.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        input = null;
        output = null;
        socket = null;
    }

    private void closeReverseSockets() {
        synchronized (reverseSockets) {
            for (Socket r : reverseSockets) {
                closeQuietly(r);
            }
            reverseSockets.clear();
        }
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

    private static void closeQuietly(ServerSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
