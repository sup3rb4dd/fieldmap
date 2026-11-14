package app.fieldmap.core;

import java.nio.charset.StandardCharsets;

/**
 * Meshtastic phone API messages (ToRadio / FromRadio), field numbers from
 * github.com/meshtastic/protobufs mesh.proto and portnums.proto.
 */
public final class Mesh {
    private Mesh() {}

    public static final int BROADCAST = 0xFFFFFFFF;
    public static final int PORT_TEXT = 1, PORT_POSITION = 3, PORT_NODEINFO = 4, PORT_ROUTING = 5, PORT_WAYPOINT = 8;

    // ---------- outgoing ----------

    public static byte[] wantConfig(int configId) {
        return new Proto.Writer().varint(3, configId & 0xFFFFFFFFL).toBytes();
    }

    public static byte[] packet(int to, int channel, int port, byte[] payload, int packetId, boolean wantAck) {
        Proto.Writer data = new Proto.Writer().varint(1, port).bytes(2, payload);
        Proto.Writer mp = new Proto.Writer()
                .fixed32(2, to, true)
                .varint(3, channel)
                .message(4, data)
                .fixed32(6, packetId)
                .bool(10, wantAck);
        return new Proto.Writer().message(1, mp).toBytes();
    }

    public static byte[] text(int to, int channel, String text, int packetId) {
        return packet(to, channel, PORT_TEXT, text.getBytes(StandardCharsets.UTF_8), packetId, true);
    }

    public static byte[] positionPayload(double lat, double lon, int altitudeM, int timeSec) {
        return new Proto.Writer()
                .fixed32(1, (int) Math.round(lat * 1e7), true)
                .fixed32(2, (int) Math.round(lon * 1e7), true)
                .int32(3, altitudeM)
                .fixed32(4, timeSec)
                .toBytes();
    }

    public static byte[] waypointPayload(int id, double lat, double lon, int expire, String name, String desc) {
        return new Proto.Writer()
                .varint(1, id & 0xFFFFFFFFL)
                .fixed32(2, (int) Math.round(lat * 1e7), true)
                .fixed32(3, (int) Math.round(lon * 1e7), true)
                .varint(4, expire & 0xFFFFFFFFL)
                .string(6, name)
                .string(7, desc)
                .toBytes();
    }

    // ---------- incoming ----------

    public enum Kind { MY_INFO, NODE_INFO, CONFIG_COMPLETE, TEXT, POSITION, USER, WAYPOINT, REBOOTED, OTHER }

    public static final class Node {
        public int num;
        public String id = "", longName = "", shortName = "";
        public double lat = Double.NaN, lon = Double.NaN;
        public long lastHeard; // unix seconds
        public int channel;    // channel index the node was last heard on
        public boolean hasPosition() { return !Double.isNaN(lat); }
        public String label() {
            if (!shortName.isEmpty() && !longName.isEmpty()) return longName;
            if (!longName.isEmpty()) return longName;
            if (!shortName.isEmpty()) return shortName;
            return nodeId(num);
        }
    }

    public static final class Waypoint {
        public int id, expire;
        public double lat = Double.NaN, lon = Double.NaN;
        public String name = "", desc = "";
        public boolean hasPosition() { return !Double.isNaN(lat); }
    }

    public static final class Event {
        public Kind kind = Kind.OTHER;
        public int myNode, configId;
        public Node node;
        public int from, to, channel, port, packetId;
        public String text;
        public double lat = Double.NaN, lon = Double.NaN;
        public Waypoint waypoint;
    }

    public static String nodeId(int num) {
        return String.format("!%08x", num);
    }

    public static Event parseFromRadio(byte[] b) {
        Event e = new Event();
        Proto.Reader r = new Proto.Reader(b);
        while (r.next()) {
            switch (r.field) {
                case 2: parsePacket(e, r.bytes()); break;
                case 3: e.kind = Kind.MY_INFO; e.myNode = parseMyInfo(r.bytes()); break;
                case 4: e.kind = Kind.NODE_INFO; e.node = parseNodeInfo(r.bytes()); break;
                case 7: e.kind = Kind.CONFIG_COMPLETE; e.configId = r.i32(); break;
                case 8: e.kind = Kind.REBOOTED; break;
                default: break;
            }
        }
        return e;
    }

    private static int parseMyInfo(byte[] b) {
        Proto.Reader r = new Proto.Reader(b);
        while (r.next()) if (r.field == 1) return r.i32();
        return 0;
    }

    static Node parseNodeInfo(byte[] b) {
        Node n = new Node();
        Proto.Reader r = new Proto.Reader(b);
        while (r.next()) {
            switch (r.field) {
                case 1: n.num = r.i32(); break;
                case 2: parseUser(n, r.bytes()); break;
                case 3: {
                    double[] ll = parsePosition(r.bytes());
                    if (ll != null) { n.lat = ll[0]; n.lon = ll[1]; }
                    break;
                }
                case 5: n.lastHeard = r.value & 0xFFFFFFFFL; break;
                case 7: n.channel = r.i32(); break;
                default: break;
            }
        }
        if (n.id.isEmpty()) n.id = nodeId(n.num);
        return n;
    }

    static void parseUser(Node n, byte[] b) {
        Proto.Reader r = new Proto.Reader(b);
        while (r.next()) {
            switch (r.field) {
                case 1: n.id = r.string(); break;
                case 2: n.longName = r.string(); break;
                case 3: n.shortName = r.string(); break;
                default: break;
            }
        }
    }

    /** Returns {lat, lon} or null when the position is empty. */
    static double[] parsePosition(byte[] b) {
        Proto.Reader r = new Proto.Reader(b);
        Integer lat = null, lon = null;
        while (r.next()) {
            if (r.field == 1) lat = r.i32();
            else if (r.field == 2) lon = r.i32();
        }
        if (lat == null || lon == null || (lat == 0 && lon == 0)) return null;
        return new double[]{lat / 1e7, lon / 1e7};
    }

    static Waypoint parseWaypoint(byte[] b) {
        Waypoint w = new Waypoint();
        Proto.Reader r = new Proto.Reader(b);
        Integer lat = null, lon = null;
        while (r.next()) {
            switch (r.field) {
                case 1: w.id = r.i32(); break;
                case 2: lat = r.i32(); break;
                case 3: lon = r.i32(); break;
                case 4: w.expire = r.i32(); break;
                case 6: w.name = r.string(); break;
                case 7: w.desc = r.string(); break;
                default: break;
            }
        }
        if (lat != null && lon != null && !(lat == 0 && lon == 0)) { w.lat = lat / 1e7; w.lon = lon / 1e7; }
        return w;
    }

    private static void parsePacket(Event e, byte[] b) {
        Proto.Reader r = new Proto.Reader(b);
        byte[] decoded = null;
        while (r.next()) {
            switch (r.field) {
                case 1: e.from = r.i32(); break;
                case 2: e.to = r.i32(); break;
                case 3: e.channel = r.i32(); break;
                case 4: decoded = r.bytes(); break;
                case 6: e.packetId = r.i32(); break;
                default: break;
            }
        }
        if (decoded == null) return; // still encrypted (not for our channels)
        byte[] payload = new byte[0];
        Proto.Reader d = new Proto.Reader(decoded);
        while (d.next()) {
            if (d.field == 1) e.port = d.i32();
            else if (d.field == 2) payload = d.bytes();
        }
        switch (e.port) {
            case PORT_TEXT:
                e.kind = Kind.TEXT;
                e.text = new String(payload, StandardCharsets.UTF_8);
                break;
            case PORT_POSITION: {
                double[] ll = parsePosition(payload);
                if (ll != null) { e.kind = Kind.POSITION; e.lat = ll[0]; e.lon = ll[1]; }
                break;
            }
            case PORT_NODEINFO: {
                Node n = new Node();
                n.num = e.from;
                parseUser(n, payload);
                e.kind = Kind.USER;
                e.node = n;
                break;
            }
            case PORT_WAYPOINT:
                e.kind = Kind.WAYPOINT;
                e.waypoint = parseWaypoint(payload);
                break;
            default:
                break;
        }
    }
}
