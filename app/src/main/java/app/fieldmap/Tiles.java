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

    public static final String[] NAMES = {
            "USGS Topo (offline OK)",
            "USGS Satellite (offline OK)",
            "USGS Satellite + Topo (offline OK)",
            "OpenStreetMap (online only)"
    };

    public static ITileSource byIndex(int i) {
        switch (i) {
            case 1: return new Usgs("USGSImagery", "USGSImageryOnly", 16);
            case 2: return new Usgs("USGSImageryTopo", "USGSImageryTopo", 16);
            case 3: return TileSourceFactory.MAPNIK;
            default: return new Usgs("USGSTopo", "USGSTopo", 16);
        }
    }

    public static boolean allowsDownload(int i) {
        return i != 3;
    }
}
