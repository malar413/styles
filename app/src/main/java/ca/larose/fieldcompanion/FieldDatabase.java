package ca.larose.fieldcompanion;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class FieldDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "field_companion.db";
    private static final int DB_VERSION = 1;

    public FieldDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE observations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "created_at INTEGER NOT NULL," +
                "common_name TEXT NOT NULL," +
                "scientific_name TEXT NOT NULL," +
                "biological_group TEXT NOT NULL," +
                "confidence REAL NOT NULL," +
                "identification_mode TEXT NOT NULL," +
                "latitude REAL," +
                "longitude REAL," +
                "image_uri TEXT," +
                "notes TEXT," +
                "first_discovery INTEGER NOT NULL DEFAULT 0" +
                ")");
        db.execSQL("CREATE INDEX idx_observations_taxon ON observations(scientific_name)");
        db.execSQL("CREATE INDEX idx_observations_date ON observations(created_at DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Schema version 1 is the P1 baseline.
    }

    public long insertObservation(Observation o) {
        ContentValues v = new ContentValues();
        v.put("created_at", o.createdAt);
        v.put("common_name", o.commonName);
        v.put("scientific_name", o.scientificName);
        v.put("biological_group", o.biologicalGroup);
        v.put("confidence", o.confidence);
        v.put("identification_mode", o.identificationMode);
        if (o.latitude != null) v.put("latitude", o.latitude); else v.putNull("latitude");
        if (o.longitude != null) v.put("longitude", o.longitude); else v.putNull("longitude");
        if (o.imageUri != null) v.put("image_uri", o.imageUri); else v.putNull("image_uri");
        v.put("notes", o.notes == null ? "" : o.notes);
        v.put("first_discovery", o.firstDiscovery ? 1 : 0);
        return getWritableDatabase().insertOrThrow("observations", null, v);
    }

    public int countObservations() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM observations", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public int countDistinctTaxa() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(DISTINCT scientific_name) FROM observations WHERE scientific_name <> ''", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public int countByScientific(String scientificName) {
        if (scientificName == null || scientificName.isEmpty()) return 0;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM observations WHERE scientific_name = ?", new String[]{scientificName})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public List<Observation> recentObservations(int limit) {
        List<Observation> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,created_at,common_name,scientific_name,biological_group,confidence,identification_mode,latitude,longitude,image_uri,notes,first_discovery " +
                        "FROM observations ORDER BY created_at DESC LIMIT " + Math.max(1, limit), null)) {
            while (c.moveToNext()) out.add(fromCursor(c));
        }
        return out;
    }

    public List<CollectionEntry> collection() {
        List<CollectionEntry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT scientific_name, MAX(common_name), MAX(biological_group), COUNT(*), MAX(created_at) " +
                        "FROM observations WHERE scientific_name <> '' GROUP BY scientific_name ORDER BY MAX(created_at) DESC", null)) {
            while (c.moveToNext()) {
                CollectionEntry e = new CollectionEntry();
                e.scientificName = c.getString(0);
                e.commonName = c.getString(1);
                e.biologicalGroup = c.getString(2);
                e.count = c.getInt(3);
                e.lastSeenAt = c.getLong(4);
                out.add(e);
            }
        }
        return out;
    }

    public Observation latestForTaxon(String scientificName) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,created_at,common_name,scientific_name,biological_group,confidence,identification_mode,latitude,longitude,image_uri,notes,first_discovery " +
                        "FROM observations WHERE scientific_name=? ORDER BY created_at DESC LIMIT 1", new String[]{scientificName})) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    public List<Observation> observationsForTaxon(String scientificName) {
        List<Observation> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,created_at,common_name,scientific_name,biological_group,confidence,identification_mode,latitude,longitude,image_uri,notes,first_discovery " +
                        "FROM observations WHERE scientific_name=? ORDER BY created_at DESC",
                new String[]{scientificName})) {
            while (c.moveToNext()) out.add(fromCursor(c));
        }
        return out;
    }

    private Observation fromCursor(Cursor c) {
        Observation o = new Observation();
        o.id = c.getLong(0);
        o.createdAt = c.getLong(1);
        o.commonName = c.getString(2);
        o.scientificName = c.getString(3);
        o.biologicalGroup = c.getString(4);
        o.confidence = c.getDouble(5);
        o.identificationMode = c.getString(6);
        o.latitude = c.isNull(7) ? null : c.getDouble(7);
        o.longitude = c.isNull(8) ? null : c.getDouble(8);
        o.imageUri = c.isNull(9) ? null : c.getString(9);
        o.notes = c.isNull(10) ? "" : c.getString(10);
        o.firstDiscovery = c.getInt(11) == 1;
        return o;
    }

    public static class Observation {
        public long id;
        public long createdAt;
        public String commonName = "";
        public String scientificName = "";
        public String biologicalGroup = "Inconnu";
        public double confidence;
        public String identificationMode = "USER_CONFIRMED";
        public Double latitude;
        public Double longitude;
        public String imageUri;
        public String notes = "";
        public boolean firstDiscovery;
    }

    public static class CollectionEntry {
        public String commonName;
        public String scientificName;
        public String biologicalGroup;
        public int count;
        public long lastSeenAt;
    }
}
