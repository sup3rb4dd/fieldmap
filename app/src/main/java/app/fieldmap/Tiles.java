package app.fieldmap;

import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.MapTileIndex;

/** Map layers. USGS layers are public domain and may be downloaded for offline use. */
public final class Tiles {
    private Tiles() {}

    /** ArcGIS-style z/y/x tile server from USGS The National Map. */
    static class Usgs extends OnlineTileSourceBase {
        Usgs(String name, String service, int maxZoom) {
            super(name, 0, maxZoom, 256, "",
                    new String[]{"https://basemap.nationalmap.gov/arcgis/rest/services/" + service + "/MapServer/tile/"},
                    "USGS The National Map");
        }

        @Override
        public String getTileURLString(long index) {
            return getBaseUrl() + MapTileIndex.getZoom(index) + "/"
                    + MapTileIndex.getY(index) + "/" + MapTileIndex.getX(index);
        }
    }

    /**
     * USGS imagery with extra zoom: cached tiles up to zoom 16, then the USGS NAIP Plus image
     * service (about 0.6–1 m per pixel, public domain) renders 17–19 on request.
     */
    static class UsgsImageryHd extends OnlineTileSourceBase {
        private static final double HALF = 20037508.342789244;
        private final String cached, naip;

        UsgsImageryHd(String name, String cachedService) {
            this(name, cachedService, USGS_NAIP);
        }

        UsgsImageryHd(String name, String cachedService, String naipExport) {
            super(name, 0, 19, 256, "",
                    new String[]{"https://basemap.nationalmap.gov/arcgis/rest/services/" + cachedService + "/MapServer/tile/"},
                    "USGS The National Map / NAIP");
            cached = "https://basemap.nationalmap.gov/arcgis/rest/services/" + cachedService + "/MapServer/tile/";
            naip = naipExport;
        }

        @Override
        public String getTileURLString(long index) {
            int z = MapTileIndex.getZoom(index), x = MapTileIndex.getX(index), y = MapTileIndex.getY(index);
            if (z <= 16) return cached + z + "/" + y + "/" + x;
            double size = 2 * HALF / (1L << z);
            double xmin = -HALF + x * size, xmax = xmin + size;
            double ymax = HALF - y * size, ymin = ymax - size;
            return naip + "?bbox=" + xmin + "," + ymin + "," + xmax + "," + ymax
                    + "&bboxSR=3857&imageSR=3857&size=256,256&format=jpg&f=image";
        }
    }

    /** NAIP image services that can render close-zoom imagery on request. */
    public static final String USGS_NAIP =
            "https://imagery.nationalmap.gov/arcgis/rest/services/USGSNAIPPlus/ImageServer/exportImage";
    public static final String GEOPLATFORM_NAIP =
            "https://imagery.geoplatform.gov/iipp/rest/services/NAIP/NAIP_plus/ImageServer/exportImage";

    public static final String[] NAMES = {
            "USGS Topo (offline OK)",
            "USGS Satellite HD (offline OK)",
            "USGS Satellite + Topo (offline OK)",
            "OpenStreetMap (online only)",
            "Satellite HD · alternate server (offline OK)"
    };

    public static ITileSource byIndex(int i) {
        switch (i) {
            case 1: return new UsgsImageryHd("USGSImageryHD", "USGSImageryOnly");
            case 2: return new Usgs("USGSImageryTopo", "USGSImageryTopo", 16);
            case 3: return TileSourceFactory.MAPNIK;
            case 4: return new UsgsImageryHd("GeoPlatformImageryHD", "USGSImageryOnly", GEOPLATFORM_NAIP);
            default: return new Usgs("USGSTopo", "USGSTopo", 16);
        }
    }

    /** Highest zoom with real (not stretched) tiles for each layer. */
    public static int maxNativeZoom(int i) {
        switch (i) {
            case 1: return 19;
            case 3: return 19;
            case 4: return 19;
            default: return 16;
        }
    }

    public static boolean allowsDownload(int i) {
        return i != 3;
    }
}
