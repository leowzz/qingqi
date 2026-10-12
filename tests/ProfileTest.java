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
        List<GattProfile.Characteristic> ff78 = List.of(c("7800",0x18),c("7801",0x18),
            c("7802",0x18),c("7803",0x10),c("7804",0x18));
        UUID service = c("ff78",0).uuid();
        check(GattProfile.hint(service, ff78).contains("FF78"));
        check(GattProfile.hint(c("ff79",0).uuid(), ff78) == null);
        check(GattProfile.hint(UUID.fromString("1234ff78-0000-1000-8000-00805f9b34fb"), ff78) == null);
        for (int i=0;i<ff78.size();i++) {
            var missing = new java.util.ArrayList<>(ff78); missing.remove(i);
            check(GattProfile.hint(service, missing) == null);
            var wrongFlags = new java.util.ArrayList<>(ff78);
            wrongFlags.set(i, new GattProfile.Characteristic(ff78.get(i).uuid(), 2));
            check(GattProfile.hint(service, wrongFlags) == null);
        }
        var lookalike = new java.util.ArrayList<>(ff78);
        lookalike.set(0, new GattProfile.Characteristic(UUID.fromString("12347800-0000-1000-8000-00805f9b34fb"),0x18));
        check(GattProfile.hint(service, lookalike) == null);
        check(GattProfile.hint(c("adb0",0).uuid(), List.of(c("adb1",32),c("adb2",8))).contains("ADB1"));
        System.out.println("Profile checks passed: ADB and exact FF78 service, UUIDs and properties");
    }
}
