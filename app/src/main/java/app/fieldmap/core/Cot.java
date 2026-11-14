package app.fieldmap.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Cursor-on-Target, the message format ATAK / WinTAK / iTAK use. Builds the handful of
 * messages FieldMap sends and reads the ones it understands (XML, plus the protobuf
 * form ATAK uses on local networks).
 */
public final class Cot {
    private Cot() {}

    public static final String ALL_CHAT = "All Chat Rooms";

    public enum Kind { PERSON, MARKER, CHAT, DELETE, SHAPE, OTHER }

    public static final class Event {
        public Kind kind = Kind.OTHER;
        public String uid = "", type = "", callsign = "";
        public double lat = Double.NaN, lon = Double.NaN;
        public long stale;
        public String chatText, chatRoom, chatToUid, fromUid, fromCallsign;
        public String deleteUid;
        public List<double[]> shape;
        public boolean closed;
        public boolean hasPosition() { return !Double.isNaN(lat) && !Double.isNaN(lon) && !(lat == 0 && lon == 0); }
    }

    // ---------- building ----------

    private static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: if (c >= 0x20 || c == '\n' || c == '\t') sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String event(String uid, String type, String how, double lat, double lon, double hae,
                                long staleSec, String detail) {
        long now = System.currentTimeMillis();
        boolean pos = !Double.isNaN(lat) && !Double.isNaN(lon);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<event version=\"2.0\" uid=\"" + esc(uid) + "\" type=\"" + type + "\" how=\"" + how + "\""
                + " time=\"" + iso(now) + "\" start=\"" + iso(now) + "\" stale=\"" + iso(now + staleSec * 1000) + "\">"
                + String.format(Locale.US, "<point lat=\"%.7f\" lon=\"%.7f\" hae=\"%s\" ce=\"9999999.0\" le=\"9999999.0\"/>",
                pos ? lat : 0, pos ? lon : 0, Double.isNaN(hae) ? "9999999.0" : String.format(Locale.US, "%.1f", hae))
                + "<detail>" + detail + "</detail></event>";
    }

    /** My own position, shown in ATAK as a team member. */
    public static String pli(String uid, String callsign, double lat, double lon, double hae,
                             double speed, double course, String version) {
        String d = "<takv device=\"Android\" platform=\"FieldMap\" os=\"Android\" version=\"" + esc(version) + "\"/>"
                + "<contact callsign=\"" + esc(callsign) + "\" endpoint=\"*:-1:stcp\"/>"
                + "<uid Droid=\"" + esc(callsign) + "\"/>"
                + "<__group name=\"Cyan\" role=\"Team Member\"/>"
                + "<precisionlocation geopointsrc=\"GPS\" altsrc=\"GPS\"/>"
                + String.format(Locale.US, "<track speed=\"%.1f\" course=\"%.1f\"/>", speed, course);
        return event(uid, "a-f-G-U-C", "m-g", lat, lon, hae, 120, d);
    }

    /** A shared waypoint. */
    public static String marker(String uid, String name, double lat, double lon, String myUid, String myCallsign) {
        String d = "<contact callsign=\"" + esc(name) + "\"/>"
                + "<link uid=\"" + esc(myUid) + "\" type=\"a-f-G-U-C\" parent_callsign=\"" + esc(myCallsign) + "\" relation=\"p-p\"/>"
                + "<archive/><color argb=\"-1\"/>";
        return event(uid, "a-u-G", "h-g-i-g-o", lat, lon, Double.NaN, 24 * 3600, d);
    }

    /** Tells other devices to remove a marker or shape. */
    public static String delete(String targetUid, String targetType) {
        String d = "<link uid=\"" + esc(targetUid) + "\" type=\"" + esc(targetType) + "\" relation=\"p-p\"/><__forcedelete/>";
        return event(java.util.UUID.randomUUID().toString(), "t-x-d-d", "h-g-i-g-o", Double.NaN, Double.NaN, Double.NaN, 180, d);
    }

    /** Group chat message to "All Chat Rooms". */
    public static String chat(String myUid, String myCallsign, String text, double lat, double lon) {
        String id = java.util.UUID.randomUUID().toString();
        String d = "<__chat parent=\"RootContactGroup\" groupOwner=\"false\" messageId=\"" + id + "\" chatroom=\"" + ALL_CHAT
                + "\" id=\"" + ALL_CHAT + "\" senderCallsign=\"" + esc(myCallsign) + "\">"
                + "<chatgrp uid0=\"" + esc(myUid) + "\" uid1=\"" + ALL_CHAT + "\" id=\"" + ALL_CHAT + "\"/></__chat>"
                + "<link uid=\"" + esc(myUid) + "\" type=\"a-f-G-U-C\" relation=\"p-p\"/>"
                + "<remarks source=\"BAO.F.ATAK." + esc(myUid) + "\" to=\"" + ALL_CHAT + "\" time=\"" + iso(System.currentTimeMillis())
                + "\">" + esc(text) + "</remarks>";
        return event("GeoChat." + myUid + "." + ALL_CHAT + "." + id, "b-t-f", "h-g-i-g-o", lat, lon, Double.NaN, 24 * 3600, d);
    }

    /** A drawn line or polygon. */
    public static String shape(String uid, String name, List<double[]> pts, boolean closed) {
        StringBuilder d = new StringBuilder();
        for (double[] p : pts) d.append(String.format(Locale.US, "<link point=\"%.6f,%.6f\"/>", p[0], p[1]));
        if (closed && !pts.isEmpty())
            d.append(String.format(Locale.US, "<link point=\"%.6f,%.6f\"/>", pts.get(0)[0], pts.get(0)[1]));
        d.append("<strokeColor value=\"-1\"/><strokeWeight value=\"3.0\"/>");
        if (closed) d.append("<fillColor value=\"872415231\"/>");
        d.append("<contact callsign=\"").append(esc(name)).append("\"/><labels_on value=\"true\"/><archive/>");
        double[] c = pts.isEmpty() ? new double[]{Double.NaN, Double.NaN} : Shapes.center(pts);
        return event(uid, "u-d-f", "h-e", c[0], c[1], Double.NaN, 24 * 3600, d.toString());
    }

    /** Keep-alive for a server connection. */
    public static String ping(String myUid) {
        return event(myUid + "-ping", "t-x-c-t", "m-g", Double.NaN, Double.NaN, Double.NaN, 20, "");
    }

    // ---------- reading ----------

    /** One UDP packet: XML, or ATAK's "mesh" protobuf (0xbf 0x01 0xbf + payload). Null if unreadable. */
    public static Event parseDatagram(byte[] data, int len) {
        try {
            if (len > 3 && (data[0] & 0xFF) == 0xBF && (data[2] & 0xFF) == 0xBF) {
                if (data[1] != 1) return null;
                byte[] body = new byte[len - 3];
                System.arraycopy(data, 3, body, 0, body.length);
                return parseProto(body);
            }
            return parseXml(new String(data, 0, len, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Event parseXml(String xml) {
        if (xml == null) return null;
        // no document type declarations: nothing legitimate needs them and they are how XML attacks work
        if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) return null;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setExpandEntityReferences(false);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element ev = doc.getDocumentElement();
            if (ev == null || !"event".equals(ev.getTagName())) return null;
            Event e = new Event();
            e.uid = ev.getAttribute("uid");
            e.type = ev.getAttribute("type");
            e.stale = time(ev.getAttribute("stale"));
            Element pt = child(ev, "point");
            if (pt != null) {
                e.lat = num(pt.getAttribute("lat"));
                e.lon = num(pt.getAttribute("lon"));
            }
            readDetail(e, child(ev, "detail"));
            classify(e);
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    private static void readDetail(Event e, Element d) {
        boolean isPerson = false;
        if (d != null) {
            Element contact = child(d, "contact");
            if (contact != null) e.callsign = contact.getAttribute("callsign");
            isPerson = child(d, "takv") != null || child(d, "__group") != null;
            Element chat = child(d, "__chat");
            if (chat != null) {
                e.chatRoom = chat.getAttribute("chatroom");
                e.chatToUid = chat.getAttribute("id");
                e.fromCallsign = chat.getAttribute("senderCallsign");
                Element grp = child(chat, "chatgrp");
                if (grp != null) e.fromUid = grp.getAttribute("uid0");
            }
            Element remarks = child(d, "remarks");
            if (remarks != null && chat != null) e.chatText = remarks.getTextContent();
            NodeList kids = d.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                Node n = kids.item(i);
                if (!(n instanceof Element) || !"link".equals(((Element) n).getTagName())) continue;
                Element link = (Element) n;
                String point = link.getAttribute("point");
                if (!point.isEmpty()) {
                    String[] f = point.split(",");
                    if (f.length >= 2) {
                        double la = num(f[0]), lo = num(f[1]);
                        if (!Double.isNaN(la) && !Double.isNaN(lo)) {
                            if (e.shape == null) e.shape = new ArrayList<>();
                            e.shape.add(new double[]{la, lo});
                        }
                    }
                } else if ("p-p".equals(link.getAttribute("relation"))) {
                    if (e.fromUid == null || e.fromUid.isEmpty()) e.fromUid = link.getAttribute("uid");
                    e.deleteUid = link.getAttribute("uid");
                }
            }
            if (child(d, "fillColor") != null) e.closed = true;
        }
        e.kind = isPerson ? Kind.PERSON : Kind.OTHER;
    }

    private static void classify(Event e) {
        boolean isPerson = e.kind == Kind.PERSON;
        e.kind = Kind.OTHER;
        String t = e.type == null ? "" : e.type;
        if (t.equals("b-t-f")) {
            if (e.chatText != null) e.kind = Kind.CHAT;
        } else if (t.equals("t-x-d-d")) {
            if (e.deleteUid != null && !e.deleteUid.isEmpty()) e.kind = Kind.DELETE;
        } else if (t.startsWith("u-d-") || t.equals("b-m-r")) {
            if (e.shape != null && e.shape.size() >= 2) {
                e.kind = Kind.SHAPE;
                // a ring that ends where it starts is an area
                double[] a = e.shape.get(0), b = e.shape.get(e.shape.size() - 1);
                if (e.shape.size() > 3 && Math.abs(a[0] - b[0]) < 1e-6 && Math.abs(a[1] - b[1]) < 1e-6) {
                    e.closed = true;
                    e.shape.remove(e.shape.size() - 1);
                } else if (e.shape.size() < 3) {
                    e.closed = false;
                }
                if (t.equals("b-m-r")) e.closed = false;
            }
        } else if (t.startsWith("a-")) {
            if (e.hasPosition()) e.kind = isPerson ? Kind.PERSON : Kind.MARKER;
        } else if (t.startsWith("b-m-p")) {
            if (e.hasPosition()) e.kind = Kind.MARKER;
        }
        if (e.kind != Kind.DELETE) e.deleteUid = null;
    }

    /** TakMessage protobuf: cotEvent = 2 { type 1, uid 5, stale 8, lat 10, lon 11, detail 15 { xmlDetail 1, contact 2 { callsign 2 }, group 3, takv 6 } } */
    public static Event parseProto(byte[] body) {
        Event e = new Event();
        boolean isPerson = false;
        String xmlDetail = null;
        Proto.Reader r = new Proto.Reader(body);
        byte[] cot = null;
        while (r.next()) if (r.field == 2 && r.wire == Proto.LEN) cot = r.bytes();
        if (cot == null) return null;
        Proto.Reader c = new Proto.Reader(cot);
        while (c.next()) {
            switch (c.field) {
                case 1: e.type = c.string(); break;
                case 5: e.uid = c.string(); break;
                case 8: e.stale = c.value; break;
                case 10: e.lat = Double.longBitsToDouble(c.value); break;
                case 11: e.lon = Double.longBitsToDouble(c.value); break;
                case 15: {
                    Proto.Reader d = new Proto.Reader(c.bytes());
                    while (d.next()) {
                        if (d.field == 1) xmlDetail = d.string();
                        else if (d.field == 2) {
                            Proto.Reader ct = new Proto.Reader(d.bytes());
                            while (ct.next()) if (ct.field == 2) e.callsign = ct.string();
                        } else if (d.field == 3 || d.field == 6) isPerson = true;
                    }
                    break;
                }
                default: break;
            }
        }
        if (xmlDetail != null && !xmlDetail.isEmpty() && !xmlDetail.contains("<!")) {
            try {
                Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                        new ByteArrayInputStream(("<detail>" + xmlDetail + "</detail>").getBytes(StandardCharsets.UTF_8)));
                String call = e.callsign;
                readDetail(e, doc.getDocumentElement());
                if (e.kind == Kind.PERSON) isPerson = true;
                if (e.callsign == null || e.callsign.isEmpty()) e.callsign = call;
            } catch (Exception ignored) {
            }
        }
        e.kind = isPerson ? Kind.PERSON : Kind.OTHER;
        classify(e);
        return e;
    }

    private static Element child(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element && tag.equals(((Element) n).getTagName())) return (Element) n;
        }
        return null;
    }

    private static double num(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return Double.NaN; }
    }

    private static long time(String iso) {
        if (iso == null || iso.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(iso.substring(0, 19));
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Cuts a TCP byte stream into whole <event>...</event> documents. */
    public static final class Splitter {
        private final StringBuilder buf = new StringBuilder();

        public List<String> feed(byte[] data, int len) {
            buf.append(new String(data, 0, len, StandardCharsets.UTF_8));
            List<String> out = new ArrayList<>();
            while (true) {
                int start = buf.indexOf("<event");
                if (start < 0) { if (buf.length() > 16) buf.delete(0, buf.length() - 16); break; }
                int end = buf.indexOf("</event>", start);
                if (end < 0) {
                    if (start > 0) buf.delete(0, start);
                    if (buf.length() > 2_000_000) buf.setLength(0);
                    break;
                }
                out.add(buf.substring(start, end + 8));
                buf.delete(0, end + 8);
            }
            return out;
        }
    }
}
