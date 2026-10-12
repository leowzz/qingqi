package local.leo.qingqi;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class MainActivity extends Activity implements BleProbe.Listener {
    private static final int GREEN = Color.rgb(49, 101, 79), INK = Color.rgb(30, 41, 35);
    private static final int MUTED = Color.rgb(101, 113, 104), PAPER = Color.rgb(246, 247, 242);
    private BleProbe probe;
    private TextView status, scanStatus, filterStatus;
    private Button scanButton, reportButton, cancelButton;
    private String testNote = "";
    private AlertDialog scanDialog;
    private ArrayAdapter<BleProbe.Device> adapter;
    private String searchQuery = "";
    private List<BleProbe.Device> candidates = List.of();
    private boolean activityVisible;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        probe = new BleProbe(this, this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAPER);
        LinearLayout content = column();
        content.setPadding(dp(24), dp(18), dp(24), dp(24));
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        setContentView(scroll);
        content.addView(text("轻骑", 30, INK, true));
        add(content, text("简单连接，轻松出发。", 16, MUTED, false), 6);

        LinearLayout vehicle = column();
        vehicle.setPadding(dp(22), dp(24), dp(22), dp(24));
        vehicle.setBackground(background(Color.WHITE, 24));
        vehicle.addView(text("我的电动车", 20, INK, true));
        add(vehicle, text("尚未添加车辆", 15, MUTED, false), 6);
        LinearLayout readings = new LinearLayout(this);
        readings.setOrientation(LinearLayout.HORIZONTAL);
        readings.addView(reading("—", "电量 · 待适配"), new LinearLayout.LayoutParams(0, -2, 1));
        readings.addView(reading("—", "续航 · 待适配"), new LinearLayout.LayoutParams(0, -2, 1));
        add(vehicle, readings, 26);
        scanButton = button("查找我的车", GREEN, Color.WHITE);
        scanButton.setOnClickListener(v -> beginDiscovery());
        add(vehicle, scanButton, 24);
        status = text("靠近车辆，先检查蓝牙连接。", 14, MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(vehicle, status, 12);
        reportButton = button("检查记录", Color.rgb(231, 235, 226), GREEN);
        reportButton.setEnabled(!lastReport().isEmpty());
        reportButton.setOnClickListener(v -> showHistory());
        add(vehicle, reportButton, 12);
        cancelButton = button("停止检查并保存", Color.rgb(231, 235, 226), GREEN);
        cancelButton.setVisibility(View.GONE);
        cancelButton.setOnClickListener(v -> probe.cancel());
        add(vehicle, cancelButton, 8);
        add(vehicle, text("一次检查连接、设备信息和重连，最多 90 秒。最近 10 次结果保存在本机。", 13, MUTED, false), 8);
        add(content, vehicle, 28);

        add(content, text("常用功能", 20, INK, true), 28);
        add(content, text("完成车辆适配后开放", 14, MUTED, false), 6);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        for (String[] labels : new String[][]{{"解锁", "锁车"}, {"寻车鸣笛", "打开座桶"}}) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < labels.length; i++) {
                Button action = button(labels[i], Color.rgb(231, 235, 226), MUTED);
                action.setEnabled(false);
                action.setContentDescription(labels[i] + "，车辆适配完成后可用");
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                if (i == 0) lp.setMarginEnd(dp(10));
                row.addView(action, lp);
            }
            add(controls, row, 10);
        }
        add(content, controls, 4);
        LinearLayout sensing = column();
        sensing.setPadding(dp(18), dp(16), dp(18), dp(16));
        sensing.setBackground(background(Color.WHITE, 18));
        Switch toggle = new Switch(this);
        toggle.setText("感应解锁");
        toggle.setTextSize(17);
        toggle.setTextColor(INK);
        toggle.setEnabled(false);
        toggle.setChecked(false);
        toggle.setMinHeight(dp(48));
        sensing.addView(toggle);
        add(sensing, text("需确认车辆支持，并完成连接验证。", 14, MUTED, false), 4);
        add(content, sensing, 12);

        add(content, text("适配预览", 15, GREEN, true), 24);
        add(content, text("当前可检查连接、读取设备提供的标准信息并验证重连。车辆电量、续航及控车尚待适配，请先用原版爱玛出行确认蓝牙控车可用。", 14, MUTED, false), 6);
    }

    private void beginDiscovery() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 41);
        } else showDiscovery();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(code, permissions, grantResults);
        if (code != 41) return;
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            showDiscovery();
        } else {
            new AlertDialog.Builder(this).setTitle("需要附近设备权限")
                .setMessage("允许后才能搜索和检查车辆的蓝牙连接。你可以在系统设置中开启。")
                .setNegativeButton("暂不设置", null)
                .setPositiveButton("打开设置", (d,w) -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())))).show();
        }
    }

    private void showDiscovery() {
        if (isFinishing() || isDestroyed()) return;
        probe.close();
        candidates = List.of();
        searchQuery = "";
        LinearLayout body = column();
        body.setPadding(dp(20), dp(4), dp(20), 0);
        scanStatus = text("正在准备搜索…", 15, MUTED, false);
        body.addView(scanStatus);
        SearchView search = new SearchView(this);
        search.setIconifiedByDefault(false);
        search.setQueryHint("名称或 MAC 地址，支持末几位");
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String query) { search.clearFocus(); return true; }
            @Override public boolean onQueryTextChange(String query) { searchQuery = query; filterDevices(); return true; }
        });
        add(body, search, 8);
        filterStatus = text("已发现 0 台", 14, MUTED, false);
        add(body, filterStatus, 4);
        ListView list = new ListView(this);
        adapter = new ArrayAdapter<BleProbe.Device>(this, android.R.layout.simple_list_item_2, android.R.id.text1, new ArrayList<>()) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                BleProbe.Device device = getItem(position);
                ((TextView) row.findViewById(android.R.id.text1)).setText(device.name());
                TextView subtitle = row.findViewById(android.R.id.text2);
                subtitle.setSingleLine(false);
                subtitle.setText(device.address() + " · " + (device.rssi() >= -65 ? "较强" : device.rssi() >= -85 ? "一般" : "较弱"));
                return row;
            }
        };
        adapter.setNotifyOnChange(false);
        list.setAdapter(adapter);
        add(body, list, 12);
        list.getLayoutParams().height = dp(260);
        scanDialog = new AlertDialog.Builder(this).setTitle("查找我的车").setView(body)
            .setNegativeButton("关闭", null).setNeutralButton("重新搜索", null).create();
        scanDialog.setOnDismissListener(d -> {
            probe.close();
            scanDialog = null; adapter = null; scanStatus = null; filterStatus = null;
            scanButton.setEnabled(true);
        });
        list.setOnItemClickListener((parent,view,position,id) -> {
            BleProbe.Device selected = (BleProbe.Device) parent.getItemAtPosition(position);
            if (selected == null) return;
            EditText note = new EditText(this);
            note.setHint("车型、固件或状态，例如：锁车后唤醒");
            note.setText(getSharedPreferences("checks", MODE_PRIVATE).getString("test_note", ""));
            note.setMaxLines(3);
            note.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(160)});
            LinearLayout noteBody = column();
            noteBody.setPadding(dp(24), 0, dp(24), 0);
            noteBody.addView(note);
            new AlertDialog.Builder(this).setTitle("完整检查这台设备？").setView(noteBody)
                .setMessage(selected.name() + "\nMAC 地址 " + selected.address()
                    + "\n\n请确认它属于你的车辆。将读取设备提供的标准信息并自动重连，最多 90 秒。请保持本页打开，不会发送开锁、鸣笛或启动指令。备注可留空。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始完整检查", (d,w) -> {
                    if (!activityVisible) return;
                    // Dismiss callbacks are queued; detach before handing scanning over to a connection.
                    if (scanDialog != null) {
                        scanDialog.setOnDismissListener(null);
                        scanDialog.dismiss();
                        scanDialog = null; adapter = null; scanStatus = null; filterStatus = null;
                    }
                    testNote = note.getText().toString().trim();
                    getSharedPreferences("checks", MODE_PRIVATE).edit().putString("test_note", testNote).apply();
                    probe.inspect(selected);
                }).show();
        });
        scanDialog.setOnShowListener(d -> {
            scanDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> probe.scan());
            search.clearFocus();
            probe.scan();
        });
        scanDialog.show();
        scanDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    @Override public void devices(List<BleProbe.Device> found) {
        candidates = List.copyOf(found);
        filterDevices();
    }

    private void filterDevices() {
        if (adapter == null) return;
        String query = searchQuery.trim().toLowerCase(Locale.ROOT);
        String macQuery = query.replaceAll("[:\\-\\.\\s]", "");
        adapter.clear();
        for (BleProbe.Device device : candidates) {
            String mac = device.address().replace(":", "").toLowerCase(Locale.ROOT);
            if (query.isEmpty() || device.name().toLowerCase(Locale.ROOT).contains(query)
                || (!macQuery.isEmpty() && mac.contains(macQuery))) adapter.add(device);
        }
        adapter.notifyDataSetChanged();
        if (filterStatus != null) filterStatus.setText(query.isEmpty() ? "已发现 " + candidates.size() + " 台"
            : "匹配 " + adapter.getCount() + " / " + candidates.size() + " 台"
                + (adapter.isEmpty() ? " · 试试名称或 MAC 末几位" : ""));
    }

    @Override public void message(String value, boolean busy) {
        if (isFinishing() || isDestroyed()) return;
        status.setText(value);
        scanButton.setEnabled(!busy);
        cancelButton.setVisibility(probe.isInspecting() ? View.VISIBLE : View.GONE);
        if (scanStatus != null) scanStatus.setText(value);
    }

    private String lastReport() {
        return getSharedPreferences("checks", MODE_PRIVATE).getString("last_report", "");
    }

    private org.json.JSONArray reportHistory() {
        try { return new org.json.JSONArray(getSharedPreferences("checks", MODE_PRIVATE).getString("history", "[]")); }
        catch (org.json.JSONException e) { return new org.json.JSONArray(); }
    }

    private void showHistory() {
        org.json.JSONArray history = reportHistory();
        if (history.length() == 0) { showReport(lastReport()); return; }
        String[] labels = new String[history.length()];
        for (int i = 0; i < labels.length; i++) {
            String report = history.optString(i);
            String time = Arrays.stream(report.split("\\n")).filter(line -> line.startsWith("时间：")).findFirst().orElse("检查记录");
            String note = Arrays.stream(report.split("\\n")).filter(line -> line.startsWith("备注：")).findFirst().orElse("");
            String result = Arrays.stream(report.split("\\n")).filter(line -> line.startsWith("结果：")).findFirst().orElse("");
            labels[i] = time + "\n" + result + (note.isBlank() ? "" : "\n" + note);
        }
        new AlertDialog.Builder(this).setTitle("检查记录 · 最近 10 次")
            .setItems(labels, (d, which) -> showReport(history.optString(which)))
            .setNegativeButton("关闭", null).show();
    }

    @Override public void result(String summary, String detail) {
        String report = "轻骑 " + BuildConfig.VERSION_NAME + " 完整检查\n时间："
            + java.time.OffsetDateTime.now() + "\n系统：Android " + android.os.Build.VERSION.RELEASE
            + "\n手机：" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
            + (testNote.isBlank() ? "" : "\n备注：" + testNote.replace('\n', ' '))
            + "\n结果：" + summary + "\n\n" + detail;
        org.json.JSONArray history = reportHistory(), updated = new org.json.JSONArray();
        updated.put(report);
        if (history.length() == 0 && !lastReport().isEmpty()) updated.put(lastReport());
        for (int i = 0; i < history.length() && updated.length() < 10; i++) updated.put(history.optString(i));
        getSharedPreferences("checks", MODE_PRIVATE).edit().putString("last_report", report)
            .putString("history", updated.toString()).apply();
        if (!activityVisible || isFinishing() || isDestroyed()) return;
        reportButton.setEnabled(true);
        new AlertDialog.Builder(this).setTitle(summary)
            .setMessage(detail.split("\n\n", 2)[0] + "\n\n结果已保存，可在首页再次查看。")
            .setPositiveButton("复制结果", (d,w) -> copyReport(report))
            .setNegativeButton("关闭", null)
            .setNeutralButton("查看详情", (d,w) -> showReport(report)).show();
    }

    private void showReport(String report) {
        TextView text = text(report, 14, INK, false);
        text.setPadding(dp(20), dp(12), dp(20), dp(12));
        text.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("检查详情").setView(scroll)
            .setPositiveButton("复制结果", (d,w) -> copyReport(report))
            .setNeutralButton("导出 / 分享", (d,w) -> {
                Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, report);
                startActivity(Intent.createChooser(share, "分享检查报告"));
            })
            .setNegativeButton("关闭", null).show();
    }

    private void copyReport(String report) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("轻骑连接检查", report));
        Toast.makeText(this, "已复制，可粘贴到聊天里", Toast.LENGTH_SHORT).show();
    }

    @Override protected void onStart() {
        super.onStart(); activityVisible = true;
        reportButton.setEnabled(!lastReport().isEmpty());
    }
    @Override protected void onStop() {
        activityVisible = false;
        probe.cancel();
        if (scanDialog != null) scanDialog.dismiss();
        scanButton.setEnabled(true);
        status.setText("靠近车辆，先检查蓝牙连接。");
        super.onStop();
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout reading(String value, String label) {
        LinearLayout v = column(); v.addView(text(value, 42, INK, true)); add(v,text(label,14,MUTED,false),4); return v;
    }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        t.setLineSpacing(dp(3),1); if (bold) t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); return t;
    }
    private Button button(String value, int color, int foreground) {
        Button b = new Button(this); b.setText(value); b.setTextSize(17); b.setTextColor(foreground); b.setAllCaps(false);
        b.setMinHeight(dp(56)); b.setPadding(dp(12),dp(12),dp(12),dp(12)); b.setBackground(background(color,16)); return b;
    }
    private GradientDrawable background(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private void add(LinearLayout parent, View child, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin = dp(top); parent.addView(child,p); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
