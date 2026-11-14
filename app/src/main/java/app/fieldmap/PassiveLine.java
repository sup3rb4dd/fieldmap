package app.fieldmap;

import android.view.MotionEvent;

import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Polyline;

/** A line that is only there to be looked at: taps pass straight through it to whatever is underneath. */
class PassiveLine extends Polyline {
    @Override
    public boolean onSingleTapConfirmed(MotionEvent e, MapView mapView) { return false; }
}
