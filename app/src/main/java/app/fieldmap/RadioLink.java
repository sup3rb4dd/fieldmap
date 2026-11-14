package app.fieldmap;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import app.fieldmap.core.Kiss;

/**
 * Bluetooth Classic serial (SPP) link to a KISS TNC, such as the BTECH UV-PRO
 * with Menu > General Settings > KISS TNC turned on.
 */
@SuppressLint("MissingPermission")
public class RadioLink {

    public enum State { OFF, CONNECTING, CONNECTED, RETRYING }

    public interface Callback {
        void onRadioState(State state, String detail);
        void onFrame(byte[] ax25);
    }

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Callback cb;
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile boolean wanted = false;
    private volatile State state = State.OFF;
    private BluetoothDevice device;
    private Thread worker;
    public volatile int rxCount = 0, txCount = 0;
    /** Stop retrying after this time; pushed out to "forever" while connected. */
    private volatile long deadline = Long.MAX_VALUE;
    private static final long AFTER_DROP_MS = 15 * 60_000L;

    public RadioLink(Callback cb) {
        this.cb = cb;
    }

    public State getState() { return state; }

    public String deviceName() {
        try {
            return device == null ? "" : (device.getName() == null ? device.getAddress() : device.getName());
        } catch (SecurityException e) {
            return "";
        }
    }

    public void connect(BluetoothDevice d) { connect(d, 2 * 60_000L); }

    /** giveUpMs: how long to keep trying before the first successful connection. */
    public synchronized void connect(BluetoothDevice d, long giveUpMs) {
        disconnect();
        deadline = System.currentTimeMillis() + giveUpMs;
        device = d;
        wanted = true;
        worker = new Thread(this::run, "radio-link");
        worker.start();
    }

    public synchronized void disconnect() {
        wanted = false;
        closeSocket();
        if (worker != null) worker.interrupt();
        worker = null;
        setState(State.OFF, "");
    }

    public boolean isConnected() { return state == State.CONNECTED; }

    /** Sends one already-KISS-encoded frame. Returns false if not connected. */
    public boolean send(byte[] kissFrame) {
        OutputStream o = out;
        if (o == null || state != State.CONNECTED) return false;
        try {
            synchronized (this) {
                o.write(kissFrame);
                o.flush();
            }
            txCount++;
            return true;
        } catch (IOException e) {
            closeSocket();
            return false;
        }
    }

    private void run() {
        int attempt = 0;
        while (wanted) {
            setState(attempt == 0 ? State.CONNECTING : State.RETRYING, deviceName());
            BluetoothSocket s = open();
            if (s == null) {
                if (System.currentTimeMillis() > deadline) {
                    // radio is off or out of range: stop draining the battery
                    wanted = false;
                    setState(State.OFF, "LOST");
                    return;
                }
                attempt++;
                if (!sleep(Math.min(30000, 3000L * attempt))) return;
                continue;
            }
            attempt = 0;
            socket = s;
            try {
                InputStream in = s.getInputStream();
                out = s.getOutputStream();
                deadline = Long.MAX_VALUE;
                setState(State.CONNECTED, deviceName());
                Kiss.Decoder dec = new Kiss.Decoder();
                byte[] buf = new byte[1024];
                while (wanted) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    List<byte[]> frames = dec.feed(buf, n);
                    for (byte[] f : frames) {
                        rxCount++;
                        main.post(() -> cb.onFrame(f));
                    }
                }
            } catch (IOException ignored) {
            }
            closeSocket();
            if (wanted) {
                deadline = System.currentTimeMillis() + AFTER_DROP_MS;
                attempt = 1;
                if (!sleep(3000)) return;
            }
        }
    }

    private BluetoothSocket open() {
        BluetoothDevice d = device;
        if (d == null) return null;
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null) a.cancelDiscovery();
        } catch (SecurityException ignored) {
        }
        // 1) standard SPP lookup
        try {
            BluetoothSocket s = d.createRfcommSocketToServiceRecord(SPP);
            s.connect();
            return s;
        } catch (IOException | SecurityException ignored) {
        }
        // 2) insecure SPP
        try {
            BluetoothSocket s = d.createInsecureRfcommSocketToServiceRecord(SPP);
            s.connect();
            return s;
        } catch (IOException | SecurityException ignored) {
        }
        // 3) direct RFCOMM channel 1 (works on some radios with odd SDP records)
        try {
            Method m = d.getClass().getMethod("createRfcommSocket", int.class);
            BluetoothSocket s = (BluetoothSocket) m.invoke(d, 1);
            if (s != null) {
                s.connect();
                return s;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void closeSocket() {
        out = null;
        BluetoothSocket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return wanted;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private void setState(State s, String detail) {
        state = s;
        main.post(() -> cb.onRadioState(s, detail));
    }
}
