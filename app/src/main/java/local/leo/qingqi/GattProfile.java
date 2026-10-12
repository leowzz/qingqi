package local.leo.qingqi;

import java.util.List;
import java.util.UUID;

/** A protocol hint, never evidence of vehicle identity or successful authentication. */
public final class GattProfile {
    public record Characteristic(UUID uuid, int properties) {}
    private GattProfile() {}
    public static String hint(UUID service, List<Characteristic> characteristics) {
        if (service.equals(DiagnosticValues.uuid("ff78"))
                && has(characteristics, "7800", 0x18) && has(characteristics, "7801", 0x18)
                && has(characteristics, "7802", 0x18) && has(characteristics, "7803", 0x10)
                && has(characteristics, "7804", 0x18))
            return "FF78 / 7800–7804 接口线索；尚未验证身份、认证或控车能力";
        if (hasAimaHint(characteristics))
            return "ADB1 指示 + ADB2 写入接口线索；尚未验证身份、协议或控车能力";
        return null;
    }
    private static boolean has(List<Characteristic> characteristics, String id, int flags) {
        return characteristics.stream().anyMatch(c -> c.uuid().equals(DiagnosticValues.uuid(id))
            && (c.properties() & flags) == flags);
    }
    public static boolean hasAimaHint(List<Characteristic> characteristics) {
        boolean indication = false, write = false;
        for (Characteristic c : characteristics) {
            String id = c.uuid().toString();
            indication |= id.contains("adb1") && (c.properties() & 0x20) != 0;
            write |= id.contains("adb2") && (c.properties() & (0x04 | 0x08)) != 0;
        }
        return indication && write;
    }
}
