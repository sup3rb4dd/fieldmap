package app.fieldmap.core;

import java.util.Locale;

public final class Geo {
    private Geo() {}

    private static final double R = 6371008.8;

    /** Great-circle distance in meters. */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Initial true bearing in degrees 0..360. */
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double b = Math.toDegrees(Math.atan2(y, x));
        return (b + 360) % 360;
    }

    public static String cardinal(double deg) {
        String[] dirs = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        return dirs[(int) Math.round(((deg % 360) / 22.5)) % 16];
    }

    public static String formatDistance(double meters, boolean imperial) {
        if (imperial) {
            double feet = meters * 3.28084;
            if (feet < 1000) return String.format(Locale.US, "%.0f ft", feet);
            double miles = meters / 1609.344;
            return String.format(Locale.US, miles < 10 ? "%.2f mi" : "%.1f mi", miles);
        }
        if (meters < 1000) return String.format(Locale.US, "%.0f m", meters);
        double km = meters / 1000;
        return String.format(Locale.US, km < 10 ? "%.2f km" : "%.1f km", km);
    }

    /** e.g. "1.24 mi @ 047° NE" */
    public static String rangeBearing(double lat1, double lon1, double lat2, double lon2, boolean imperial) {
        double d = distance(lat1, lon1, lat2, lon2);
        double b = bearing(lat1, lon1, lat2, lon2);
        return String.format(Locale.US, "%s @ %03.0f° %s", formatDistance(d, imperial), b, cardinal(b));
    }

    /** Signed smallest turn from heading to bearing, -180..180 (positive = turn right). */
    public static double turn(double bearing, double heading) {
        return ((bearing - heading) % 360 + 540) % 360 - 180;
    }

    public static String formatSpeed(double mps, boolean imperial) {
        return imperial ? String.format(Locale.US, "%.1f MPH", mps * 2.236936)
                : String.format(Locale.US, "%.1f KM/H", mps * 3.6);
    }

    /** "<1 MIN", "12 MIN", "1H 05M"; "--" when not moving or absurdly far. */
    public static String formatEta(double meters, double mps) {
        if (mps < 0.3) return "--";
        long s = Math.round(meters / mps);
        if (s < 60) return "<1 MIN";
        if (s < 3600) return (s / 60) + " MIN";
        if (s > 99 * 3600) return "--";
        return String.format(Locale.US, "%dH %02dM", s / 3600, (s % 3600) / 60);
    }

    public static String ago(long thenMs, long nowMs) {
        long s = Math.max(0, (nowMs - thenMs) / 1000);
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }
}
