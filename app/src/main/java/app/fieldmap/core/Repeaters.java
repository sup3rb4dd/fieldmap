package app.fieldmap.core;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Repeaters on the map: ham and GMRS. Data comes from HearHam.live's free repeater list
 * (https://hearham.com/api/repeaters/v1, "free to use in your application"), from a CSV the
 * user imports, or typed in by hand. No Android dependencies, so it can be unit tested.
 */
public final class Repeaters {
    private Repeaters() {}

    public static final String HEARHAM_URL = "https://hearham.com/api/repeaters/v1";

    public static class Repeater {
        public String id = "";        // "hh:123" (HearHam), "my:<uuid>" (typed in), "csv:<n>" (imported)
        public String region = "";    // state code it was downloaded for, "MINE", or the import file name
        public String callsign = "";
        public double lat, lon;
        public long outHz;            // what you listen on
        public long offsetHz;         // input = output + offset
        public String toneTx = "";    // tone to transmit to open it: "151.4", "D023" or ""
        public String toneRx = "";    // tone the repeater sends back, "" if none
        public String mode = "FM";
        public String city = "";
        public String notes = "";
        public int status = 1;        // 1 on the air, 0 listed as off the air or unknown

        public long inHz() { return outHz + offsetHz; }
        public boolean gmrs() { return isGmrs(outHz); }
        public boolean mine() { return id.startsWith("my:"); }
    }

    // ---------- formatting ----------

    /** GMRS repeater outputs are 462.550-462.725 MHz. */
    public static boolean isGmrs(long hz) {
        return hz >= 462_540_000L && hz <= 462_735_000L;
    }

    /** 146820000 -> "146.820" (more digits only when needed, e.g. 146.9850 -> "146.985"). */
    public static String mhz(long hz) {
        String s = String.format(Locale.US, "%.4f", hz / 1e6);
        while (s.endsWith("0") && s.indexOf('.') < s.length() - 4) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** "+0.600", "-5.000", "simplex". */
    public static String offset(long hz) {
        if (hz == 0) return "simplex";
        return (hz > 0 ? "+" : "-") + String.format(Locale.US, "%.3f", Math.abs(hz) / 1e6);
    }

    /** Cleans up a tone: "100.00" -> "100.0", "0.00"/"" -> "", "DCS023"/"D23N" -> "D023". */
    public static String tone(String raw) {
        if (raw == null) return "";
        String t = raw.trim().toUpperCase(Locale.US).replace("HZ", "").replace("PL", "").trim();
        if (t.isEmpty() || t.equals("NONE") || t.equals("CSQ")) return "";
        if (t.startsWith("DCS") || t.startsWith("D")) {
            String digits = t.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) return "";
            int n = Integer.parseInt(digits.length() > 3 ? digits.substring(0, 3) : digits);
            return n == 0 ? "" : String.format(Locale.US, "D%03d", n);
        }
        try {
            double v = Double.parseDouble(t);
            if (v < 60 || v > 260) return "";
            return String.format(Locale.US, "%.1f", v);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** Short tone text for the card: "151.4 Hz", "DCS 023", "none". */
    public static String toneText(String t) {
        if (t == null || t.isEmpty()) return "none";
        return t.startsWith("D") ? "DCS " + t.substring(1) : t + " Hz";
    }

    // ---------- states ----------

    /** code, name, south, north, west, east. Boxes, so a state's list also has repeaters just over its borders. */
    public static final Object[][] STATES = {
            {"AL", "Alabama", 30.14, 35.01, -88.47, -84.89}, {"AK", "Alaska", 51.2, 71.4, -179.2, -129.9},
            {"AZ", "Arizona", 31.33, 37.00, -114.82, -109.04}, {"AR", "Arkansas", 33.00, 36.50, -94.62, -89.64},
            {"CA", "California", 32.53, 42.01, -124.48, -114.13}, {"CO", "Colorado", 36.99, 41.00, -109.06, -102.04},
            {"CT", "Connecticut", 40.95, 42.05, -73.73, -71.79}, {"DE", "Delaware", 38.45, 39.84, -75.79, -75.05},
            {"DC", "District of Columbia", 38.79, 39.00, -77.12, -76.91}, {"FL", "Florida", 24.40, 31.00, -87.63, -80.03},
            {"GA", "Georgia", 30.36, 35.00, -85.61, -80.84}, {"HI", "Hawaii", 18.91, 22.24, -160.25, -154.81},
            {"ID", "Idaho", 41.99, 49.00, -117.24, -111.04}, {"IL", "Illinois", 36.97, 42.51, -91.51, -87.02},
            {"IN", "Indiana", 37.77, 41.76, -88.10, -84.78}, {"IA", "Iowa", 40.38, 43.50, -96.64, -90.14},
            {"KS", "Kansas", 36.99, 40.00, -102.05, -94.59}, {"KY", "Kentucky", 36.50, 39.15, -89.57, -81.96},
            {"LA", "Louisiana", 28.93, 33.02, -94.04, -88.82}, {"ME", "Maine", 43.06, 47.46, -71.08, -66.95},
            {"MD", "Maryland", 37.91, 39.72, -79.49, -75.05}, {"MA", "Massachusetts", 41.24, 42.89, -73.51, -69.93},
            {"MI", "Michigan", 41.70, 48.31, -90.42, -82.41}, {"MN", "Minnesota", 43.50, 49.38, -97.24, -89.49},
            {"MS", "Mississippi", 30.17, 35.00, -91.66, -88.10}, {"MO", "Missouri", 35.99, 40.61, -95.77, -89.10},
            {"MT", "Montana", 44.36, 49.00, -116.05, -104.04}, {"NE", "Nebraska", 40.00, 43.00, -104.05, -95.31},
            {"NV", "Nevada", 35.00, 42.00, -120.01, -114.04}, {"NH", "New Hampshire", 42.70, 45.31, -72.56, -70.61},
            {"NJ", "New Jersey", 38.93, 41.36, -75.56, -73.89}, {"NM", "New Mexico", 31.33, 37.00, -109.05, -103.00},
            {"NY", "New York", 40.50, 45.02, -79.76, -71.86}, {"NC", "North Carolina", 33.84, 36.59, -84.32, -75.46},
            {"ND", "North Dakota", 45.94, 49.00, -104.05, -96.55}, {"OH", "Ohio", 38.40, 41.98, -84.82, -80.52},
            {"OK", "Oklahoma", 33.62, 37.00, -103.00, -94.43}, {"OR", "Oregon", 41.99, 46.29, -124.57, -116.46},
            {"PA", "Pennsylvania", 39.72, 42.27, -80.52, -74.69}, {"RI", "Rhode Island", 41.15, 42.02, -71.86, -71.12},
            {"SC", "South Carolina", 32.03, 35.22, -83.35, -78.54}, {"SD", "South Dakota", 42.48, 45.95, -104.06, -96.44},
            {"TN", "Tennessee", 34.98, 36.68, -90.31, -81.65}, {"TX", "Texas", 25.84, 36.50, -106.65, -93.51},
            {"UT", "Utah", 37.00, 42.00, -114.05, -109.04}, {"VT", "Vermont", 42.73, 45.02, -73.44, -71.46},
            {"VA", "Virginia", 36.54, 39.47, -83.68, -75.24}, {"WA", "Washington", 45.54, 49.00, -124.85, -116.92},
            {"WV", "West Virginia", 37.20, 40.64, -82.64, -77.72}, {"WI", "Wisconsin", 42.49, 47.31, -92.89, -86.25},
            {"WY", "Wyoming", 40.99, 45.01, -111.06, -104.05},
    };

    public static Object[] state(String code) {
        for (Object[] s : STATES) if (s[0].equals(code)) return s;
        return null;
    }

    public static boolean inState(Object[] s, double lat, double lon) {
        return lat >= (double) s[2] && lat <= (double) s[3] && lon >= (double) s[4] && lon <= (double) s[5];
    }

    // ---------- HearHam ----------

    public interface Filter { boolean keep(double lat, double lon); }

    /**
     * Reads HearHam's list (a JSON array of flat objects) and keeps the repeaters the filter
     * accepts. Streams, so the whole 10 MB list never has to sit in memory at once.
     * Duplicate entries (same callsign and frequency) are kept once.
     */
    public static List<Repeater> parseHearHam(Reader in, String region, Filter filter) throws IOException {
        List<Repeater> out = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        Json j = new Json(in);
        j.expect('[');
        if (j.peekIs(']')) return out;
        while (true) {
            Map<String, Object> o = j.flatObject();
            Repeater r = fromHearHam(o, region);
            if (r != null && filter.keep(r.lat, r.lon)) {
                String key = r.callsign + "|" + r.outHz;
                if (seen.put(key, Boolean.TRUE) == null) out.add(r);
            }
            int c = j.next();
            if (c == ']') break;
            if (c != ',') throw new IOException("Bad repeater list near item " + out.size());
        }
        return out;
    }

    static Repeater fromHearHam(Map<String, Object> o, String region) {
        double lat = num(o.get("latitude")), lon = num(o.get("longitude"));
        long f = (long) num(o.get("frequency"));
        if (Double.isNaN(lat) || Double.isNaN(lon) || f < 1_000_000L || (lat == 0 && lon == 0)) return null;
        Repeater r = new Repeater();
        r.id = "hh:" + str(o.get("id"));
        r.region = region;
        r.callsign = str(o.get("callsign")).trim().toUpperCase(Locale.US);
        r.lat = lat;
        r.lon = lon;
        r.outHz = f;
        double off = num(o.get("offset"));
        r.offsetHz = Double.isNaN(off) ? 0 : (long) off;
        r.toneTx = tone(str(o.get("encode")));
        r.toneRx = tone(str(o.get("decode")));
        r.mode = str(o.get("mode")).trim().replaceAll("\\s+", " ");
        if (r.mode.isEmpty()) r.mode = "FM";
        r.city = str(o.get("city")).trim();
        StringBuilder n = new StringBuilder(str(o.get("description")).trim());
        String rest = str(o.get("restriction")).trim();
        if (!rest.isEmpty()) n.append(n.length() > 0 ? "\n" : "").append("Restriction: ").append(rest);
        String group = str(o.get("group")).trim(), node = str(o.get("internet_node")).trim();
        if (!group.isEmpty() && !node.isEmpty() && n.indexOf(node) < 0) n.append(n.length() > 0 ? "\n" : "").append(group).append(" node ").append(node);
        r.notes = n.toString();
        r.status = num(o.get("operational")) == 1 ? 1 : 0;
        return r;
    }

    private static double num(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble(((String) v).trim()); } catch (NumberFormatException e) { return Double.NaN; }
        }
        return Double.NaN;
    }

    private static String str(Object v) {
        if (v == null) return "";
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d)) return Long.toString((long) d);
        }
        return String.valueOf(v);
    }

    /** Just enough JSON for an array of flat objects. */
    static final class Json {
        private final Reader in;
        private int peeked = -2;
        Json(Reader in) { this.in = in; }

        private int read() throws IOException {
            if (peeked != -2) { int c = peeked; peeked = -2; return c; }
            return in.read();
        }
        private int peek() throws IOException {
            if (peeked == -2) peeked = in.read();
            return peeked;
        }
        int next() throws IOException {
            int c;
            do { c = read(); } while (c == ' ' || c == '\n' || c == '\r' || c == '\t');
            if (c < 0) throw new IOException("The repeater list ended early");
            return c;
        }
        boolean peekIs(char want) throws IOException {
            int c;
            while ((c = peek()) == ' ' || c == '\n' || c == '\r' || c == '\t') read();
            if (c == want) { read(); return true; }
            return false;
        }
        void expect(char want) throws IOException {
            int c = next();
            if (c != want) throw new IOException("This isn't a repeater list (expected '" + want + "')");
        }
        Map<String, Object> flatObject() throws IOException {
            expect('{');
            Map<String, Object> o = new HashMap<>();
            if (peekIs('}')) return o;
            while (true) {
                expect('"');
                String k = string();
                expect(':');
                o.put(k, value());
                int c = next();
                if (c == '}') return o;
                if (c != ',') throw new IOException("Bad repeater entry");
            }
        }
        Object value() throws IOException {
            int c = next();
            if (c == '"') return string();
            if (c == '{' || c == '[') { skip(c); return null; }
            StringBuilder b = new StringBuilder().append((char) c);
            while (true) {
                int p = peek();
                if (p < 0 || p == ',' || p == '}' || p == ']' || p == ' ' || p == '\n' || p == '\r' || p == '\t') break;
                b.append((char) read());
            }
            String t = b.toString();
            if (t.equals("null")) return null;
            if (t.equals("true")) return Boolean.TRUE;
            if (t.equals("false")) return Boolean.FALSE;
            try { return Double.parseDouble(t); } catch (NumberFormatException e) { return t; }
        }
        private void skip(int open) throws IOException {
            int depth = 1;
            while (depth > 0) {
                int c = next();
                if (c == '"') string();
                else if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
            }
        }
        String string() throws IOException {
            StringBuilder b = new StringBuilder();
            while (true) {
                int c = read();
                if (c < 0) throw new IOException("The repeater list ended early");
                if (c == '"') return b.toString();
                if (c == '\\') {
                    int e = read();
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': break;
                        case 'b': case 'f': break;
                        case 'u': {
                            char[] h = new char[4];
                            for (int i = 0; i < 4; i++) h[i] = (char) read();
                            try { b.append((char) Integer.parseInt(new String(h), 16)); } catch (NumberFormatException x) { /* skip */ }
                            break;
                        }
                        default: b.append((char) e);
                    }
                } else {
                    b.append((char) c);
                }
            }
        }
    }

    // ---------- CSV import ----------

    public static class ImportResult {
        public final List<Repeater> repeaters = new ArrayList<>();
        public int skippedNoLocation, skippedNoFrequency;
    }

    /**
     * Imports a CSV with a header row. Recognised columns (any order, case ignored):
     * output/frequency/freq/rx, input/tx/input freq, offset, tone/ctcss/pl/encode/uplink tone,
     * rx tone/tsq/decode, callsign/call, latitude/lat, longitude/lon/long, location/city/name,
     * notes/comment/description, mode. Rows without a position are skipped (the map needs one).
     */
    public static ImportResult parseCsv(String text, String fileName) {
        ImportResult res = new ImportResult();
        List<List<String>> rows = csvRows(text);
        if (rows.isEmpty()) return res;
        Map<String, Integer> col = new HashMap<>();
        List<String> head = rows.get(0);
        Integer chirpTone = null, chirpDuplex = null, chirpOffset = null;
        for (int i = 0; i < head.size(); i++) {
            String h = head.get(i).trim().toLowerCase(Locale.US).replaceAll("[^a-z]", "");
            if (h.equals("rtonefreq")) chirpTone = i;
            if (h.equals("duplex")) chirpDuplex = i;
            if (h.equals("offset")) chirpOffset = i;
            col.putIfAbsent(canon(h), i);
        }
        // CHIRP files: "Tone" is the tone mode and rToneFreq the tone; Duplex is the sign and Offset the size.
        if (chirpTone != null) col.put("tone", chirpTone);
        boolean chirp = chirpDuplex != null && chirpOffset != null;
        int n = 0;
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.size() == 1 && row.get(0).trim().isEmpty()) continue;
            long out = parseMhz(cell(row, col, "out"));
            if (out <= 0) { res.skippedNoFrequency++; continue; }
            double lat = parseD(cell(row, col, "lat")), lon = parseD(cell(row, col, "lon"));
            if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180 || (lat == 0 && lon == 0)) {
                res.skippedNoLocation++;
                continue;
            }
            Repeater x = new Repeater();
            x.id = "csv:" + fileName + ":" + (n++);
            x.region = fileName;
            x.outHz = out;
            long in = parseMhz(cell(row, col, "in"));
            String off = cell(row, col, "offset").trim();
            if (in > 0) x.offsetHz = in - out;
            else if (chirp) {
                String sign = row.size() > chirpDuplex ? row.get(chirpDuplex).trim() : "";
                double size = parseD(row.size() > chirpOffset ? row.get(chirpOffset) : "");
                if (sign.equals("+") || sign.equals("-")) {
                    long hz = Double.isNaN(size) || size == 0 ? Math.abs(defaultOffset(out)) : (size >= 1000 ? Math.round(size) : Math.round(size * 1e6));
                    x.offsetHz = sign.equals("+") ? hz : -hz;
                } else if (sign.equals("split") && !Double.isNaN(size)) {
                    x.offsetHz = parseMhz(Double.toString(size)) - out;
                } else x.offsetHz = 0;
            }
            else if (!off.isEmpty()) x.offsetHz = parseOffset(off, out);
            else x.offsetHz = defaultOffset(out);
            x.toneTx = tone(cell(row, col, "tone"));
            String rx = cell(row, col, "rxtone");
            x.toneRx = rx.isEmpty() ? x.toneTx : tone(rx);
            x.callsign = cell(row, col, "call").trim().toUpperCase(Locale.US);
            x.lat = lat;
            x.lon = lon;
            x.city = cell(row, col, "city").trim();
            x.notes = cell(row, col, "notes").trim();
            String m = cell(row, col, "mode").trim();
            if (!m.isEmpty()) x.mode = m;
            res.repeaters.add(x);
        }
        return res;
    }

    private static String canon(String h) {
        switch (h) {
            case "output": case "outputfreq": case "outputfrequency": case "frequency": case "freq": case "rx": case "rxfreq":
            case "rxfrequency": case "downlink": case "downlinkfreq": case "receive": return "out";
            case "input": case "inputfreq": case "inputfrequency": case "tx": case "txfreq": case "txfrequency": case "uplink":
            case "uplinkfreq": case "transmit": return "in";
            case "offset": case "duplex": case "shift": return "offset";
            case "tone": case "ctcss": case "pl": case "pltone": case "encode": case "uplinktone": case "txtone": case "ctcssdcs":
            case "rtonefreq": case "access": return "tone";
            case "rxtone": case "tsq": case "decode": case "downlinktone": case "ctonefreq": return "rxtone";
            case "callsign": case "call": case "trustee": return "call";
            case "latitude": case "lat": return "lat";
            case "longitude": case "lon": case "long": case "lng": return "lon";
            case "location": case "city": case "name": case "nearestcity": case "site": return "city";
            case "notes": case "note": case "comment": case "comments": case "description": return "notes";
            case "mode": return "mode";
            default: return h;
        }
    }

    private static String cell(List<String> row, Map<String, Integer> col, String key) {
        Integer i = col.get(key);
        return i == null || i >= row.size() ? "" : row.get(i);
    }

    static long parseMhz(String s) {
        double v = parseD(s);
        if (Double.isNaN(v) || v <= 0) return 0;
        if (v > 1e6) return Math.round(v);          // given in Hz
        if (v > 1e3) return Math.round(v * 1e3);    // in kHz
        return Math.round(v * 1e6);                 // in MHz
    }

    /** "+", "-", "+0.6", "-5", "0.600", "simplex"/"off" */
    static long parseOffset(String s, long out) {
        String t = s.trim().toLowerCase(Locale.US);
        if (t.equals("+") || t.equals("-")) {
            long d = Math.abs(defaultOffset(out));
            if (d == 0) d = out > 300_000_000L ? 5_000_000L : 600_000L;
            return t.equals("+") ? d : -d;
        }
        if (t.isEmpty() || t.startsWith("simp") || t.equals("off") || t.equals("0")) return 0;
        double v = parseD(t.replace("+", ""));
        if (Double.isNaN(v)) return defaultOffset(out);
        long hz = Math.abs(v) >= 1000 ? Math.round(v) : Math.round(v * 1e6);
        return hz;
    }

    /** US band-plan defaults. */
    static long defaultOffset(long out) {
        double m = out / 1e6;
        if (isGmrs(out)) return 5_000_000L;
        if (m >= 145.1 && m < 145.5) return -600_000L;
        if (m >= 146.0 && m < 146.4) return 600_000L;
        if (m >= 146.6 && m < 147.0) return -600_000L;
        if (m >= 147.0 && m < 147.4) return 600_000L;
        if (m >= 223.0 && m < 225.0) return -1_600_000L;
        if (m >= 442.0 && m < 445.0) return 5_000_000L;
        if (m >= 447.0 && m < 450.0) return -5_000_000L;
        return 0;
    }

    private static double parseD(String s) {
        if (s == null) return Double.NaN;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }

    static List<List<String>> csvRows(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = false;
                } else cell.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                rows.add(row); row = new ArrayList<>();
            } else if (c != '\uFEFF') cell.append(c);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }

    // ---------- saving ----------

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unesc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char e = s.charAt(++i);
                b.append(e == 't' ? '\t' : e == 'n' ? '\n' : e);
            } else b.append(c);
        }
        return b.toString();
    }

    /** One line per repeater, tab-separated. */
    public static String toLine(Repeater r) {
        return String.join("\t", esc(r.id), esc(r.region), esc(r.callsign), String.format(Locale.US, "%.6f", r.lat),
                String.format(Locale.US, "%.6f", r.lon), Long.toString(r.outHz), Long.toString(r.offsetHz), esc(r.toneTx),
                esc(r.toneRx), esc(r.mode), esc(r.city), esc(r.notes), Integer.toString(r.status));
    }

    public static Repeater fromLine(String line) {
        String[] p = line.split("\t", -1);
        if (p.length < 13) return null;
        try {
            Repeater r = new Repeater();
            r.id = unesc(p[0]);
            r.region = unesc(p[1]);
            r.callsign = unesc(p[2]);
            r.lat = Double.parseDouble(p[3]);
            r.lon = Double.parseDouble(p[4]);
            r.outHz = Long.parseLong(p[5]);
            r.offsetHz = Long.parseLong(p[6]);
            r.toneTx = unesc(p[7]);
            r.toneRx = unesc(p[8]);
            r.mode = unesc(p[9]);
            r.city = unesc(p[10]);
            r.notes = unesc(p[11]);
            r.status = Integer.parseInt(p[12]);
            return r;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
