package app.fieldmap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** Steering arrow: points where to walk, relative to the top of the phone. */
public class ArrowView extends View {
    private float angle = 0;
    private boolean active = false;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();

    public ArrowView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.5f * d);
        ring.setColor(Color.parseColor("#FF2E8B2E"));
        fill.setColor(Color.parseColor("#FFFFB000"));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.5f * d);
        edge.setColor(Color.BLACK);
    }

    /** Degrees clockwise from straight ahead. */
    public void setAngle(float degrees) {
        if (active && Math.abs(degrees - angle) < 1f) return;
        angle = degrees;
        active = true;
        invalidate();
    }

    /** No heading available: show an empty ring. */
    public void setInactive() {
        if (!active) return;
        active = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - ring.getStrokeWidth();
        c.drawCircle(cx, cy, r, ring);
        // tick at 12 o'clock = straight ahead
        c.drawLine(cx, cy - r, cx, cy - r * 0.78f, ring);
        if (!active) return;
        float a = r * 0.72f;
        arrow.rewind();
        arrow.moveTo(cx, cy - a);
        arrow.lineTo(cx + a * 0.62f, cy + a * 0.75f);
        arrow.lineTo(cx, cy + a * 0.35f);
        arrow.lineTo(cx - a * 0.62f, cy + a * 0.75f);
        arrow.close();
        c.save();
        c.rotate(angle, cx, cy);
        c.drawPath(arrow, fill);
        c.drawPath(arrow, edge);
        c.restore();
    }
}
