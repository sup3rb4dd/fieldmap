package app.fieldmap.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

/** Reads points, lines and polygons out of GPX, KML and KMZ files. */
public final class GeoImport {
    private GeoImport() {}

    public static final class Item {
        public String name = "";
        public boolean point, closed;
        public List<double[]> pts = new ArrayList<>();
    }

    public static List<Item> parse(byte[] file) throws IOException {
        if (file.length > 3 && file[0] == 'P' && file[1] == 'K') file = unzipKml(file);
        // entity definitions can be used to attack XML readers; no real map file needs them.
        // The whole file is checked, and so are 16-bit encodings that would hide the text from this check.
        if (file.length >= 2 && ((file[0] == (byte) 0xFE && file[1] == (byte) 0xFF) || (file[0] == (byte) 0xFF && file[1] == (byte) 0xFE)
                || file[0] == 0 || file[1] == 0)) throw new IOException("file not accepted");
        if (new String(file, StandardCharsets.ISO_8859_1).contains("<!ENTITY")) throw new IOException("file not accepted");
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setExpandEntityReferences(false);
            doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(file));
        } catch (Exception e) {
            throw new IOException("not a readable GPX or KML file");
        }
        Element root = doc.getDocumentElement();
        String tag = local(root);
        List<Item> out = new ArrayList<>();
        if (tag.equals("gpx")) gpx(root, out);
        else if (tag.equals("kml")) kml(root, out);
        else throw new IOException("not a GPX or KML file");
        return out;
    }

    private static byte[] unzipKml(byte[] zip) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory() || !e.getName().toLowerCase().endsWith(".kml")) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > 30_000_000) throw new IOException("KML inside the KMZ is too large");
                }
                return bo.toByteArray();
            }
        }
        throw new IOException("no KML found inside the KMZ");
    }

    // ---------- GPX ----------

    private static void gpx(Element root, List<Item> out) {
        for (Element w : all(root, "wpt")) {
            Item it = new Item();
            it.point = true;
            it.name = text(w, "name");
            double[] p = latLon(w);
            if (p != null) { it.pts.add(p); out.add(it); }
        }
        for (Element r : all(root, "rte")) {
            Item it = new Item();
            it.name = text(r, "name");
            for (Element p : all(r, "rtept")) { double[] ll = latLon(p); if (ll != null) it.pts.add(ll); }
            if (it.pts.size() >= 2) out.add(it);
        }
        for (Element t : all(root, "trk")) {
            String name = text(t, "name");
            int seg = 0;
            for (Element s : all(t, "trkseg")) {
                Item it = new Item();
                it.name = seg++ == 0 ? name : name + " " + seg;
                for (Element p : all(s, "trkpt")) { double[] ll = latLon(p); if (ll != null) it.pts.add(ll); }
                if (it.pts.size() >= 2) out.add(it);
            }
        }
    }

    private static double[] latLon(Element e) {
        try {
            double la = Double.parseDouble(e.getAttribute("lat")), lo = Double.parseDouble(e.getAttribute("lon"));
            return valid(la, lo) ? new double[]{la, lo} : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ---------- KML ----------

    private static void kml(Element root, List<Item> out) {
        for (Element pm : all(root, "Placemark")) {
            String name = text(pm, "name");
            for (Element pt : all(pm, "Point")) {
                List<double[]> c = coords(pt);
                if (c.isEmpty()) continue;
                Item it = new Item();
                it.point = true;
                it.name = name;
                it.pts.add(c.get(0));
                out.add(it);
            }
            for (Element ls : all(pm, "LineString")) {
                List<double[]> c = coords(ls);
                if (c.size() < 2) continue;
                Item it = new Item();
                it.name = name;
                it.pts = c;
                out.add(it);
            }
            for (Element pg : all(pm, "Polygon")) {
                List<Element> outer = all(pg, "outerBoundaryIs");
                List<double[]> c = coords(outer.isEmpty() ? pg : outer.get(0));
                if (c.size() > 1 && same(c.get(0), c.get(c.size() - 1))) c.remove(c.size() - 1);
                if (c.size() < 3) continue;
                Item it = new Item();
                it.name = name;
                it.closed = true;
                it.pts = c;
                out.add(it);
            }
        }
    }

    private static boolean same(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-7 && Math.abs(a[1] - b[1]) < 1e-7;
    }

    /** First <coordinates> under e: "lon,lat[,alt] lon,lat[,alt] ..." */
    private static List<double[]> coords(Element e) {
        List<double[]> out = new ArrayList<>();
        List<Element> cs = all(e, "coordinates");
        if (cs.isEmpty()) return out;
        for (String tok : cs.get(0).getTextContent().trim().split("\\s+")) {
            String[] f = tok.split(",");
            if (f.length < 2) continue;
            try {
                double lo = Double.parseDouble(f[0]), la = Double.parseDouble(f[1]);
                if (valid(la, lo)) out.add(new double[]{la, lo});
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    // ---------- helpers ----------

    private static boolean valid(double la, double lo) {
        return !Double.isNaN(la) && !Double.isNaN(lo) && Math.abs(la) <= 90 && Math.abs(lo) <= 180;
    }

    private static String local(Element e) {
        String t = e.getTagName();
        int c = t.indexOf(':');
        return c >= 0 ? t.substring(c + 1) : t;
    }

    /** All descendants with this tag name, ignoring any namespace prefix. */
    private static List<Element> all(Element root, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nl = root.getElementsByTagName("*");
        for (int i = 0; i < nl.getLength(); i++) {
            Element e = (Element) nl.item(i);
            if (local(e).equals(tag)) out.add(e);
        }
        return out;
    }

    /** Text of a direct child element. */
    private static String text(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element && local((Element) n).equals(tag)) return n.getTextContent().trim();
        }
        return "";
    }
}
