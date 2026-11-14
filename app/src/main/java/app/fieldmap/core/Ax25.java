package app.fieldmap.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** AX.25 UI frames as used by APRS. */
public final class Ax25 {
    public final String dest;
    public final String source;
    public final List<String> path;
    public final byte[] info;

    public Ax25(String dest, String source, List<String> path, byte[] info) {
        this.dest = dest; this.source = source; this.path = path; this.info = info;
    }

    public String infoString() {
        return new String(info, StandardCharsets.ISO_8859_1);
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder(source).append('>').append(dest);
        for (String p : path) sb.append(',').append(p);
        return sb.append(':').append(infoString()).toString();
    }

    public static Ax25 ui(String source, String dest, List<String> path, String info) {
        return new Ax25(dest, source, path, info.getBytes(StandardCharsets.ISO_8859_1));
    }

    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> addrs = new ArrayList<>();
        addrs.add(dest);
        addrs.add(source);
        addrs.addAll(path);
        for (int i = 0; i < addrs.size(); i++) {
            writeAddr(out, addrs.get(i), i == 0, i == addrs.size() - 1);
        }
        out.write(0x03); // UI frame
        out.write(0xF0); // no layer 3
        out.write(info, 0, info.length);
        return out.toByteArray();
    }

    private static void writeAddr(ByteArrayOutputStream out, String addr, boolean isDest, boolean last) {
        String a = addr.trim().toUpperCase(Locale.US);
        boolean repeated = a.endsWith("*");
        if (repeated) a = a.substring(0, a.length() - 1);
        String call = a;
        int ssid = 0;
        int dash = a.indexOf('-');
        if (dash >= 0) {
            call = a.substring(0, dash);
            try { ssid = Integer.parseInt(a.substring(dash + 1)); } catch (NumberFormatException ignored) {}
        }
        if (call.length() > 6) call = call.substring(0, 6);
        for (int i = 0; i < 6; i++) {
            char c = i < call.length() ? call.charAt(i) : ' ';
            out.write((c & 0x7F) << 1);
        }
        int b = 0x60 | ((ssid & 0x0F) << 1);
        if (isDest) b |= 0x80; // command bit
        if (repeated) b |= 0x80;
        if (last) b |= 0x01;
        out.write(b);
    }

    /** Returns null if the bytes are not a valid UI frame. */
    public static Ax25 decode(byte[] f) {
        if (f == null || f.length < 16) return null;
        List<String> addrs = new ArrayList<>();
        int pos = 0;
        boolean last = false;
        while (!last) {
            if (pos + 7 > f.length || addrs.size() > 10) return null;
            StringBuilder call = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                char c = (char) ((f[pos + i] & 0xFF) >> 1);
                if (c != ' ') call.append(c);
            }
            int b = f[pos + 6] & 0xFF;
            int ssid = (b >> 1) & 0x0F;
            String s = call.toString();
            if (ssid != 0) s += "-" + ssid;
            if (addrs.size() >= 2 && (b & 0x80) != 0) s += "*";
            addrs.add(s);
            last = (b & 0x01) != 0;
            pos += 7;
        }
        if (addrs.size() < 2 || pos + 2 > f.length) return null;
        if ((f[pos] & 0xFF) != 0x03) return null; // only UI frames
        pos += 2; // control + PID
        byte[] info = new byte[f.length - pos];
        System.arraycopy(f, pos, info, 0, info.length);
        return new Ax25(addrs.get(0), addrs.get(1), new ArrayList<>(addrs.subList(2, addrs.size())), info);
    }
}
