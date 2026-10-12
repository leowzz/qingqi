package local.leo.qingqi;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Framework-only instrumentation, runs against an isolated database and preferences. */
public final class StorageTestRunner extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try { checkStorage(); result.putString("stream", "Storage integration checks passed\n"); finish(Activity.RESULT_OK, result); }
        catch (Throwable e) { result.putString("stream", "FAILED: " + e + "\n" + Arrays.toString(e.getStackTrace())); finish(Activity.RESULT_CANCELED, result); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static long scalar(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) { c.moveToFirst(); return c.getLong(0); }
    }
    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192]; int n;
        while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
    }
    private void checkStorage() throws Exception {
        Context context = getTargetContext();
        String dbName = "storage-test-" + UUID.randomUUID() + ".db";
        String prefsName = "storage-test-" + UUID.randomUUID();
        SharedPreferences legacy = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        File snapshot = new File(context.getCacheDir(), dbName);
        DiagnosticStore store = new DiagnosticStore(context, dbName);
        try {
            String old = "旧版报告\n时间：2026-10-12T10:00:00+08:00\n备注：锁车\n结果：成功\n\n原始记录";
            legacy.edit().putString("last_report", old).putString("history", new org.json.JSONArray().put(old).put("另一个旧报告").toString()).commit();
            store.initialize(legacy); check(store.count() == 2, "legacy deduplication");
            try (Cursor mode = store.getReadableDatabase().rawQuery("PRAGMA journal_mode", null)) {
                mode.moveToFirst(); check("delete".equals(mode.getString(0)), "standalone DB must not depend on WAL");
            }
            legacy.edit().putBoolean("sqlite_migrated", false).commit();
            store.migrate(legacy); check(store.count() == 2, "migration retry must be idempotent");
            check(legacy.getString("last_report", "").equals(old), "legacy fallback retained");
            boolean exact = false;
            for (var row : store.list(0)) exact |= store.report(row.id()).equals(old);
            check(exact, "legacy report preserved verbatim");
            BleProbe.Device device = new BleProbe.Device("00:11:22:33:44:55", "TEST ONLY", -60, "advertisement");
            for (int i = 0; i < 55; i++) {
                String id = store.begin(device, "状态 " + i, "test", "test", "test");
                store.append(id, 10, "连接", "事件 " + i);
                store.finish(id, "完成", "报告 " + i);
            }
            check(store.count() == 57, "records must not be truncated at ten");
            check(store.list(0).size() == 50 && store.list(50).size() == 7, "history pagination");
            check(scalar(store.getReadableDatabase(), "SELECT COUNT(*) FROM devices") == 1, "device deduplication");
            String interrupted = store.begin(device, "意外停止", "test", "test", "test");
            store.append(interrupted, 123, "服务发现", "已保存接口数据");
            try { store.append("missing", 0, "bad", "orphan"); throw new AssertionError("must reject orphan event"); }
            catch (android.database.sqlite.SQLiteException expected) { }
            check(scalar(store.getReadableDatabase(), "SELECT COUNT(*) FROM events") == 56, "invalid relation rolled back");
            store.close(); store = new DiagnosticStore(context, dbName); store.initialize(legacy);
            check(store.report(interrupted).contains("意外中断") && store.report(interrupted).contains("已保存接口数据"), "process restart recovery");
            check(scalar(store.getReadableDatabase(), "SELECT COUNT(*) FROM sessions WHERE state='running'") == 0, "running marker recovered");
            check(scalar(store.getReadableDatabase(), "SELECT COUNT(*) FROM sessions WHERE state='finished'") == 55, "finished reports unaffected");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); store.exportZip(bytes);
            int reports = 0; boolean hasReadme = false, hasSnapshot = false;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.getName().equals("diagnostics.sqlite")) {
                        try (OutputStream out = new FileOutputStream(snapshot)) { copy(zip, out); }
                        hasSnapshot = true;
                    } else if (entry.getName().startsWith("reports/")) {
                        reports++; ByteArrayOutputStream text = new ByteArrayOutputStream(); copy(zip, text);
                        check(text.size() > 0, "report should be nonempty");
                    } else if (entry.getName().equals("README.txt")) hasReadme = true;
                }
            }
            check(reports == 58 && hasSnapshot && hasReadme, "ZIP contains all records and database");
            try (SQLiteDatabase exported = SQLiteDatabase.openDatabase(snapshot.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                try (Cursor c = exported.rawQuery("PRAGMA integrity_check", null)) { c.moveToFirst(); check(c.getString(0).equals("ok"), "snapshot integrity"); }
                check(scalar(exported, "SELECT COUNT(*) FROM sessions") == 58, "snapshot row count");
                check(scalar(exported, "SELECT COUNT(*) FROM events") == 56, "snapshot event count");
            }
            String afterExport = store.begin(device, "导出后", "test", "test", "test");
            store.append(afterExport, 1, "test", "still writable"); store.finish(afterExport, "完成", "完成");
            check(store.count() == 59, "database reopens after export");
        } finally {
            store.close(); context.deleteDatabase(dbName); context.deleteSharedPreferences(prefsName);
            if (snapshot.exists() && !snapshot.delete()) throw new IOException("Test snapshot cleanup failed");
        }
    }
}
