package app.fieldmap.core;

import java.util.Locale;

/**
 * Lat/lon <-> UTM <-> MGRS conversion on the WGS84 ellipsoid.
 * No external dependencies so it can be unit-tested on a plain JVM.
 */
public final class Mgrs {
    private Mgrs() {}

    private static final double A = 6378137.0;
    private static final double F = 1 / 298.257223563;
    private static final double E2 = F * (2 - F);
    private static final double EP2 = E2 / (1 - E2);
    private static final double K0 = 0.9996;

    private static final String BANDS = "CDEFGHJKLMNPQRSTUVWX";
    private static final String[] COL_SETS = {"ABCDEFGH", "JKLMNPQR", "STUVWXYZ"};
    private static final String ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV";

    /** UTM result: zone, hemisphere-independent band letter, easting, northing (northing includes 10,000 km false northing in the south). */
    public static final class Utm {
        public final int zone; public final char band; public final double easting; public final double northing;
        Utm(int zone, char band, double easting, double northing) {
            this.zone = zone; this.band = band; this.easting = easting; this.northing = northing;
        }
    }

    public static int zoneFor(double lat, double lon) {
        int zone = (int) Math.floor((lon + 180) / 6) + 1;
        if (zone > 60) zone = 60;
        // Norway / Svalbard exceptions
        if (lat >= 56 && lat < 64 && lon >= 3 && lon < 12) zone = 32;
        if (lat >= 72 && lat < 84) {
            if (lon >= 0 && lon < 9) zone = 31;
            else if (lon >= 9 && lon < 21) zone = 33;
            else if (lon >= 21 && lon < 33) zone = 35;
            else if (lon >= 33 && lon < 42) zone = 37;
        }
        return zone;
    }

    public static char bandFor(double lat) {
        if (lat >= 72) return 'X';
        int i = (int) Math.floor((lat + 80) / 8);
        if (i < 0) i = 0;
        if (i > 19) i = 19;
        return BANDS.charAt(i);
    }

    public static Utm toUtm(double lat, double lon) {
        return toUtm(lat, lon, zoneFor(lat, lon));
    }

    public static Utm toUtm(double lat, double lon, int zone) {
        double lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3);
        double phi = Math.toRadians(lat);
        double lam = Math.toRadians(lon);
        double sin = Math.sin(phi), cos = Math.cos(phi), tan = Math.tan(phi);
        double n = A / Math.sqrt(1 - E2 * sin * sin);
        double t = tan * tan;
        double c = EP2 * cos * cos;
        double a = cos * (lam - lon0);
        double m = meridianArc(phi);
        double easting = K0 * n * (a + (1 - t + c) * Math.pow(a, 3) / 6
                + (5 - 18 * t + t * t + 72 * c - 58 * EP2) * Math.pow(a, 5) / 120) + 500000.0;
        double northing = K0 * (m + n * tan * (a * a / 2
                + (5 - t + 9 * c + 4 * c * c) * Math.pow(a, 4) / 24
                + (61 - 58 * t + t * t + 600 * c - 330 * EP2) * Math.pow(a, 6) / 720));
        if (lat < 0) northing += 10000000.0;
        return new Utm(zone, bandFor(lat), easting, northing);
    }

    private static double meridianArc(double phi) {
        double e4 = E2 * E2, e6 = e4 * E2;
        return A * ((1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi
                - (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * Math.sin(2 * phi)
                + (15 * e4 / 256 + 45 * e6 / 1024) * Math.sin(4 * phi)
                - (35 * e6 / 3072) * Math.sin(6 * phi));
    }

    /** Inverse UTM. Returns {lat, lon}. */
    public static double[] fromUtm(int zone, boolean south, double easting, double northing) {
        double x = easting - 500000.0;
        double y = south ? northing - 10000000.0 : northing;
        double lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3);
        double m = y / K0;
        double mu = m / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * Math.pow(E2, 3) / 256));
        double e1 = (1 - Math.sqrt(1 - E2)) / (1 + Math.sqrt(1 - E2));
        double phi1 = mu + (3 * e1 / 2 - 27 * Math.pow(e1, 3) / 32) * Math.sin(2 * mu)
                + (21 * e1 * e1 / 16 - 55 * Math.pow(e1, 4) / 32) * Math.sin(4 * mu)
                + (151 * Math.pow(e1, 3) / 96) * Math.sin(6 * mu)
                + (1097 * Math.pow(e1, 4) / 512) * Math.sin(8 * mu);
        double sin1 = Math.sin(phi1), cos1 = Math.cos(phi1), tan1 = Math.tan(phi1);
        double n1 = A / Math.sqrt(1 - E2 * sin1 * sin1);
        double t1 = tan1 * tan1;
        double c1 = EP2 * cos1 * cos1;
        double r1 = A * (1 - E2) / Math.pow(1 - E2 * sin1 * sin1, 1.5);
        double d = x / (n1 * K0);
        double lat = phi1 - (n1 * tan1 / r1) * (d * d / 2
                - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * Math.pow(d, 4) / 24
                + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * Math.pow(d, 6) / 720);
        double lon = lon0 + (d - (1 + 2 * t1 + c1) * Math.pow(d, 3) / 6
                + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * Math.pow(d, 5) / 120) / cos1;
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon)};
    }

    /** Full 1-meter MGRS, formatted "14S PF 12345 67890". Returns "" outside 80S..84N. */
    public static String format(double lat, double lon) {
        return format(lat, lon, 5);
    }

    /** digits per axis: 1..5 (5 = 1 m, 4 = 10 m, 3 = 100 m). */
    public static String format(double lat, double lon, int digits) {
        if (lat < -80 || lat > 84 || Double.isNaN(lat) || Double.isNaN(lon)) return "";
        Utm u = toUtm(lat, lon);
        int colIdx = (int) Math.floor(u.easting / 100000.0); // 1..8
        if (colIdx < 1) colIdx = 1;
        if (colIdx > 8) colIdx = 8;
        char col = COL_SETS[(u.zone - 1) % 3].charAt(colIdx - 1);
        int rowIdx = (int) Math.floor(u.northing / 100000.0) % 20;
        if (u.zone % 2 == 0) rowIdx = (rowIdx + 5) % 20;
        char row = ROW_LETTERS.charAt(rowIdx);
        long e = (long) Math.floor(u.easting % 100000.0);
        long n = (long) Math.floor(u.northing % 100000.0);
        int div = (int) Math.pow(10, 5 - digits);
        String fmt = "%0" + digits + "d";
        return String.format(Locale.US, "%d%c %c%c " + fmt + " " + fmt,
                u.zone, u.band, col, row, e / div, n / div);
    }

    /**
     * Parses MGRS like "14SPF1234567890", "14S PF 12345 67890", "14spf 123 678".
     * Returns {lat, lon} of the grid square's south-west corner, or null if invalid.
     */
    public static double[] parse(String text) {
        if (text == null) return null;
        String s = text.replaceAll("\\s+", "").toUpperCase(Locale.US);
        java.util.regex.Matcher mt = java.util.regex.Pattern
                .compile("^(\\d{1,2})([C-HJ-NP-X])([A-HJ-NP-Z])([A-HJ-NP-V])(\\d*)$").matcher(s);
        if (!mt.matches()) return null;
        int zone = Integer.parseInt(mt.group(1));
        if (zone < 1 || zone > 60) return null;
        char band = mt.group(2).charAt(0);
        char col = mt.group(3).charAt(0);
        char row = mt.group(4).charAt(0);
        String digits = mt.group(5);
        if (digits.length() % 2 != 0 || digits.length() > 10) return null;
        int half = digits.length() / 2;
        double e = 0, n = 0;
        if (half > 0) {
            double scale = Math.pow(10, 5 - half);
            e = Long.parseLong(digits.substring(0, half)) * scale;
            n = Long.parseLong(digits.substring(half)) * scale;
        }
        int colIdx = COL_SETS[(zone - 1) % 3].indexOf(col);
        if (colIdx < 0) return null;
        double easting = (colIdx + 1) * 100000.0 + e;
        int rowIdx = ROW_LETTERS.indexOf(row);
        if (zone % 2 == 0) rowIdx = (rowIdx - 5 + 20) % 20;
        double base = rowIdx * 100000.0 + n;
        int bandIdx = BANDS.indexOf(band);
        double bandSouth = -80 + 8 * bandIdx;
        double bandNorth = band == 'X' ? 84 : bandSouth + 8;
        boolean south = band < 'N';
        for (int k = 0; k < 6; k++) {
            double northing = base + k * 2000000.0;
            double[] ll = fromUtm(zone, south, easting, northing);
            if (ll[0] >= bandSouth - 0.6 && ll[0] <= bandNorth + 0.6) return ll;
        }
        return null;
    }
}
