package local.leo.qingqi;

import java.util.List;
import java.util.UUID;

/** A protocol hint, never evidence of vehicle identity or successful authentication. */
public final class GattProfile {
    public record Characteristic(UUID uuid, int properties) {}
    private GattProfile() {}
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
