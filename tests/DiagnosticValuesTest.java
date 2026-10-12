import local.leo.qingqi.DiagnosticValues;
import static local.leo.qingqi.DiagnosticValues.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class DiagnosticValuesTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check(readLabel(uuid("180f"), uuid("2a19")) != null);
        check(readLabel(uuid("180a"), uuid("2a19")) == null);
        check(readLabel(uuid("180f"), uuid("2a26")) == null);
        for (String id : new String[]{"2a24", "2a26", "2a27", "2a28", "2a29"})
            check(readLabel(uuid("180a"), uuid(id)) != null);
        check(readLabel(uuid("180a"), uuid("2a25")) == null); // No serial number collection.
        check(readLabel(uuid("adb0"), uuid("adb1")) == null); // No guessed private reads.
        check(readLabel(UUID.fromString("1234180a-0000-1000-8000-00805f9b34fb"), uuid("2a26")) == null);
        check(decode(uuid("2a19"), new byte[]{0}).equals("0%"));
        check(decode(uuid("2a19"), new byte[]{100}).equals("100%"));
        check(!decode(uuid("2a19"), new byte[]{(byte)255}).contains("%"));
        check(!decode(uuid("2a19"), new byte[]{50, 10}).contains("%"));
        check(decode(uuid("2a19"), new byte[0]).equals("空值"));
        check(decode(uuid("2a26"), "V20.0.5\n伪造日志".getBytes(StandardCharsets.UTF_8)).equals("V20.0.5 伪造日志"));
        check(hex(new byte[]{0, (byte)255, (byte)128}).equals("00FF80"));
        check(hex(new byte[513]).contains("513"));
        check(properties(0x22).contains("读取") && properties(0x22).contains("指示"));
        System.out.println("Diagnostic allowlist, malformed-value and reporting checks passed");
    }
}
