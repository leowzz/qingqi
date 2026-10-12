package local.leo.qingqi;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.*;

/** Foreground, user-selected service discovery. No pairing, authentication, or writes. */
@SuppressLint("MissingPermission")
public final class BleProbe {
    public record Device(String address, String name, int rssi) {}
    public interface Listener {
        void devices(List<Device> devices);
        void message(String message, boolean busy);
        void result(String summary, String detail);
    }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private int generation;
    private Runnable timeout;
    private Device inspectedDevice;

    public BleProbe(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void scan() {
        close();
        int run = generation;
        devices.clear();
        listener.devices(List.of());
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        try {
            if (adapter == null || !adapter.isEnabled()) {
                listener.message("请先打开手机蓝牙，然后重新搜索。", false);
                return;
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                listener.message("蓝牙尚未准备好，请稍后重试。", false);
                return;
            }
            scanCallback = new ScanCallback() {
                @Override public void onScanResult(int callbackType, ScanResult result) {
                    main.post(() -> {
                        if (generation != run || scanCallback != this) return;
                        String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                        if (name == null || name.isBlank()) name = "未命名设备";
                        String address = result.getDevice().getAddress();
                        devices.put(address, new Device(address, name, result.getRssi()));
                        // Keep discovery order stable while RSSI updates so rows do not jump under a tap.
                        listener.devices(new ArrayList<>(devices.values()));
                    });
                }
                @Override public void onScanFailed(int errorCode) {
                    main.post(() -> {
                        if (generation != run || scanCallback != this) return;
                        close();
                        listener.message(errorCode == SCAN_FAILED_SCANNING_TOO_FREQUENTLY
                            ? "搜索太频繁，请等半分钟再试。" : "蓝牙搜索失败，请稍后重试（" + errorCode + "）。", false);
                    });
                }
            };
            scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
            listener.message("正在搜索附近设备…", true);
            timeout = () -> {
                if (generation != run) return;
                close();
                listener.message(devices.isEmpty()
                    ? "没有发现设备。请靠近车辆并按车辆说明唤醒蓝牙后重试；本次未发现不代表车辆不支持。"
                    : "搜索结束。请只选择你能确认属于自己车辆的设备。", false);
            };
            main.postDelayed(timeout, 15000);
        } catch (SecurityException e) {
            close(); listener.message("缺少附近设备权限，请在系统设置中允许后重试。", false);
        } catch (IllegalStateException e) {
            close(); listener.message("蓝牙状态已变化，请打开蓝牙后重试。", false);
        }
    }

    public void inspect(Device device) {
        close();
        inspectedDevice = device;
        int run = generation;
        listener.message("正在检查连接…", true);
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                finish(run, "未能开始连接", "请先打开手机蓝牙。"); return;
            }
            gatt = adapter.getRemoteDevice(device.address()).connectGatt(context, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt connection, int status, int newState) {
                    main.post(() -> {
                        if (generation != run || connection != gatt) { connection.close(); return; }
                        if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                            finish(run, "未能完成连接检查", "请靠近车辆、关闭原版 App 的蓝牙连接后重试。状态码：" + status);
                        } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                            try {
                                if (!connection.discoverServices()) finish(run, "未能读取连接信息", "请稍后重试。");
                            } catch (SecurityException e) { finish(run, "附近设备权限已关闭", "请重新允许权限后再试。"); }
                        }
                    });
                }
                @Override public void onServicesDiscovered(BluetoothGatt connection, int status) {
                    main.post(() -> {
                        if (generation != run || connection != gatt) { connection.close(); return; }
                        if (status != BluetoothGatt.GATT_SUCCESS) { finish(run, "未能读取连接信息", "状态码：" + status); return; }
                        List<GattProfile.Characteristic> all = new ArrayList<>();
                        StringBuilder details = new StringBuilder();
                        for (BluetoothGattService service : connection.getServices()) {
                            details.append("\n服务 ").append(service.getUuid()).append('\n');
                            for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                                all.add(new GattProfile.Characteristic(c.getUuid(), c.getProperties()));
                                details.append("  ").append(c.getUuid()).append(" / ").append(c.getProperties()).append('\n');
                            }
                        }
                        boolean hint = GattProfile.hasAimaHint(all);
                        finish(run, hint ? "发现可能匹配的车辆接口" : "尚未确认车辆兼容性",
                            (hint ? "接口与原版蓝牙模块的特征相符，但还需验证车主身份和车型。"
                                : "设备可以连接，但没有发现已知接口。它可能使用其他协议，不能据此断定不支持。")
                            + "\n本次仅检查连接，未发送任何车辆控制指令。\n\n" + details);
                    });
                }
            }, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) { finish(run, "未能建立蓝牙连接", "请重试。"); return; }
            timeout = () -> finish(run, "连接检查超时", "请确认车辆在附近且处于可连接状态。原版 App 占用连接时，也可能无法连接。");
            main.postDelayed(timeout, 18000);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            finish(run, "未能开始连接", "请检查蓝牙和附近设备权限后重试。");
        }
    }

    private void finish(int run, String summary, String detail) {
        if (generation != run) return;
        String identity = inspectedDevice == null ? "" : "\n\n设备：" + inspectedDevice.name()
            + "\n蓝牙地址：" + inspectedDevice.address();
        close();
        listener.message(summary, false);
        listener.result(summary, detail + identity);
    }

    public void close() {
        generation++;
        inspectedDevice = null;
        if (timeout != null) { main.removeCallbacks(timeout); timeout = null; }
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (SecurityException | IllegalStateException ignored) {}
        }
        scanCallback = null; scanner = null;
        if (gatt != null) {
            BluetoothGatt previous = gatt; gatt = null;
            try { previous.disconnect(); } catch (SecurityException ignored) {}
            previous.close();
        }
    }
}
