package app.fieldmap;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

import app.fieldmap.core.Gmrs;

public class SettingsActivity extends AppCompatActivity {

    private static final String[] BEACON_NAMES = {"Off (manual only)", "1 minute", "2 minutes", "5 minutes", "10 minutes", "30 minutes"};
    private static final int[] BEACON_SECS = {0, 60, 120, 300, 600, 1800};
    private static final String[] OVERDUE_NAMES = {"10 minutes", "15 minutes", "30 minutes", "60 minutes"};
    private static final int[] OVERDUE_MIN = {10, 15, 30, 60};
    private static final String[] CH_NAMES = {"0 · Primary", "1 · Secondary", "2", "3", "4", "5", "6", "7"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        FieldMapApp app = FieldMapApp.get();

        EditText call = findViewById(R.id.setCall);
        EditText ssid = findViewById(R.id.setSsid);
        EditText group = findViewById(R.id.setGroup);
        EditText path = findViewById(R.id.setPath);
        EditText comment = findViewById(R.id.setComment);
        EditText filter = findViewById(R.id.setFilter);
        Spinner beacon = findViewById(R.id.setBeacon);
        Spinner symbol = findViewById(R.id.setSymbol);
        Spinner detail = findViewById(R.id.setDetail);
        detail.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Sharpest (smallest text)", "Balanced", "Large text (blurrier)"}));
        detail.setSelection(app.prefs.getInt("detail", 1));
        CheckBox imperial = findViewById(R.id.setImperial);
        CheckBox keepOn = findViewById(R.id.setKeepOn);
        Spinner meshCh = findViewById(R.id.setMeshCh);
        CheckBox teamOnly = findViewById(R.id.setTeamOnly);
        CheckBox aprsTeam = findViewById(R.id.setAprsTeam);
        CheckBox aprsPos = findViewById(R.id.setAprsPos);
        CheckBox nogate = findViewById(R.id.setNogate);
        CheckBox background = findViewById(R.id.setBackground);
        CheckBox alertMsg = findViewById(R.id.setAlertMsg);
        CheckBox alertWpt = findViewById(R.id.setAlertWpt);
        CheckBox alertOverdue = findViewById(R.id.setAlertOverdue);
        Spinner overdueMin = findViewById(R.id.setOverdueMin);
        CheckBox autoConnect = findViewById(R.id.setAutoConnect);
        CheckBox alertSos = findViewById(R.id.setAlertSos);
        CheckBox weather = findViewById(R.id.setWeather);
        CheckBox trails = findViewById(R.id.setTrails);
        CheckBox elevation = findViewById(R.id.setElevation);
        CheckBox takLan = findViewById(R.id.setTakLan);
        EditText takServer = findViewById(R.id.setTakServer);
        EditText takCall = findViewById(R.id.setTakCall);
        CheckBox gmrs = findViewById(R.id.setGmrs);
        EditText gmrsCall = findViewById(R.id.setGmrsCall);
        EditText gmrsUnit = findViewById(R.id.setGmrsUnit);
        CheckBox gmrsPos = findViewById(R.id.setGmrsPos);
        CheckBox meshCh0Ok = findViewById(R.id.setMeshCh0Ok);
        CheckBox plain = findViewById(R.id.setPlain);
        CheckBox localTime = findViewById(R.id.setLocalTime);
        CheckBox updateCheck = findViewById(R.id.setUpdateCheck);
        final boolean gmrsWas = app.gmrs();
        gmrs.setChecked(gmrsWas);
        gmrsCall.setText(app.gmrsCall());
        gmrsUnit.setText(app.gmrsUnit());
        gmrsPos.setChecked(app.prefs.getBoolean("gmrsPos", false));
        meshCh0Ok.setChecked(app.prefs.getBoolean("meshCh0Ok", false));
        plain.setChecked(app.prefs.getBoolean("plain", false));
        localTime.setChecked(app.prefs.getBoolean("localTime", false));
        updateCheck.setChecked(app.prefs.getBoolean("updateCheck", true));
        elevation.setChecked(app.prefs.getBoolean("elevation", true));
        takLan.setChecked(app.prefs.getBoolean("takLan", false));
        takServer.setText(app.prefs.getString("takServer", ""));
        takCall.setText(app.prefs.getString("takCall", ""));
        takCall.setHint(app.myName());
        autoConnect.setChecked(app.prefs.getBoolean("autoConnect", true));
        alertSos.setChecked(app.prefs.getBoolean("alertSos", true));
        weather.setChecked(app.prefs.getBoolean("weather", true));
        trails.setChecked(app.prefs.getBoolean("trails", true));

        meshCh.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, CH_NAMES));
        meshCh.setSelection(Math.max(0, Math.min(7, app.meshChannel())));
        overdueMin.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, OVERDUE_NAMES));
        int om = app.prefs.getInt("overdueMin", 15);
        for (int i = 0; i < OVERDUE_MIN.length; i++) if (OVERDUE_MIN[i] == om) overdueMin.setSelection(i);
        teamOnly.setChecked(app.meshTeamOnly());
        aprsTeam.setChecked(app.aprsTeam());
        aprsPos.setChecked(app.aprsPosition());
        nogate.setChecked(app.noGate());
        background.setChecked(app.background());
        alertMsg.setChecked(app.prefs.getBoolean("alertMsg", true));
        alertWpt.setChecked(app.prefs.getBoolean("alertWpt", true));
        alertOverdue.setChecked(app.prefs.getBoolean("alertOverdue", true));

        call.setText(app.callsign());
        ssid.setText(String.valueOf(app.ssid()));
        group.setText(app.group());
        path.setText(app.path());
        comment.setText(app.statusComment());
        filter.setText(app.prefs.getString("filter", ""));
        imperial.setChecked(app.imperial());
        keepOn.setChecked(app.prefs.getBoolean("keepOn", false));

        ArrayAdapter<String> ba = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, BEACON_NAMES);
        beacon.setAdapter(ba);
        int cur = app.beaconSeconds();
        for (int i = 0; i < BEACON_SECS.length; i++) if (BEACON_SECS[i] == cur) beacon.setSelection(i);

        ArrayAdapter<String> sa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, FieldMapApp.SYMBOL_NAMES);
        symbol.setAdapter(sa);
        String curSym = "" + app.symTable() + app.symCode();
        int si = FieldMapApp.SYMBOL_CODES.indexOf(curSym);
        if (si >= 0) symbol.setSelection(si);

        findViewById(R.id.setSave).setOnClickListener(v -> {
            String c = call.getText().toString().trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
            if (!c.isEmpty() && !c.matches(".*\\d.*")) {
                Toast.makeText(this, "That doesn't look like a callsign", Toast.LENGTH_LONG).show();
                return;
            }
            int s;
            try { s = Integer.parseInt(ssid.getText().toString().trim()); } catch (NumberFormatException e) { s = 0; }
            if (s < 0 || s > 15) s = 0;
            String g = group.getText().toString().trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9-]", "");
            String sym = FieldMapApp.SYMBOL_CODES.get(Math.max(0, symbol.getSelectedItemPosition()));
            final String gc = Gmrs.cleanCall(gmrsCall.getText().toString());
            final String gu = Gmrs.cleanUnit(gmrsUnit.getText().toString());
            if (gmrs.isChecked() && !Gmrs.validCall(gc)) {
                Toast.makeText(this, "GMRS mode needs your GMRS callsign (like WRXX123)", Toast.LENGTH_LONG).show();
                return;
            }
            if (gmrs.isChecked() && gu.isEmpty()) {
                Toast.makeText(this, "GMRS mode needs a unit name for this radio (like ELI)", Toast.LENGTH_LONG).show();
                return;
            }
            final int sF = s;
            final String cF = c, gF = g, symF = sym;
            final Runnable commit = () -> {
            app.prefs.edit()
                    .putBoolean("gmrs", gmrs.isChecked())
                    .putString("gmrsCall", gc)
                    .putString("gmrsUnit", gu)
                    .putBoolean("gmrsPos", gmrsPos.isChecked())
                    .putBoolean("meshCh0Ok", meshCh0Ok.isChecked())
                    .putBoolean("plain", plain.isChecked())
                    .putBoolean("localTime", localTime.isChecked())
                    .putBoolean("updateCheck", updateCheck.isChecked())
                    .putString("call", cF)
                    .putInt("ssid", sF)
                    .putString("group", gF.isEmpty() ? "FIELDMAP" : gF)
                    .putString("path", path.getText().toString().trim().toUpperCase(Locale.US))
                    .putString("comment", comment.getText().toString().trim())
                    .putString("filter", filter.getText().toString().trim().toUpperCase(Locale.US))
                    .putInt("beacon", BEACON_SECS[Math.max(0, beacon.getSelectedItemPosition())])
                    .putString("symTable", symF.substring(0, 1))
                    .putString("symCode", symF.substring(1, 2))
                    .putBoolean("imperial", imperial.isChecked())
                    .putBoolean("keepOn", keepOn.isChecked())
                    .putInt("detail", Math.max(0, detail.getSelectedItemPosition()))
                    .putInt("meshChannel", Math.max(0, meshCh.getSelectedItemPosition()))
                    .putBoolean("meshTeamOnly", teamOnly.isChecked())
                    .putBoolean("aprsTeam", aprsTeam.isChecked())
                    .putBoolean("aprsPos", aprsPos.isChecked())
                    .putBoolean("nogate", nogate.isChecked())
                    .putBoolean("background", background.isChecked())
                    .putBoolean("alertMsg", alertMsg.isChecked())
                    .putBoolean("alertWpt", alertWpt.isChecked())
                    .putBoolean("alertOverdue", alertOverdue.isChecked())
                    .putBoolean("autoConnect", autoConnect.isChecked())
                    .putBoolean("alertSos", alertSos.isChecked())
                    .putBoolean("weather", weather.isChecked())
                    .putBoolean("trails", trails.isChecked())
                    .putBoolean("elevation", elevation.isChecked())
                    .putBoolean("takLan", takLan.isChecked())
                    .putString("takServer", takServer.getText().toString().trim())
                    .putString("takCall", takCall.getText().toString().trim())
                    .putInt("overdueMin", OVERDUE_MIN[Math.max(0, overdueMin.getSelectedItemPosition())])
                    .apply();
            app.gmrsQueue.clear();
            app.applyTakSettings();
            app.updateService();
            Toast.makeText(this, gmrs.isChecked() ? "SAVED · GMRS MODE IS ON" : gmrsWas ? "SAVED · GMRS MODE IS OFF" : "CONFIG COMMITTED", Toast.LENGTH_SHORT).show();
            finish();
            };
            if (gmrs.isChecked() && !gmrsWas) {
                // turning GMRS mode on: say plainly what it means before it takes effect
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("// TURN ON GMRS MODE?")
                        .setMessage("The UV-PRO link will send as " + gc + " / " + gu + " on whatever channel the radio is tuned to.\n\n"
                                + "• Tune the radio to a GMRS channel (1-22, not a repeater channel) before connecting. The app cannot check or change the channel.\n"
                                + "• The FCC only allows GMRS data from handhelds certified for GMRS with a fixed antenna (BTECH's GMRS-PRO is the certified one; the UV-PRO is certified as a ham radio). Which radio you transmit with is your call as the licensee.\n"
                                + "• Data bursts must be 1 second or less, at most one every 30 seconds. The app enforces this: 60-character texts, and messages wait their turn. Set the radio's KISS TX delay to 300 ms or less.\n"
                                + "• Your callsign rides in every burst, but the rules ask for voice ID too: say your callsign on the channel when you finish.\n"
                                + "• Nothing on GMRS is encrypted.\n\n"
                                + "An amber GMRS MODE bar stays on screen while this is on.")
                        .setPositiveButton("Turn on", (d, w) -> commit.run())
                        .setNegativeButton("Cancel", null)
                        .show();
            } else {
                commit.run();
            }
        });
    }
}
