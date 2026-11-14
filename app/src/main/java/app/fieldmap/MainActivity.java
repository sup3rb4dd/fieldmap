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
import app.fieldmap.core.Mesh;
import app.fieldmap.core.Mgrs;

@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity implements FieldMapApp.Listener {

    private static final int REQ_LOCATION = 1, REQ_BT = 2, REQ_MESH = 3;

    private FieldMapApp app;
    private MapView map;
    private MyLocationNewOverlay myLocOverlay;
    private Polyline rangeLine;
    private final List<Overlay> itemOverlays = new ArrayList<>();
    private TextView tvMe, tvCenter, tvTarget, radioStatus, tvClock;
    private LinearLayout targetRow;
    private Button btnChat;
    private LocationManager lm;
    private int layerIndex;

    // selected target: "pin:<id>" or "sta:<call>" or "pt:<lat>,<lon>"
    private String target;

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

        setupMap();

        findViewById(R.id.btnMe).setOnClickListener(v -> centerOnMe());
        findViewById(R.id.btnPin).setOnClickListener(v -> {
            IGeoPoint c = map.getMapCenter();
            promptNewPin(c.getLatitude(), c.getLongitude());
        });
        findViewById(R.id.btnGrid).setOnClickListener(v -> promptGoToGrid());
        findViewById(R.id.btnLayer).setOnClickListener(v -> chooseLayer());
        findViewById(R.id.btnNorth).setOnClickListener(v -> map.setMapOrientation(0));
        tvClock = findViewById(R.id.tvClock);

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
                            + "• Use Map ▸ ☰ ▸ Download this area to save maps for offline use.\n\n"
                            + "Tap RADIO at the top to connect a UV-PRO (APRS) and/or a Meshtastic radio like the Heltec V4.\n\n"
                            + "UV-PRO: turn on Menu ▸ General Settings ▸ KISS TNC and pair it in Android Bluetooth settings first. "
                            + "APRS transmit needs your callsign in Settings.\n\n"
                            + "Meshtastic: close the official Meshtastic app first; no callsign needed.")
                    .setPositiveButton("Settings", (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
                    .setNegativeButton("Later", null)
                    .show();
        }
    }

    // ---------- map ----------

    private void setupMap() {
        layerIndex = app.prefs.getInt("layer", 0);
        map.setTileSource(Tiles.byIndex(layerIndex));
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
            @Override public boolean singleTapConfirmedHelper(GeoPoint p) { return false; }
            @Override public boolean longPressHelper(GeoPoint p) {
                promptNewPin(p.getLatitude(), p.getLongitude());
                return true;
            }
        });
        map.getOverlays().add(0, events);

        RotationGestureOverlay rot = new RotationGestureOverlay(map);
        rot.setEnabled(true);
        map.getOverlays().add(rot);

        ScaleBarOverlay scale = new ScaleBarOverlay(map);
        scale.setAlignBottom(false);
        scale.setScaleBarOffset(30, (int) (90 * getResources().getDisplayMetrics().density));
        scale.setUnitsOfMeasure(app.imperial() ? ScaleBarOverlay.UnitsOfMeasure.imperial : ScaleBarOverlay.UnitsOfMeasure.metric);
        scale.getBarPaint().setColor(Color.parseColor("#FF39FF14"));
        scale.getBarPaint().setStrokeWidth(3 * getResources().getDisplayMetrics().density);
        scale.getTextPaint().setColor(Color.parseColor("#FF39FF14"));
        scale.getTextPaint().setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD));
        scale.getTextPaint().setShadowLayer(4, 0, 0, Color.BLACK);
        map.getOverlays().add(scale);

        rangeLine = new Polyline();
        float dd = getResources().getDisplayMetrics().density;
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
            @Override public boolean onScroll(ScrollEvent event) { updateCenterText(); return false; }
            @Override public boolean onZoom(ZoomEvent event) { updateCenterText(); return false; }
        });
        refreshMarkers();
    }

    /**
     * Map sharpness. Scaling 256 px tiles up by the full screen density (3x on most phones)
     * makes everything blurry; a smaller factor loads the next zoom level's tiles instead.
     */
    private void applyDetail() {
        float density = getResources().getDisplayMetrics().density;
        int detail = app.prefs.getInt("detail", 1); // 0 sharp, 1 balanced, 2 large text
        float factor = detail == 0 ? 1f : detail == 2 ? density : Math.max(1f, density * 0.6f);
        map.setTilesScaleFactor(factor);
        map.setTilesScaledToDpi(false); // also re-applies the tile size
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
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("ddHHmm'Z' MMMyy", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            tvClock.setText(f.format(new java.util.Date()).toUpperCase(Locale.US));
            clockHandler.postDelayed(this, 1000);
        }
    };

    private void chooseLayer() {
        new AlertDialog.Builder(this)
                .setTitle("// SELECT LAYER")
                .setSingleChoiceItems(Tiles.NAMES, layerIndex, (d, which) -> {
                    layerIndex = which;
                    app.prefs.edit().putInt("layer", which).apply();
                    map.setTileSource(Tiles.byIndex(which));
                    d.dismiss();
                })
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
            toast("Switch to a USGS layer (Map button) to download for offline use");
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
                    .setPositiveButton("Download", (d, w) -> cm.downloadAreaAsync(this, bb, zMin, zMax))
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            toast("This layer can't be downloaded: " + e.getMessage());
        }
    }

    // ---------- markers ----------

    private void refreshMarkers() {
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
            m.setIcon(labelIcon(label, color, p.place ? 2 : 1));
            m.setAnchor(0.5f, iconAnchorY);
            m.setInfoWindow(null);
            String key = "pin:" + p.id;
            m.setOnMarkerClickListener((mk, mv) -> { pinTapped(p.id); return true; });
            itemOverlays.add(m);
        }
        for (Store.Station s : app.store.stations.values()) {
            Marker m = new Marker(map);
            m.setPosition(new GeoPoint(s.lat, s.lon));
            boolean stale = now - s.time > 30 * 60 * 1000L;
            int color = stale ? Color.GRAY
                    : "MESH".equals(s.via) ? Color.parseColor("#FF00E5FF") : Color.parseColor("#FF39FF14");
            m.setIcon(labelIcon(s.label(), color, 0));
            m.setAnchor(0.5f, iconAnchorY);
            m.setInfoWindow(null);
            m.setOnMarkerClickListener((mk, mv) -> { stationTapped(s.call); return true; });
            itemOverlays.add(m);
        }
        map.getOverlays().addAll(insertAt, itemOverlays);
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
    private Drawable labelIcon(String label, int color, int shape) {
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
            String[] parts = target.substring(3).split(",");
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
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
        return "Grid point";
    }

    private void refreshTarget() {
        double[] t = targetPosition();
        if (t == null) {
            target = null;
            targetRow.setVisibility(android.view.View.GONE);
            rangeLine.setVisible(false);
            map.invalidate();
            return;
        }
        targetRow.setVisibility(android.view.View.VISIBLE);
        Location l = app.lastLoc;
        if (l != null) {
            tvTarget.setText(targetName().toUpperCase(Locale.US) + "\n" + rngBrg(l.getLatitude(), l.getLongitude(), t[0], t[1]));
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
        List<String> items = new ArrayList<>();
        items.add("Radios (UV-PRO / Meshtastic)");
        items.add("Send my position now");
        items.add("Download this area for offline");
        items.add("Clear heard stations");
        items.add("Packet log");
        items.add("Settings");
        items.add("Map diagnostics");
        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    switch (which) {
                        case 0: radioTapped(); break;
                        case 1: if (requireShare()) { if (app.beaconNow()) toast("Position sent"); else toast("No GPS fix yet"); } break;
                        case 2: downloadArea(); break;
                        case 3: app.store.clearStations(); refreshMarkers(); break;
                        case 4: startActivity(new Intent(this, LogActivity.class)); break;
                        case 5: startActivity(new Intent(this, SettingsActivity.class)); break;
                        case 6: runMapDiagnostics(); break;
                    }
                })
                .show();
    }

    private boolean requireShare() {
        if (app.mesh.isConnected()) return true;
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
            toast("Connect the UV-PRO or Meshtastic radio first (tap RADIO)");
            return false;
        }
        if (!app.hasCallsign()) {
            new AlertDialog.Builder(this)
                    .setTitle("// CALLSIGN REQUIRED")
                    .setMessage("Sending over the radio needs your amateur callsign. Add it in Settings.")
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
        String aprs = "UV-PRO (APRS): " + stateWord(app.radio.getState())
                + (app.radio.isConnected() ? " · " + app.radio.deviceName() : "");
        String mesh = "Meshtastic: " + stateWord(app.mesh.getState())
                + (app.mesh.isConnected() ? " · " + app.mesh.deviceName() : "");
        new AlertDialog.Builder(this)
                .setTitle("// COMMS")
                .setItems(new String[]{aprs, mesh}, (d, which) -> {
                    if (which == 0) aprsTapped(); else meshTapped();
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
                    .setPositiveButton("Disconnect", (d, w) -> app.radio.disconnect())
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
                    app.prefs.edit().putString("lastRadio", dev.getAddress()).apply();
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
                    .setPositiveButton("Disconnect", (d, w) -> app.mesh.disconnect())
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

    private String radioSummary() {
        return "State: " + app.radio.getState() + "\nDevice: " + app.radio.deviceName()
                + "\nPackets received: " + app.radio.rxCount + "\nPackets sent: " + app.radio.txCount
                + "\nMy callsign: " + (app.hasCallsign() ? app.myCall() : "(not set — listen only)")
                + "\nBeacon: " + (app.beaconSeconds() == 0 ? "off" : "every " + app.beaconSeconds() / 60 + " min");
    }

    private void updateRadioStatus() {
        RadioLink.State s = app.radio.getState();
        RadioLink.State ms = app.mesh.getState();
        if (ms != RadioLink.State.OFF) {
            String a = s == RadioLink.State.CONNECTED ? "APRS▲" : s == RadioLink.State.OFF ? "APRS·" : "APRS…";
            String m = ms == RadioLink.State.CONNECTED ? "MESH▲" : "MESH…";
            radioStatus.setText("■ " + a + " " + m + "  RX" + (app.radio.rxCount + app.mesh.rxCount));
            boolean anyOn = s == RadioLink.State.CONNECTED || ms == RadioLink.State.CONNECTED;
            radioStatus.setTextColor(ContextCompat.getColor(this, anyOn ? R.color.accent : R.color.warn));
            int unread = app.store.unread;
            btnChat.setText(unread > 0 ? "MSG·" + unread : "MSG");
            return;
        }
        String txt;
        int color;
        switch (s) {
            case CONNECTED:
                txt = "■ APRS▲  RX" + app.radio.rxCount + " TX" + app.radio.txCount
                        + (app.hasCallsign() ? "" : " RX-ONLY");
                color = ContextCompat.getColor(this, R.color.accent);
                break;
            case CONNECTING:
                txt = "■ COMMS: LINKING…";
                color = ContextCompat.getColor(this, R.color.warn);
                break;
            case RETRYING:
                txt = "■ COMMS: RE-LINK…";
                color = ContextCompat.getColor(this, R.color.bad);
                break;
            default:
                txt = "■ COMMS: OFFLINE";
                color = ContextCompat.getColor(this, R.color.dim);
        }
        radioStatus.setText(txt);
        radioStatus.setTextColor(color);
        int unread = app.store.unread;
        btnChat.setText(unread > 0 ? "MSG·" + unread : "MSG");
    }

    // ---------- location ----------

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(@NonNull Location loc) { onNewLocation(loc); }
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
            if (last != null) onNewLocation(last);
        } catch (Exception e) {
            toast("Location error: " + e.getMessage());
        }
    }

    private void onNewLocation(Location loc) {
        Location prev = app.lastLoc;
        boolean better = prev == null
                || loc.getTime() - prev.getTime() > 10000
                || loc.getAccuracy() <= prev.getAccuracy()
                || LocationManager.GPS_PROVIDER.equals(loc.getProvider());
        if (!better) return;
        app.lastLoc = loc;
        tvMe.setText(Mgrs.format(loc.getLatitude(), loc.getLongitude())
                + (loc.hasAccuracy() ? String.format(Locale.US, "  ±%.0f%s",
                app.imperial() ? loc.getAccuracy() * 3.28084 : loc.getAccuracy(), app.imperial() ? "FT" : "M") : ""));
        if (firstFix && !app.prefs.contains("mapLat")) {
            firstFix = false;
            map.getController().setZoom(15.0);
            map.getController().setCenter(new GeoPoint(loc.getLatitude(), loc.getLongitude()));
        }
        firstFix = false;
        updateCenterText();
        refreshTarget();
    }

    private void updateCenterText() {
        IGeoPoint c = map.getMapCenter();
        String s = Mgrs.format(c.getLatitude(), c.getLongitude());
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
        app.addListener(this);
        if (app.prefs.getBoolean("keepOn", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refreshMarkers();
        updateRadioStatus();
        updateCenterText();
        app.updateService();
    }

    @Override
    protected void onPause() {
        super.onPause();
        map.onPause();
        clockHandler.removeCallbacks(clockTick);
        app.removeListener(this);
        IGeoPoint c = map.getMapCenter();
        app.prefs.edit()
                .putLong("mapLat", Double.doubleToLongBits(c.getLatitude()))
                .putLong("mapLon", Double.doubleToLongBits(c.getLongitude()))
                .putLong("mapZoom", Double.doubleToLongBits(map.getZoomLevelDouble()))
                .apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (lm != null) lm.removeUpdates(locListener);
    }

    @Override public void onDataChanged() { refreshMarkers(); updateRadioStatus(); }
    @Override public void onRadioChanged() { updateRadioStatus(); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void toastOnce(String key, String s) {
        if (app.prefs.getBoolean("hint_" + key, false)) return;
        app.prefs.edit().putBoolean("hint_" + key, true).apply();
        toast(s);
    }
}
