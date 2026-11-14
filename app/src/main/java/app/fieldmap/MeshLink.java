package app.fieldmap;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Random;
import java.util.UUID;

import app.fieldmap.core.Mesh;

/**
 * Bluetooth LE link to a Meshtastic node (e.g. Heltec V4), using the official phone API:
 * write ToRadio protobufs, read FromRadio until empty, FromNum notifies when new data waits.
 * All GATT operations run one at a time on the main thread.
 */
@SuppressLint("MissingPermission")
public class MeshLink {

    public interface Callback {
        void onMeshState(RadioLink.State state, String detail);
        void onFromRadio(byte[] data);
    }

    public static final UUID SERVICE = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd");
    private static final UUID TO_RADIO = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7");
    private static final UUID FROM_RADIO = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002");
    private static final UUID FROM_NUM = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Callback cb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();

    private BluetoothDevice device;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic toRadio, fromRadio, fromNum;
    private boolean wanted, busy, draining, ready;
    private int emptyReads, attempt;
    private volatile RadioLink.State state = RadioLink.State.OFF;
    public volatile int rxCount = 0, txCount = 0;

    public MeshLink(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
    }

    public RadioLink.State getState() { return state; }
    public boolean isConnected() { return state == RadioLink.State.CONNECTED; }

    public String deviceName() {
        try {
            return device == null ? "" : (device.getName() == null ? device.getAddress() : device.getName());
        } catch (SecurityException e) {
            return "";
        }
    }

    public void connect(BluetoothDevice d) {
        main.post(() -> {
            closeGatt();
            device = d;
            wanted = true;
            attempt = 0;
            openGatt();
        });
    }

    public void disconnect() {
        main.post(() -> {
            wanted = false;
            main.removeCallbacksAndMessages(null);
            closeGatt();
            setState(RadioLink.State.OFF);
        });
    }

    /** Queues a ToRadio protobuf for sending. */
    public boolean send(byte[] toRadioBytes) {
        if (!isConnected()) return false;
        main.post(() -> {
            writeQueue.add(toRadioBytes);
            pump();
        });
        return true;
    }

    // ---------- connection ----------

    private void openGatt() {
        if (!wanted || device == null) return;
        setState(attempt == 0 ? RadioLink.State.CONNECTING : RadioLink.State.RETRYING);
        try {
            gatt = Build.VERSION.SDK_INT >= 23
                    ? device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
                    : device.connectGatt(ctx, false, gattCb);
        } catch (SecurityException e) {
            scheduleRetry();
        }
    }

    private void closeGatt() {
        ready = false;
        busy = false;
        draining = false;
        writeQueue.clear();
        toRadio = fromRadio = fromNum = null;
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { }
            gatt = null;
        }
    }

    private void scheduleRetry() {
        closeGatt();
        if (!wanted) return;
        attempt++;
        setState(RadioLink.State.RETRYING);
        main.postDelayed(this::openGatt, Math.min(30000, 3000L * attempt));
    }

    /** Safety net in case FromNum notifications don't arrive: check for data every few seconds. */
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!wanted) return;
            if (ready && !draining && !busy) startDrain();
            main.postDelayed(this, 5000);
        }
    };

    private void setState(RadioLink.State s) {
        if (s == RadioLink.State.CONNECTED) { main.removeCallbacks(poll); main.postDelayed(poll, 5000); }
        state = s;
        String d = deviceName();
        main.post(() -> cb.onMeshState(s, d));
    }

    // ---------- operation queue (main thread only) ----------

    private void pump() {
        if (busy || !ready || gatt == null) return;
        if (!writeQueue.isEmpty()) {
            byte[] b = writeQueue.poll();
            busy = true;
            if (!write(toRadio, b)) { busy = false; scheduleRetry(); return; }
            txCount++;
            return;
        }
        if (draining) {
            busy = true;
            if (!gatt.readCharacteristic(fromRadio)) { busy = false; draining = false; }
        }
    }

    private void startDrain() {
        draining = true;
        emptyReads = 0;
        pump();
    }

    @SuppressWarnings("deprecation")
    private boolean write(BluetoothGattCharacteristic c, byte[] value) {
        if (Build.VERSION.SDK_INT >= 33) {
            return gatt.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    == android.bluetooth.BluetoothStatusCodes.SUCCESS;
        }
        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        c.setValue(value);
        return gatt.writeCharacteristic(c);
    }

    @SuppressWarnings("deprecation")
    private boolean writeDescriptor(BluetoothGattDescriptor d, byte[] value) {
        if (Build.VERSION.SDK_INT >= 33) {
            return gatt.writeDescriptor(d, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS;
        }
        d.setValue(value);
        return gatt.writeDescriptor(d);
    }

    // ---------- GATT callbacks (binder thread -> main) ----------

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!g.requestMtu(512)) g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    scheduleRetry();
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            main.post(() -> { if (g == gatt) g.discoverServices(); });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                if (g != gatt) return;
                BluetoothGattService s = g.getService(SERVICE);
                if (s == null) { scheduleRetry(); return; }
                toRadio = s.getCharacteristic(TO_RADIO);
                fromRadio = s.getCharacteristic(FROM_RADIO);
                fromNum = s.getCharacteristic(FROM_NUM);
                if (toRadio == null || fromRadio == null) { scheduleRetry(); return; }
                if (fromNum != null) {
                    g.setCharacteristicNotification(fromNum, true);
                    BluetoothGattDescriptor d = fromNum.getDescriptor(CCCD);
                    if (d != null) {
                        busy = true;
                        if (!writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) busy = false;
                    }
                }
                ready = true;
                attempt = 0;
                setState(RadioLink.State.CONNECTED);
                // ask the node to send its whole node database, then drain it
                writeQueue.add(Mesh.wantConfig(new Random().nextInt(Integer.MAX_VALUE - 1) + 1));
                pump();
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(() -> { if (g == gatt) { busy = false; pump(); } });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            main.post(() -> {
                if (g != gatt) return;
                busy = false;
                if (status != BluetoothGatt.GATT_SUCCESS) { scheduleRetry(); return; }
                startDrain(); // a reply may be waiting
            });
        }

        // Android 13+
        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            handleRead(g, value, status);
        }

        // Android 12 and older
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (Build.VERSION.SDK_INT < 33) handleRead(g, c.getValue(), status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            main.post(() -> { if (g == gatt && !draining) startDrain(); });
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) main.post(() -> { if (g == gatt && !draining) startDrain(); });
        }
    };

    private void handleRead(BluetoothGatt g, byte[] value, int status) {
        byte[] v = value == null ? null : value.clone();
        main.post(() -> {
            if (g != gatt) return;
            busy = false;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // usually "insufficient authentication" while Android shows the pairing PIN prompt
                draining = false;
                main.postDelayed(this::startDrain, 2000);
                return;
            }
            if (v == null || v.length == 0) {
                emptyReads++;
                if (emptyReads < 3) {
                    main.postDelayed(this::pump, 100);
                } else {
                    draining = false;
                    pump();
                }
                return;
            }
            emptyReads = 0;
            rxCount++;
            cb.onFromRadio(v);
            pump();
        });
    }
}
