package ca.larose.fieldcompanion;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 30;
    private static final int REQ_CAPTURE = 31;

    private static final int GREEN = Color.rgb(47, 93, 80);
    private static final int GREEN_DARK = Color.rgb(24, 53, 46);
    private static final int SAND = Color.rgb(243, 239, 228);
    private static final int TEXT = Color.rgb(32, 42, 38);
    private static final int MUTED = Color.rgb(93, 108, 101);

    private final String[] candidates = {
            "Non identifié — information insuffisante",
            "Érable à sucre — Acer saccharum",
            "Bouleau jaune — Betula alleghaniensis",
            "Chanterelle commune — Cantharellus cibarius",
            "Mésange à tête noire — Poecile atricapillus",
            "Geai bleu — Cyanocitta cristata",
            "Écureuil gris — Sciurus carolinensis",
            "Grenouille verte — Lithobates clamitans",
            "Monarque — Danaus plexippus"
    };

    private FieldDatabase db;
    private Uri pendingImage;
    private Double pendingLat;
    private Double pendingLon;
    private LocalNaturalistAi.Result pendingAiResult;
    private List<LocalNaturalistAi.Candidate> aiCandidates = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new FieldDatabase(this);
        home();
        if (getIntent().getBooleanExtra("ai_smoke", false)) runAiSmokeTest();
    }


    private void runAiSmokeTest() {
        new Thread(() -> {
            try {
                Bitmap test = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888);
                test.eraseColor(Color.rgb(60, 140, 70));
                LocalNaturalistAi.Result result = new LocalNaturalistAi(this).classify(test);
                Log.i("FieldCompanionAI", "AI_SMOKE_OK candidates=" + result.candidates.size() + " elapsedMs=" + result.elapsedMs);
            } catch (Throwable t) {
                Log.e("FieldCompanionAI", "AI_SMOKE_FAIL", t);
            }
        }, "FieldCompanion-AI-Smoke").start();
    }

    private void setPage(View content) {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(SAND);
        shell.addView(header(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
        shell.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        shell.addView(nav(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)));
        setContentView(shell);
    }

    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), 0, dp(18), 0);
        bar.setBackgroundColor(GREEN_DARK);
        bar.addView(txt("FIELD COMPANION", 20, Color.WHITE, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView offline = txt("HORS LIGNE", 11, Color.WHITE, true);
        offline.setPadding(dp(10), dp(6), dp(10), dp(6));
        offline.setBackground(round(Color.rgb(66, 118, 101), 40));
        bar.addView(offline);
        return bar;
    }

    private View nav() {
        LinearLayout n = new LinearLayout(this);
        n.setOrientation(LinearLayout.HORIZONTAL);
        n.setBackgroundColor(Color.WHITE);
        n.addView(navButton("Explorer", v -> home()), navParams());
        n.addView(navButton("Observer", v -> beginObservation()), navParams());
        n.addView(navButton("Collection", v -> collection()), navParams());
        n.addView(navButton("Journal", v -> journal()), navParams());
        return n;
    }

    private LinearLayout.LayoutParams navParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    private Button navButton(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(GREEN_DARK);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setOnClickListener(listener);
        return b;
    }

    private void home() {
        ScrollView sv = scroll();
        LinearLayout body = column();
        sv.addView(body);

        body.addView(txt("Compagnon naturaliste local", 28, TEXT, true));
        TextView intro = txt("Explorer → identifier → confirmer → découvrir → documenter → retrouver.", 16, MUTED, false);
        intro.setPadding(0, dp(8), 0, dp(16));
        body.addView(intro);

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.addView(stat(String.valueOf(db.countObservations()), "observations"), new LinearLayout.LayoutParams(0, dp(110), 1f));
        View gap = new View(this);
        stats.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
        stats.addView(stat(String.valueOf(db.countDistinctTaxa()), "découvertes"), new LinearLayout.LayoutParams(0, dp(110), 1f));
        body.addView(stats);

        Button capture = primary("📷  Nouvelle observation");
        capture.setOnClickListener(v -> beginObservation());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        cp.setMargins(0, dp(18), 0, dp(14));
        body.addView(capture, cp);

        LinearLayout info = card();
        info.addView(txt("P1 — identification prudente", 17, TEXT, true));
        TextView p = txt("La capture, le GPS et le journal local sont fonctionnels. Aucun modèle IA non validé n’est présenté comme réel : l’identification est confirmée manuellement dans cette build.", 14, MUTED, false);
        p.setPadding(0, dp(8), 0, 0);
        info.addView(p);
        body.addView(info);

        Button about = secondary("À propos et confidentialité");
        about.setOnClickListener(v -> about());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        ap.setMargins(0, dp(14), 0, dp(20));
        body.addView(about, ap);
        setPage(sv);
    }

    private View stat(String value, String label) {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER);
        c.addView(txt(value, 32, GREEN_DARK, true));
        c.addView(txt(label, 13, MUTED, false));
        return c;
    }

    private void beginObservation() {
        pendingImage = null;
        pendingLat = null;
        pendingLon = null;
        pendingAiResult = null;
        aiCandidates = new ArrayList<>();
        updateLocation();
        openCamera();
    }

    private void updateLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location best = null;
        for (String provider : lm.getProviders(true)) {
            try {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getAccuracy() < best.getAccuracy())) best = l;
            } catch (SecurityException ignored) {}
        }
        if (best != null) {
            pendingLat = best.getLatitude();
            pendingLon = best.getLongitude();
        }
    }

    private void openCamera() {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "FieldCompanion_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FieldCompanion");
            pendingImage = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, pendingImage);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAPTURE);
        } catch (Exception e) {
            pendingImage = null;
            Toast.makeText(this, "Capture indisponible : " + e.getMessage(), Toast.LENGTH_LONG).show();
            editor();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            if (resultCode != RESULT_OK && pendingImage != null) {
                try { getContentResolver().delete(pendingImage, null, null); } catch (Exception ignored) {}
                pendingImage = null;
            }
            updateLocation();
            if (resultCode == RESULT_OK && pendingImage != null) analyzeImageThenEditor();
            else editor();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOCATION && results.length > 0) updateLocation();
    }


    private void analyzeImageThenEditor() {
        ScrollView sv = scroll();
        LinearLayout body = column();
        sv.addView(body);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.addView(txt("Analyse locale en cours…", 25, TEXT, true));
        TextView detail = txt("BioCLIP Mobile pour les plantes + experts oiseaux/insectes\nAucune photo n’est envoyée sur Internet.", 14, MUTED, false);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(12), 0, 0);
        body.addView(detail);
        setPage(sv);

        new Thread(() -> {
            try {
                Bitmap bitmap;
                try (java.io.InputStream in = getContentResolver().openInputStream(pendingImage)) {
                    bitmap = BitmapFactory.decodeStream(in);
                }
                if (bitmap == null) throw new IllegalStateException("Photo illisible");
                LocalNaturalistAi.Result result = new LocalNaturalistAi(this).classify(bitmap);
                runOnUiThread(() -> {
                    pendingAiResult = result;
                    aiCandidates = new ArrayList<>(result.candidates);
                    editor();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pendingAiResult = null;
                    aiCandidates = new ArrayList<>();
                    Toast.makeText(this, "IA locale indisponible : " + e.getMessage(), Toast.LENGTH_LONG).show();
                    editor();
                });
            }
        }, "FieldCompanion-AI").start();
    }

    private void editor() {
        ScrollView sv = scroll();
        LinearLayout body = column();
        sv.addView(body);
        body.addView(txt("Nouvelle observation", 27, TEXT, true));

        if (pendingImage != null) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try { image.setImageURI(pendingImage); } catch (Exception ignored) {}
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250));
            ip.setMargins(0, dp(14), 0, dp(14));
            body.addView(image, ip);
        }

        LinearLayout context = card();
        context.addView(txt("Contexte terrain", 16, TEXT, true));
        context.addView(txt(locationText(), 14, MUTED, false));
        body.addView(context);

        LinearLayout aiCard = card();
        aiCard.addView(txt("IA naturaliste locale — alpha", 16, TEXT, true));
        String aiStatus;
        if (pendingAiResult == null) {
            aiStatus = "Aucune hypothèse IA disponible. Tu peux enregistrer l’observation comme non identifiée.";
        } else {
            aiStatus = pendingAiResult.reliability + " • " + pendingAiResult.elapsedMs + " ms • 100 % hors ligne";
        }
        aiCard.addView(txt(aiStatus, 13, MUTED, false));
        LinearLayout.LayoutParams aip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aip.setMargins(0, dp(14), 0, 0);
        body.addView(aiCard, aip);

        TextView label = txt("Identification à confirmer", 16, TEXT, true);
        label.setPadding(0, dp(18), 0, dp(7));
        body.addView(label);

        List<String> choices = new ArrayList<>();
        choices.add("Non identifié — information insuffisante");
        for (LocalNaturalistAi.Candidate candidate : aiCandidates) choices.add(candidate.display());

        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
        spinner.setBackground(round(Color.WHITE, 12));
        body.addView(spinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        TextView warning = txt("Les scores des experts ne sont pas directement comparables. Pour les plantes, « sim. » indique une similarité BioCLIP, pas une probabilité. « Non identifié » reste le choix prudent.", 13, MUTED, false);
        warning.setPadding(0, dp(8), 0, dp(14));
        body.addView(warning);

        EditText notes = new EditText(this);
        notes.setHint("Notes de terrain…");
        notes.setGravity(Gravity.TOP);
        notes.setMinLines(3);
        notes.setPadding(dp(12), dp(12), dp(12), dp(12));
        notes.setBackground(round(Color.WHITE, 12));
        body.addView(notes, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));

        Button save = primary("Confirmer et enregistrer");
        save.setOnClickListener(v -> save(spinner.getSelectedItemPosition(), notes.getText().toString()));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        sp.setMargins(0, dp(16), 0, dp(20));
        body.addView(save, sp);
        setPage(sv);
    }

    private String locationText() {
        if (pendingLat == null || pendingLon == null) return "GPS : position non disponible";
        return String.format(Locale.CANADA_FRENCH, "GPS : %.5f, %.5f", pendingLat, pendingLon);
    }

    private void save(int index, String notes) {
        FieldDatabase.Observation o = new FieldDatabase.Observation();
        o.createdAt = System.currentTimeMillis();
        o.latitude = pendingLat;
        o.longitude = pendingLon;
        o.imageUri = pendingImage == null ? null : pendingImage.toString();
        o.notes = notes;

        if (index <= 0 || index - 1 >= aiCandidates.size()) {
            o.commonName = "Non identifié";
            o.scientificName = "";
            o.biologicalGroup = "Inconnu";
            o.confidence = 0;
            o.identificationMode = "INSUFFICIENT_INFORMATION";
        } else {
            LocalNaturalistAi.Candidate candidate = aiCandidates.get(index - 1);
            o.commonName = (candidate.commonName == null || candidate.commonName.trim().isEmpty()) ? candidate.scientificName : candidate.commonName;
            o.scientificName = candidate.scientificName;
            o.biologicalGroup = candidate.group;
            o.confidence = candidate.confidence;
            o.identificationMode = "LOCAL_AI_USER_CONFIRMED";
            o.firstDiscovery = db.countByScientific(o.scientificName) == 0;
        }

        try {
            o.id = db.insertObservation(o);
            saved(o);
        } catch (Exception e) {
            Toast.makeText(this, "Erreur : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String group(String scientific) {
        if (scientific.startsWith("Acer") || scientific.startsWith("Betula")) return "Plante";
        if (scientific.startsWith("Cantharellus")) return "Champignon";
        if (scientific.startsWith("Poecile") || scientific.startsWith("Cyanocitta")) return "Oiseau";
        if (scientific.startsWith("Sciurus")) return "Mammifère";
        if (scientific.startsWith("Lithobates")) return "Amphibien";
        if (scientific.startsWith("Danaus")) return "Insecte";
        return "Inconnu";
    }

    private void saved(FieldDatabase.Observation o) {
        ScrollView sv = scroll();
        LinearLayout body = column();
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        sv.addView(body);
        body.addView(txt(o.firstDiscovery ? "★" : "✓", 58, o.firstDiscovery ? Color.rgb(170, 112, 25) : GREEN, true));
        body.addView(txt(o.firstDiscovery ? "Nouvelle découverte !" : "Observation enregistrée", 27, TEXT, true));
        body.addView(txt(o.commonName, 20, GREEN_DARK, true));
        if (!o.scientificName.isEmpty()) {
            TextView sci = txt(o.scientificName, 16, MUTED, false);
            sci.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
            body.addView(sci);
        }
        Button see = primary("Voir ma collection");
        see.setOnClickListener(v -> collection());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        p.setMargins(0, dp(25), 0, dp(10));
        body.addView(see, p);
        setPage(sv);
    }

    private void collection() {
        ScrollView sv = scroll();
        LinearLayout body = column();
        sv.addView(body);
        body.addView(txt("Ma collection", 27, TEXT, true));
        TextView count = txt(db.countDistinctTaxa() + " taxons confirmés", 14, MUTED, false);
        count.setPadding(0, dp(5), 0, dp(14));
        body.addView(count);

        List<FieldDatabase.CollectionEntry> entries = db.collection();
        if (entries.isEmpty()) {
            body.addView(empty("Aucune découverte confirmée pour l’instant."));
        } else {
            for (FieldDatabase.CollectionEntry e : entries) {
                LinearLayout c = card();
                c.setOnClickListener(v -> taxon(e.scientificName));
                c.addView(txt(e.commonName, 18, TEXT, true));
                TextView sci = txt(e.scientificName, 14, GREEN, false);
                sci.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
                c.addView(sci);
                c.addView(txt(e.biologicalGroup + " • " + e.count + " observation(s)", 13, MUTED, false));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                p.setMargins(0, 0, 0, dp(10));
                body.addView(c, p);
            }
        }
        setPage(sv);
    }

    private void taxon(String scientific) {
        FieldDatabase.Observation o = db.latestForTaxon(scientific);
        if (o == null) { collection(); return; }
        ScrollView sv = scroll();
        LinearLayout body = column();
        sv.addView(body);
        body.addView(txt(o.commonName, 28, TEXT, true));
        TextView s = txt(o.scientificName, 18, GREEN, false);
        s.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        body.addView(s);
        body.addView(txt(o.biologicalGroup + " • " + db.countByScientific(scientific) + " observation(s)", 14, MUTED, false));

        if (o.imageUri != null) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try { image.setImageURI(Uri.parse(o.imageUri)); } catch (Exception ignored) {}
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260));
            ip.setMargins(0, dp(14), 0, dp(14));
            body.addView(image, ip);
        }

        LinearLayout c = card();
        c.addView(txt("Dernière observation", 16, TEXT, true));
        c.addView(txt(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.CANADA_FRENCH).format(new Date(o.createdAt)), 14, MUTED, false));
        c.addView(txt(o.latitude == null ? "Position non enregistrée" : String.format(Locale.CANADA_FRENCH, "%.5f, %.5f", o.latitude, o.longitude), 14, MUTED, false));
        if (o.notes != null && !o.notes.trim().isEmpty()) c.addView(txt(o.notes, 14, TEXT, false));
        body.addView(c);
        setPage(sv);
    }

    private void journal() {
        ScrollView sv = scroll();
        LinearLayout body = column();
        sv.addView(body);
        body.addView(txt("Journal d’observations", 27, TEXT, true));
        TextView count = txt(db.countObservations() + " entrées locales", 14, MUTED, false);
        count.setPadding(0, dp(5), 0, dp(14));
        body.addView(count);

        List<FieldDatabase.Observation> list = db.recentObservations(100);
        if (list.isEmpty()) {
            body.addView(empty("Ton journal est vide."));
        } else {
            for (FieldDatabase.Observation o : list) {
                LinearLayout c = card();
                c.addView(txt(o.commonName, 17, TEXT, true));
                String meta = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.CANADA_FRENCH).format(new Date(o.createdAt));
                c.addView(txt(meta, 13, MUTED, false));
                if (o.firstDiscovery) c.addView(txt("★ Première découverte", 13, Color.rgb(160, 105, 20), true));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                p.setMargins(0, 0, 0, dp(10));
                body.addView(c, p);
            }
        }
        setPage(sv);
    }

    private void about() {
        ScrollView sv = scroll();
        LinearLayout body = column();
        sv.addView(body);
        body.addView(txt("À propos", 27, TEXT, true));
        LinearLayout c = card();
        c.addView(txt("Field Companion 0.4.0-ai2", 18, TEXT, true));
        c.addView(txt("Application locale, sans compte requis. Les observations sont conservées dans SQLite sur l’appareil. La sauvegarde cloud Android automatique est exclue; le transfert volontaire appareil-à-appareil est permis.", 14, MUTED, false));
        body.addView(c);

        LinearLayout ai = card();
        ai.addView(txt("État de l’IA", 18, TEXT, true));
        ai.addView(txt("Expert botanique BioCLIP 2.5 Mobile (4 271 espèces) avec repli prudent au genre, plus experts locaux oiseaux et insectes. Les hypothèses restent à confirmer et « non identifié » demeure toujours possible.", 14, MUTED, false));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(12), 0, 0);
        body.addView(ai, p);
        setPage(sv);
    }

    private ScrollView scroll() {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        return s;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(18), dp(18), dp(18), dp(24));
        return l;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(15), dp(16), dp(15));
        l.setBackground(round(Color.WHITE, 16));
        return l;
    }

    private View empty(String message) {
        LinearLayout l = card();
        l.addView(txt(message, 15, MUTED, false));
        return l;
    }

    private TextView txt(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button primary(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(round(GREEN, 14));
        return b;
    }

    private Button secondary(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(GREEN_DARK);
        b.setBackground(round(Color.WHITE, 14));
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
