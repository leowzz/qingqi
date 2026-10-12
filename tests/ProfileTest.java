import java.util.List;
import java.util.UUID;
import local.leo.qingqi.GattProfile;

public class ProfileTest {
    static GattProfile.Characteristic c(String shortId, int flags) {
        return new GattProfile.Characteristic(UUID.fromString("0000" + shortId + "-0000-1000-8000-00805f9b34fb"), flags);
    }
    static void check(boolean result) { if (!result) throw new AssertionError(); }
    public static void main(String[] args) {
        check(!GattProfile.hasAimaHint(List.of()));
        check(!GattProfile.hasAimaHint(List.of(c("adb1",32))));
        check(!GattProfile.hasAimaHint(List.of(c("adb1",2),c("adb2",8))));
        check(!GattProfile.hasAimaHint(List.of(c("adb1",32),c("adb2",2))));
        check(GattProfile.hasAimaHint(List.of(c("adb1",32),c("adb2",8))));
        check(GattProfile.hasAimaHint(List.of(c("adb1",32),c("adb2",4))));
        check(!GattProfile.hasAimaHint(List.of(c("2a19",2))));
        System.out.println("7 profile checks passed");
    }
}
