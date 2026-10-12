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
        String[][] extra = {{"1800","2a00"},{"1800","2a01"},{"180a","2a50"},{"1812","2a4a"},{"1812","2a4b"}};
        for (String[] pair : extra) {
            check(readLabel(uuid(pair[0]), uuid(pair[1])) != null);
            check(readLabel(uuid("ff78"), uuid(pair[1])) == null);
        }
        for (String id : new String[]{"2a33","2a4c","2a4d"})
            check(readLabel(uuid("1812"), uuid(id)) == null); // No live HID input or control.
        check(decode(uuid("2a50"),new byte[]{1,(byte)0xA8,1,0x34,0x12,0,1})
            .equals("厂商编号来源 Bluetooth SIG；厂商 0x01A8；产品 0x1234；产品版本原值 0x0100"));
        check(decode(uuid("2a50"),new byte[]{2,0,0,0,0,0,0}).contains("USB-IF"));
        check(decode(uuid("2a50"),new byte[]{(byte)255,0,0,0,0,0,0}).contains("未知来源 255"));
        check(decode(uuid("2a50"),new byte[]{1,(byte)255,(byte)255,0,0,0,0}).contains("0xFFFF"));
        check(decode(uuid("2a01"),new byte[]{(byte)0xC2,3}).contains("0x03C2（类别 15 / 子类 2"));
        check(decode(uuid("2a4a"),new byte[]{0x11,1,0,2}).equals("HID 版本原值 0x0111；国家码 0；标志 0x02"));
        for (String[] pair : new String[][]{{"2a50","7"},{"2a01","2"},{"2a4a","4"}}) {
            int length = Integer.parseInt(pair[1]);
            for (int n : new int[]{1,length-1,length+1})
                check(decode(uuid(pair[0]),new byte[n]).contains("格式异常"));
            check(decode(uuid(pair[0]),new byte[0]).equals("空值"));
        }
        check(decode(uuid("2a4b"),new byte[]{5,1,9,6}).contains("共 4 字节"));
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
