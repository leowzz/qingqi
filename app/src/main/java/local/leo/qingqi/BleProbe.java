package local.leo.qingqi;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.*;

/** Foreground diagnostics; standard allowlisted reads only, no writes or owner authentication. */
@SuppressLint("MissingPermission")
public final class BleProbe {
    public record Device(String address, String name, int rssi, String advertisement) {}
    public interface Listener {
        void devices(List<Device> devices);
        void message(String message, boolean busy);
        void result(String summary, String detail);
        default void evidence(long elapsedMillis, String phase, String message) {}
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
    private final StringBuilder evidence = new StringBuilder();
    private final ArrayDeque<BluetoothGattCharacteristic> reads = new ArrayDeque<>();
    private BluetoothGattCharacteristic pendingRead;
    private Runnable stepTimeout;
    private String phase = "";
    private long started;
    private int pass, signalSamples, readOk, readFailed, readPlanned;
    private boolean inspecting, firstDiscovered, secondDiscovered, incomplete;
    private String firstTopology;

    public boolean isInspecting() { return inspecting; }
    private void log(String message) {
        listener.evidence(android.os.SystemClock.elapsedRealtime() - started, phase, message);
        evidence.append('[').append((android.os.SystemClock.elapsedRealtime() - started) / 1000)
            .append("s] ").append(message).append('\n');
    }

    private static String advertisement(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        StringBuilder out = new StringBuilder("可连接：").append(result.isConnectable())
            .append("；广播 RSSI：").append(result.getRssi()).append(" dBm")
            .append("；PHY：").append(result.getPrimaryPhy()).append('/').append(result.getSecondaryPhy());
        if (record == null) return out.append("\n无广播记录").toString();
        out.append("\n广播服务：").append(record.getServiceUuids())
            .append("\n广播发射功率：").append(record.getTxPowerLevel() == Integer.MIN_VALUE ? "未提供" : record.getTxPowerLevel());
        for (int i = 0; i < record.getManufacturerSpecificData().size(); i++) {
            int id = record.getManufacturerSpecificData().keyAt(i);
            out.append("\n厂商数据 ").append(id).append(": ")
                .append(DiagnosticValues.hex(record.getManufacturerSpecificData().valueAt(i)));
        }
        if (record.getServiceData() != null) for (var entry : record.getServiceData().entrySet())
            out.append("\n服务数据 ").append(entry.getKey()).append(": ").append(DiagnosticValues.hex(entry.getValue()));
        return out.append("\n原始广播：").append(DiagnosticValues.hex(record.getBytes())).toString();
    }

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
                        devices.put(address, new Device(address, name, result.getRssi(), advertisement(result)));
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
        inspecting = true;
        started = android.os.SystemClock.elapsedRealtime();
        evidence.setLength(0);
        phase = "开始检查";
        pass = 1; signalSamples = 0; readOk = 0; readFailed = 0; readPlanned = 0;
        firstDiscovered = false; secondDiscovered = false; incomplete = false; firstTopology = null;
        log("设备：" + device.name() + "\n蓝牙地址：" + device.address() + "\n" + device.advertisement());
        int run = generation;
        timeout = () -> finish(run, "检查达到时间上限", "总时限 90 秒；停止于：" + phase);
        main.postDelayed(timeout, 90000);
        connect(run);
    }

    private void connect(int run) {
        if (run != generation) return;
        stage(run, pass == 1 ? "正在连接车辆…" : "正在验证断开后重连…", 15000);
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                finish(run, "蓝牙未开启", "请打开手机蓝牙后重试。"); return;
            }
            BluetoothDevice remote = adapter.getRemoteDevice(inspectedDevice.address());
            log("第 " + pass + " 次连接；系统绑定状态：" + remote.getBondState() + "（10 未绑定 / 11 绑定中 / 12 已绑定）");
            gatt = remote.connectGatt(context, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt connection, int status, int newState) {
                    main.post(() -> {
                        if (!active(run, connection)) return;
                        log("连接回调：" + DiagnosticValues.status(status) + "；状态 " + newState);
                        if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                            failedPass(run, "连接结束：" + DiagnosticValues.status(status));
                        } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                            stage(run, "正在读取第 " + pass + " 次连接的接口…", 10000);
                            try {
                                if (!connection.discoverServices()) failedPass(run, "系统未接受服务发现请求");
                            } catch (SecurityException | IllegalStateException e) { failedPass(run, "服务发现不可用，请检查蓝牙权限和状态"); }
                        }
                    });
                }
                @Override public void onServicesDiscovered(BluetoothGatt connection, int status) {
                    main.post(() -> {
                        if (!active(run, connection)) return;
                        if (status != BluetoothGatt.GATT_SUCCESS) { failedPass(run, "服务发现：" + DiagnosticValues.status(status)); return; }
                        discovered(run, connection);
                    });
                }
                @Override public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic c, byte[] value, int status) {
                    byte[] snapshot = value == null ? new byte[0] : value.clone();
                    main.post(() -> readResult(run, connection, c, snapshot, status));
                }
                @SuppressWarnings("deprecation")
                @Override public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic c, int status) {
                    // Android 13+ calls the value-bearing overload; Android 12 needs this one.
                    if (android.os.Build.VERSION.SDK_INT >= 33) return;
                    byte[] snapshot = c.getValue() == null ? new byte[0] : c.getValue().clone();
                    main.post(() -> readResult(run, connection, c, snapshot, status));
                }
                @Override public void onReadRemoteRssi(BluetoothGatt connection, int rssi, int status) {
                    main.post(() -> {
                        if (!active(run, connection) || !phase.startsWith("正在测量连接信号")) return;
                        clearStep();
                        log("连接信号 #" + (signalSamples + 1) + "：" + (status == 0 ? rssi + " dBm" : DiagnosticValues.status(status)));
                        if (status != 0) incomplete = true;
                        signalSamples++;
                        nextSignal(run);
                    });
                }
            }, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) failedPass(run, "系统未能创建连接");
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            failedPass(run, "无法连接，请检查蓝牙和附近设备权限");
        }
    }

    private boolean active(int run, BluetoothGatt connection) { return generation == run && inspecting && connection == gatt; }

    private void discovered(int run, BluetoothGatt connection) {
        clearStep();
        StringBuilder topology = new StringBuilder();
        int services = 0, characteristics = 0, candidates = 0;
        for (BluetoothGattService service : connection.getServices()) {
            services++;
            topology.append("服务 ").append(service.getUuid()).append(" #").append(service.getInstanceId())
                .append(" 类型 ").append(service.getType()).append('\n');
            List<GattProfile.Characteristic> inService = new ArrayList<>();
            for (BluetoothGattService included : service.getIncludedServices())
                topology.append("  引用服务 ").append(included.getUuid()).append('\n');
            for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                characteristics++;
                inService.add(new GattProfile.Characteristic(c.getUuid(), c.getProperties()));
                topology.append("  特征 ").append(c.getUuid()).append(" #").append(c.getInstanceId())
                    .append(" / ").append(DiagnosticValues.properties(c.getProperties())).append('\n');
                for (BluetoothGattDescriptor d : c.getDescriptors()) topology.append("    描述符 ").append(d.getUuid()).append('\n');
                if (pass == 1) {
                    String label = DiagnosticValues.readLabel(service.getUuid(), c.getUuid());
                    if (label != null) {
                        if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0)
                            log(label + "：存在但未声明可读，跳过");
                        else if (reads.size() < 12) reads.add(c);
                        else { incomplete = true; log(label + "：超过单次 12 项读取上限，跳过"); }
                    }
                }
            }
            if (GattProfile.hasAimaHint(inService)) {
                candidates++;
                topology.append("  线索：同一服务内含 ADB1 指示 + ADB2 写入；尚未验证身份、协议或控车能力\n");
            }
        }
        log("第 " + pass + " 次服务发现成功：" + services + " 个服务 / " + characteristics + " 个特征 / " + candidates + " 组候选接口\n" + topology);
        if (pass == 1) {
            firstDiscovered = true;
            firstTopology = topology.toString();
            readPlanned = reads.size();
            if (reads.isEmpty()) log("未发现允许读取的标准信息；不等于车辆不支持电量或版本查询，可能需要私有协议认证。");
            nextRead(run);
        } else {
            secondDiscovered = true;
            log(firstTopology == null ? "首次服务发现未完成，无法对比接口" :
                "重连接口对比（含实例及枚举顺序）：" + (firstTopology.equals(topology.toString()) ? "一致" : "有变化，请对照两次清单"));
            nextSignal(run);
        }
    }

    private void nextRead(int run) {
        pendingRead = reads.poll();
        if (pendingRead == null) { nextSignal(run); return; }
        String label = DiagnosticValues.readLabel(pendingRead.getService().getUuid(), pendingRead.getUuid());
        stage(run, "正在读取" + label + "…", 4000);
        try {
            if (!gatt.readCharacteristic(pendingRead)) {
                readFailed++; incomplete = true; log(label + "：系统未接受读取请求");
                clearStep(); nextRead(run);
            }
        } catch (SecurityException | IllegalStateException e) { failedPass(run, "标准信息读取中断：请检查蓝牙权限和状态"); }
    }

    private void readResult(int run, BluetoothGatt connection, BluetoothGattCharacteristic c, byte[] value, int status) {
        if (!active(run, connection) || c != pendingRead) return;
        clearStep();
        String label = DiagnosticValues.readLabel(c.getService().getUuid(), c.getUuid());
        log(label + " [" + c.getService().getUuid() + "/" + c.getUuid() + " #" + c.getInstanceId() + "]："
            + (status == 0 ? DiagnosticValues.decode(c.getUuid(), value) + "；原始值 " + DiagnosticValues.hex(value) : DiagnosticValues.status(status)));
        if (status == 0) readOk++; else { readFailed++; incomplete = true; }
        pendingRead = null;
        nextRead(run);
    }

    private void nextSignal(int run) {
        int target = pass == 1 ? 2 : 3;
        if (signalSamples >= target) {
            if (pass == 1) reconnect(run);
            else finish(run, incomplete ? "检查完成，部分项目未通过" : "完整检查已完成",
                "已完成两轮连接检查。接口及读取结果见详情；尚未验证控车能力。");
            return;
        }
        stage(run, "正在测量连接信号（" + (signalSamples + 1) + "/3）…", 3000);
        try {
            if (!gatt.readRemoteRssi()) {
                incomplete = true; log("信号测量请求未被系统接受"); signalSamples++; clearStep(); nextSignal(run);
            }
        } catch (SecurityException | IllegalStateException e) { failedPass(run, "信号测量中断：请检查蓝牙权限和状态"); }
    }

    private void reconnect(int run) {
        clearStep();
        log("主动断开，1.2 秒后重新连接；首次未完成的读取不重试。");
        releaseGatt();
        reads.clear(); pendingRead = null; signalSamples = 2; pass = 2;
        phase = "等待重新连接";
        listener.message("首次检查结束，正在准备重连…", true);
        stepTimeout = () -> { if (generation == run) connect(run); };
        main.postDelayed(stepTimeout, 1200);
    }

    private void failedPass(int run, String reason) {
        if (run != generation) return;
        incomplete = true;
        log(reason + "；阶段：" + phase);
        if (pass == 1) reconnect(run);
        else finish(run, "检查部分完成", "第二次连接检查未完成。请查看详情中的失败阶段。");
    }

    private void stage(int run, String text, long milliseconds) {
        clearStep(); phase = text;
        log(text);
        listener.message(text, true);
        stepTimeout = () -> {
            if (generation != run) return;
            // Never advance on the same GATT after a missing callback: a late response could be misattributed.
            failedPass(run, "此步骤超时（" + milliseconds / 1000 + " 秒）");
        };
        main.postDelayed(stepTimeout, milliseconds);
    }
    private void clearStep() {
        if (stepTimeout != null) main.removeCallbacks(stepTimeout);
        stepTimeout = null;
    }

    public void cancel() {
        if (inspecting) finish(generation, "检查已停止", "停止于：" + phase);
        else close();
    }

    private void finish(int run, String summary, String detail) {
        if (generation != run) return;
        String report = detail + "\n本次未发送控车指令，未验证车主认证。\n\n"
            + "首次服务发现：" + (firstDiscovered ? "成功" : "未完成")
            + "\n重连服务发现：" + (secondDiscovered ? "成功" : "未完成")
            + "\n标准读取：成功 " + readOk + " / 失败 " + readFailed + " / 计划 " + readPlanned + " / 未完成 " + (readPlanned - readOk - readFailed)
            + "\n未执行：私有特征读写、通知订阅、认证、开关锁、鸣笛、启动、感应解锁。\n\n" + evidence;
        close();
        listener.message(summary, false);
        listener.result(summary, report);
    }

    private void releaseGatt() {
        if (gatt != null) {
            BluetoothGatt previous = gatt; gatt = null;
            try { previous.disconnect(); } catch (SecurityException | IllegalStateException ignored) {}
            try { previous.close(); } catch (SecurityException | IllegalStateException ignored) {}
        }
    }
    public void close() {
        generation++;
        inspecting = false; inspectedDevice = null;
        reads.clear(); pendingRead = null;
        clearStep();
        if (timeout != null) { main.removeCallbacks(timeout); timeout = null; }
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (SecurityException | IllegalStateException ignored) {}
        }
        scanCallback = null; scanner = null;
        releaseGatt();
    }
}
