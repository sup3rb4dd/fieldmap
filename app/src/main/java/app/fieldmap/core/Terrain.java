package app.fieldmap.core;

/** Line-of-sight over a terrain profile, with earth curvature. Terrain only: no trees or buildings. */
public final class Terrain {
    private Terrain() {}

    private static final double EARTH_R = 6371008.8;

    public static final class Result {
        public boolean clear;          // straight line clears the ground
        public double minClearanceM;   // smallest gap (negative = how far the ground pokes through)
        public int worstIndex;         // sample where that happens
        public double worstDistanceM;  // its distance from the start
        public double distanceM;
        public int missing;            // samples with no elevation data
        public double[] sight;         // sight-line height at each sample (m above sea level, curvature included)
        public float[] ground;
        /** If blocked: how much higher the antenna at A (or at B) would need to be to clear. 0 when clear. */
        public double raiseA, raiseB;
    }

    /**
     * ground: evenly spaced heights from A to B in meters (NaN = unknown).
     * hA / hB: eye or antenna height above ground at each end.
     */
    public static Result los(float[] ground, double distanceM, double hA, double hB) {
        Result r = new Result();
        int n = ground.length;
        r.ground = ground;
        r.distanceM = distanceM;
        r.sight = new double[n];
        r.minClearanceM = Double.POSITIVE_INFINITY;
        r.worstIndex = -1;
        if (n < 2 || Float.isNaN(ground[0]) || Float.isNaN(ground[n - 1])) {
            r.missing = n;
            return r;
        }
        double a = ground[0] + hA, b = ground[n - 1] + hB;
        double k = 4.0 / 3.0; // standard atmosphere bends radio (and roughly light) around the curve a little
        for (int i = 0; i < n; i++) {
            double t = i / (double) (n - 1);
            double d1 = t * distanceM, d2 = distanceM - d1;
            double bulge = d1 * d2 / (2 * k * EARTH_R);
            // drop the line instead of raising the ground: same geometry, easier to draw
            r.sight[i] = a + (b - a) * t - bulge;
            if (i == 0 || i == n - 1) continue;
            if (Float.isNaN(ground[i])) { r.missing++; continue; }
            double clearance = r.sight[i] - ground[i];
            if (clearance < r.minClearanceM) {
                r.minClearanceM = clearance;
                r.worstIndex = i;
                r.worstDistanceM = d1;
            }
            if (clearance < 0) {
                // lifting one end by h lifts the line by h * (share of the way to the other end)
                r.raiseA = Math.max(r.raiseA, -clearance / (1 - t));
                r.raiseB = Math.max(r.raiseB, -clearance / t);
            }
        }
        if (r.worstIndex < 0) { r.minClearanceM = Math.min(hA, hB); }
        r.clear = r.minClearanceM >= 0;
        return r;
    }

    /** Terrarium tile pixel (R, G, B 0..255) to meters. */
    public static float terrarium(int red, int green, int blue) {
        return (red * 256f + green + blue / 256f) - 32768f;
    }
}
