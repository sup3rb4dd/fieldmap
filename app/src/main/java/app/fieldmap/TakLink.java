package app.fieldmap;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.fieldmap.core.Cot;

/**
 * Talks to ATAK / WinTAK / iTAK. Two ways, usable together:
 *  - local network: the standard multicast groups TAK devices use on shared Wi-Fi
 *  - a TAK server over plain TCP (FreeTAKServer / OpenTAKServer style, no certificates)
 */
public class TakLink {

    public interface Callback {
        void onTakState(RadioLink.State state, String detail);
        void onTakEvent(Cot.Event e);
    }

    private static final String SA_GROUP = "239.2.3.1", CHAT_GROUP = "224.10.10.1";
    private static final int SA_PORT = 6969, CHAT_PORT = 17012;

    private final Context ctx;
    private final Callback cb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService tx;
    private volatile boolean running = false, lan = false, lanUp = false, serverUp = false;
    private volatile String host = "";
    private volatile int port = 8087;
    private volatile MulticastSocket saSock, chatSock;
    private volatile Socket server;
    private volatile OutputStream serverOut;
    private WifiManager.MulticastLock mcLock;
    private volatile RadioLink.State state = RadioLink.State.OFF;
    private volatile String pingXml = "";
    public volatile int rxCount = 0, txCount = 0;
    private int generation = 0;

    public TakLink(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
    }

    public RadioLink.State getState() { return state; }
    public boolean isConnected() { return state == RadioLink.State.CONNECTED; }
    public boolean lanUp() { return lanUp; }
    public boolean serverUp() { return serverUp; }
    public String describe() {
        return (lan ? "Wi-Fi " + (lanUp ? "▲" : "…") : "") + (lan && !host.isEmpty() ? "  " : "")
                + (host.isEmpty() ? "" : host + ":" + port + " " + (serverUp ? "▲" : "…"));
    }

    /** (Re)starts with these settings. Empty host = no server. Neither = off. */
    public synchronized void configure(boolean useLan, String serverHost, int serverPort, String myUid) {
        stop();
        if (!useLan && (serverHost == null || serverHost.trim().isEmpty())) return;
        lan = useLan;
        host = serverHost == null ? "" : serverHost.trim();
        port = serverPort;
        pingXml = Cot.ping(myUid);
        running = true;
        final int gen = ++generation;
        tx = Executors.newSingleThreadExecutor();
        if (lan) {
            try {
                WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    mcLock = wm.createMulticastLock("FieldMap:tak");
                    mcLock.setReferenceCounted(false);
                    mcLock.acquire();
                }
            } catch (Exception ignored) {
            }
            new Thread(() -> lanLoop(gen, SA_GROUP, SA_PORT, true), "tak-sa").start();
            new Thread(() -> lanLoop(gen, CHAT_GROUP, CHAT_PORT, false), "tak-chat").start();
        }
        if (!host.isEmpty()) new Thread(() -> serverLoop(gen), "tak-server").start();
        update();
    }

    public synchronized void stop() {
        running = false;
        generation++;
        close(saSock);
        close(chatSock);
        saSock = chatSock = null;
        Socket s = server;
        server = null;
        serverOut = null;
        if (s != null) try { s.close(); } catch (Exception ignored) { }
        if (tx != null) tx.shutdownNow();
        tx = null;
        if (mcLock != null) try { mcLock.release(); } catch (Exception ignored) { }
        mcLock = null;
        lan = false;
        lanUp = serverUp = false;
        host = "";
        update();
    }

    private static void close(MulticastSocket s) {
        if (s != null) try { s.close(); } catch (Exception ignored) { }
    }

    private boolean alive(int gen) { return running && gen == generation; }

    private void update() {
        RadioLink.State s = !running ? RadioLink.State.OFF
                : (lanUp || serverUp) ? RadioLink.State.CONNECTED : RadioLink.State.RETRYING;
        boolean changed = s != state;
        state = s;
        final String d = describe();
        if (changed) main.post(() -> cb.onTakState(s, d));
    }

    private static NetworkInterface wifi() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isUp() && !ni.isLoopback() && ni.supportsMulticast() && ni.getName().startsWith("wlan")) return ni;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---------- local network ----------

    private void lanLoop(int gen, String group, int port, boolean sa) {
        byte[] buf = new byte[65535];
        while (alive(gen)) {
            MulticastSocket sock = null;
            try {
                NetworkInterface ni = wifi();
                if (ni == null) throw new java.io.IOException("no Wi-Fi");
                // unbound first so the port can be shared with ATAK running on the same phone
                sock = new MulticastSocket((java.net.SocketAddress) null);
                sock.setReuseAddress(true);
                sock.bind(new InetSocketAddress(port));
                sock.setNetworkInterface(ni);
                sock.setTimeToLive(8);
                sock.setLoopbackMode(true); // true = do not hear my own packets
                sock.joinGroup(new InetSocketAddress(InetAddress.getByName(group), port), ni);
                if (sa) { saSock = sock; lanUp = true; update(); } else chatSock = sock;
                while (alive(gen)) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    final Cot.Event e = Cot.parseDatagram(p.getData(), p.getLength());
                    rxCount++;
                    if (e != null) main.post(() -> cb.onTakEvent(e));
                }
            } catch (Exception e) {
                // Wi-Fi off, or the socket was closed by stop(): fall through and maybe retry
            } finally {
                close(sock);
                if (sa) { if (saSock == sock) saSock = null; if (lanUp) { lanUp = false; update(); } }
                else if (chatSock == sock) chatSock = null;
            }
            if (!pause(gen, 10_000)) return;
        }
    }

    // ---------- server ----------

    private void serverLoop(int gen) {
        int attempt = 0;
        while (alive(gen)) {
            Socket s = null;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress(host, port), 15000);
                s.setSoTimeout(25000);
                s.setKeepAlive(true);
                server = s;
                serverOut = s.getOutputStream();
                serverUp = true;
                attempt = 0;
                update();
                InputStream in = s.getInputStream();
                Cot.Splitter split = new Cot.Splitter();
                byte[] buf = new byte[16384];
                while (alive(gen)) {
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (java.net.SocketTimeoutException quiet) {
                        // nothing for 25 s: say hello so the server keeps us, and notice a dead link
                        rawSend(pingXml.getBytes(StandardCharsets.UTF_8));
                        continue;
                    }
                    if (n < 0) break;
                    for (String xml : split.feed(buf, n)) {
                        final Cot.Event e = Cot.parseXml(xml);
                        rxCount++;
                        if (e != null) main.post(() -> cb.onTakEvent(e));
                    }
                }
            } catch (Exception e) {
                // refused, unreachable or dropped
            } finally {
                if (s != null) try { s.close(); } catch (Exception ignored) { }
                if (server == s) { server = null; serverOut = null; }
                if (serverUp) { serverUp = false; update(); }
            }
            attempt++;
            if (!pause(gen, Math.min(60_000, 5000L * attempt))) return;
        }
    }

    private boolean pause(int gen, long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { return false; }
        return alive(gen);
    }

    private void rawSend(byte[] data) throws java.io.IOException {
        OutputStream o = serverOut;
        if (o == null) throw new java.io.IOException("not connected");
        synchronized (this.cb) {
            o.write(data);
            o.flush();
        }
    }

    // ---------- sending ----------

    /** Sends one CoT event on every active path. chat = also use the local chat group. */
    public boolean send(final String xml, final boolean chat) {
        final ExecutorService ex = tx;
        if (!isConnected() || ex == null) return false;
        final byte[] data = xml.getBytes(StandardCharsets.UTF_8);
        try {
            ex.execute(() -> {
                boolean sent = false;
                try {
                    MulticastSocket ms = chat ? chatSock : saSock;
                    if (ms != null) {
                        ms.send(new DatagramPacket(data, data.length,
                                InetAddress.getByName(chat ? CHAT_GROUP : SA_GROUP), chat ? CHAT_PORT : SA_PORT));
                        sent = true;
                    }
                } catch (Exception ignored) {
                }
                try {
                    if (serverOut != null) { rawSend(data); sent = true; }
                } catch (Exception e) {
                    Socket s = server;
                    if (s != null) try { s.close(); } catch (Exception ignored) { }
                }
                if (sent) txCount++;
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return false;
        }
        return true;
    }
}
