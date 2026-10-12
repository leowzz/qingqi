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
        if (service.equals(uuid("1800"))) {
            if (characteristic.equals(uuid("2a00"))) return "设备名称";
            if (characteristic.equals(uuid("2a01"))) return "设备外观码（不能据此确认车型）";
        }
        if (service.equals(uuid("1812"))) {
            if (characteristic.equals(uuid("2a4a"))) return "HID 信息";
            if (characteristic.equals(uuid("2a4b"))) return "HID 报告描述（不读取按键事件）";
        }
        if (service.equals(uuid("180f")) && characteristic.equals(uuid("2a19"))) return "标准电量（来源设备，未确认是车辆动力电池）";
        if (!service.equals(uuid("180a"))) return null;
        if (characteristic.equals(uuid("2a24"))) return "型号";
        if (characteristic.equals(uuid("2a26"))) return "固件版本";
        if (characteristic.equals(uuid("2a27"))) return "硬件版本";
        if (characteristic.equals(uuid("2a28"))) return "软件版本";
        if (characteristic.equals(uuid("2a29"))) return "制造商";
        if (characteristic.equals(uuid("2a50"))) return "PnP 产品标识";
        return null;
    }
    public static String decode(UUID characteristic, byte[] value) {
        if (value == null || value.length == 0) return "空值";
        if (characteristic.equals(uuid("2a19"))) {
            int level = value[0] & 0xff;
            return value.length == 1 && level <= 100 ? level + "%" : "格式异常，不能作为电量";
        }
        if (characteristic.equals(uuid("2a50"))) {
            if (value.length != 7) return "格式异常，PnP 标识应为 7 字节";
            String source = switch (value[0] & 0xff) {
                case 1 -> "Bluetooth SIG";
                case 2 -> "USB-IF";
                default -> "未知来源 " + (value[0] & 0xff);
            };
            return String.format(Locale.ROOT, "厂商编号来源 %s；厂商 0x%04X；产品 0x%04X；产品版本原值 0x%04X",
                source, uint16(value, 1), uint16(value, 3), uint16(value, 5));
        }
        if (characteristic.equals(uuid("2a01"))) {
            if (value.length != 2) return "格式异常，外观码应为 2 字节";
            int appearance = uint16(value, 0);
            return String.format(Locale.ROOT, "0x%04X（类别 %d / 子类 %d；不代表车型）",
                appearance, appearance >> 6, appearance & 63);
        }
        if (characteristic.equals(uuid("2a4a"))) {
            if (value.length != 4) return "格式异常，HID 信息应为 4 字节";
            return String.format(Locale.ROOT, "HID 版本原值 0x%04X；国家码 %d；标志 0x%02X",
                uint16(value, 0), value[2] & 0xff, value[3] & 0xff);
        }
        if (characteristic.equals(uuid("2a4b"))) return "二进制报告描述，共 " + value.length + " 字节（见原始值）";
        return new String(value, StandardCharsets.UTF_8).replaceAll("[\\p{Cntrl}]", " ").trim();
    }
    private static int uint16(byte[] value, int offset) {
        return (value[offset] & 0xff) | ((value[offset + 1] & 0xff) << 8);
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
