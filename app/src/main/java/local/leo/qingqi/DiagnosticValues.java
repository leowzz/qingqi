package local.leo.qingqi;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Standard, explicitly allowlisted reads. Private characteristics are inventoried only. */
public final class DiagnosticValues {
    private DiagnosticValues() {}
    public static UUID uuid(String shortId) {
        return UUID.fromString("0000" + shortId + "-0000-1000-8000-00805f9b34fb");
    }
    public static String readLabel(UUID service, UUID characteristic) {
        if (service.equals(uuid("180f")) && characteristic.equals(uuid("2a19"))) return "标准电量（来源设备，未确认是车辆动力电池）";
        if (!service.equals(uuid("180a"))) return null;
        if (characteristic.equals(uuid("2a24"))) return "型号";
        if (characteristic.equals(uuid("2a26"))) return "固件版本";
        if (characteristic.equals(uuid("2a27"))) return "硬件版本";
        if (characteristic.equals(uuid("2a28"))) return "软件版本";
        if (characteristic.equals(uuid("2a29"))) return "制造商";
        return null;
    }
    public static String decode(UUID characteristic, byte[] value) {
        if (value == null || value.length == 0) return "空值";
        if (characteristic.equals(uuid("2a19"))) {
            int level = value[0] & 0xff;
            return value.length == 1 && level <= 100 ? level + "%" : "格式异常，不能作为电量";
        }
        return new String(value, StandardCharsets.UTF_8).replaceAll("[\\p{Cntrl}]", " ").trim();
    }
    public static String hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "（空）";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, 512); i++) out.append(String.format(Locale.ROOT, "%02X", bytes[i] & 0xff));
        if (bytes.length > 512) out.append("… 共 ").append(bytes.length).append(" 字节");
        return out.toString();
    }
    public static String properties(int flags) {
        String[] names = {"广播", "读取", "无响应写入", "写入", "通知", "指示", "签名写入", "扩展"};
        List<String> result = new ArrayList<>();
        for (int i = 0; i < names.length; i++) if ((flags & (1 << i)) != 0) result.add(names[i]);
        return String.join(" / ", result) + " (0x" + Integer.toHexString(flags) + ")";
    }
    public static String status(int status) {
        return switch (status) {
            case 0 -> "成功 (0)";
            case 2 -> "不允许读取 (2)";
            case 5 -> "需要认证 (5)";
            case 6 -> "不支持此请求 (6)";
            case 8 -> "状态 8（ATT 可能为授权不足；连接阶段可能为超时）";
            case 15 -> "需要加密 (15)";
            case 133 -> "Android GATT 错误 (133)";
            default -> "状态码 " + status;
        };
    }
}
