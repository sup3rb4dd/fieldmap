package app.fieldmap;

import android.app.Activity;
import android.content.Context;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.view.Display;
import android.view.Surface;

/** Which way the top of the phone points, in degrees from true north. */
public class Compass implements SensorEventListener {

    public interface Listener { void onHeading(float trueDegrees); }

    private final SensorManager sm;
    private final Sensor sensor;
    private final Display display;
    private final Listener listener;
    private final float[] rot = new float[9], remapped = new float[9], orient = new float[3], vec = new float[4];
    private float smooth = Float.NaN, declination = 0;
    private long lastEmit, lastDeclination;

    @SuppressWarnings("deprecation")
    public Compass(Activity a, Listener l) {
        sm = (SensorManager) a.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (s == null && sm != null) s = sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        sensor = s;
        display = a.getWindowManager().getDefaultDisplay();
        listener = l;
    }

    public boolean available() { return sensor != null; }

    public void start() {
        if (sensor != null) sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);
    }

    public void stop() {
        if (sm != null) sm.unregisterListener(this);
        smooth = Float.NaN;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.values.length < 3) return;
        // some phones send 5 values, which older Android versions reject
        int n = Math.min(4, e.values.length);
        System.arraycopy(e.values, 0, vec, 0, n);
        try {
            if (n == 4) SensorManager.getRotationMatrixFromVector(rot, vec);
            else SensorManager.getRotationMatrixFromVector(rot, new float[]{vec[0], vec[1], vec[2]});
        } catch (IllegalArgumentException ex) {
            return;
        }
        int ax = SensorManager.AXIS_X, ay = SensorManager.AXIS_Y;
        switch (display.getRotation()) {
            case Surface.ROTATION_90: ax = SensorManager.AXIS_Y; ay = SensorManager.AXIS_MINUS_X; break;
            case Surface.ROTATION_180: ax = SensorManager.AXIS_MINUS_X; ay = SensorManager.AXIS_MINUS_Y; break;
            case Surface.ROTATION_270: ax = SensorManager.AXIS_MINUS_Y; ay = SensorManager.AXIS_X; break;
            default: break;
        }
        if (!SensorManager.remapCoordinateSystem(rot, ax, ay, remapped)) return;
        SensorManager.getOrientation(remapped, orient);
        float az = (float) Math.toDegrees(orient[0]);

        long now = System.currentTimeMillis();
        if (now - lastDeclination > 5 * 60_000L) {
            Location loc = FieldMapApp.get().lastLoc;
            if (loc != null) {
                declination = new GeomagneticField((float) loc.getLatitude(), (float) loc.getLongitude(),
                        (float) loc.getAltitude(), now).getDeclination();
                lastDeclination = now;
            }
        }
        az = ((az + declination) % 360 + 360) % 360;
        if (Float.isNaN(smooth)) {
            smooth = az;
        } else {
            float diff = ((az - smooth) % 360 + 540) % 360 - 180;
            smooth = ((smooth + 0.2f * diff) % 360 + 360) % 360;
        }
        if (now - lastEmit >= 100) {
            lastEmit = now;
            listener.onHeading(smooth);
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int accuracy) { }
}
