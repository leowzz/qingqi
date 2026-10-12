package local.leo.qingqi;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.json.*;

/** All production access runs on IO. Logical relations are maintained in transactions. */
public final class DiagnosticStore extends SQLiteOpenHelper {
    public static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static DiagnosticStore instance;
    public static synchronized DiagnosticStore get(Context context) {
        if (instance == null) instance = new DiagnosticStore(context, "diagnostics.db");
        return instance;
    }
    private final Context context;
    private final String databaseName;
    private boolean initialized;
    public record Entry(String id, String started, String note, String summary, String state, String deviceName) {}
    public DiagnosticStore(Context context, String name) {
        super(context.getApplicationContext(), name, null, 1);
        this.context = context.getApplicationContext(); databaseName = name;
        setOpenParams(new SQLiteDatabase.OpenParams.Builder().setJournalMode(SQLiteDatabase.JOURNAL_MODE_DELETE).build());
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE devices (id INTEGER PRIMARY KEY, address TEXT NOT NULL UNIQUE, name TEXT NOT NULL, last_seen TEXT NOT NULL)");
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, device_id INTEGER, device_name TEXT NOT NULL, address TEXT NOT NULL, started_at TEXT NOT NULL, ended_at TEXT, app_version TEXT NOT NULL, android_version TEXT NOT NULL, phone_model TEXT NOT NULL, note TEXT NOT NULL, state TEXT NOT NULL, summary TEXT NOT NULL, report TEXT NOT NULL)");
        db.execSQL("CREATE INDEX sessions_started ON sessions(started_at DESC, id DESC)");
        db.execSQL("CREATE INDEX sessions_device ON sessions(device_id, started_at DESC)");
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY, session_id TEXT NOT NULL, elapsed_ms INTEGER NOT NULL, phase TEXT NOT NULL, message TEXT NOT NULL)");
        db.execSQL("CREATE INDEX events_session ON events(session_id, id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new SQLiteException("Unsupported diagnostics schema upgrade: " + oldVersion + " -> " + newVersion);
    }
    public void initialize(SharedPreferences legacy) throws JSONException {
        if (initialized) return;
        migrate(legacy);
        ContentValues values = new ContentValues();
        values.put("state", "interrupted"); values.put("summary", "上次检查意外中断，已保留过程记录");
        getWritableDatabase().update("sessions", values, "state=?", new String[]{"running"});
        initialized = true;
    }
    // Stable IDs make retry safe if the process exits between the transaction and preference commit.
    public void migrate(SharedPreferences legacy) throws JSONException {
        if (legacy.getBoolean("sqlite_migrated", false)) return;
        LinkedHashSet<String> reports = new LinkedHashSet<>();
        String last = legacy.getString("last_report", "");
        if (!last.isBlank()) reports.add(last);
        JSONArray history = new JSONArray(legacy.getString("history", "[]"));
        for (int i = 0; i < history.length(); i++) {
            String report = history.getString(i); if (!report.isBlank()) reports.add(report);
        }
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            for (String report : reports) {
                ContentValues v = new ContentValues();
                v.put("id", "legacy-" + UUID.nameUUIDFromBytes(report.getBytes(StandardCharsets.UTF_8)));
                v.put("device_name", "旧版记录"); v.put("address", "");
                v.put("started_at", normalizedTime(field(report, "时间：", "")));
                v.put("app_version", "legacy"); v.put("android_version", field(report, "系统：", ""));
                v.put("phone_model", field(report, "手机：", "")); v.put("note", field(report, "备注：", ""));
                v.put("state", "imported"); v.put("summary", field(report, "结果：", "旧版检查记录")); v.put("report", report);
                db.insertWithOnConflict("sessions", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        // Keep the old values intact as an upgrade fallback; new records go only to SQLite.
        legacy.edit().putBoolean("sqlite_migrated", true).commit();
    }
    private static String field(String report, String prefix, String fallback) {
        for (String line : report.split("\n")) if (line.startsWith(prefix)) return line.substring(prefix.length());
        return fallback;
    }
    private static final java.time.format.DateTimeFormatter TIME = new java.time.format.DateTimeFormatterBuilder()
        .appendInstant(3).toFormatter();
    private static String now() { return TIME.format(java.time.Instant.now()); }
    private static String normalizedTime(String value) {
        try { return TIME.format(java.time.OffsetDateTime.parse(value).toInstant()); }
        catch (java.time.format.DateTimeParseException e) { return TIME.format(java.time.Instant.EPOCH); }
    }
    public String begin(BleProbe.Device device, String note, String version, String androidVersion, String phone) {
        String id = UUID.randomUUID().toString(), started = now();
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues d = new ContentValues(); d.put("address", device.address()); d.put("name", device.name()); d.put("last_seen", started);
            db.insertWithOnConflict("devices", null, d, SQLiteDatabase.CONFLICT_IGNORE);
            db.update("devices", d, "address=?", new String[]{device.address()});
            long deviceId;
            try (Cursor cursor = db.rawQuery("SELECT id FROM devices WHERE address=?", new String[]{device.address()})) {
                if (!cursor.moveToFirst()) throw new SQLiteException("Device could not be saved");
                deviceId = cursor.getLong(0);
            }
            ContentValues v = new ContentValues();
            v.put("id", id); v.put("device_id", deviceId); v.put("device_name", device.name()); v.put("address", device.address());
            v.put("started_at", started); v.put("note", note); v.put("app_version", version);
            v.put("android_version", androidVersion); v.put("phone_model", phone); v.put("state", "running");
            v.put("summary", "检查进行中"); v.put("report", ""); db.insertOrThrow("sessions", null, v);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return id;
    }
    public void append(String session, long elapsed, String phase, String message) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            requireRunning(db, session);
            ContentValues v = new ContentValues(); v.put("session_id", session); v.put("elapsed_ms", elapsed);
            v.put("phase", phase); v.put("message", message); db.insertOrThrow("events", null, v);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private static void requireRunning(SQLiteDatabase db, String session) {
        try (Cursor c = db.rawQuery("SELECT state FROM sessions WHERE id=?", new String[]{session})) {
            if (!c.moveToFirst() || !"running".equals(c.getString(0))) throw new SQLiteException("Session is not running");
        }
    }
    public void finish(String session, String summary, String report) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            requireRunning(db, session);
            ContentValues v = new ContentValues(); v.put("ended_at", now()); v.put("state", "finished");
            v.put("summary", summary); v.put("report", report);
            db.update("sessions", v, "id=?", new String[]{session}); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public List<Entry> list(int offset) {
        List<Entry> entries = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, started_at, note, summary, state, device_name FROM sessions ORDER BY started_at DESC, id DESC LIMIT 50 OFFSET ?", new String[]{Integer.toString(offset)})) {
            while (c.moveToNext()) entries.add(new Entry(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5)));
        }
        return entries;
    }
    public long count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sessions", null)) { c.moveToFirst(); return c.getLong(0); }
    }
    public String report(String id) {
        StringBuilder out = new StringBuilder();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM sessions WHERE id=?", new String[]{id})) {
            if (!c.moveToFirst()) throw new SQLiteException("Record no longer exists");
            String finishedReport = c.getString(c.getColumnIndexOrThrow("report"));
            if (!finishedReport.isEmpty()) return finishedReport;
            out.append("轻骑实测版 · 过程记录\n");
            for (String[] field : new String[][]{{"started_at","开始时间"},{"app_version","应用版本"},{"android_version","系统"},{"phone_model","手机"},{"device_name","设备"},{"address","蓝牙地址"},{"note","备注"},{"summary","结果"}})
                out.append(field[1]).append('：').append(c.getString(c.getColumnIndexOrThrow(field[0]))).append('\n');
        }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT elapsed_ms, phase, message FROM events WHERE session_id=? ORDER BY id", new String[]{id})) {
            while (c.moveToNext()) out.append('\n').append('[').append(c.getLong(0)).append("ms] ").append(c.getString(1)).append('\n').append(c.getString(2)).append('\n');
        }
        return out.toString();
    }
    /** Called on the same IO queue as writes. Closing checkpoints WAL even if the platform enabled it. */
    public void exportZip(OutputStream destination) throws IOException {
        getWritableDatabase();
        close();
        try (ZipOutputStream zip = new ZipOutputStream(destination, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("diagnostics.sqlite"));
            Files.copy(context.getDatabasePath(databaseName).toPath(), zip); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write(("轻骑实测版诊断包\n包含 SQLite 数据库和逐次检查报告。\n数据库表：devices、sessions、events；schema user_version=1。\n包内包含设备标识与原始广播，请私下发送给分析人员。\n").getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            // No writers can interleave on IO; reopening reads the same closed snapshot.
            try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM sessions ORDER BY started_at,id", null)) {
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    zip.putNextEntry(new ZipEntry("reports/" + id + ".txt"));
                    zip.write(report(id).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                }
            }
        }
    }
}
