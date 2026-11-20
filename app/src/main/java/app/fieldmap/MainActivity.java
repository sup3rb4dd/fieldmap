package app.fieldmap;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.ParcelUuid;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.cachemanager.CacheManager;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.ScaleBarOverlay;
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.fieldmap.core.Geo;
import app.fieldmap.core.Gpx;
import app.fieldmap.core.Mesh;
import app.fieldmap.core.Mgrs;

@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity implements FieldMapApp.Listener {

    private static final int REQ_LOCATION = 1, REQ_BT = 2, REQ_MESH = 3;
    private static final int REQ_BACKUP = 21, REQ_RESTORE = 22, REQ_GPX = 23;
    static final int REQ_IMPORT = 24, REQ_MBTILES = 25;

    FieldMapApp app;
    MapView map;
    MapTools tools;
    private MyLocationNewOverlay myLocOverlay;
    private Polyline rangeLine;
    private final List<Overlay> itemOverlays = new ArrayList<>();
    private TextView tvMe, tvCenter, tvTarget, radioStatus, tvClock;
    private LinearLayout targetRow;
    private Button btnChat;
    private LocationManager lm;
    int layerIndex;

    private ScaleBarOverlay scale;
    private Polyline trailLine;
    private int trailDrawn = 0;
    private final List<Overlay> wxOverlays = new ArrayList<>();
    private String wxDrawn = "";
    private Compass compass;
    private ArrowView navArrow;
    private TextView tvSos, tvWx, tvHdg, tvGmrs, tvUpdate;
    private boolean meshAsked = false;
    private Button btnSos, btnNorth;
    private float heading = Float.NaN;
    private boolean headingUp = false;

    // selected target: "pin:<id>" or "sta:<call>" or "pt:<lat>,<lon>"
    // routes being followed: "rte:<point index>:<shape id>"
    String target;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = FieldMapApp.get();
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue(getPackageName());
        // keep map tiles in app storage (works on all Android versions, no storage permission)
        java.io.File base = new java.io.File(getFilesDir(), "osmdroid");
        Configuration.getInstance().setOsmdroidBasePath(base);
        Configuration.getInstance().setOsmdroidTileCache(new java.io.File(base, "tiles"));
        Configuration.getInstance().setTileFileSystemCacheMaxBytes(3000L * 1024 * 1024);
        Configuration.getInstance().setTileFileSystemCacheTrimBytes(2800L * 1024 * 1024);
        // HD imagery is rendered on demand by USGS and can be slow: fetch more tiles in parallel
        Configuration.getInstance().setTileDownloadThreads((short) 8);
        Configuration.getInstance().setTileDownloadMaxQueueSize((short) 80);
        setContentView(R.layout.activity_main);

        map = findViewById(R.id.map);
        tvMe = findViewById(R.id.tvMe);
        tvCenter = findViewById(R.id.tvCenter);
        tvTarget = findViewById(R.id.tvTarget);
        targetRow = findViewById(R.id.targetRow);
        radioStatus = findViewById(R.id.radioStatus);
        btnChat = findViewById(R.id.btnChat);
        tools = new MapTools(this, map);

        setupMap();

        findViewById(R.id.btnMe).setOnClickListener(v -> centerOnMe());
        findViewById(R.id.btnPin).setOnClickListener(v -> {
            IGeoPoint c = map.getMapCenter();
            if (tools.drawing) tools.addPoint(c.getLatitude(), c.getLongitude());
            else promptNewPin(c.getLatitude(), c.getLongitude());
        });
        findViewById(R.id.btnGrid).setOnClickListener(v -> promptGoToGrid());
        findViewById(R.id.btnLayer).setOnClickListener(v -> chooseLayer());
        tvClock = findViewById(R.id.tvClock);
        navArrow = findViewById(R.id.navArrow);
        tvSos = findViewById(R.id.tvSos);
        tvWx = findViewById(R.id.tvWx);
        tvHdg = findViewById(R.id.tvHdg);
        btnSos = findViewById(R.id.btnSos);
        btnNorth = findViewById(R.id.btnNorth);
        btnNorth.setOnClickListener(v -> {
            setHeadingUp(false);
            map.setMapOrientation(0);
            toastOnce("hdgUp", "Hold this button to make the map turn with you");
        });
        btnNorth.setOnLongClickListener(v -> { setHeadingUp(!headingUp); return true; });
        btnSos.setOnClickListener(v -> sosTapped());
        tvSos.setOnClickListener(v -> sosBannerTapped());
        tvGmrs = findViewById(R.id.tvGmrs);
        tvGmrs.setOnClickListener(v -> gmrsTapped());
        tvUpdate = findViewById(R.id.tvUpdate);
        tvUpdate.setOnClickListener(v -> updateTapped());
        tvWx.setOnClickListener(v -> showWeather(app.weather.top()));
        tvTarget.setOnClickListener(v -> {
            double[] t = targetPosition();
            if (t != null) tools.terrainTo(t[0], t[1], targetName());
        });
        compass = new Compass(this, this::onHeading);
        // keep the scale bar just under the top panel, however tall the banners make it
        findViewById(R.id.topBox).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            if (b != ob && scale != null) {
                scale.setScaleBarOffset(30, b + (int) (10 * getResources().getDisplayMetrics().density));
                map.invalidate();
            }
        });

        findViewById(R.id.btnClearTarget).setOnClickListener(v -> { target = null; refreshTarget(); });
        btnChat.setOnClickListener(v -> startActivity(new Intent(this, ChatActivity.class)));
        findViewById(R.id.btnList).setOnClickListener(v -> showList());
        findViewById(R.id.btnMenu).setOnClickListener(v -> showMenu());
        radioStatus.setOnClickListener(v -> radioTapped());

        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (hasLocationPermission()) startLocation();
        else ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED && !app.prefs.getBoolean("askedNotif", false)) {
            app.prefs.edit().putBoolean("askedNotif", true).apply();
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9);
        }

        if (!app.hasCallsign() && !app.prefs.getBoolean("introShown", false)) {
            app.prefs.edit().putBoolean("introShown", true).apply();
            new AlertDialog.Builder(this)
                    .setTitle("◆ FIELDMAP // BRIEFING")
                    .setMessage("• Long-press the map to drop a pin.\n"
                            + "• Tap any pin or station to get range and bearing.\n"
                            + "• ≡ ▸ Download this area saves maps for offline use.\n"
                            + "• SOS (red button) alerts your team with your grid.\n\n"
                            + "Tap RADIO at the top to connect a UV-PRO (APRS) and/or a Meshtastic radio like the Heltec V4.\n\n"
                            + "UV-PRO: turn on Menu ▸ General Settings ▸ KISS TNC and pair it in Android Bluetooth settings first. "
                            + "APRS transmit needs your callsign in Settings.\n\n"
                            + "Meshtastic: close the official Meshtastic app first; no callsign needed.")
                    .setPositiveButton("Settings", (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
                    .setNegativeButton("Later", null)
                    .show();
        }

        app.autoConnect();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** "Show on map" from chat: jump to a grid and track it. */
    private void handleIntent(Intent i) {
        if (i == null || !i.hasExtra("gotoLat")) return;
        double lat = i.getDoubleExtra("gotoLat", 0), lon = i.getDoubleExtra("gotoLon", 0);
        String name = i.getStringExtra("gotoName");
        i.removeExtra("gotoLat");
        if (map.getZoomLevelDouble() < 13) map.getController().setZoom(15.0);
        map.getController().setCenter(new GeoPoint(lat, lon));
        target = "pt:" + lat + "," + lon + "," + (name == null ? "" : name.replace(",", " "));
        refreshTarget();
    }

    // ---------- map ----------

    private void setupMap() {
        layerIndex = app.prefs.getInt("layer", 0);
        tools.applyLayer(layerIndex);
        map.setMultiTouchControls(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        applyDetail();
        map.setMinZoomLevel(3.0);
        map.setMaxZoomLevel(19.0);

        double lat = Double.longBitsToDouble(app.prefs.getLong("mapLat", Double.doubleToLongBits(39.5)));
        double lon = Double.longBitsToDouble(app.prefs.getLong("mapLon", Double.doubleToLongBits(-98.35)));
        double zoom = Double.longBitsToDouble(app.prefs.getLong("mapZoom", Double.doubleToLongBits(4.0)));
        map.getController().setZoom(zoom);
        map.getController().setCenter(new GeoPoint(lat, lon));

        MapEventsOverlay events = new MapEventsOverlay(new MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(GeoPoint p) {
                if (!tools.drawing) return false;
                tools.addPoint(p.getLatitude(), p.getLongitude());
                return true;
            }
            @Override public boolean longPressHelper(GeoPoint p) {
                if (tools.drawing) return false;
                promptNewPin(p.getLatitude(), p.getLongitude());
                return true;
            }
        });
        map.getOverlays().add(0, events);

        RotationGestureOverlay rot = new RotationGestureOverlay(map);
        rot.setEnabled(true);
        map.getOverlays().add(rot);

        scale = new ScaleBarOverlay(map);
        scale.setAlignBottom(false);
        scale.setScaleBarOffset(30, (int) (108 * getResources().getDisplayMetrics().density));
        scale.setUnitsOfMeasure(app.imperial() ? ScaleBarOverlay.UnitsOfMeasure.imperial : ScaleBarOverlay.UnitsOfMeasure.metric);
        scale.getBarPaint().setColor(Color.parseColor("#FF39FF14"));
        scale.getBarPaint().setStrokeWidth(3 * getResources().getDisplayMetrics().density);
        scale.getTextPaint().setColor(Color.parseColor("#FF39FF14"));
        scale.getTextPaint().setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD));
        scale.getTextPaint().setShadowLayer(4, 0, 0, Color.BLACK);
        map.getOverlays().add(scale);

        float dd = getResources().getDisplayMetrics().density;
        trailLine = new PassiveLine();
        trailLine.getOutlinePaint().setColor(Color.parseColor("#B339FF14"));
        trailLine.getOutlinePaint().setStrokeWidth(2f * dd);
        trailLine.setInfoWindow(null);
        map.getOverlays().add(trailLine);

        rangeLine = new PassiveLine();
        rangeLine.getOutlinePaint().setColor(Color.parseColor("#FFFFB000"));
        rangeLine.getOutlinePaint().setStrokeWidth(2.5f * dd);
        rangeLine.getOutlinePaint().setPathEffect(new android.graphics.DashPathEffect(new float[]{10 * dd, 6 * dd}, 0));
        rangeLine.setVisible(false);
        rangeLine.setInfoWindow(null);
        map.getOverlays().add(rangeLine);

        myLocOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(this), map);
        Bitmap me = selfIcon(false), meMoving = selfIcon(true);
        myLocOverlay.setDirectionArrow(me, meMoving);
        myLocOverlay.setPersonAnchor(0.5f, 0.5f);
        myLocOverlay.setDirectionAnchor(0.5f, 0.5f);
        map.getOverlays().add(myLocOverlay);

        map.addMapListener(new MapListener() {
            @Override public boolean onScroll(ScrollEvent event) { updateCenterText(); tools.onMapMoved(); return false; }
            @Override public boolean onZoom(ZoomEvent event) { updateCenterText(); tools.onMapMoved(); return false; }
        });
        refreshMarkers();
    }

    /**
     * Map sharpness. Scaling 256 px tiles up by the full screen density (3x on most phones)
     * makes everything blurry; a smaller factor loads the next zoom level's tiles instead.
     */
    void applyDetail() {
        float density = getResources().getDisplayMetrics().density;
        int detail = app.prefs.getInt("detail", 1); // 0 sharp, 1 balanced, 2 large text
        float factor = detail == 0 ? 1f : detail == 2 ? density : Math.max(1f, density * 0.6f);
        map.setTilesScaleFactor(factor);
        map.setTilesScaledToDpi(false); // also re-applies the tile size
    }

    /** Shows when map imagery is still loading or missing, so blur isn't a mystery. */
    private void updateTileStatus() {
        TextView tv = findViewById(R.id.tvTiles);
        try {
            org.osmdroid.tileprovider.TileStates ts = map.getOverlayManager().getTilesOverlay().getTileStates();
            int total = ts.getTotal();
            int good = ts.getUpToDate() + ts.getExpired();
            int missing = ts.getNotFound();
            int loading = total - good - missing;
            if (total == 0 || (loading <= 0 && missing == 0)) { tv.setVisibility(android.view.View.GONE); return; }
            tv.setVisibility(android.view.View.VISIBLE);
            if (loading > 0) {
                tv.setTextColor(ContextCompat.getColor(this, R.color.warn));
                tv.setText(String.format(Locale.US, "IMG LOADING %d/%d · Z%d — close zoom can take 15s",
                        good, total, (int) map.getZoomLevelDouble()));
            } else {
                tv.setTextColor(ContextCompat.getColor(this, R.color.bad));
                tv.setText(missing + " TILES UNAVAILABLE · try LYR ▸ alternate server");
            }
        } catch (Exception e) {
            tv.setVisibility(android.view.View.GONE);
        }
    }

    /** Fetches one normal and one HD tile at the map center and reports what the servers sent back. */
    private void runMapDiagnostics() {
        IGeoPoint c = map.getMapCenter();
        double lat = c.getLatitude(), lon = c.getLongitude();
        org.osmdroid.tileprovider.tilesource.ITileSource src = map.getTileProvider().getTileSource();
        StringBuilder sb = new StringBuilder();
        String version = "?";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        sb.append("App ").append(version).append("\nLayer: ").append(src.name())
                .append(" (max ").append(src.getMaximumZoomLevel()).append(")")
                .append(String.format(Locale.US, "\nZoom %.1f · detail %d · scale %.2f",
                        map.getZoomLevelDouble(), app.prefs.getInt("detail", 1), map.getTilesScaleFactor()));
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("// MAP DIAGNOSTICS")
                .setMessage(sb + "\n\nTesting tile servers…")
                .setPositiveButton("Close", null)
                .show();
        new Thread(() -> {
            StringBuilder r = new StringBuilder(sb);
            String[] hdServers = {Tiles.USGS_NAIP, Tiles.GEOPLATFORM_NAIP};
            for (int z : new int[]{16, 18}) {
                int n = 1 << z;
                int x = (int) Math.floor((lon + 180) / 360 * n);
                double latR = Math.toRadians(lat);
                int y = (int) Math.floor((1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2 * n);
                long idx = org.osmdroid.util.MapTileIndex.getTileIndex(z, x, y);
                String url = src instanceof org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
                        ? ((org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase) src).getTileURLString(idx) : "";
                if (z > src.getMaximumZoomLevel()) {
                    r.append("\n\nz").append(z).append(": layer stops at ").append(src.getMaximumZoomLevel());
                    continue;
                }
                r.append("\n\nz").append(z).append(": ").append(probe(url));
            }
            // also try both close-zoom imagery servers directly, whatever layer is showing
            int z = 18, n = 1 << z;
            int x = (int) Math.floor((lon + 180) / 360 * n);
            double latR = Math.toRadians(lat);
            int y = (int) Math.floor((1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2 * n);
            double half = 20037508.342789244, size = 2 * half / n;
            double xmin = -half + x * size, ymax = half - y * size;
            for (String srv : hdServers) {
                String u = srv + "?bbox=" + xmin + "," + (ymax - size) + "," + (xmin + size) + "," + ymax
                        + "&bboxSR=3857&imageSR=3857&size=256,256&format=jpg&f=image";
                r.append("\n\nHD ").append(srv.contains("geoplatform") ? "ALT" : "USGS").append(": ").append(probe(u));
            }
            app.log("Map diagnostics:\n" + r);
            runOnUiThread(() -> { if (dlg.isShowing()) dlg.setMessage(r + "\n\n(Copied to Packet log)"); });
        }).start();
    }

    private String probe(String url) {
        long t0 = System.currentTimeMillis();
        java.net.HttpURLConnection con = null;
        try {
            con = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setRequestProperty("User-Agent", Configuration.getInstance().getUserAgentValue());
            int code = con.getResponseCode();
            String type = con.getContentType();
            java.io.InputStream in = code < 400 ? con.getInputStream() : con.getErrorStream();
            byte[] buf = new byte[65536];
            int total = 0, k;
            StringBuilder head = new StringBuilder();
            while (in != null && (k = in.read(buf)) > 0) {
                if (total == 0 && type != null && !type.startsWith("image"))
                    head.append(new String(buf, 0, Math.min(k, 200), "UTF-8"));
                total += k;
            }
            long ms = System.currentTimeMillis() - t0;
            String host = new java.net.URL(url).getHost();
            return "HTTP " + code + " · " + type + " · " + total + " bytes · " + ms + " ms · " + host
                    + (head.length() > 0 ? "\n" + head : "");
        } catch (Exception e) {
            return "FAILED after " + (System.currentTimeMillis() - t0) + " ms: " + e;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    // ---------- compass and steering ----------

    private void onHeading(float h) {
        heading = h;
        tvHdg.setText(String.format(Locale.US, "HDG %03.0f°", h));
        if (headingUp) map.setMapOrientation(-h);
        updateArrow();
    }

    private void setHeadingUp(boolean on) {
        if (on && !compass.available()) {
            toast("This phone has no compass sensor");
            return;
        }
        boolean was = headingUp;
        headingUp = on;
        btnNorth.setText(on ? app.lbl("HDG↑", "TURN") : app.lbl("N↑", "NORTH"));
        if (was && !on) map.setMapOrientation(0);
    }

    /** Points the steering arrow at the tracked target, relative to the top of the phone. */
    private void updateArrow() {
        double[] t = targetPosition();
        Location l = app.lastLoc;
        if (t == null || l == null) { navArrow.setInactive(); return; }
        double hdg;
        if (!Float.isNaN(heading)) hdg = heading;
        else if (l.hasBearing() && l.hasSpeed() && l.getSpeed() > 1.5f) hdg = l.getBearing();
        else { navArrow.setInactive(); return; }
        navArrow.setAngle((float) Geo.turn(Geo.bearing(l.getLatitude(), l.getLongitude(), t[0], t[1]), hdg));
    }

    // ---------- my trail ----------

    private void syncTrail() {
        boolean on = app.prefs.getBoolean("trails", true);
        if (!on) { trailLine.setVisible(false); return; }
        int n = app.trail.size();
        if (n == trailDrawn && n > 0) { trailLine.setVisible(n >= 2); return; }
        if (n < trailDrawn || trailDrawn == 0 || n - trailDrawn > 50) {
            // first draw, cleared, trimmed, or far behind: rebuild (thinned out if very long)
            List<double[]> pts = app.trail.copy();
            int step = Math.max(1, pts.size() / 3000);
            List<GeoPoint> gp = new ArrayList<>();
            for (int i = 0; i < pts.size(); i += step) gp.add(new GeoPoint(pts.get(i)[0], pts.get(i)[1]));
            if (!pts.isEmpty() && (pts.size() - 1) % step != 0)
                gp.add(new GeoPoint(pts.get(pts.size() - 1)[0], pts.get(pts.size() - 1)[1]));
            trailLine.setPoints(gp);
            n = pts.size();
        } else {
            for (int i = trailDrawn; i < n; i++) {
                double[] p = app.trail.get(i);
                trailLine.addPoint(new GeoPoint(p[0], p[1]));
            }
        }
        trailDrawn = n;
        trailLine.setVisible(n >= 2); // a line needs two points
        map.invalidate();
    }

    private void trailMenu() {
        int n = app.trail.size();
        String title = "// MY TRAIL · " + n + " PTS · " + Geo.formatDistance(app.trail.length(), app.imperial()).toUpperCase(Locale.US);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(new String[]{"Export as GPX file (with my waypoints)", "Clear my trail"}, (d, which) -> {
                    if (which == 0) {
                        String day = new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new java.util.Date());
                        pickFile(Intent.ACTION_CREATE_DOCUMENT, "application/gpx+xml", "fieldmap-" + day + ".gpx", REQ_GPX);
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("// CLEAR TRAIL?")
                                .setMessage("Deletes all " + n + " recorded points from this phone.")
                                .setPositiveButton("Clear", (d2, w) -> { app.trail.clear(); trailDrawn = 0; syncTrail(); })
                                .setNegativeButton("Cancel", null).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ---------- files: GPX export, backup, restore ----------

    void pickFile(String action, String mime, String name, int req) {
        try {
            Intent i = new Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType(mime);
            if (name != null) i.putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(i, req);
        } catch (Exception e) {
            toast("No file picker available on this phone");
        }
    }

    private void backupMenu() {
        new AlertDialog.Builder(this)
                .setTitle("// BACKUP / RESTORE")
                .setItems(new String[]{"Save a backup file (places, waypoints, settings)", "Restore from a backup file"}, (d, which) -> {
                    if (which == 0) {
                        String day = new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(new java.util.Date());
                        pickFile(Intent.ACTION_CREATE_DOCUMENT, "application/json", "fieldmap-backup-" + day + ".json", REQ_BACKUP);
                    } else {
                        pickFile(Intent.ACTION_OPEN_DOCUMENT, "*/*", null, REQ_RESTORE);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static final List<String> BACKUP_SKIP = Arrays.asList(
            "mapLat", "mapLon", "mapZoom", "introShown", "askedNotif", "msgCounter",
            "lastRadio", "lastMesh", "wantAprs", "wantMesh", "takUid", "layer");

    private String backupJson() throws Exception {
        org.json.JSONObject root = new org.json.JSONObject();
        root.put("app", "FieldMap");
        root.put("version", 1);
        root.put("exported", System.currentTimeMillis());
        org.json.JSONObject st = new org.json.JSONObject();
        for (java.util.Map.Entry<String, ?> e : app.prefs.getAll().entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (BACKUP_SKIP.contains(k) || k.startsWith("hint_")) continue;
            if (v instanceof Boolean || v instanceof Integer || v instanceof String) st.put(k, v);
        }
        root.put("settings", st);
        root.put("pins", app.store.exportMyPins());
        root.put("shapes", app.store.exportMyShapes());
        return root.toString(2);
    }

    private void restoreFrom(String json) throws Exception {
        final org.json.JSONObject root = new org.json.JSONObject(json);
        if (!"FieldMap".equals(root.optString("app"))) { toast("That isn't a FieldMap backup file"); return; }
        final org.json.JSONArray pins = root.optJSONArray("pins");
        int n = pins == null ? 0 : pins.length();
        new AlertDialog.Builder(this)
                .setTitle("// RESTORE")
                .setItems(new String[]{"Places & waypoints only (" + n + ")", "Everything, including settings and callsign"}, (d, which) -> {
                    if (which == 1) {
                        org.json.JSONObject st = root.optJSONObject("settings");
                        android.content.SharedPreferences.Editor ed = app.prefs.edit();
                        for (java.util.Iterator<String> it = st == null ? null : st.keys(); it != null && it.hasNext(); ) {
                            String k = it.next();
                            Object v = st.opt(k);
                            if (BACKUP_SKIP.contains(k)) continue;
                            if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
                            else if (v instanceof Integer) ed.putInt(k, (Integer) v);
                            else if (v instanceof String) ed.putString(k, (String) v);
                        }
                        ed.apply();
                    }
                    int got = app.store.importPins(pins) + app.store.importShapes(root.optJSONArray("shapes"));
                    toast("Restored " + got + " places, waypoints and drawings" + (which == 1 ? " plus settings" : ""));
                    applyDetail();
                    app.applyTakSettings();
                    app.updateService();
                    refreshMarkers();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String gpxText() {
        List<String> names = new ArrayList<>();
        List<double[]> wpts = new ArrayList<>();
        for (Store.Pin p : app.store.pins.values()) {
            if (!p.isMine() || !p.src.isEmpty()) continue;
            names.add(p.name);
            wpts.add(new double[]{p.lat, p.lon});
        }
        List<String> rteNames = new ArrayList<>();
        List<List<double[]>> rtes = new ArrayList<>();
        for (Store.Shape sh : app.store.shapes.values()) {
            if (!sh.isMine() || !sh.src.isEmpty()) continue;
            List<double[]> pts = new ArrayList<>(sh.pts);
            if (sh.closed && !pts.isEmpty()) pts.add(pts.get(0));
            rteNames.add(sh.name);
            rtes.add(pts);
        }
        return Gpx.build("FieldMap trail", app.trail.copy(), names, wpts, rteNames, rtes);
    }

    private void writeText(android.net.Uri uri, String text) throws Exception {
        java.io.OutputStream out;
        try {
            out = getContentResolver().openOutputStream(uri, "wt");
        } catch (Exception e) {
            out = getContentResolver().openOutputStream(uri);
        }
        if (out == null) throw new java.io.IOException("could not open the file");
        try {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    private String readText(android.net.Uri uri) throws Exception {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("could not open the file");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int k;
            while ((k = in.read(buf)) > 0) {
                bo.write(buf, 0, k);
                if (bo.size() > 5_000_000) throw new java.io.IOException("file too large");
            }
            return bo.toString("UTF-8");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            if (requestCode == REQ_GPX) { writeText(data.getData(), gpxText()); toast("Trail saved"); }
            else if (requestCode == REQ_BACKUP) { writeText(data.getData(), backupJson()); toast("Backup saved"); }
            else if (requestCode == REQ_RESTORE) restoreFrom(readText(data.getData()));
            else if (requestCode == REQ_IMPORT) tools.importFile(data.getData());
            else if (requestCode == REQ_MBTILES) tools.copyMbtiles(data.getData());
        } catch (Exception e) {
            toast("File problem: " + e.getMessage());
        }
    }

    // ---------- SOS ----------

    private void sosTapped() {
        if (app.sosActive) {
            new AlertDialog.Builder(this)
                    .setTitle("// YOUR SOS IS ACTIVE")
                    .setMessage("It repeats every 2 minutes with your current grid, for up to an hour.")
                    .setPositiveButton("Send all-clear & stop", (d, w) -> { app.cancelSos(); updateBanners(); })
                    .setNegativeButton("Keep sending", null)
                    .show();
            return;
        }
        String links = app.sosLinks();
        if (links.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("// NO RADIO CONNECTED")
                    .setMessage("SOS goes out over your radios, and none is connected"
                            + (app.radio.isConnected() ? " that can transmit (the UV-PRO needs a callsign in Settings)" : "")
                            + ".\n\nIf you have phone service, call 911.")
                    .setPositiveButton("Connect a radio", (d, w) -> radioTapped())
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("// SEND SOS?")
                .setMessage("Sends *SOS* with your name and grid to your team now, then every 2 minutes until you cancel (1 hour at most). "
                        + "Their phones sound an alarm and show you in red.\n\nGoes out on:\n" + links
                        + (app.lastLoc == null ? "\n\nNo GPS fix yet: it goes without a position until one arrives." : "")
                        + "\n\nThis reaches your team's radios only. It does not call 911.")
                .setPositiveButton("SEND SOS", (d, w) -> {
                    if (!app.startSos()) toast("No radio connected");
                    updateBanners();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sosBannerTapped() {
        androidx.core.app.NotificationManagerCompat.from(this).cancel(Alerts.ID_SOS);
        String key = app.activeSosFrom();
        if (key == null) { if (app.sosActive) sosTapped(); return; }
        Store.Station s = app.store.stations.get(key);
        if (s == null) { toast("No position received from " + app.displayName(key) + " yet"); return; }
        target = "sta:" + key;
        if (map.getZoomLevelDouble() < 13) map.getController().setZoom(15.0);
        map.getController().animateTo(new GeoPoint(s.lat, s.lon));
        refreshTarget();
    }

    private static android.graphics.drawable.Drawable frame(int stroke, int fill, float density) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setStroke((int) (2 * density), stroke);
        return g;
    }

    /** SOS and weather banners under the top panel. Runs every second so the SOS one can flash. */
    private void updateBanners() {
        long now = System.currentTimeMillis();
        float dd = getResources().getDisplayMetrics().density;
        boolean flash = (now / 700) % 2 == 0;
        int red = 0xFFFF3B30, black = 0xF0000000;
        String key = app.activeSosFrom();
        String sosText = null;
        if (key != null) {
            Store.Station s = app.store.stations.get(key);
            Location l = app.lastLoc;
            Long heard = app.sosFrom.get(key);
            sosText = "▲ SOS · " + app.displayName(key).toUpperCase(Locale.US)
                    + (heard == null ? "" : " · " + Geo.ago(heard, now).toUpperCase(Locale.US)) + "\n"
                    + (s == null ? "NO POSITION RECEIVED YET"
                    : (l == null ? Mgrs.format(s.lat, s.lon) : rngBrg(l.getLatitude(), l.getLongitude(), s.lat, s.lon)) + " · TAP TO TRACK");
        } else if (app.sosActive) {
            sosText = "▲ YOUR SOS IS TRANSMITTING\nTAP TO SEND ALL-CLEAR";
        }
        if (sosText == null) {
            tvSos.setVisibility(android.view.View.GONE);
        } else {
            tvSos.setText(sosText);
            tvSos.setTextColor(flash ? Color.BLACK : red);
            tvSos.setBackground(frame(red, flash ? red : black, dd));
            tvSos.setVisibility(android.view.View.VISIBLE);
        }
        btnSos.setText(app.sosActive ? "SOS●" : "SOS");

        // GMRS mode: an amber bar that is always there while the mode is on
        if (!app.gmrs()) {
            tvGmrs.setVisibility(android.view.View.GONE);
        } else {
            String line2;
            if (!app.gmrsReady()) line2 = "SETUP NOT FINISHED · TAP";
            else if (!app.radio.isConnected()) line2 = "RADIO NOT CONNECTED · NOTHING IS BEING SENT";
            else {
                int w = app.gmrsWaitSeconds(), n = app.gmrsQueue.size();
                line2 = (n > 0 ? n + " WAITING · NEXT BURST IN " + w + " S" : w > 0 ? "CAN SEND AGAIN IN " + w + " S" : "READY TO SEND")
                        + " · NOT ENCRYPTED";
            }
            tvGmrs.setText("▲ GMRS MODE ON" + (app.gmrsReady() ? " · " + app.gmrsCall() + " / " + app.gmrsUnit() : "") + "\n" + line2);
            tvGmrs.setBackground(frame(0xFFFFB000, 0xFFFFB000, dd));
            tvGmrs.setVisibility(android.view.View.VISIBLE);
        }
        int ub = app.updateBuild();
        if (ub == 0) tvUpdate.setVisibility(android.view.View.GONE);
        else {
            tvUpdate.setText("⬇ NEWER BUILD AVAILABLE (" + ub + ") · TAP");
            tvUpdate.setVisibility(android.view.View.VISIBLE);
        }

        Weather.Alert a = app.prefs.getBoolean("weather", true) ? app.weather.top() : null;
        if (a == null) {
            tvWx.setVisibility(android.view.View.GONE);
        } else {
            int more = app.weather.hereCount() - 1;
            String until = a.expires == 0 ? "" : " · UNTIL "
                    + new java.text.SimpleDateFormat("h:mm a", Locale.US).format(new java.util.Date(a.expires));
            tvWx.setText("⚠ " + a.event.toUpperCase(Locale.US) + until + (more > 0 ? "  (+" + more + ")" : "") + "\nTAP FOR DETAILS");
            tvWx.setTextColor(a.color());
            tvWx.setBackground(frame(a.color(), black, dd));
            tvWx.setVisibility(android.view.View.VISIBLE);
        }
    }

    // ---------- weather ----------

    private void drawWeather() {
        boolean on = app.prefs.getBoolean("weather", true);
        String sig = on + "|" + app.weather.lastOk;
        if (sig.equals(wxDrawn)) return;
        wxDrawn = sig;
        map.getOverlays().removeAll(wxOverlays);
        wxOverlays.clear();
        if (on) {
            float dd = getResources().getDisplayMetrics().density;
            for (final Weather.Alert a : app.weather.alerts) {
                if (!a.active()) continue;
                for (List<double[]> ring : a.polygons) {
                    org.osmdroid.views.overlay.Polygon pg = new org.osmdroid.views.overlay.Polygon(map);
                    List<GeoPoint> pts = new ArrayList<>();
                    for (double[] p : ring) pts.add(new GeoPoint(p[0], p[1]));
                    pg.setPoints(pts);
                    pg.getFillPaint().setColor((a.color() & 0x00FFFFFF) | 0x2E000000);
                    pg.getOutlinePaint().setColor(a.color());
                    pg.getOutlinePaint().setStrokeWidth(2.5f * dd);
                    pg.setInfoWindow(null);
                    pg.setOnClickListener((polygon, mapView, pos) -> {
                        if (tools.drawing) return false;
                        showWeather(a);
                        return true;
                    });
                    wxOverlays.add(pg);
                }
            }
            // index 0 is the long-press catcher; warnings sit just above it, under every marker
            map.getOverlays().addAll(Math.min(1, map.getOverlays().size()), wxOverlays);
        }
        map.invalidate();
    }

    private void showWeather(Weather.Alert a) {
        if (a == null) return;
        String body = (a.headline.isEmpty() ? "" : a.headline + "\n\n")
                + (a.area.isEmpty() ? "" : "AREA: " + a.area + "\n\n")
                + (a.instruction.isEmpty() ? "" : "WHAT TO DO: " + a.instruction + "\n\n")
                + a.description;
        if (body.length() > 4000) body = body.substring(0, 4000) + "…";
        new AlertDialog.Builder(this)
                .setTitle("⚠ " + a.event.toUpperCase(Locale.US))
                .setMessage(body + "\n\nSource: National Weather Service")
                .setPositiveButton("Close", null)
                .show();
    }

    private void weatherMenu() {
        if (!app.prefs.getBoolean("weather", true)) { toast("Weather warnings are turned off in Settings"); return; }
        Weather w = app.weather;
        final List<Weather.Alert> here = new ArrayList<>();
        for (Weather.Alert a : w.alerts) if (a.here && a.active()) here.add(a);
        String status = w.lastOk == 0
                ? (w.lastError != null ? "Couldn't reach the weather service (" + w.lastError + ")"
                : app.lastLoc == null ? "Waiting for a GPS fix" : "Not checked yet")
                : "Checked " + Geo.ago(w.lastOk, System.currentTimeMillis())
                + (w.lastError != null ? " · latest try failed, showing the last good data" : "");
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("// WEATHER · NWS");
        if (here.isEmpty()) {
            b.setMessage("No active alerts for your location.\n" + status
                    + "\n\nNeeds internet. Tornado, severe storm and flash flood warning areas within about 250 miles are drawn on the map.");
        } else {
            String[] items = new String[here.size()];
            for (int i = 0; i < items.length; i++) items[i] = "⚠ " + here.get(i).event;
            b.setItems(items, (d, which) -> showWeather(here.get(which)));
        }
        b.setPositiveButton("Check now", (d, x) -> { app.pollWeather(true); toast("Checking…"); })
                .setNegativeButton("Close", null)
                .show();
    }

    // ---------- tactical HUD helpers ----------

    /** "RNG 1.24 MI · BRG 047° NE" */
    private String rngBrg(double lat1, double lon1, double lat2, double lon2) {
        double d = Geo.distance(lat1, lon1, lat2, lon2);
        double b = Geo.bearing(lat1, lon1, lat2, lon2);
        return String.format(Locale.US, "RNG %s · BRG %03.0f° %s",
                Geo.formatDistance(d, app.imperial()).toUpperCase(Locale.US), b, Geo.cardinal(b));
    }

    private final android.os.Handler clockHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            boolean local = app.prefs.getBoolean("localTime", false);
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(local ? "HH:mm  dd MMM" : "ddHHmm'Z' MMMyy", Locale.US);
            if (!local) f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            tvClock.setText(f.format(new java.util.Date()).toUpperCase(Locale.US));
            updateTileStatus();
            updateBanners();
            clockHandler.postDelayed(this, 1000);
        }
    };

    private void chooseLayer() { tools.chooseLayer(); }

    private void gmrsTapped() {
        new AlertDialog.Builder(this)
                .setTitle("// GMRS MODE IS ON")
                .setMessage("The UV-PRO link is sending as GMRS station " + (app.gmrsReady() ? app.gmrsCall() + " (unit " + app.gmrsUnit() + ")" : "(setup not finished)")
                        + ", on whatever channel the radio is tuned to.\n\n"
                        + "• Team chat, pins, small routes and zones, and your position (when you send it) go out on GMRS.\n"
                        + "• One burst every 30 seconds, 60 characters per text. Extra messages wait in line; SOS jumps the line.\n"
                        + "• Not encrypted. Say your callsign by voice when you finish.\n"
                        + "• Use channels 1-22, not a repeater channel.\n\n"
                        + "Turn it off in Settings ▸ GMRS mode.")
                .setPositiveButton("Settings", (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
                .setNegativeButton("Close", null)
                .show();
    }

    private void updateTapped() {
        new AlertDialog.Builder(this)
                .setTitle("// NEWER BUILD: " + app.updateBuild())
                .setMessage("You have build " + app.versionCode() + ". Download the new one and open it to install over this one. Your pins and settings stay.\n\n"
                        + "If Android says the app can't be installed, the signing key changed: use ≡ ▸ Files ▸ Backup first, uninstall FieldMap, install the new one, then restore.")
                .setPositiveButton("Download", (d, w) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(FieldMapApp.DOWNLOAD_URL))); }
                    catch (Exception e) { toast("No browser found"); }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    /** Button names: military-style or plain, as chosen in Settings. */
    private void applyLabels() {
        ((Button) findViewById(R.id.btnMe)).setText(app.lbl("POS", "ME"));
        ((Button) findViewById(R.id.btnPin)).setText(app.lbl("+WPT", "+PIN"));
        ((Button) findViewById(R.id.btnGrid)).setText(app.lbl("GRID", "GO TO"));
        ((Button) findViewById(R.id.btnLayer)).setText(app.lbl("LYR", "MAP"));
        ((Button) findViewById(R.id.btnList)).setText(app.lbl("TRK", "TEAM"));
        ((Button) findViewById(R.id.btnMenu)).setText(app.lbl("≡", "MENU"));
        ((Button) findViewById(R.id.btnMenu)).setTextSize(app.prefs.getBoolean("plain", false) ? 12 : 16);
        btnNorth.setText(headingUp ? app.lbl("HDG↑", "TURN") : app.lbl("N↑", "NORTH"));
    }

    /** Phones (Samsung especially) stop background apps to save battery unless told not to. */
    private void askBattery(boolean force) {
        if (Build.VERSION.SDK_INT < 23) return;
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            if (force) toast("Already allowed: Android will not put FieldMap to sleep");
            return;
        }
        if (!force && app.prefs.getBoolean("askedBattery", false)) return;
        app.prefs.edit().putBoolean("askedBattery", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("// KEEP RUNNING WITH THE SCREEN OFF?")
                .setMessage("To save battery, Android may put FieldMap to sleep in your pocket, and alerts and position reports would stop without warning. "
                        + "Allow it to keep running? It only stays awake while a radio is connected.\n\n"
                        + "Samsung phones: also check Settings ▸ Battery ▸ Background usage limits, and keep FieldMap out of the sleeping apps list.")
                .setPositiveButton("Allow", (d, w) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        try { startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                        catch (Exception e2) { toast("Open Android Settings ▸ Battery and set FieldMap to Unrestricted"); }
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    /** Meshtastic just connected and the team channel is 0, which is public on a factory-default radio. */
    private void askMeshChannel() {
        if (meshAsked || !app.mesh.isConnected() || app.meshTeamOk() || isFinishing()) return;
        meshAsked = true;
        new AlertDialog.Builder(this)
                .setTitle("// IS YOUR MESH CHANNEL PRIVATE?")
                .setMessage("Your team channel is set to 0. On a Meshtastic radio with factory settings, channel 0 is the public channel that every Meshtastic user nearby shares, "
                        + "so your chat, pins and position would go to strangers.\n\n"
                        + "Team traffic is on hold until you choose. Receiving still works, and SOS always goes out.\n\n"
                        + "• If your group changed channel 0 to its own name and key in the Meshtastic app, it is private: choose that.\n"
                        + "• If your private channel is a different number, pick it in Settings ▸ Privacy.\n"
                        + "• Not sure? Keep it on hold.")
                .setPositiveButton("Channel 0 is private", (d, w) -> {
                    app.prefs.edit().putBoolean("meshCh0Ok", true).apply();
                    updateRadioStatus();
                })
                .setNeutralButton("Settings", (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
                .setNegativeButton("Keep on hold", null)
                .show();
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("// HELP")
                .setMessage("THE MAP\n"
                        + "• Drag to move, pinch to zoom. The crosshair marks the middle; its grid is on the bottom line.\n"
                        + "• Long-press the map, or tap " + app.lbl("+WPT", "+PIN") + ", to drop a pin. Tap a pin or a teammate for distance, direction, and options.\n"
                        + "• " + app.lbl("POS", "ME") + " jumps to you. " + app.lbl("GRID", "GO TO") + " jumps to a grid you type. " + app.lbl("LYR", "MAP") + " changes the map type.\n"
                        + "• DRAW makes a route or a shaded zone. While drawing, hold a corner and then tap where it should go to move it.\n"
                        + "• ≡ ▸ Download this area saves the map for use without internet.\n\n"
                        + "RADIOS\n"
                        + "• Tap the status line at the top to connect. ▲ means connected.\n"
                        + "• Meshtastic (Heltec): private if your team uses its own channel. Close the Meshtastic app first.\n"
                        + "• UV-PRO as APRS: ham bands, public, needs a ham callsign.\n"
                        + "• UV-PRO as GMRS: turn on GMRS mode in Settings. Short texts and map data, one burst every 30 seconds, not encrypted. An amber bar shows while it is on.\n\n"
                        + "MESSAGES\n"
                        + "• " + app.lbl("MSG", "CHAT") + " opens team chat. Messages go out on every connected link.\n"
                        + "• ✓ means the other end (or the mesh) confirmed it. … means sent but not confirmed.\n"
                        + "• Tap a message with a grid in it to jump there.\n\n"
                        + "SOS\n"
                        + "• The red button sends your name and grid to your team every 2 minutes until you cancel. It reaches radios only. It does not call 911.\n\n"
                        + "IF SOMETHING IS OFF\n"
                        + "• ≡ ▸ Packet log shows everything sent and received. Use SEND TO… to share it.\n"
                        + "• Alerts stop with the screen off? ≡ ▸ Keep running in background.\n"
                        + "• Prefer plain words and local time? Settings ▸ Display.\n\n"
                        + "Build " + app.versionCode())
                .setPositiveButton("Close", null)
                .show();
    }

    private void centerOnMe() {
        Location l = app.lastLoc;
        if (l == null) {
            toast(hasLocationPermission() ? "Waiting for GPS fix…" : "Location permission is off");
            return;
        }
        if (map.getZoomLevelDouble() < 14) map.getController().setZoom(15.0);
        map.getController().animateTo(new GeoPoint(l.getLatitude(), l.getLongitude()));
    }

    private void downloadArea() {
        if (!Tiles.allowsDownload(layerIndex)) {
            toast(layerIndex == Tiles.MBTILES ? "This map is already an offline file" : "Switch to a USGS layer (LYR button) to download for offline use");
            return;
        }
        try {
            CacheManager cm = new CacheManager(map);
            BoundingBox bb = map.getBoundingBox();
            int z = (int) Math.floor(map.getZoomLevelDouble());
            int zMin = Math.max(5, z - 3);
            int zMax = Math.min(Tiles.maxNativeZoom(layerIndex), z + 3);
            int count = cm.possibleTilesInArea(bb, zMin, zMax);
            String size = String.format(Locale.US, "%.0f", count * 0.025); // ~25 KB per tile
            if (count > 25000) {
                new AlertDialog.Builder(this)
                        .setTitle("// AREA TOO LARGE")
                        .setMessage(count + " tiles. Zoom in closer and try again (limit 25,000).")
                        .setPositiveButton("OK", null).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("// CACHE AREA OFFLINE")
                    .setMessage("Save the visible area, zoom " + zMin + "–" + zMax + ".\n"
                            + count + " tiles, about " + size + " MB.\nKeep the app open until it finishes.")
                    .setPositiveButton("Download", (d, w) -> {
                        cm.downloadAreaAsync(this, bb, zMin, zMax);
                        // height data for the same area, so line of sight works offline too
                        if (Elevation.tilesInBox(bb.getLatNorth(), bb.getLatSouth(), bb.getLonEast(), bb.getLonWest()) <= 400)
                            app.elev.prefetch(bb.getLatNorth(), bb.getLatSouth(), bb.getLonEast(), bb.getLonWest(),
                                    (ok, failed) -> app.log("Elevation saved for offline: " + ok + " tiles" + (failed > 0 ? ", " + failed + " failed" : "")));
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            toast("This layer can't be downloaded: " + e.getMessage());
        }
    }

    // ---------- markers ----------

    void refreshMarkers() {
        map.getOverlays().removeAll(itemOverlays);
        itemOverlays.clear();
        long now = System.currentTimeMillis();
        int insertAt = map.getOverlays().indexOf(myLocOverlay);
        if (insertAt < 0) insertAt = map.getOverlays().size();

        for (Store.Pin p : app.store.pins.values()) {
            Marker m = new Marker(map);
            m.setPosition(new GeoPoint(p.lat, p.lon));
            int color = p.place ? Color.parseColor("#FFF2F2F2")
                    : p.isMine() ? Color.parseColor("#FFFFB000") : Color.parseColor("#FFFF6EC7");
            String label = p.isMine() ? p.name : p.name + " (" + p.owner + ")";
            if (!p.src.isEmpty()) color = Color.parseColor("#FFB0C4DE"); // imported
            m.setIcon(labelIcon(label, color, p.place ? 2 : 1));
            m.setAnchor(0.5f, iconAnchorY);
            m.setInfoWindow(null);
            String key = "pin:" + p.id;
            m.setOnMarkerClickListener((mk, mv) -> {
                if (tools.drawing) return false;
                pinTapped(p.id);
                return true;
            });
            itemOverlays.add(m);
        }
        boolean trails = app.prefs.getBoolean("trails", true);
        float dd = getResources().getDisplayMetrics().density;
        for (Store.Station s : app.store.stations.values()) {
            Marker m = new Marker(map);
            m.setPosition(new GeoPoint(s.lat, s.lon));
            boolean stale = now - s.time > 30 * 60 * 1000L;
            boolean sos = app.isSos(s.call);
            int color = sos ? Color.parseColor("#FFFF3B30") : stale ? Color.GRAY
                    : "MESH".equals(s.via) ? Color.parseColor("#FF00E5FF")
                    : "TAK".equals(s.via) ? Color.parseColor("#FF7FB2FF") : Color.parseColor("#FF39FF14");
            if (trails && s.trail.size() > 1) {
                Polyline tl = new PassiveLine();
                List<GeoPoint> tp = new ArrayList<>();
                for (double[] q : s.trail) tp.add(new GeoPoint(q[0], q[1]));
                tl.setPoints(tp);
                tl.getOutlinePaint().setColor((color & 0x00FFFFFF) | 0x99000000);
                tl.getOutlinePaint().setStrokeWidth(2f * dd);
                tl.setInfoWindow(null);
                itemOverlays.add(tl);
            }
            m.setIcon(labelIcon((sos ? "SOS " : "") + s.label(), color, 0));
            m.setAnchor(0.5f, iconAnchorY);
            m.setInfoWindow(null);
            m.setOnMarkerClickListener((mk, mv) -> {
                if (tools.drawing) return false;
                stationTapped(s.call);
                return true;
            });
            itemOverlays.add(m);
        }
        map.getOverlays().addAll(insertAt, itemOverlays);
        tools.syncShapes(trailLine);
        refreshTarget();
        map.invalidate();
    }

    private float iconAnchorY = 0.25f;

    /** Own-position symbol: green ring with a center dot, or a chevron when moving. */
    private Bitmap selfIcon(boolean moving) {
        float d = getResources().getDisplayMetrics().density;
        int size = (int) (36 * d);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float cx = size / 2f;
        Paint black = new Paint(Paint.ANTI_ALIAS_FLAG);
        black.setColor(Color.BLACK);
        black.setStyle(Paint.Style.STROKE);
        black.setStrokeWidth(5 * d);
        Paint green = new Paint(Paint.ANTI_ALIAS_FLAG);
        green.setColor(Color.parseColor("#FF39FF14"));
        green.setStyle(Paint.Style.STROKE);
        green.setStrokeWidth(2.5f * d);
        if (moving) {
            Path p = new Path();
            p.moveTo(cx, 3 * d); p.lineTo(size - 7 * d, size - 6 * d); p.lineTo(cx, size - 13 * d); p.lineTo(7 * d, size - 6 * d); p.close();
            c.drawPath(p, black);
            Paint fill = new Paint(green); fill.setStyle(Paint.Style.FILL); fill.setAlpha(110);
            c.drawPath(p, fill);
            c.drawPath(p, green);
        } else {
            c.drawCircle(cx, cx, 11 * d, black);
            c.drawCircle(cx, cx, 11 * d, green);
            Paint dot = new Paint(green); dot.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cx, 3.5f * d, dot);
            c.drawLine(cx, 0, cx, 6 * d, green);
            c.drawLine(cx, size - 6 * d, cx, size, green);
            c.drawLine(0, cx, 6 * d, cx, green);
            c.drawLine(size - 6 * d, cx, size, cx, green);
        }
        return bmp;
    }

    /** Diamond (waypoint) or bracketed square (station) with a monospace tag under it. */
    /** shape: 0 = station square, 1 = waypoint diamond, 2 = saved place (house). */
    private final java.util.Map<String, Drawable> iconCache = new java.util.HashMap<>();

    /** Marker pictures are reused: a busy mesh redraws the map often, and making hundreds of bitmaps each time is slow. */
    private Drawable labelIcon(String label, int color, int shape) {
        String key = shape + "|" + color + "|" + label;
        Drawable cached = iconCache.get(key);
        if (cached != null) return cached;
        if (iconCache.size() > 600) iconCache.clear();
        Drawable made = makeLabelIcon(label, color, shape);
        iconCache.put(key, made);
        return made;
    }

    private Drawable makeLabelIcon(String label, int color, int shape) {
        float d = getResources().getDisplayMetrics().density;
        label = label.toUpperCase(Locale.US);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextSize(11 * d);
        text.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        float tw = text.measureText(label);
        int sym = (int) (18 * d);
        int w = (int) Math.max(sym, tw + 8 * d);
        int h = (int) (sym * 2 + 16 * d);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float cx = w / 2f, cy = sym / 2f + 2 * d;
        iconAnchorY = cy / h;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setColor(Color.BLACK);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2 * d);
        float r = sym / 2f - 2 * d;
        if (shape == 2) {
            Path house = new Path();
            house.moveTo(cx, cy - r);                 // roof peak
            house.lineTo(cx + r, cy - r * 0.05f);
            house.lineTo(cx + r * 0.72f, cy - r * 0.05f);
            house.lineTo(cx + r * 0.72f, cy + r * 0.85f);
            house.lineTo(cx - r * 0.72f, cy + r * 0.85f);
            house.lineTo(cx - r * 0.72f, cy - r * 0.05f);
            house.lineTo(cx - r, cy - r * 0.05f);
            house.close();
            c.drawPath(house, edge);
            c.drawPath(house, fill);
            Paint door = new Paint();
            door.setColor(Color.BLACK);
            c.drawRect(cx - r * 0.2f, cy + r * 0.25f, cx + r * 0.2f, cy + r * 0.85f, door);
        } else if (shape == 1) {
            Path path = new Path();
            path.moveTo(cx, cy - r); path.lineTo(cx + r, cy); path.lineTo(cx, cy + r); path.lineTo(cx - r, cy); path.close();
            c.drawPath(path, fill);
            c.drawPath(path, edge);
        } else {
            float hs = r * 0.75f;
            c.drawRect(cx - hs, cy - hs, cx + hs, cy + hs, edge);
            Paint frame = new Paint(fill);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(2 * d);
            c.drawRect(cx - hs, cy - hs, cx + hs, cy + hs, frame);
            Paint core = new Paint(fill);
            c.drawRect(cx - hs * 0.35f, cy - hs * 0.35f, cx + hs * 0.35f, cy + hs * 0.35f, core);
        }
        float ty = sym + 12 * d;
        // tag plate: black box with colored text
        float tw2 = text.measureText(label) / 2 + 4 * d;
        Paint plate = new Paint();
        plate.setColor(Color.parseColor("#CC000000"));
        c.drawRect(cx - tw2, ty - 11 * d, cx + tw2, ty + 3 * d, plate);
        text.setColor(color);
        c.drawText(label, cx, ty, text);
        return new BitmapDrawable(getResources(), bmp);
    }

    // ---------- pins, stations, targets ----------

    private void promptNewPin(double lat, double lon) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView grid = new TextView(this);
        grid.setText(Mgrs.format(lat, lon));
        grid.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.addView(grid);
        EditText name = new EditText(this);
        name.setHint("Name (max 9 chars to share)");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setText("WPT" + (app.store.pins.size() + 1));
        name.selectAll();
        box.addView(name);
        android.widget.CheckBox place = new android.widget.CheckBox(this);
        place.setText("Saved place (home, family…) — private, never sent");
        box.addView(place);
        android.widget.CheckBox share = new android.widget.CheckBox(this);
        share.setText("Share with team over radio");
        share.setChecked(false);
        share.setEnabled(app.canShare());
        box.addView(share);
        place.setOnCheckedChangeListener((b, on) -> {
            share.setEnabled(!on && app.canShare());
            if (on) share.setChecked(false);
        });

        new AlertDialog.Builder(this)
                .setTitle("// MARK WAYPOINT")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) n = "WPT";
                    Store.Pin p = app.store.addMyPin(n, lat, lon);
                    p.place = place.isChecked();
                    app.store.save();
                    if (share.isChecked() && !p.place) app.sharePin(p, true);
                    refreshMarkers();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pinTapped(String id) {
        Store.Pin p = app.store.pins.get(id);
        if (p == null) return;
        String info = Mgrs.format(p.lat, p.lon) + "\n" + rangeFromMe(p.lat, p.lon)
                + (p.place ? "\nSaved place · private" : "")
                + (p.isMine() ? "" : "\nFrom " + p.owner + " · " + Geo.ago(p.time, System.currentTimeMillis()))
                + (p.comment == null || p.comment.isEmpty() ? "" : "\n" + p.comment);
        List<String> actions = new ArrayList<>();
        if (p.isMine() && !p.place) actions.add(p.shared ? "Re-send to team" : "Share with team");
        if (p.isMine()) actions.add("Rename");
        if (p.isMine()) actions.add(p.place ? "Make it a waypoint" : "Make it a saved place");
        actions.add("Delete");
        boolean tracking = ("pin:" + id).equals(target);
        new AlertDialog.Builder(this)
                .setTitle(p.name.toUpperCase(Locale.US))
                .setMessage(info)
                .setPositiveButton(tracking ? "Stop track" : "Track", (d, w) -> {
                    target = tracking ? null : "pin:" + id;
                    refreshTarget();
                })
                .setNegativeButton("Close", null)
                .setNeutralButton("More…", (d, w) -> new AlertDialog.Builder(this)
                        .setTitle(p.name.toUpperCase(Locale.US))
                        .setItems(actions.toArray(new String[0]), (d2, which) -> {
                            String a = actions.get(which);
                            if (a.startsWith("Share") || a.startsWith("Re-send")) {
                                if (requireShare()) {
                                    if (app.sharePin(p, true)) toast("Sent " + p.name);
                                }
                            } else if (a.equals("Rename")) {
                                renamePin(p);
                            } else if (a.startsWith("Make it a saved place")) {
                                if (p.shared && app.canShare()) app.sharePin(p, false);
                                p.place = true;
                                p.shared = false;
                                app.store.save();
                                refreshMarkers();
                            } else if (a.startsWith("Make it a waypoint")) {
                                p.place = false;
                                app.store.save();
                                refreshMarkers();
                            } else if (a.equals("Delete")) {
                                if (p.isMine() && p.shared && app.canShare()) app.sharePin(p, false);
                                app.store.removePin(p.id);
                                if (("pin:" + p.id).equals(target)) target = null;
                                refreshMarkers();
                            }
                        }).show())
                .show();
    }

    private void renamePin(Store.Pin p) {
        EditText name = new EditText(this);
        name.setText(p.name);
        new AlertDialog.Builder(this)
                .setTitle("// RENAME WAYPOINT")
                .setView(name)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) return;
                    boolean wasShared = p.shared;
                    if (wasShared && app.canShare()) app.sharePin(p, false);
                    p.name = n;
                    app.store.save();
                    if (wasShared && app.canShare()) app.sharePin(p, true);
                    refreshMarkers();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void stationTapped(String call) {
        Store.Station s = app.store.stations.get(call);
        if (s == null) return;
        String info = Mgrs.format(s.lat, s.lon) + "\n" + rangeFromMe(s.lat, s.lon)
                + "\nHeard " + Geo.ago(s.time, System.currentTimeMillis())
                + (s.checkTime > 0 ? "\nChecked in OK " + Geo.ago(s.checkTime, System.currentTimeMillis()) : "")
                + (app.isSos(call) ? "\n▲ SOS ACTIVE" : "")
                + (s.comment.isEmpty() ? "" : "\n" + s.comment);
        boolean tracking = ("sta:" + call).equals(target);
        new AlertDialog.Builder(this)
                .setTitle(s.label().toUpperCase(Locale.US) + ("MESH".equals(s.via) ? "  (" + s.call + ")" : ""))
                .setMessage(info)
                .setPositiveButton(tracking ? "Stop track" : "Track", (d, w) -> {
                    target = tracking ? null : "sta:" + call;
                    refreshTarget();
                })
                .setNeutralButton("Message", (d, w) -> {
                    Intent i = new Intent(this, ChatActivity.class);
                    i.putExtra("to", call);
                    startActivity(i);
                })
                .setNegativeButton("Remove", (d, w) -> {
                    app.store.removeStation(call);
                    if (("sta:" + call).equals(target)) target = null;
                    refreshMarkers();
                })
                .show();
    }

    /** Following a route: once I reach a point, aim at the next one. */
    private void advanceRoute() {
        if (target == null || !target.startsWith("rte:")) return;
        Location l = app.lastLoc;
        String[] rp = target.split(":", 3);
        Store.Shape s = rp.length < 3 ? null : app.store.shapes.get(rp[2]);
        if (l == null || s == null) return;
        int i = Integer.parseInt(rp[1]);
        double reach = Math.max(25, l.hasAccuracy() ? l.getAccuracy() : 0);
        while (i < s.pts.size() - 1 && Geo.distance(l.getLatitude(), l.getLongitude(), s.pts.get(i)[0], s.pts.get(i)[1]) < reach) i++;
        target = "rte:" + i + ":" + rp[2];
    }

    private String rangeFromMe(double lat, double lon) {
        Location l = app.lastLoc;
        if (l == null) return "Range: no GPS fix";
        return Geo.rangeBearing(l.getLatitude(), l.getLongitude(), lat, lon, app.imperial());
    }

    private double[] targetPosition() {
        if (target == null) return null;
        if (target.startsWith("pin:")) {
            Store.Pin p = app.store.pins.get(target.substring(4));
            return p == null ? null : new double[]{p.lat, p.lon};
        }
        if (target.startsWith("sta:")) {
            Store.Station s = app.store.stations.get(target.substring(4));
            return s == null ? null : new double[]{s.lat, s.lon};
        }
        if (target.startsWith("pt:")) {
            String[] parts = target.substring(3).split(",", 3);
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        }
        if (target.startsWith("rte:")) {
            String[] parts = target.split(":", 3);
            Store.Shape s = parts.length < 3 ? null : app.store.shapes.get(parts[2]);
            if (s == null) return null;
            int i = Math.max(0, Math.min(s.pts.size() - 1, Integer.parseInt(parts[1])));
            return s.pts.get(i);
        }
        return null;
    }

    private String targetName() {
        if (target == null) return "";
        if (target.startsWith("pin:")) {
            Store.Pin p = app.store.pins.get(target.substring(4));
            return p == null ? "" : p.name;
        }
        if (target.startsWith("sta:")) return app.displayName(target.substring(4));
        if (target.startsWith("rte:")) {
            String[] rp = target.split(":", 3);
            Store.Shape s = rp.length < 3 ? null : app.store.shapes.get(rp[2]);
            return s == null ? "" : s.name + " " + (Integer.parseInt(rp[1]) + 1) + "/" + s.pts.size();
        }
        String[] parts = target.substring(3).split(",", 3);
        return parts.length > 2 && !parts[2].trim().isEmpty() ? parts[2].trim() : "Grid point";
    }

    void refreshTarget() {
        advanceRoute();
        double[] t = targetPosition();
        if (t == null) {
            target = null;
            targetRow.setVisibility(android.view.View.GONE);
            rangeLine.setVisible(false);
            map.invalidate();
            return;
        }
        if (targetRow.getVisibility() != android.view.View.VISIBLE) {
            targetRow.setVisibility(android.view.View.VISIBLE);
            toastOnce("steer", "The arrow shows which way to walk. Hold the phone flat, top pointing ahead. Tap the readout for line of sight.");
        }
        updateArrow();
        Location l = app.lastLoc;
        if (l != null) {
            String nav = rngBrg(l.getLatitude(), l.getLongitude(), t[0], t[1]);
            if (l.hasSpeed() && l.getSpeed() > 0.7f && System.currentTimeMillis() - l.getTime() < 30_000L) {
                nav += "\nETA " + Geo.formatEta(Geo.distance(l.getLatitude(), l.getLongitude(), t[0], t[1]), l.getSpeed())
                        + " @ " + Geo.formatSpeed(l.getSpeed(), app.imperial());
            }
            tvTarget.setText(targetName().toUpperCase(Locale.US) + "\n" + nav);
            rangeLine.setPoints(Arrays.asList(new GeoPoint(l.getLatitude(), l.getLongitude()), new GeoPoint(t[0], t[1])));
            rangeLine.setVisible(true);
        } else {
            tvTarget.setText(targetName().toUpperCase(Locale.US) + "\nNO GPS FIX");
            rangeLine.setVisible(false);
        }
        map.invalidate();
    }

    private void promptGoToGrid() {
        EditText in = new EditText(this);
        in.setHint("e.g. 14S PF 12345 67890");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        IGeoPoint c = map.getMapCenter();
        String cur = Mgrs.format(c.getLatitude(), c.getLongitude());
        if (cur.length() > 6) in.setText(cur.substring(0, 6));
        in.setSelection(in.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("// GO TO GRID")
                .setView(in)
                .setPositiveButton("Go", (d, w) -> {
                    double[] ll = Mgrs.parse(in.getText().toString());
                    if (ll == null) { toast("Not a valid MGRS grid"); return; }
                    if (map.getZoomLevelDouble() < 13) map.getController().setZoom(14.0);
                    map.getController().animateTo(new GeoPoint(ll[0], ll[1]));
                    target = "pt:" + ll[0] + "," + ll[1];
                    refreshTarget();
                })
                .setNeutralButton("Go + pin", (d, w) -> {
                    double[] ll = Mgrs.parse(in.getText().toString());
                    if (ll == null) { toast("Not a valid MGRS grid"); return; }
                    if (map.getZoomLevelDouble() < 13) map.getController().setZoom(14.0);
                    map.getController().animateTo(new GeoPoint(ll[0], ll[1]));
                    promptNewPin(ll[0], ll[1]);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showList() {
        final List<String> keys = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<Double> dists = new ArrayList<>();
        Location l = app.lastLoc;
        long now = System.currentTimeMillis();
        for (Store.Station s : app.store.stations.values()) {
            keys.add("sta:" + s.call);
            dists.add(l == null ? 0 : Geo.distance(l.getLatitude(), l.getLongitude(), s.lat, s.lon));
            labels.add("● " + s.label() + "  " + (l == null ? "" : Geo.rangeBearing(l.getLatitude(), l.getLongitude(), s.lat, s.lon, app.imperial()))
                    + "  · " + Geo.ago(s.time, now));
        }
        for (Store.Pin p : app.store.pins.values()) {
            keys.add("pin:" + p.id);
            dists.add(l == null ? 0 : Geo.distance(l.getLatitude(), l.getLongitude(), p.lat, p.lon));
            labels.add((p.place ? "⌂ " : "◆ ") + p.name + "  " + (l == null ? "" : Geo.rangeBearing(l.getLatitude(), l.getLongitude(), p.lat, p.lon, app.imperial())));
        }
        if (keys.isEmpty()) { toast("No pins or stations yet"); return; }
        Integer[] order = new Integer[keys.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(dists.get(a), dists.get(b)));
        String[] items = new String[order.length];
        for (int i = 0; i < order.length; i++) items[i] = labels.get(order[i]);
        new AlertDialog.Builder(this)
                .setTitle("// TRACKS & WAYPOINTS — NEAREST")
                .setItems(items, (d, which) -> {
                    String key = keys.get(order[which]);
                    if (key.startsWith("pin:")) pinTapped(key.substring(4));
                    else stationTapped(key.substring(4));
                    String keep = target;
                    target = key;
                    double[] t = targetPosition();
                    target = keep;
                    if (t != null) map.getController().animateTo(new GeoPoint(t[0], t[1]));
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ---------- menu ----------

    private void showMenu() {
        final String RADIOS = "Radios and TAK link", CHK = "Check in: send OK + my grid",
                POS = "Send my position now", DRAW = "Draw a route or zone",
                LOS = "Line of sight: me → crosshair", DL = "Download this area for offline",
                FILES = "Files: import, trail, backup", WX = "Weather alerts", ASK = "Ask Lantern (offline library)", CLR = "Clear heard stations",
                LOG = "Packet log", DIAG = "Map diagnostics", SET = "Settings",
                BAT = "Keep running in background (battery)", HELP = "Help";
        final String[] items = {RADIOS, CHK, POS, DRAW, LOS, DL, ASK, FILES, WX, CLR, LOG, DIAG, BAT, SET, HELP};
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    String a = items[which];
                    if (a.equals(RADIOS)) radioTapped();
                    else if (a.equals(CHK)) { if (requireShare()) toast(app.checkIn() ? "Check-in sent" : "Send failed. See the packet log"); }
                    else if (a.equals(POS)) { if (requireShare()) toast(app.beaconNow() ? (app.gmrs() && app.radio.isConnected() ? "Position queued for the next GMRS burst" : "Position sent")
                            : "Not sent: no GPS fix in the last 3 minutes"); }
                    else if (a.equals(DRAW)) tools.chooseDraw();
                    else if (a.equals(LOS)) {
                        IGeoPoint c = map.getMapCenter();
                        tools.terrainTo(c.getLatitude(), c.getLongitude(), "Crosshair");
                    }
                    else if (a.equals(DL)) downloadArea();
                    else if (a.equals(ASK)) startActivity(new Intent(this, AskActivity.class));
                    else if (a.equals(FILES)) filesMenu();
                    else if (a.equals(WX)) weatherMenu();
                    else if (a.equals(CLR)) { app.store.clearStations(); app.sosFrom.clear(); refreshMarkers(); }
                    else if (a.equals(LOG)) startActivity(new Intent(this, LogActivity.class));
                    else if (a.equals(DIAG)) runMapDiagnostics();
                    else if (a.equals(SET)) startActivity(new Intent(this, SettingsActivity.class));
                    else if (a.equals(BAT)) askBattery(true);
                    else if (a.equals(HELP)) showHelp();
                })
                .show();
    }

    private void filesMenu() {
        final String IMP = "Import a KML, KMZ or GPX file…", IMPS = "Imported files (remove)",
                TRK = "My trail: export GPX / clear", BAK = "Backup / restore places and drawings";
        final String[] items = {IMP, IMPS, TRK, BAK};
        new AlertDialog.Builder(this)
                .setTitle("// FILES")
                .setItems(items, (d, which) -> {
                    String a = items[which];
                    if (a.equals(IMP)) pickFile(Intent.ACTION_OPEN_DOCUMENT, "*/*", null, REQ_IMPORT);
                    else if (a.equals(IMPS)) tools.importedMenu();
                    else if (a.equals(TRK)) trailMenu();
                    else if (a.equals(BAK)) backupMenu();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private boolean requireShare() {
        if (app.meshTx() || app.tak.isConnected()) return true;
        if (app.canShare()) return true;
        if (app.mesh.isConnected() && !app.meshTeamOk()) {
            meshAsked = false;
            askMeshChannel();
            return false;
        }
        if (app.canTransmit() && !app.aprsTeam()) {
            new AlertDialog.Builder(this)
                    .setTitle("// APRS IS PUBLIC")
                    .setMessage("Only the UV-PRO is connected, and team traffic over APRS is turned off because "
                            + "anyone can read APRS. Connect the Meshtastic radio, or allow APRS in Config ▸ Privacy.")
                    .setPositiveButton("Config", (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
                    .setNegativeButton("Close", null).show();
            return false;
        }
        if (app.canShare()) return true;
        if (!app.radio.isConnected()) {
            toast("Connect a radio or the TAK link first (tap the COMMS line)");
            return false;
        }
        if (!app.hasCallsign()) {
            new AlertDialog.Builder(this)
                    .setTitle("// CALLSIGN REQUIRED")
                    .setMessage(app.gmrs() ? "GMRS mode needs your GMRS callsign and a unit name. Add them in Settings ▸ GMRS mode."
                            : "Sending over the radio needs your amateur callsign. Add it in Settings. (With a GMRS licence instead, turn on GMRS mode there.)")
                    .setPositiveButton("Settings", (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
                    .setNegativeButton("Cancel", null).show();
            return false;
        }
        if (!app.radio.isConnected()) {
            toast("Radio not connected");
            return false;
        }
        return true;
    }

    // ---------- radio ----------

    private void radioTapped() {
        String aprs = (app.gmrs() ? "UV-PRO (GMRS MODE ON): " : "UV-PRO (APRS): ") + stateWord(app.radio.getState())
                + (app.radio.isConnected() ? " · " + app.radio.deviceName() : "");
        String mesh = "Meshtastic: " + stateWord(app.mesh.getState())
                + (app.mesh.isConnected() ? " · " + app.mesh.deviceName() + (app.meshTeamOk() ? "" : " · TEAM TRAFFIC ON HOLD") : "");
        String tak = "TAK link (ATAK users): " + (app.tak.getState() == RadioLink.State.OFF ? "off — set up in Settings"
                : app.tak.describe().trim() + " · RX " + app.tak.rxCount);
        new AlertDialog.Builder(this)
                .setTitle("// COMMS")
                .setItems(new String[]{aprs, mesh, tak}, (d, which) -> {
                    if (which == 0) aprsTapped();
                    else if (which == 1) meshTapped();
                    else startActivity(new Intent(this, SettingsActivity.class));
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static String stateWord(RadioLink.State s) {
        switch (s) {
            case CONNECTED: return "ON";
            case CONNECTING: return "connecting…";
            case RETRYING: return "reconnecting…";
            default: return "off — tap to connect";
        }
    }

    private BluetoothAdapter adapterOrToast() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) { toast("This phone has no Bluetooth"); return null; }
        if (!adapter.isEnabled()) { toast("Turn on Bluetooth first"); return null; }
        return adapter;
    }

    private void aprsTapped() {
        if (app.radio.getState() != RadioLink.State.OFF) {
            new AlertDialog.Builder(this)
                    .setTitle("// UV-PRO · APRS")
                    .setMessage(radioSummary())
                    .setPositiveButton("Disconnect", (d, w) -> {
                        app.prefs.edit().putBoolean("wantAprs", false).apply();
                        app.radio.disconnect();
                    })
                    .setNeutralButton("Connection test", (d, w) -> new AlertDialog.Builder(this)
                            .setTitle("// UV-PRO CONNECTION")
                            .setItems(new String[]{"Find the working connection (guided test)", "Choose the connection by hand"}, (d2, w2) -> {
                                if (w2 == 0) startWizard(); else choosePort();
                            })
                            .setNegativeButton("Cancel", null).show())
                    .setNegativeButton("Close", null).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
            return;
        }
        BluetoothAdapter adapter = adapterOrToast();
        if (adapter == null) return;
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        final List<BluetoothDevice> devices = new ArrayList<>(bonded);
        Collections.sort(devices, (a, b) -> {
            boolean ua = looksLikeRadio(a), ub = looksLikeRadio(b);
            if (ua != ub) return ua ? -1 : 1;
            return safeName(a).compareToIgnoreCase(safeName(b));
        });
        if (devices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("// NO PAIRED RADIOS")
                    .setMessage("On the UV-PRO: Menu ▸ General Settings ▸ KISS TNC ▸ On.\n"
                            + "Then pair it in Android Settings ▸ Bluetooth, and come back here.")
                    .setPositiveButton("Open Bluetooth settings", (d, w) ->
                            startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)))
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        String[] names = new String[devices.size()];
        for (int i = 0; i < names.length; i++) names[i] = safeName(devices.get(i)) + "\n" + devices.get(i).getAddress();
        new AlertDialog.Builder(this)
                .setTitle("// LINK UV-PRO")
                .setItems(names, (d, which) -> {
                    BluetoothDevice dev = devices.get(which);
                    app.prefs.edit().putString("lastRadio", dev.getAddress()).putBoolean("wantAprs", true).apply();
                    app.radio.connect(dev);
                })
                .setNegativeButton("Cancel", null)
                .show();
        toastOnce("kissHint", "Make sure KISS TNC is ON in the UV-PRO menu (and the BTECH app is closed)");
    }

    // ---------- Meshtastic ----------

    private void meshTapped() {
        if (app.mesh.getState() != RadioLink.State.OFF) {
            new AlertDialog.Builder(this)
                    .setTitle("// MESHTASTIC")
                    .setMessage("State: " + app.mesh.getState() + "\nDevice: " + app.mesh.deviceName()
                            + "\nMy node: " + (app.myNodeNum == 0 ? "?" : Mesh.nodeId(app.myNodeNum))
                            + "\nPackets received: " + app.mesh.rxCount + "\nPackets sent: " + app.mesh.txCount)
                    .setPositiveButton("Disconnect", (d, w) -> {
                        app.prefs.edit().putBoolean("wantMesh", false).apply();
                        app.mesh.disconnect();
                    })
                    .setNegativeButton("Close", null).show();
            return;
        }
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (!hasLocationPermission()) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_MESH);
            return;
        }
        BluetoothAdapter adapter = adapterOrToast();
        if (adapter == null) return;
        scanForMesh(adapter);
    }

    private void scanForMesh(BluetoothAdapter adapter) {
        final List<BluetoothDevice> found = new ArrayList<>();
        // already-paired Meshtastic devices first
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            String n = safeName(d).toLowerCase(Locale.US);
            if (n.contains("meshtastic") || n.contains("heltec") || n.contains("_")) found.add(d);
        }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { toast("Bluetooth LE scanning isn't available"); return; }
        final AlertDialog searching = new AlertDialog.Builder(this)
                .setTitle("// SCANNING FOR MESH NODES…")
                .setMessage("Make sure the Heltec is powered on and Bluetooth is enabled in its Meshtastic settings.")
                .setNegativeButton("Cancel", null)
                .show();
        final ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice d = result.getDevice();
                for (BluetoothDevice f : found) if (f.getAddress().equals(d.getAddress())) return;
                found.add(d);
                searching.setMessage("Found " + found.size() + "…");
            }
        };
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(MeshLink.SERVICE)).build());
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(filters, settings, cb);
        } catch (SecurityException e) {
            searching.dismiss();
            toast("Bluetooth scan permission is off");
            return;
        }
        map.postDelayed(() -> {
            try { scanner.stopScan(cb); } catch (Exception ignored) { }
            if (!searching.isShowing()) return;
            searching.dismiss();
            if (found.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("// NO MESH NODE FOUND")
                        .setMessage("Check that the radio is on and close the official Meshtastic app (only one app can connect at a time). Then try again.")
                        .setPositiveButton("OK", null).show();
                return;
            }
            String[] names = new String[found.size()];
            for (int i = 0; i < names.length; i++) names[i] = safeName(found.get(i)) + "\n" + found.get(i).getAddress();
            new AlertDialog.Builder(this)
                    .setTitle("// LINK MESH NODE")
                    .setItems(names, (d, which) -> {
                        app.prefs.edit().putString("lastMesh", found.get(which).getAddress()).putBoolean("wantMesh", true).apply();
                        app.mesh.connect(found.get(which));
                        toastOnce("meshPin", "If Android asks for a PIN, use the one shown on the Heltec's screen (default 123456)");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }, 6000);
    }

    private static boolean looksLikeRadio(BluetoothDevice d) {
        String n = safeName(d).toUpperCase(Locale.US);
        return n.contains("UV-PRO") || n.contains("UV PRO") || n.contains("N76") || n.contains("GA-5WB")
                || n.contains("TNC") || n.contains("GMRS-PRO") || n.contains("UV-50");
    }

    private static String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? d.getAddress() : n;
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    /** The UV-PRO has several Bluetooth serial ports; only one is the packet modem. */
    private void choosePort() {
        final int[] vals = {0, RadioLink.PORT_BLE, 100, 1, 2, 3};
        final String[] names = {"Auto (classic Bluetooth, port 1 first)", "Bluetooth Low Energy", "Classic: standard lookup only", "Classic: port 1", "Classic: port 2", "Classic: port 3"};
        int cur = app.prefs.getInt("kissPort", 0), sel = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i] == cur) sel = i;
        new AlertDialog.Builder(this)
                .setTitle("// UV-PRO BLUETOOTH PORT")
                .setSingleChoiceItems(names, sel, (d, which) -> {
                    app.prefs.edit().putInt("kissPort", vals[which]).apply();
                    d.dismiss();
                    String addr = app.prefs.getString("lastRadio", "");
                    BluetoothAdapter ad = adapterOrToast();
                    app.radio.disconnect();
                    if (ad != null && BluetoothAdapter.checkBluetoothAddress(addr)) {
                        final BluetoothDevice dev = ad.getRemoteDevice(addr);
                        // give the radio a moment to let go of the old connection
                        clockHandler.postDelayed(() -> app.radio.connect(dev), 1500);
                        toast("Reconnecting on " + names[which]);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- guided connection test ----------

    private static final int[] WIZ_VALS = {100, 1, 2, 3, RadioLink.PORT_BLE};
    private static final String[] WIZ_NAMES = {"Classic Bluetooth, standard lookup", "Classic Bluetooth, port 1", "Classic Bluetooth, port 2",
            "Classic Bluetooth, port 3", "Bluetooth Low Energy"};
    private int wizStep = -1;
    private long wizSince;

    private void startWizard() {
        if (!app.hasCallsign()) { toast(app.gmrs() ? "Finish GMRS setup in Settings first" : "Set your callsign in Settings first"); return; }
        int wait = app.gmrs() ? app.gmrsWaitSeconds() : 0;
        new AlertDialog.Builder(this)
                .setTitle("// FIND THE WORKING CONNECTION")
                .setMessage("The app will try each way of connecting to the UV-PRO in turn. For each one it sends a single short test packet, then asks whether the radio transmitted.\n\n"
                        + "Watch the radio: when a packet goes out, its transmit light comes on for about a second (a second radio on the same channel hears a short buzz).\n\n"
                        + "Before starting: KISS TNC ticked on the radio, radio on your test channel, BTECH app closed."
                        + (wait > 0 ? "\n\nA packet went out " + (30 - wait) + " s ago: wait " + wait + " s before starting." : ""))
                .setPositiveButton("Start", (d, w) -> {
                    if (app.gmrs() && app.gmrsWaitSeconds() > 0) toast("Wait " + app.gmrsWaitSeconds() + " s, then start again");
                    else wizardTry(0);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void wizardTry(int step) {
        if (step >= WIZ_VALS.length) {
            wizStep = -1;
            app.prefs.edit().putInt("kissPort", 0).apply();
            app.log("CONNECTION TEST: none of the " + WIZ_VALS.length + " methods made the radio transmit");
            new AlertDialog.Builder(this)
                    .setTitle("// NONE OF THEM TRANSMITTED")
                    .setMessage("Every connection method was tried. The packet log now holds the full record of what was sent and what the radio answered: "
                            + "open it and use SEND TO… to share it.\n\nOne more thing to try: in Android's Bluetooth settings, forget the UV-PRO, "
                            + "pair it again while KISS TNC is ticked, then run this test once more.")
                    .setPositiveButton("Open the log", (d, w) -> startActivity(new Intent(this, LogActivity.class)))
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }
        String addr = app.prefs.getString("lastRadio", "");
        BluetoothAdapter ad = adapterOrToast();
        if (ad == null || !BluetoothAdapter.checkBluetoothAddress(addr)) { toast("Connect the UV-PRO once first"); return; }
        wizStep = step;
        wizSince = System.currentTimeMillis();
        app.log("CONNECTION TEST " + (step + 1) + "/" + WIZ_VALS.length + ": " + WIZ_NAMES[step]);
        app.prefs.edit().putInt("kissPort", WIZ_VALS[step]).putBoolean("wantAprs", true).apply();
        app.radio.disconnect();
        final BluetoothDevice dev = ad.getRemoteDevice(addr);
        toast("Trying " + (step + 1) + " of " + WIZ_VALS.length + ": " + WIZ_NAMES[step]);
        clockHandler.postDelayed(() -> { app.radio.connect(dev, 15_000L); clockHandler.postDelayed(wizWatch, 1000); }, 2000);
    }

    private final Runnable wizWatch = new Runnable() {
        @Override public void run() {
            if (wizStep < 0 || isFinishing()) return;
            final int step = wizStep;
            if (app.radio.isConnected()) {
                String err = app.linkTest();
                if (err != null) { app.log("CONNECTION TEST: " + err); wizardTry(step + 1); return; }
                // give the radio a few seconds to key up before asking
                clockHandler.postDelayed(() -> { if (wizStep == step) wizAsk(step); }, 3500);
            } else if (System.currentTimeMillis() - wizSince > 22_000L) {
                app.log("CONNECTION TEST: could not connect with " + WIZ_NAMES[step]);
                wizardTry(step + 1);
            } else {
                clockHandler.postDelayed(this, 1000);
            }
        }
    };

    private void wizAsk(final int step) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("// DID THE RADIO TRANSMIT?")
                .setMessage("Tried: " + WIZ_NAMES[step] + "\nConnected as: " + app.radio.how + "\n\nA test packet was just handed to the radio. Did its transmit light come on?")
                .setCancelable(false)
                .setPositiveButton("Yes, it transmitted", (d, w) -> {
                    wizStep = -1;
                    app.log("CONNECTION TEST: WORKS with " + WIZ_NAMES[step] + " (" + app.radio.how + ")");
                    toast("Saved. The app will use this connection from now on.");
                })
                .setNegativeButton("No", (d, w) -> {
                    app.log("CONNECTION TEST: no transmit with " + WIZ_NAMES[step] + ", " + app.radio.rawBytes + " bytes heard from the radio");
                    app.gmrsQueue.forgetLast();
                    wizardTry(step + 1);
                })
                .show();
    }

    private String radioSummary() {
        return "State: " + app.radio.getState() + "\nDevice: " + app.radio.deviceName()
                + "\nBluetooth: " + app.radio.how + " · " + app.radio.rawBytes + " bytes heard"
                + "\n\nIf packets show as sent in the log but the radio never keys up, the app may be on the wrong port: try Change port. "
                + "On the right one the radio's screen shows a small link icon next to the power level.\n"
                + "\nPackets received: " + app.radio.rxCount + "\nPackets sent: " + app.radio.txCount
                + (app.gmrs() ? "\nMode: GMRS · " + (app.gmrsReady() ? app.gmrsCall() + " / " + app.gmrsUnit() : "setup not finished — listen only")
                        + "\nWaiting to send: " + app.gmrsQueue.size()
                : "\nMy callsign: " + (app.hasCallsign() ? app.myCall() : "(not set — listen only)"))
                + "\nBeacon: " + (app.beaconSeconds() == 0 ? "off" : "every " + app.beaconSeconds() / 60 + " min");
    }

    private static String linkMark(RadioLink.State s) {
        return s == RadioLink.State.CONNECTED ? "▲" : "…";
    }

    private void updateRadioStatus() {
        RadioLink.State s = app.radio.getState(), ms = app.mesh.getState(), ts = app.tak.getState();
        StringBuilder sb = new StringBuilder("■");
        boolean anyOn = false, anyWanted = false;
        if (s != RadioLink.State.OFF) { sb.append(' ').append(app.radioLabel()).append(linkMark(s)); anyWanted = true; anyOn |= s == RadioLink.State.CONNECTED; }
        if (ms != RadioLink.State.OFF) {
            sb.append(" MESH").append(linkMark(ms));
            if (ms == RadioLink.State.CONNECTED && !app.meshTeamOk()) sb.append("(HELD)");
            anyWanted = true; anyOn |= ms == RadioLink.State.CONNECTED;
        }
        if (ms == RadioLink.State.CONNECTED) askMeshChannel();
        if (ts != RadioLink.State.OFF) { sb.append(" TAK").append(linkMark(ts)); anyWanted = true; anyOn |= ts == RadioLink.State.CONNECTED; }
        if (!anyWanted) {
            radioStatus.setText(app.lbl("■ COMMS: OFFLINE", "■ RADIOS: OFF · TAP TO CONNECT"));
            radioStatus.setTextColor(ContextCompat.getColor(this, R.color.dim));
        } else {
            sb.append("  RX").append(app.radio.rxCount + app.mesh.rxCount + app.tak.rxCount);
            if (s == RadioLink.State.CONNECTED && !app.hasCallsign() && ms == RadioLink.State.OFF && ts == RadioLink.State.OFF)
                sb.append(" RX-ONLY");
            radioStatus.setText(sb.toString());
            radioStatus.setTextColor(ContextCompat.getColor(this, anyOn ? R.color.accent : R.color.warn));
        }
        int unread = app.store.unread;
        String chat = app.lbl("MSG", "CHAT");
        btnChat.setText(unread > 0 ? chat + "·" + unread : chat);
        if (anyOn && app.background()) askBattery(false);
    }

    // ---------- location ----------

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(@NonNull Location loc) { app.onLocation(loc); }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override public void onProviderEnabled(@NonNull String provider) { }
        @Override public void onProviderDisabled(@NonNull String provider) { }
    };

    private boolean firstFix = true;

    private void startLocation() {
        if (!hasLocationPermission() || lm == null) return;
        myLocOverlay.enableMyLocation();
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, locListener);
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 0, locListener);
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) app.onLocation(last);
        } catch (Exception e) {
            toast("Location error: " + e.getMessage());
        }
    }

    /** A new fix accepted by the app (from this screen or the background service). */
    @Override
    public void onLocation(Location loc) {
        tvMe.setText(Mgrs.format(loc.getLatitude(), loc.getLongitude())
                + (loc.hasAccuracy() ? String.format(Locale.US, "  ±%.0f%s",
                app.imperial() ? loc.getAccuracy() * 3.28084 : loc.getAccuracy(), app.imperial() ? "FT" : "M") : ""));
        if (firstFix && !app.prefs.contains("mapLat")) {
            firstFix = false;
            map.getController().setZoom(15.0);
            map.getController().setCenter(new GeoPoint(loc.getLatitude(), loc.getLongitude()));
        }
        firstFix = false;
        syncTrail();
        updateCenterText();
        refreshTarget();
    }

    private final Runnable elevRequest = new Runnable() {
        @Override public void run() {
            IGeoPoint c = map.getMapCenter();
            app.elev.request(c.getLatitude(), c.getLongitude(), MainActivity.this::updateCenterText);
        }
    };

    private void updateCenterText() {
        IGeoPoint c = map.getMapCenter();
        String s = Mgrs.format(c.getLatitude(), c.getLongitude());
        if (app.prefs.getBoolean("elevation", true) && map.getZoomLevelDouble() >= 9) {
            Float h = app.elev.cached(c.getLatitude(), c.getLongitude());
            if (h != null) {
                s += app.imperial() ? String.format(Locale.US, "  ELV %,d FT", Math.round(h * 3.28084))
                        : String.format(Locale.US, "  ELV %,d M", Math.round(h));
            } else {
                // wait until the map stops moving before fetching height data
                clockHandler.removeCallbacks(elevRequest);
                clockHandler.postDelayed(elevRequest, 700);
            }
        }
        Location l = app.lastLoc;
        if (l != null) {
            double dist = Geo.distance(l.getLatitude(), l.getLongitude(), c.getLatitude(), c.getLongitude());
            if (dist > 5) s += "\n" + rngBrg(l.getLatitude(), l.getLongitude(), c.getLatitude(), c.getLongitude());
        }
        tvCenter.setText(s);
    }

    // ---------- lifecycle ----------

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_LOCATION && hasLocationPermission()) startLocation();
        if (requestCode == REQ_BT) {
            if (granted) aprsTapped();
            else toast("Bluetooth permission is needed to talk to the radio");
        }
        if (requestCode == REQ_MESH) {
            boolean all = grantResults.length > 0;
            for (int g : grantResults) all &= g == PackageManager.PERMISSION_GRANTED;
            if (all) meshTapped();
            else toast("Bluetooth permissions are needed to find the Meshtastic radio");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        map.onResume();
        applyDetail();
        clockHandler.removeCallbacks(clockTick);
        clockHandler.post(clockTick);
        applyLabels();
        app.checkUpdate();
        app.addListener(this);
        if (app.prefs.getBoolean("keepOn", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        app.mapVisible = true;
        compass.start();
        androidx.core.app.NotificationManagerCompat.from(this).cancel(Alerts.ID_SOS);
        trailDrawn = 0;
        if (app.lastLoc != null) onLocation(app.lastLoc);
        syncTrail();
        refreshMarkers();
        updateRadioStatus();
        updateCenterText();
        drawWeather();
        updateBanners();
        app.updateService();
        app.pollWeather(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        map.onPause();
        clockHandler.removeCallbacks(clockTick);
        app.removeListener(this);
        app.mapVisible = false;
        compass.stop();
        heading = Float.NaN;
        tvHdg.setText("");
        IGeoPoint c = map.getMapCenter();
        app.prefs.edit()
                .putLong("mapLat", Double.doubleToLongBits(c.getLatitude()))
                .putLong("mapLon", Double.doubleToLongBits(c.getLongitude()))
                .putLong("mapZoom", Double.doubleToLongBits(map.getZoomLevelDouble()))
                .apply();
        app.store.flush();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (lm != null) lm.removeUpdates(locListener);
    }

    @Override public void onDataChanged() { refreshMarkers(); updateRadioStatus(); drawWeather(); updateBanners(); }
    @Override public void onRadioChanged() { updateRadioStatus(); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    void toastOnce(String key, String s) {
        if (app.prefs.getBoolean("hint_" + key, false)) return;
        app.prefs.edit().putBoolean("hint_" + key, true).apply();
        toast(s);
    }
}
