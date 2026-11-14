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
        if (FieldMapApp.get().prefs.getInt("kissPort", 0) == PORT_BLE) {
            bleMode = true;
            main.post(this::bleOpen);
            return;
        }
        bleMode = false;
        worker = new Thread(this::run, "radio-link");
        worker.start();
    }

    public synchronized void disconnect() {
        wanted = false;
        closeSocket();
        if (worker != null) worker.interrupt();
        worker = null;
        main.post(this::bleClose);
        setState(State.OFF, "");
    }

    public boolean isConnected() { return state == State.CONNECTED; }

    /** Sends one already-KISS-encoded frame. Returns false if not connected. */
    public boolean send(byte[] kissFrame) {
        if (bleMode) return bleSend(kissFrame);
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
                rawBytes = 0;
                while (wanted) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    if (rawBytes == 0) {
                        // the first bytes show what kind of port this is: a packet modem starts frames with C0
                        StringBuilder hx = new StringBuilder();
                        for (int i = 0; i < Math.min(n, 16); i++) hx.append(String.format("%02X ", buf[i] & 0xFF));
                        FieldMapApp.get().log("Radio first bytes: " + hx.toString().trim());
                    }
                    rawBytes += n;
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
        // what the radio says it offers, for the log (helps when a connection opens but nothing works)
        try {
            android.os.ParcelUuid[] us = d.getUuids();
            StringBuilder sb = new StringBuilder();
            if (us != null) for (android.os.ParcelUuid u : us) sb.append(u.toString(), 0, 8).append(' ');
            FieldMapApp.get().log("Radio offers: " + (sb.length() == 0 ? "(nothing listed)" : sb.toString().trim()));
        } catch (Exception ignored) { }
        // The UV-PRO offers more than one serial port over Bluetooth, and only one of them is the
        // KISS modem (port 1 on the radios people have written up). The standard lookup can land on
        // the other one, which accepts the connection and then ignores every packet. So port 1 is
        // tried first, and the port can be chosen by hand from the UV-PRO screen.
        int want = FieldMapApp.get().prefs.getInt("kissPort", 0);   // 0 auto, 1-9 that port, 100 standard lookup only
        if (want >= 1 && want <= 9) return channel(d, want);
        if (want == 0) {
            BluetoothSocket s = channel(d, 1);
            if (s != null) return s;
        }
        try {
            BluetoothSocket s = d.createRfcommSocketToServiceRecord(SPP);
            s.connect();
            how = "standard lookup";
            return s;
        } catch (IOException | SecurityException ignored) {
        }
        try {
            BluetoothSocket s = d.createInsecureRfcommSocketToServiceRecord(SPP);
            s.connect();
            how = "standard lookup (insecure)";
            return s;
        } catch (IOException | SecurityException ignored) {
        }
        return null;
    }

    /** How the current connection was opened, for the UV-PRO screen and the log. */
    public volatile String how = "";
    /** Bytes of any kind received on this connection. */
    public volatile long rawBytes = 0;

    private BluetoothSocket channel(BluetoothDevice d, int ch) {
        try {
            Method m = d.getClass().getMethod("createRfcommSocket", int.class);
            BluetoothSocket s = (BluetoothSocket) m.invoke(d, ch);
            if (s != null) {
                s.connect();
                how = "port " + ch;
                return s;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // =====================================================================
    // Bluetooth Low Energy: some radios (and some firmware versions) only offer the packet
    // modem this way. Bytes are the same KISS stream, cut into small pieces.
    // =====================================================================

    public static final int PORT_BLE = 200;
    private static final UUID KISS_SVC = UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb");
    private static final UUID NUS_SVC = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private volatile boolean bleMode = false;
    private android.bluetooth.BluetoothGatt gatt;
    private android.bluetooth.BluetoothGattCharacteristic bleWrite;
    private final java.util.ArrayDeque<byte[]> bleQueue = new java.util.ArrayDeque<>();
    private boolean bleBusy = false;
    private int bleChunk = 20, bleAttempt = 0;
    private final Kiss.Decoder bleDecoder = new Kiss.Decoder();

    private static void note(String s) { FieldMapApp.get().log("BLE " + s); }

    private void bleOpen() {
        BluetoothDevice d = device;
        if (!wanted || !bleMode || d == null) return;
        setState(bleAttempt == 0 ? State.CONNECTING : State.RETRYING, deviceName());
        try {
            gatt = d.connectGatt(FieldMapApp.get(), false, gattCb, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            bleRetry();
        }
    }

    private void bleClose() {
        bleWrite = null;
        bleBusy = false;
        bleQueue.clear();
        android.bluetooth.BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try { g.disconnect(); g.close(); } catch (Exception ignored) { }
        }
    }

    private void bleRetry() {
        bleClose();
        if (!wanted || !bleMode) return;
        if (System.currentTimeMillis() > deadline) {
            wanted = false;
            setState(State.OFF, "LOST");
            return;
        }
        bleAttempt++;
        setState(State.RETRYING, deviceName());
        main.postDelayed(this::bleOpen, Math.min(30000, 3000L * bleAttempt));
    }

    private boolean bleSend(byte[] kiss) {
        if (state != State.CONNECTED) return false;
        final byte[] copy = kiss.clone();
        main.post(() -> {
            for (int i = 0; i < copy.length; i += bleChunk)
                bleQueue.add(java.util.Arrays.copyOfRange(copy, i, Math.min(copy.length, i + bleChunk)));
            blePump();
        });
        txCount++;
        return true;
    }

    @SuppressWarnings("deprecation")
    private void blePump() {
        android.bluetooth.BluetoothGatt g = gatt;
        android.bluetooth.BluetoothGattCharacteristic c = bleWrite;
        if (bleBusy || g == null || c == null || bleQueue.isEmpty()) return;
        byte[] part = bleQueue.peek();
        int type = (c.getProperties() & android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        boolean ok;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ok = g.writeCharacteristic(c, part, type) == android.bluetooth.BluetoothStatusCodes.SUCCESS;
            } else {
                c.setWriteType(type);
                c.setValue(part);
                ok = g.writeCharacteristic(c);
            }
        } catch (SecurityException e) {
            ok = false;
        }
        if (ok) { bleBusy = true; bleQueue.poll(); }
        else main.postDelayed(this::blePump, 300);
    }

    private void bleBytes(byte[] v) {
        if (v == null) return;
        final byte[] copy = v.clone();
        main.post(() -> {
            rawBytes += copy.length;
            for (byte[] f : bleDecoder.feed(copy, copy.length)) {
                rxCount++;
                cb.onFrame(f);
            }
        });
    }

    private final android.bluetooth.BluetoothGattCallback gattCb = new android.bluetooth.BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(android.bluetooth.BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (g != gatt) return;
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    try { if (!g.requestMtu(185)) g.discoverServices(); } catch (SecurityException e) { bleRetry(); }
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    note("link closed (status " + status + ")");
                    bleRetry();
                }
            });
        }

        @Override
        public void onMtuChanged(android.bluetooth.BluetoothGatt g, int mtu, int status) {
            main.post(() -> {
                if (g != gatt) return;
                if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS && mtu > 23) bleChunk = Math.min(mtu - 3, 180);
                try { g.discoverServices(); } catch (SecurityException e) { bleRetry(); }
            });
        }

        @Override
        public void onServicesDiscovered(android.bluetooth.BluetoothGatt g, int status) {
            main.post(() -> {
                if (g != gatt) return;
                android.bluetooth.BluetoothGattService svc = g.getService(KISS_SVC);
                String name = "packet-modem service";
                if (svc == null) { svc = g.getService(NUS_SVC); name = "serial service"; }
                if (svc == null) {
                    StringBuilder sb = new StringBuilder();
                    for (android.bluetooth.BluetoothGattService x : g.getServices()) sb.append(x.getUuid().toString(), 0, 8).append(' ');
                    note("no packet modem found on this radio. Services: " + sb);
                    wanted = false;
                    bleClose();
                    setState(State.OFF, "NO MODEM OVER LOW ENERGY");
                    return;
                }
                // which of the two channels is "to the radio" differs between write-ups: go by what each one can do
                android.bluetooth.BluetoothGattCharacteristic w = null, n = null;
                for (android.bluetooth.BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                    int p = c.getProperties();
                    if (n == null && (p & (android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) n = c;
                    else if (w == null && (p & (android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
                            | android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) w = c;
                }
                if (w == null) { note("modem service has no channel to send on"); bleRetry(); return; }
                bleWrite = w;
                how = "Low Energy, " + name + ", " + bleChunk + "-byte pieces";
                boolean waiting = false;
                if (n != null) {
                    try {
                        g.setCharacteristicNotification(n, true);
                        android.bluetooth.BluetoothGattDescriptor d = n.getDescriptor(CCCD);
                        if (d != null) {
                            byte[] on = (n.getProperties() & android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                                    ? android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    : android.bluetooth.BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                waiting = g.writeDescriptor(d, on) == android.bluetooth.BluetoothStatusCodes.SUCCESS;
                            } else {
                                bleLegacyDescriptor(g, d, on);
                                waiting = true;
                            }
                        }
                    } catch (SecurityException ignored) { }
                } else {
                    note("modem service has no channel to receive on (send only)");
                }
                bleBusy = waiting;
                bleAttempt = 0;
                deadline = Long.MAX_VALUE;
                setState(State.CONNECTED, deviceName());
            });
        }

        @Override
        public void onDescriptorWrite(android.bluetooth.BluetoothGatt g, android.bluetooth.BluetoothGattDescriptor d, int status) {
            main.post(() -> {
                if (g != gatt) return;
                if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) note("could not turn on receiving (status " + status + ")");
                bleBusy = false;
                blePump();
            });
        }

        @Override
        public void onCharacteristicWrite(android.bluetooth.BluetoothGatt g, android.bluetooth.BluetoothGattCharacteristic c, int status) {
            main.post(() -> {
                if (g != gatt) return;
                if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) note("radio refused a packet piece (status " + status + ")");
                bleBusy = false;
                blePump();
            });
        }

        // Android 13+
        @Override
        public void onCharacteristicChanged(android.bluetooth.BluetoothGatt g, android.bluetooth.BluetoothGattCharacteristic c, byte[] value) {
            if (g == gatt) bleBytes(value);
        }

        // Android 12 and older
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(android.bluetooth.BluetoothGatt g, android.bluetooth.BluetoothGattCharacteristic c) {
            if (android.os.Build.VERSION.SDK_INT < 33 && g == gatt) bleBytes(c.getValue());
        }
    };

    @SuppressWarnings("deprecation")
    private static void bleLegacyDescriptor(android.bluetooth.BluetoothGatt g, android.bluetooth.BluetoothGattDescriptor d, byte[] v) {
        d.setValue(v);
        g.writeDescriptor(d);
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
