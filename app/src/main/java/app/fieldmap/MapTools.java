package app.fieldmap;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.location.Location;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.modules.IArchiveFile;
import org.osmdroid.tileprovider.modules.OfflineTileProvider;
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.fieldmap.core.GeoImport;
import app.fieldmap.core.Geo;
import app.fieldmap.core.Shapes;
import app.fieldmap.core.Terrain;

/** Map-screen tools that would otherwise bloat MainActivity: drawing, shapes, terrain, imports, map sources. */
class MapTools {
    private final MainActivity a;
    private final FieldMapApp app;
    private final MapView map;
    private final float dd;

    MapTools(MainActivity a, MapView map) {
        this.a = a;
        this.app = FieldMapApp.get();
        this.map = map;
        this.dd = a.getResources().getDisplayMetrics().density;
        drawRow = a.findViewById(R.id.drawRow);
        tvDraw = a.findViewById(R.id.tvDraw);
        btnDraw = a.findViewById(R.id.btnDraw);
        btnDraw.setOnClickListener(v -> { if (drawing) finishDraw(); else chooseDraw(); });
        a.findViewById(R.id.btnUndo).setOnClickListener(v -> undo());
        a.findViewById(R.id.btnDone).setOnClickListener(v -> finishDraw());
        a.findViewById(R.id.btnDrawCancel).setOnClickListener(v -> cancelDraw());
    }

    // =====================================================================
    // Drawing routes and areas
    // =====================================================================

    boolean drawing = false;
    private final List<double[]> draft = new ArrayList<>();
    private Polyline draftLine, leadLine;
    private final View drawRow;
    private final TextView tvDraw;
    private final Button btnDraw;

    private boolean zoneMode = false;
    private Store.Shape editing;   // set while a saved shape is being redrawn
    private Polygon draftFill;
    private Overlay draftTop;

    /**
     * Sits above everything else while drawing. It takes every tap (so pins, lines and shaded
     * areas underneath can't swallow or redirect one) and draws a dot on each corner placed.
     */
    private class DraftOverlay extends Overlay {
        private final android.graphics.Paint dot = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG),
                ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Point px = new android.graphics.Point();

        DraftOverlay() {
            dot.setColor(Color.WHITE);
            ring.setStyle(android.graphics.Paint.Style.STROKE);
            ring.setStrokeWidth(2 * dd);
            ring.setColor(Color.BLACK);
        }

        @Override
        public boolean onSingleTapConfirmed(android.view.MotionEvent e, MapView mv) {
            if (!drawing) return false;
            org.osmdroid.api.IGeoPoint g = mv.getProjection().fromPixels((int) e.getX(), (int) e.getY());
            tapped(g.getLatitude(), g.getLongitude());
            return true;
        }

        @Override
        public boolean onLongPress(android.view.MotionEvent e, MapView mv) {
            if (!drawing) return false;
            // holding a corner picks it up; the next tap puts it down
            org.osmdroid.api.IGeoPoint g = mv.getProjection().fromPixels((int) e.getX(), (int) e.getY());
            double reach = 30 * dd * metersPerPixel(g.getLatitude()), best = Double.MAX_VALUE;
            int pick = -1;
            for (int i = 0; i < draft.size(); i++) {
                double dist = Geo.distance(draft.get(i)[0], draft.get(i)[1], g.getLatitude(), g.getLongitude());
                if (dist < reach && dist < best) { best = dist; pick = i; }
            }
            if (pick >= 0) {
                moving = pick;
                tvDraw.setText("CORNER PICKED UP · TAP ITS NEW SPOT");
                mv.invalidate();
            }
            return true;
        }

        @Override
        public void draw(android.graphics.Canvas c, org.osmdroid.views.Projection proj) {
            if (!drawing) return;
            for (int i = 0; i < draft.size(); i++) {
                proj.toPixels(new GeoPoint(draft.get(i)[0], draft.get(i)[1]), px);
                // in zone mode the first corner is bigger: tap it to close the zone
                boolean closer = zoneMode && i == 0 && draft.size() >= 3;
                float r = (closer ? 9 : 5) * dd;
                dot.setColor(i == moving ? Color.parseColor("#FFFFB000") : closer ? Color.parseColor("#FF39FF14") : Color.WHITE);
                if (i == moving) r = 10 * dd;
                c.drawCircle(px.x, px.y, r, dot);
                c.drawCircle(px.x, px.y, r, ring);
            }
        }
    }

    /** Meters covered by one screen pixel at this spot and zoom. */
    private double metersPerPixel(double lat) {
        return 40075016.686 * Math.cos(Math.toRadians(lat))
                / (org.osmdroid.util.TileSystem.getTileSize() * Math.pow(2, map.getZoomLevelDouble()));
    }

    /** Corner being moved (held, waiting for a tap at its new spot), or -1. */
    private int moving = -1;

    private void tapped(double lat, double lon) {
        if (moving >= 0 && moving < draft.size()) {
            draft.set(moving, new double[]{lat, lon});
            moving = -1;
            updateDraw();
            return;
        }
        moving = -1;
        if (zoneMode && draft.size() >= 3) {
            double[] first = draft.get(0);
            // a tap on (or close to) the first corner closes the zone
            if (Geo.distance(first[0], first[1], lat, lon) < 30 * dd * metersPerPixel(lat)) { finishDraw(); return; }
        }
        addPoint(lat, lon);
    }

    /** Asks what is being drawn, then starts. */
    void chooseDraw() {
        new AlertDialog.Builder(a)
                .setTitle("// DRAW")
                .setItems(new String[]{"Route (a line to follow or measure)", "Zone (a shaded area)"}, (d, which) -> {
                    zoneMode = which == 1;
                    startDraw();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void startDraw() {
        drawing = true;
        editing = null;
        draft.clear();
        if (draftFill == null) {
            draftFill = new Polygon(map) {
                @Override public boolean onSingleTapConfirmed(android.view.MotionEvent e, MapView mv) { return false; }
            };
            draftFill.getFillPaint().setColor(0x40FFFFFF);
            draftFill.getOutlinePaint().setColor(0x00000000);
            draftFill.setInfoWindow(null);
        }
        if (!map.getOverlays().contains(draftFill)) map.getOverlays().add(draftFill);
        if (draftLine == null) {
            draftLine = new PassiveLine();
            draftLine.getOutlinePaint().setColor(Color.WHITE);
            draftLine.getOutlinePaint().setStrokeWidth(3 * dd);
            draftLine.getOutlinePaint().setPathEffect(new DashPathEffect(new float[]{6 * dd, 6 * dd}, 0));
            draftLine.setInfoWindow(null);
        }
        if (leadLine == null) {
            // thinner, fainter line from the last point to the crosshair: where the next segment will go
            leadLine = new PassiveLine();
            leadLine.getOutlinePaint().setColor(Color.parseColor("#CCFFFFFF"));
            leadLine.getOutlinePaint().setStrokeWidth(1.5f * dd);
            leadLine.getOutlinePaint().setPathEffect(new DashPathEffect(new float[]{3 * dd, 5 * dd}, 0));
            leadLine.setInfoWindow(null);
        }
        if (!map.getOverlays().contains(draftLine)) map.getOverlays().add(draftLine);
        if (!map.getOverlays().contains(leadLine)) map.getOverlays().add(leadLine);
        // always the top layer, so it sees taps first
        if (draftTop == null) draftTop = new DraftOverlay();
        map.getOverlays().remove(draftTop);
        map.getOverlays().add(draftTop);
        updateDraw();
        a.toastOnce("draw2", "Tap the map to add points, or put the crosshair on a spot and press +WPT. For a zone, tap the green first corner (or DONE) to close it.");
    }

    void addPoint(double lat, double lon) {
        if (!drawing) return;
        if (draft.size() >= 200) { a.toast("200 points is the limit for one shape"); return; }
        draft.add(new double[]{lat, lon});
        updateDraw();
    }

    private void undo() {
        moving = -1;
        if (!draft.isEmpty()) draft.remove(draft.size() - 1);
        updateDraw();
    }

    void cancelDraw() {
        boolean wasEditing = editing != null;
        editing = null;
        drawing = false;
        if (draftTop != null) map.getOverlays().remove(draftTop);
        draft.clear();
        updateDraw();
        if (wasEditing) { app.store.touchShapes(); a.refreshMarkers(); } // show the saved copy again
    }

    private void updateDraw() {
        drawRow.setVisibility(drawing ? View.VISIBLE : View.GONE);
        btnDraw.setText(drawing ? "DRAW●" : "DRAW");
        if (!drawing) moving = -1;
        if (draftLine != null) {
            List<GeoPoint> gp = new ArrayList<>();
            for (double[] p : draft) gp.add(new GeoPoint(p[0], p[1]));
            draftLine.setPoints(gp);
            draftLine.setVisible(drawing && draft.size() >= 2);
        }
        updateLead();
        map.invalidate();
    }

    /** Called whenever the map moves: keeps the lead line stretched from the last point to the crosshair. */
    void onMapMoved() {
        if (!drawing) return;
        updateLead();
        map.invalidate();
    }

    /** Zone preview: shades the area as it would be if the crosshair were the next corner. */
    private void updateFill() {
        if (draftFill == null) return;
        boolean show = drawing && zoneMode && draft.size() >= 2;
        if (show) {
            List<GeoPoint> gp = new ArrayList<>();
            for (double[] p : draft) gp.add(new GeoPoint(p[0], p[1]));
            org.osmdroid.api.IGeoPoint c = map.getMapCenter();
            gp.add(new GeoPoint(c.getLatitude(), c.getLongitude()));
            draftFill.setPoints(gp);
        }
        draftFill.setVisible(show);
    }

    private void updateLead() {
        updateFill();
        if (leadLine == null) return;
        if (!drawing || draft.isEmpty()) {
            leadLine.setVisible(false);
            if (drawing) tvDraw.setText(zoneMode ? "ZONE · TAP THE CORNERS" : "DRAW · TAP THE MAP");
            return;
        }
        double[] last = draft.get(draft.size() - 1);
        org.osmdroid.api.IGeoPoint c = map.getMapCenter();
        List<GeoPoint> gp = new ArrayList<>();
        gp.add(new GeoPoint(last[0], last[1]));
        gp.add(new GeoPoint(c.getLatitude(), c.getLongitude()));
        leadLine.setPoints(gp);
        leadLine.setVisible(true);
        double next = Geo.distance(last[0], last[1], c.getLatitude(), c.getLongitude());
        tvDraw.setText((zoneMode ? "ZONE · " : "DRAW · ") + draft.size() + " PTS · "
                + (zoneMode && draft.size() >= 3 ? Shapes.formatArea(Shapes.area(draft), app.imperial()).toUpperCase(Locale.US)
                : Geo.formatDistance(Shapes.length(draft, false), app.imperial()).toUpperCase(Locale.US))
                + "\nNEXT +" + Geo.formatDistance(next, app.imperial()).toUpperCase(Locale.US)
                + String.format(Locale.US, " @ %03.0f°", Geo.bearing(last[0], last[1], c.getLatitude(), c.getLongitude())));
    }

    private void finishDraw() {
        if (draft.size() < 2) { a.toast("Add at least two points first"); return; }
        final List<double[]> pts = new ArrayList<>(draft);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dd);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText name = new EditText(a);
        name.setHint("Name");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        final Store.Shape editingNow = editing;
        name.setText(editingNow != null ? editingNow.name : (zoneMode ? "ZONE" : "RTE") + (app.store.shapes.size() + 1));
        name.selectAll();
        box.addView(name);
        final RadioGroup kind = new RadioGroup(a);
        final RadioButton route = new RadioButton(a), area = new RadioButton(a);
        route.setText("Route · " + Geo.formatDistance(Shapes.length(pts, false), app.imperial()));
        area.setText(pts.size() >= 3 ? "Zone (shaded area) · " + Shapes.formatArea(Shapes.area(pts), app.imperial()) : "Zone (needs 3 points)");
        area.setEnabled(pts.size() >= 3);
        route.setId(View.generateViewId());
        area.setId(View.generateViewId());
        kind.addView(route);
        kind.addView(area);
        kind.check(zoneMode && pts.size() >= 3 ? area.getId() : route.getId());
        box.addView(kind);
        final android.widget.Spinner color = new android.widget.Spinner(a);
        color.setAdapter(new android.widget.ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, COLOR_NAMES));
        if (editingNow != null) color.setSelection(colorIndex(colorOf(editingNow)));
        box.addView(color);
        final CheckBox share = new CheckBox(a);
        boolean can = app.mesh.isConnected() || app.tak.isConnected();
        share.setText(can ? "Share with team (Meshtastic / TAK)" : "Share with team (connect Meshtastic or TAK first)");
        share.setEnabled(can);
        box.addView(share);
        new AlertDialog.Builder(a)
                .setTitle("// SAVE DRAWING")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().trim();
                    boolean closed = kind.getCheckedRadioButtonId() == area.getId();
                    if (n.isEmpty()) n = closed ? "ZONE" : "ROUTE";
                    Store.Shape s;
                    if (editingNow != null && app.store.shapes.containsKey(editingNow.id)) {
                        s = editingNow;
                        s.name = n;
                        s.closed = closed;
                        s.pts = new ArrayList<>(pts);
                    } else {
                        s = app.store.addMyShape(n, closed, pts, "");
                    }
                    s.color = COLORS[Math.max(0, color.getSelectedItemPosition())];
                    app.store.touchShapes();
                    if (share.isChecked() && !app.shareShape(s)) a.toast("Saved, but it could not be sent");
                    cancelDraw();
                    a.refreshMarkers();
                })
                .setNeutralButton("Keep drawing", null)
                .setNegativeButton("Discard", (d, w) -> cancelDraw())
                .show();
    }

    // =====================================================================
    // Shapes on the map
    // =====================================================================

    static final String[] COLOR_NAMES = {"Amber", "Red", "Green", "Blue", "Purple", "White"};
    static final int[] COLORS = {0xFFFFB000, 0xFFFF3B30, 0xFF39FF14, 0xFF40A0FF, 0xFFC77DFF, 0xFFF2F2F2};

    private int colorOf(Store.Shape s) {
        if (s.color != 0) return s.color;
        return !s.src.isEmpty() ? Color.parseColor("#FFB0C4DE")
                : s.isMine() ? Color.parseColor("#FFFFB000") : Color.parseColor("#FFFF6EC7");
    }

    /** Name tag drawn in the middle of a zone. */
    private android.graphics.drawable.Drawable zoneLabel(String name, int color) {
        String label = name.toUpperCase(Locale.US);
        android.graphics.Paint text = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        text.setTextSize(12 * dd);
        text.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD));
        text.setColor(color);
        int w = (int) (text.measureText(label) + 12 * dd), h = (int) (20 * dd);
        // the picture is bigger than the tag: the clear margin around it still counts as a tap,
        // so a finger doesn't have to land exactly on a small label
        int mx = (int) (14 * dd), my = (int) (14 * dd);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w + 2 * mx, h + 2 * my, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        c.translate(mx, my);
        android.graphics.Paint plate = new android.graphics.Paint();
        plate.setColor(Color.parseColor("#CC000000"));
        c.drawRect(0, 0, w, h, plate);
        android.graphics.Paint frame = new android.graphics.Paint();
        frame.setStyle(android.graphics.Paint.Style.STROKE);
        frame.setStrokeWidth(dd);
        frame.setColor(color);
        c.drawRect(0.5f * dd, 0.5f * dd, w - 0.5f * dd, h - 0.5f * dd, frame);
        c.drawText(label, 6 * dd, 14.5f * dd, text);
        return new android.graphics.drawable.BitmapDrawable(a.getResources(), bmp);
    }

    private void pickColor(final Store.Shape s) {
        new AlertDialog.Builder(a)
                .setTitle("// COLOR")
                .setItems(COLOR_NAMES, (d, which) -> {
                    s.color = COLORS[which];
                    app.store.touchShapes();
                    a.refreshMarkers();
                })
                .show();
    }

    private final List<Overlay> shapeOverlays = new ArrayList<>();
    private int shapesRevDrawn = -1;

    /** Rebuilds route/area overlays only when the saved shapes changed. Inserted just under `above`. */
    void syncShapes(Overlay above) {
        if (app.store.shapesRev == shapesRevDrawn) return;
        shapesRevDrawn = app.store.shapesRev;
        map.getOverlays().removeAll(shapeOverlays);
        shapeOverlays.clear();
        for (final Store.Shape s : app.store.shapes.values()) {
            if (s.pts.size() < 2) continue;
            if (editing != null && editing.id.equals(s.id)) continue; // its draft is on screen instead
            int color = colorOf(s);
            List<GeoPoint> gp = new ArrayList<>();
            for (double[] p : s.pts) gp.add(new GeoPoint(p[0], p[1]));
            if (s.closed && s.pts.size() >= 3) {
                Polygon pg = new Polygon(map);
                pg.setPoints(gp);
                pg.getFillPaint().setColor((color & 0x00FFFFFF) | 0x55000000); // about one-third opaque
                pg.getOutlinePaint().setColor(color);
                pg.getOutlinePaint().setStrokeWidth(2.5f * dd);
                pg.setInfoWindow(null);
                pg.setOnClickListener((polygon, mv, pos) -> {
                    if (drawing) return false;
                    shapeTapped(s.id);
                    return true;
                });
                shapeOverlays.add(pg);
                // name tag in the middle (imports can be numerous, so only when they are named)
                if (s.src.isEmpty() || !s.name.isEmpty()) {
                    double[] c = Shapes.center(s.pts);
                    org.osmdroid.views.overlay.Marker tag = new org.osmdroid.views.overlay.Marker(map);
                    tag.setPosition(new GeoPoint(c[0], c[1]));
                    tag.setIcon(zoneLabel(s.name, color));
                    tag.setAnchor(0.5f, 0.5f);
                    tag.setInfoWindow(null);
                    tag.setOnMarkerClickListener((mk, mv) -> {
                        if (drawing) return false;
                        shapeTapped(s.id);
                        return true;
                    });
                    shapeOverlays.add(tag);
                }
            } else {
                Polyline pl = new Polyline(map);
                pl.setPoints(gp);
                pl.getOutlinePaint().setColor(color);
                pl.getOutlinePaint().setStrokeWidth(3f * dd);
                pl.setInfoWindow(null);
                pl.setOnClickListener((polyline, mv, pos) -> {
                    if (drawing) return false;
                    shapeTapped(s.id);
                    return true;
                });
                shapeOverlays.add(pl);
            }
        }
        int at = map.getOverlays().indexOf(above);
        if (at < 0) at = Math.min(1, map.getOverlays().size());
        map.getOverlays().addAll(at, shapeOverlays);
    }

    /** Tapping a route, a zone or a zone's name tag: everything you can do with it, one tap away. */
    void shapeTapped(final String id) {
        final Store.Shape s = app.store.shapes.get(id);
        if (s == null) return;
        String size = s.closed
                ? Shapes.formatArea(Shapes.area(s.pts), app.imperial()) + " · " + Geo.formatDistance(Shapes.length(s.pts, true), app.imperial()) + " around"
                : Geo.formatDistance(Shapes.length(s.pts, false), app.imperial()) + " · " + s.pts.size() + " points";
        String from = (s.isMine() ? "" : " · from " + s.owner) + (s.src.isEmpty() ? "" : " · " + s.src);
        final String EDIT = "Edit name and color", REDRAW = s.closed ? "Move or redraw the corners" : "Move or redraw the points",
                FOLLOW = "Follow this route", CENTER = "Track the center", DELETE = "Delete",
                SHARE = s.shared ? "Re-send to team" : "Share with team";
        final List<String> actions = new ArrayList<>();
        actions.add(EDIT);
        if (s.src.isEmpty()) actions.add(REDRAW);
        actions.add(s.closed ? CENTER : FOLLOW);
        if (s.isMine() && s.src.isEmpty()) actions.add(SHARE);
        actions.add(DELETE);
        new AlertDialog.Builder(a)
                .setTitle(s.name.toUpperCase(Locale.US) + "\n" + (s.closed ? "ZONE · " : "ROUTE · ") + size + from)
                .setItems(actions.toArray(new String[0]), (d, which) -> {
                    String act = actions.get(which);
                    if (act.equals(EDIT)) editShape(s);
                    else if (act.equals(REDRAW)) redraw(s);
                    else if (act.equals(SHARE)) {
                        if (!app.canShare()) a.toast("Connect a radio or the TAK link first");
                        else a.toast(app.shareShape(s) ? "Sent " + s.name + (app.gmrs() && app.radio.isConnected() ? " (thinned to fit one GMRS burst)" : "")
                                : "Could not send (too long for one message?)");
                    } else if (act.equals(FOLLOW)) {
                        // start from the point nearest to me
                        int best = 0;
                        Location l = app.lastLoc;
                        if (l != null) {
                            double bd = Double.MAX_VALUE;
                            for (int i = 0; i < s.pts.size(); i++) {
                                double dist = Geo.distance(l.getLatitude(), l.getLongitude(), s.pts.get(i)[0], s.pts.get(i)[1]);
                                if (dist < bd) { bd = dist; best = i; }
                            }
                            // already standing on it: head for the next one
                            if (bd < 30 && best < s.pts.size() - 1) best++;
                        }
                        a.target = "rte:" + best + ":" + s.id;
                        a.refreshTarget();
                    } else if (act.equals(CENTER)) {
                        double[] c = Shapes.center(s.pts);
                        a.target = "pt:" + c[0] + "," + c[1] + "," + s.name.replace(",", " ");
                        a.refreshTarget();
                    } else if (act.equals(DELETE)) {
                        new AlertDialog.Builder(a)
                                .setTitle("// DELETE " + s.name.toUpperCase(Locale.US) + "?")
                                .setPositiveButton("Delete", (d2, w2) -> {
                                    app.unshareShape(s);
                                    app.store.removeShape(s.id);
                                    if (a.target != null && a.target.endsWith(":" + s.id)) a.target = null;
                                    a.refreshMarkers();
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void editShape(final Store.Shape s) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dd);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText name = new EditText(a);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setText(s.name);
        name.setSelection(name.getText().length());
        box.addView(name);
        final android.widget.Spinner color = new android.widget.Spinner(a);
        color.setAdapter(new android.widget.ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, COLOR_NAMES));
        color.setSelection(colorIndex(colorOf(s)));
        box.addView(color);
        new AlertDialog.Builder(a)
                .setTitle("// EDIT")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (!n.isEmpty()) s.name = n;
                    s.color = COLORS[Math.max(0, color.getSelectedItemPosition())];
                    app.store.touchShapes();
                    a.refreshMarkers();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static int colorIndex(int argb) {
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == argb) return i;
        return 0;
    }

    /** Puts a saved shape back into the draw tool so its corners can be undone and re-placed. */
    private void redraw(Store.Shape s) {
        zoneMode = s.closed;
        startDraw();
        editing = s;
        draft.addAll(s.pts);
        app.store.touchShapes(); // hide the saved copy while its draft is on screen
        a.refreshMarkers();
        updateDraw();
        a.toast("Hold a corner, then tap where it should go. UNDO removes the last one. DONE saves.");
    }

    // =====================================================================
    // Terrain: line of sight
    // =====================================================================

    private String height(double m) {
        return app.imperial() ? String.format(Locale.US, "%,d FT", Math.round(m * 3.28084))
                : String.format(Locale.US, "%,d M", Math.round(m));
    }

    void terrainTo(final double lat, final double lon, final String name) {
        final Location l = app.lastLoc;
        if (l == null) { a.toast("Need a GPS fix first"); return; }
        final double dist = Geo.distance(l.getLatitude(), l.getLongitude(), lat, lon);
        if (dist < 50) { a.toast("That point is right next to you"); return; }
        if (dist > 150_000) { a.toast("Line of sight works up to about 90 miles"); return; }
        final AlertDialog wait = new AlertDialog.Builder(a)
                .setTitle("// TERRAIN")
                .setMessage("Loading elevation data…")
                .setNegativeButton("Cancel", null)
                .show();
        int n = (int) Math.max(60, Math.min(400, dist / 30));
        app.elev.profile(l.getLatitude(), l.getLongitude(), lat, lon, n, heights -> {
            if (!wait.isShowing()) return;
            wait.dismiss();
            final double EYE = 1.8; // a person holding a radio
            Terrain.Result r = Terrain.los(heights, dist, EYE, EYE);
            if (r.missing > heights.length / 5) {
                new AlertDialog.Builder(a).setTitle("// TERRAIN")
                        .setMessage("No elevation data for this path yet. It downloads the first time you look at an area, so this needs internet once. "
                                + "\"Download this area\" also saves it for offline use.")
                        .setPositiveButton("OK", null).show();
                return;
            }
            String where = Geo.formatDistance(r.worstDistanceM, app.imperial()).toUpperCase(Locale.US);
            StringBuilder sb = new StringBuilder();
            if (r.clear) {
                sb.append("CLEAR LINE OF SIGHT\n");
                if (r.worstIndex >= 0) sb.append("Tightest spot: ").append(height(r.minClearanceM)).append(" above the ground, ").append(where).append(" out.\n");
            } else {
                sb.append("BLOCKED BY TERRAIN\n")
                        .append("Ground rises ").append(height(-r.minClearanceM)).append(" above the line, ").append(where).append(" out.\n")
                        .append("To clear it: raise your end ").append(height(r.raiseA)).append(", or the far end ").append(height(r.raiseB)).append(".\n");
            }
            float hi = Float.NEGATIVE_INFINITY;
            for (float h : heights) if (!Float.isNaN(h)) hi = Math.max(hi, h);
            sb.append("\nYou ").append(height(heights[0])).append(" · ").append(name).append(" ").append(height(heights[heights.length - 1]))
                    .append(" · highest ground ").append(height(hi))
                    .append("\nDistance ").append(Geo.formatDistance(dist, app.imperial()))
                    .append("\n\nTerrain only, sampled about every 100 ft. Trees and buildings are not counted. Assumes 6 ft above ground at both ends, and includes the earth's curve.");
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (16 * dd);
            box.setPadding(pad, pad / 2, pad, 0);
            ProfileView pv = new ProfileView(a, r, "YOU", name.toUpperCase(Locale.US));
            box.addView(pv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (150 * dd)));
            TextView tv = new TextView(a);
            tv.setText(sb.toString());
            tv.setTextSize(13);
            tv.setPadding(0, pad / 2, 0, 0);
            box.addView(tv);
            android.widget.ScrollView sv = new android.widget.ScrollView(a);
            sv.addView(box);
            new AlertDialog.Builder(a)
                    .setTitle(r.clear ? "// LINE OF SIGHT: CLEAR" : "// LINE OF SIGHT: BLOCKED")
                    .setView(sv)
                    .setPositiveButton("Close", null)
                    .show();
        });
    }

    // =====================================================================
    // Importing KML / KMZ / GPX
    // =====================================================================

    String displayName(Uri uri) {
        String name = null;
        try (android.database.Cursor c = a.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
        return name == null || name.isEmpty() ? "file" : name;
    }

    private static byte[] readAll(InputStream in, int max) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > max) throw new java.io.IOException("file is too large (limit " + max / 1_000_000 + " MB)");
        }
        return bo.toByteArray();
    }

    void importFile(final Uri uri) {
        final String src = displayName(uri);
        a.toast("Reading " + src + "…");
        new Thread(() -> {
            try {
                byte[] data;
                try (InputStream in = a.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("could not open the file");
                    data = readAll(in, 30_000_000);
                }
                final List<GeoImport.Item> items = GeoImport.parse(data);
                // thin long tracks before they reach the map
                for (GeoImport.Item it : items) if (!it.point) it.pts = Shapes.limit(it.pts, 1500);
                a.runOnUiThread(() -> addImported(src, items));
            } catch (final Exception e) {
                a.runOnUiThread(() -> a.toast("Import failed: " + e.getMessage()));
            }
        }, "import").start();
    }

    private void addImported(String src, List<GeoImport.Item> items) {
        if (items.isEmpty()) { a.toast("Nothing to import in " + src); return; }
        app.store.removeImport(src); // importing the same file again replaces it
        int pts = 0, lines = 0, skipped = 0;
        double n = -90, s = 90, e = -180, w = 180;
        for (GeoImport.Item it : items) {
            if (it.point ? pts >= 400 : lines >= 150) { skipped++; continue; }
            String name = it.name.isEmpty() ? (it.point ? "PT" : it.closed ? "AREA" : "LINE") : it.name;
            if (it.point) { app.store.addImportedPin(name, it.pts.get(0)[0], it.pts.get(0)[1], src); pts++; }
            else { app.store.addMyShape(name, it.closed, it.pts, src); lines++; }
            for (double[] p : it.pts) { n = Math.max(n, p[0]); s = Math.min(s, p[0]); e = Math.max(e, p[1]); w = Math.min(w, p[1]); }
        }
        a.refreshMarkers();
        final BoundingBox bb = new BoundingBox(n, e, s, w);
        new AlertDialog.Builder(a)
                .setTitle("// IMPORTED")
                .setMessage(src + "\n" + pts + " points · " + lines + " lines and areas"
                        + (skipped > 0 ? "\n" + skipped + " more were left out (limit 400 points, 150 lines per file)" : "")
                        + "\n\nRemove it any time from ≡ ▸ Files ▸ Imported files.")
                .setPositiveButton("Show on map", (d, x) -> {
                    try {
                        if (bb.getLatNorth() - bb.getLatSouth() < 1e-5 && Math.abs(bb.getLonEast() - bb.getLonWest()) < 1e-5) {
                            map.getController().setZoom(15.0);
                            map.getController().setCenter(new GeoPoint(bb.getCenterLatitude(), bb.getCenterLongitude()));
                        } else {
                            map.zoomToBoundingBox(bb, true, (int) (60 * dd));
                        }
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    void importedMenu() {
        final List<String> srcs = new ArrayList<>(app.store.importSources());
        if (srcs.isEmpty()) { a.toast("No imported files"); return; }
        new AlertDialog.Builder(a)
                .setTitle("// IMPORTED FILES · TAP TO REMOVE")
                .setItems(srcs.toArray(new String[0]), (d, which) -> {
                    final String src = srcs.get(which);
                    new AlertDialog.Builder(a)
                            .setTitle("// REMOVE?")
                            .setMessage("Takes everything from " + src + " off the map. The file itself is not touched.")
                            .setPositiveButton("Remove", (d2, w) -> {
                                int n = app.store.removeImport(src);
                                a.target = null;
                                a.refreshMarkers();
                                a.toast("Removed " + n + " items");
                            })
                            .setNegativeButton("Cancel", null).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // =====================================================================
    // Map sources
    // =====================================================================

    private boolean offlineProvider = false;

    private File mbtilesFile() { return new File(a.getFilesDir(), "maps/offline.mbtiles"); }

    /** Switches the map to a layer. Falls back to USGS Topo if a custom one is not usable. */
    void applyLayer(int idx) {
        if (idx == Tiles.MBTILES) {
            File f = mbtilesFile();
            try {
                if (!f.exists()) throw new java.io.IOException("file missing");
                OfflineTileProvider p = new OfflineTileProvider(new SimpleRegisterReceiver(a.getApplicationContext()), new File[]{f});
                map.setTileProvider(p);
                String source = null;
                IArchiveFile[] archives = p.getArchives();
                if (archives.length > 0) {
                    Set<String> names = archives[0].getTileSources();
                    if (names != null && !names.isEmpty()) source = names.iterator().next();
                }
                map.setTileSource(source != null ? FileBasedTileSource.getSource(source) : TileSourceFactory.DEFAULT_TILE_SOURCE);
                offlineProvider = true;
                a.layerIndex = idx;
                map.invalidate();
                return;
            } catch (Exception e) {
                a.toast("Couldn't open the offline map file: " + e.getMessage());
                idx = 0;
            }
        }
        if (offlineProvider) {
            map.setTileProvider(new MapTileProviderBasic(a.getApplicationContext()));
            offlineProvider = false;
        }
        String url = app.prefs.getString("customUrl", "");
        if (idx == Tiles.CUSTOM && !Tiles.validTemplate(url)) idx = 0;
        if (idx < 0 || (idx > 4 && idx != Tiles.CUSTOM)) idx = 0;
        map.setTileSource(idx == Tiles.CUSTOM ? Tiles.custom(url) : Tiles.byIndex(idx));
        a.layerIndex = idx;
        map.invalidate();
    }

    private void pick(int idx) {
        app.prefs.edit().putInt("layer", idx).apply();
        applyLayer(idx);
        a.applyDetail();
    }

    void chooseLayer() {
        final List<String> names = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < Tiles.NAMES.length; i++) { names.add(Tiles.NAMES[i]); ids.add(i); }
        if (Tiles.validTemplate(app.prefs.getString("customUrl", ""))) {
            names.add("Custom: " + app.prefs.getString("customName", "my map"));
            ids.add(Tiles.CUSTOM);
        }
        if (mbtilesFile().exists()) {
            names.add("Offline file: " + app.prefs.getString("mbtilesName", "map"));
            ids.add(Tiles.MBTILES);
        }
        names.add("+ Add a map by web address…");
        ids.add(-1);
        names.add("+ Open an offline map file (.mbtiles)…");
        ids.add(-2);
        int checked = ids.indexOf(a.layerIndex);
        new AlertDialog.Builder(a)
                .setTitle("// SELECT LAYER")
                .setSingleChoiceItems(names.toArray(new String[0]), checked, (d, which) -> {
                    d.dismiss();
                    int id = ids.get(which);
                    if (id == -1) addCustomUrl();
                    else if (id == -2) a.pickFile(Intent.ACTION_OPEN_DOCUMENT, "*/*", null, MainActivity.REQ_MBTILES);
                    else pick(id);
                })
                .show();
    }

    private void addCustomUrl() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dd);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText name = new EditText(a);
        name.setHint("Name (e.g. County parcels)");
        name.setText(app.prefs.getString("customName", ""));
        final EditText url = new EditText(a);
        url.setHint("https://example.com/tiles/{z}/{x}/{y}.png");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(app.prefs.getString("customUrl", ""));
        TextView help = new TextView(a);
        help.setText("Any map server with standard tiles works. The address must contain {z}, {x} and {y}. Check the provider allows it. "
                + "Addresses starting with http:// (not https) work too, but are not encrypted: fine for a server on your own Wi-Fi.");
        help.setTextSize(12);
        box.addView(name);
        box.addView(url);
        box.addView(help);
        new AlertDialog.Builder(a)
                .setTitle("// ADD A MAP")
                .setView(box)
                .setPositiveButton("Use", (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (!Tiles.validTemplate(u)) { a.toast("That address needs http(s):// and {z} {x} {y}"); return; }
                    String n = name.getText().toString().trim();
                    app.prefs.edit().putString("customUrl", u).putString("customName", n.isEmpty() ? "my map" : n).apply();
                    pick(Tiles.CUSTOM);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Copies a picked .mbtiles file into the app (it can be large), then switches to it. */
    void copyMbtiles(final Uri uri) {
        final String name = displayName(uri);
        final AlertDialog wait = new AlertDialog.Builder(a)
                .setTitle("// COPYING MAP FILE")
                .setMessage(name + "\nStarting…")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            File dst = mbtilesFile(), tmp = new File(dst.getPath() + ".tmp");
            try {
                dst.getParentFile().mkdirs();
                long total = 0, lastShown = 0;
                byte[] head = new byte[16];
                try (InputStream in = a.getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(tmp)) {
                    if (in == null) throw new java.io.IOException("could not open the file");
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (total < 16) System.arraycopy(buf, 0, head, (int) total, (int) Math.min(n, 16 - total));
                        out.write(buf, 0, n);
                        total += n;
                        if (total - lastShown > 20_000_000) {
                            lastShown = total;
                            final long t = total;
                            a.runOnUiThread(() -> wait.setMessage(name + "\n" + t / 1_000_000 + " MB copied…"));
                        }
                    }
                }
                if (!new String(head, 0, 15, java.nio.charset.StandardCharsets.US_ASCII).equals("SQLite format 3"))
                    throw new java.io.IOException("that is not an MBTiles file");
                // release the old file before replacing it
                a.runOnUiThread(() -> { if (offlineProvider) applyLayer(0); });
                Thread.sleep(300);
                dst.delete();
                if (!tmp.renameTo(dst)) throw new java.io.IOException("could not save the file");
                a.runOnUiThread(() -> {
                    wait.dismiss();
                    app.prefs.edit().putString("mbtilesName", name).apply();
                    pick(Tiles.MBTILES);
                    a.toast("Using " + name + ". Only picture-style (raster) MBTiles can be shown.");
                });
            } catch (final Exception e) {
                tmp.delete();
                a.runOnUiThread(() -> { wait.dismiss(); a.toast("Couldn't use that file: " + e.getMessage()); });
            }
        }, "mbtiles").start();
    }
}
