package app.fieldmap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import app.fieldmap.core.Terrain;

/** Side-on picture of the ground between two points with the sight line drawn over it. */
public class ProfileView extends View {
    private final Terrain.Result r;
    private final Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
            line = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String leftLabel, rightLabel;

    public ProfileView(Context c, Terrain.Result r, String leftLabel, String rightLabel) {
        super(c);
        this.r = r;
        this.leftLabel = leftLabel;
        this.rightLabel = rightLabel;
        float d = getResources().getDisplayMetrics().density;
        ground.setColor(Color.parseColor("#FF0E3A0E"));
        edge.setColor(Color.parseColor("#FF39FF14"));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.5f * d);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f * d);
        line.setColor(r.clear ? Color.parseColor("#FFFFB000") : Color.parseColor("#FFFF3B30"));
        line.setPathEffect(new DashPathEffect(new float[]{8 * d, 5 * d}, 0));
        mark.setColor(Color.parseColor("#FFFF3B30"));
        text.setColor(Color.parseColor("#FFA8FFA0"));
        text.setTextSize(10 * d);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = r.ground.length;
        if (n < 2 || r.sight == null) return;
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight(), padTop = 14 * d, padBottom = 14 * d;
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (!Float.isNaN(r.ground[i])) { lo = Math.min(lo, r.ground[i]); hi = Math.max(hi, r.ground[i]); }
            lo = Math.min(lo, r.sight[i]);
            hi = Math.max(hi, r.sight[i]);
        }
        if (hi - lo < 10) hi = lo + 10;
        double span = hi - lo;
        Path fill = new Path(), top = new Path();
        boolean started = false;
        float lastX = 0;
        for (int i = 0; i < n; i++) {
            if (Float.isNaN(r.ground[i])) continue;
            float x = w * i / (n - 1), y = (float) (padTop + (h - padTop - padBottom) * (1 - (r.ground[i] - lo) / span));
            if (!started) { fill.moveTo(x, h); fill.lineTo(x, y); top.moveTo(x, y); started = true; }
            else { fill.lineTo(x, y); top.lineTo(x, y); }
            lastX = x;
        }
        if (started) {
            fill.lineTo(lastX, h);
            fill.close();
            c.drawPath(fill, ground);
            c.drawPath(top, edge);
        }
        float y0 = (float) (padTop + (h - padTop - padBottom) * (1 - (r.sight[0] - lo) / span));
        float y1 = (float) (padTop + (h - padTop - padBottom) * (1 - (r.sight[n - 1] - lo) / span));
        // the sight line is drawn straight; the earth's curve is folded into the ground-vs-line math, not the picture
        Path sl = new Path();
        for (int i = 0; i < n; i++) {
            float x = w * i / (n - 1), y = (float) (padTop + (h - padTop - padBottom) * (1 - (r.sight[i] - lo) / span));
            if (i == 0) sl.moveTo(x, y); else sl.lineTo(x, y);
        }
        c.drawPath(sl, line);
        if (!r.clear && r.worstIndex > 0) {
            float x = w * r.worstIndex / (n - 1);
            float y = (float) (padTop + (h - padTop - padBottom) * (1 - (r.ground[r.worstIndex] - lo) / span));
            c.drawCircle(x, y, 4 * d, mark);
        }
        c.drawText(leftLabel, 2 * d, Math.max(10 * d, y0 - 4 * d), text);
        float rw = text.measureText(rightLabel);
        c.drawText(rightLabel, w - rw - 2 * d, Math.max(10 * d, y1 - 4 * d), text);
    }
}
