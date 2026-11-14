package app.fieldmap.core;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Minimal APRS encode/decode: positions (incl. compressed and Mic-E), objects, items and messages. */
public final class Aprs {
    private Aprs() {}

    public enum Type { POSITION, OBJECT, MESSAGE, OTHER }

    public static final class Packet {
        public Type type = Type.OTHER;
        public String source;        // station callsign that sent it
        public String name;          // object/item name (OBJECT) or source (POSITION)
        public double lat = Double.NaN, lon = Double.NaN;
        public char symTable = '/', symCode = '[';
        public String comment = "";
        public boolean killed;       // object deleted
        public String msgTo, msgText, msgId;
        public boolean isAck, isRej;
        public String raw;

        public boolean hasPosition() { return !Double.isNaN(lat) && !Double.isNaN(lon); }
    }

    // ---------- encoding ----------

    public static String latString(double lat) {
        char h = lat >= 0 ? 'N' : 'S';
        lat = Math.abs(lat);
        int deg = (int) lat;
        double min = (lat - deg) * 60;
        if (min >= 59.995) { deg++; min = 0; }
        return String.format(Locale.US, "%02d%05.2f%c", deg, min, h);
    }

    public static String lonString(double lon) {
        char h = lon >= 0 ? 'E' : 'W';
        lon = Math.abs(lon);
        int deg = (int) lon;
        double min = (lon - deg) * 60;
        if (min >= 59.995) { deg++; min = 0; }
        return String.format(Locale.US, "%03d%05.2f%c", deg, min, h);
    }

    /** Position report without timestamp, messaging capable. */
    public static String position(double lat, double lon, char table, char code, String comment) {
        return "=" + latString(lat) + table + lonString(lon) + code + clean(comment, 43);
    }

    /** APRS object. live=false deletes it on other stations. */
    public static String object(String name, double lat, double lon, char table, char code, String comment, boolean live) {
        String n = pad(clean(name, 9), 9);
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        String ts = String.format(Locale.US, "%02d%02d%02dz",
                c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        return ";" + n + (live ? '*' : '_') + ts + latString(lat) + table + lonString(lon) + code + clean(comment, 43);
    }

    public static String message(String to, String text, String id) {
        String m = ":" + pad(clean(to, 9).toUpperCase(Locale.US), 9) + ":" + clean(text, 67);
        if (id != null && !id.isEmpty()) m += "{" + id;
        return m;
    }

    public static String ack(String to, String id) {
        return ":" + pad(to.toUpperCase(Locale.US), 9) + ":ack" + id;
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String r = s.replaceAll("[\\r\\n|~{]", " ");
        return r.length() > max ? r.substring(0, max) : r;
    }

    // ---------- decoding ----------

    public static Packet decode(Ax25 f) {
        Packet p = new Packet();
        p.source = f.source;
        p.name = f.source;
        String info = f.infoString();
        p.raw = f.toString();
        if (info.isEmpty()) return p;
        char t = info.charAt(0);
        try {
            switch (t) {
                case '!': case '=':
                    parsePositionAt(p, info, 1);
                    break;
                case '/': case '@':
                    parsePositionAt(p, info, 8); // skip 7-char timestamp
                    break;
                case ';':
                    parseObject(p, info);
                    break;
                case ')':
                    parseItem(p, info);
                    break;
                case ':':
                    parseMessage(p, info);
                    break;
                case '`': case '\'': case 0x1c: case 0x1d:
                    parseMicE(p, f.dest, info);
                    break;
                default:
                    // Some stations put '!' later in the packet
                    int bang = info.indexOf('!');
                    if (bang > 0 && bang < 40) parsePositionAt(p, info, bang + 1);
            }
        } catch (RuntimeException e) {
            // malformed packet: leave as OTHER/no position
            if (p.type == Type.POSITION && !p.hasPosition()) p.type = Type.OTHER;
        }
        return p;
    }

    /** Parses an uncompressed or compressed position starting at index i. */
    static int parsePositionAt(Packet p, String s, int i) {
        if (i >= s.length()) return i;
        char c = s.charAt(i);
        if (Character.isDigit(c) || c == ' ') {
            if (s.length() < i + 19) return i;
            p.lat = parseLat(s.substring(i, i + 8));
            p.symTable = s.charAt(i + 8);
            p.lon = parseLon(s.substring(i + 9, i + 18));
            p.symCode = s.charAt(i + 18);
            p.comment = s.substring(i + 19).trim();
            if (p.type == Type.OTHER) p.type = Type.POSITION;
            return i + 19;
        }
        // compressed: table(1) YYYY XXXX code(1) cs(2) T(1)
        if (s.length() < i + 13) return i;
        p.symTable = c;
        double y = base91(s, i + 1);
        double x = base91(s, i + 5);
        p.lat = 90 - y / 380926.0;
        p.lon = -180 + x / 190463.0;
        p.symCode = s.charAt(i + 9);
        p.comment = s.substring(i + 13).trim();
        if (p.type == Type.OTHER) p.type = Type.POSITION;
        return i + 13;
    }

    private static double base91(String s, int i) {
        double v = 0;
        for (int k = 0; k < 4; k++) v = v * 91 + (s.charAt(i + k) - 33);
        return v;
    }

    static double parseLat(String s) {
        s = s.replace(' ', '0');
        int deg = Integer.parseInt(s.substring(0, 2));
        double min = Double.parseDouble(s.substring(2, 7));
        double v = deg + min / 60.0;
        char h = Character.toUpperCase(s.charAt(7));
        if (h == 'S') v = -v; else if (h != 'N') throw new IllegalArgumentException("lat");
        return v;
    }

    static double parseLon(String s) {
        s = s.replace(' ', '0');
        int deg = Integer.parseInt(s.substring(0, 3));
        double min = Double.parseDouble(s.substring(3, 8));
        double v = deg + min / 60.0;
        char h = Character.toUpperCase(s.charAt(8));
        if (h == 'W') v = -v; else if (h != 'E') throw new IllegalArgumentException("lon");
        return v;
    }

    private static void parseObject(Packet p, String s) {
        // ;NNNNNNNNN*DDHHMMz<position>
        if (s.length() < 18) return;
        p.type = Type.OBJECT;
        p.name = s.substring(1, 10).trim();
        p.killed = s.charAt(10) == '_';
        parsePositionAt(p, s, 18);
    }

    private static void parseItem(Packet p, String s) {
        // )NAME!<position> or )NAME_<position>, name 3..9 chars
        int end = -1;
        for (int i = 4; i < Math.min(s.length(), 11); i++) {
            char c = s.charAt(i);
            if (c == '!' || c == '_') { end = i; break; }
        }
        if (end < 0) return;
        p.type = Type.OBJECT;
        p.name = s.substring(1, end).trim();
        p.killed = s.charAt(end) == '_';
        parsePositionAt(p, s, end + 1);
    }

    private static void parseMessage(Packet p, String s) {
        // :ADDRESSEE:text{id
        if (s.length() < 11 || s.charAt(10) != ':') return;
        p.type = Type.MESSAGE;
        p.msgTo = s.substring(1, 10).trim();
        String body = s.substring(11);
        if (body.startsWith("ack") && body.length() <= 8 && !body.contains(" ")) {
            p.isAck = true; p.msgId = body.substring(3); p.msgText = body; return;
        }
        if (body.startsWith("rej") && body.length() <= 8 && !body.contains(" ")) {
            p.isRej = true; p.msgId = body.substring(3); p.msgText = body; return;
        }
        int brace = body.lastIndexOf('{');
        if (brace >= 0) {
            String id = body.substring(brace + 1);
            int close = id.indexOf('}');
            if (close >= 0) id = id.substring(0, close); // reply-ack format {MM}AA
            p.msgId = id;
            body = body.substring(0, brace);
        }
        p.msgText = body;
    }

    private static void parseMicE(Packet p, String dest, String s) {
        String d = dest;
        int dash = d.indexOf('-');
        if (dash >= 0) d = d.substring(0, dash);
        if (d.length() < 6 || s.length() < 9) return;
        int[] dig = new int[6];
        for (int i = 0; i < 6; i++) {
            char c = d.charAt(i);
            if (c >= '0' && c <= '9') dig[i] = c - '0';
            else if (c >= 'A' && c <= 'J') dig[i] = c - 'A';
            else if (c >= 'P' && c <= 'Y') dig[i] = c - 'P';
            else if (c == 'K' || c == 'L' || c == 'Z') dig[i] = 0;
            else return;
        }
        boolean north = d.charAt(3) >= 'P';
        boolean lonOffset = d.charAt(4) >= 'P';
        boolean west = d.charAt(5) >= 'P';
        double lat = dig[0] * 10 + dig[1] + (dig[2] * 10 + dig[3] + (dig[4] * 10 + dig[5]) / 100.0) / 60.0;
        if (!north) lat = -lat;

        int lonDeg = s.charAt(1) - 28;
        if (lonOffset) lonDeg += 100;
        if (lonDeg >= 180 && lonDeg <= 189) lonDeg -= 80;
        else if (lonDeg >= 190 && lonDeg <= 199) lonDeg -= 190;
        int lonMin = s.charAt(2) - 28;
        if (lonMin >= 60) lonMin -= 60;
        int lonHun = s.charAt(3) - 28;
        double lon = lonDeg + (lonMin + lonHun / 100.0) / 60.0;
        if (west) lon = -lon;

        p.type = Type.POSITION;
        p.lat = lat;
        p.lon = lon;
        p.symCode = s.charAt(7);
        p.symTable = s.charAt(8);
        p.comment = s.length() > 9 ? s.substring(9).trim() : "";
    }
}
