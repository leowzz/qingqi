import android.bluetooth.*;
import android.os.Handler;
import local.leo.qingqi.BleProbe;
import java.util.*;

/** Exercises production callbacks and timers, not a second implementation of the flow. */
public class BleProbeFlowTest {
    static class Results implements BleProbe.Listener {
        int count, eventCount; long lastElapsed; String detail = "", summary = "";
        public void devices(List<BleProbe.Device> d) {}
        public void message(String s, boolean busy) {}
        public void evidence(long elapsed, String phase, String message) {
            check(elapsed >= lastElapsed && !phase.isEmpty() && !message.isEmpty());
            lastElapsed = elapsed; eventCount++;
        }
        public void result(String s, String d) { count++; summary=s; detail=d; }
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static BleProbe start(Results out) {
        Handler.reset(); BluetoothGatt.created.clear();
        BleProbe p = new BleProbe(new android.content.Context(), out);
        p.inspect(new BleProbe.Device("00:11:22:33:44:55", "test", -60, "advertisement evidence"));
        return p;
    }
    static BluetoothGatt current() { return BluetoothGatt.created.get(BluetoothGatt.created.size()-1); }
    static void discover(BluetoothGatt g) {
        g.cb.onConnectionStateChange(g, 0, 2); Handler.advance(0);
        g.cb.onServicesDiscovered(g, 0); Handler.advance(0);
    }
    static BluetoothGattCharacteristic battery(BluetoothGatt g) {
        BluetoothGattService service = new BluetoothGattService("180f"); g.services.add(service);
        return new BluetoothGattCharacteristic(service,"2a19",2);
    }
    static void finishSecond() { Handler.advance(1200); BluetoothGatt b=current(); discover(b); b.signal(); }
    public static void main(String[] args) {
        for (int api : new int[]{31,33}) {
            android.os.Build.VERSION.SDK_INT=api;
            Results r=new Results(); BleProbe p=start(r); BluetoothGatt a=current();
            BluetoothGattCharacteristic c=battery(a); discover(a);
            check(a.readCalls==1 && a.signalCalls==0);
            a.read(c,0); a.signal(); a.signal(); check(a.closed);
            finishSecond(); check(r.count==1 && !p.isInspecting() && r.eventCount>5);
            check(r.detail.contains("50%") && r.detail.contains("重连服务发现：成功"));
            check(r.detail.contains("advertisement evidence"));
            Handler.advance(100000); check(r.count==1);
        }

        Results r=new Results(); BleProbe p=start(r); BluetoothGatt a=current();
        BluetoothGattCharacteristic c=battery(a); discover(a);
        Handler.advance(4000); check(a.closed); Handler.advance(1200);
        BluetoothGatt b=current(); a.read(c,0); check(b.readCalls==0 && r.count==0); // Stale callback.
        discover(b); b.signal(); check(r.count==1 && r.detail.contains("超时") && r.detail.contains("未完成 1"));

        r=new Results(); p=start(r); a=current(); c=battery(a);
        BluetoothGattService info=new BluetoothGattService("180a"); a.services.add(info);
        BluetoothGattCharacteristic firmware=new BluetoothGattCharacteristic(info,"2a26",2);
        discover(a); a.read(c,5); check(a.readCalls==2); a.read(firmware,0); a.signal(); a.signal(); finishSecond();
        check(r.detail.contains("需要认证") && r.detail.contains("成功 1 / 失败 1 / 计划 2"));

        r=new Results(); p=start(r); a=current(); battery(a); a.rejectRead=true; discover(a);
        check(a.signalCalls==1); a.signal(); a.signal(); finishSecond();
        check(r.detail.contains("系统未接受读取请求"));

        r=new Results(); p=start(r); a=current(); discover(a); p.cancel();
        a.signal(); Handler.advance(100000); check(r.count==1 && a.closed && r.detail.contains("首次服务发现：成功"));

        r=new Results(); p=start(r); Handler.advance(15000+1200+15000);
        check(r.count==1 && r.detail.contains("首次服务发现：未完成") && r.detail.contains("重连服务发现：未完成"));

        r=new Results(); p=start(r); a=current();
        BluetoothGattService privateService=new BluetoothGattService("adb0"); a.services.add(privateService);
        new BluetoothGattCharacteristic(privateService,"adb1",34);
        BluetoothGattService another=new BluetoothGattService("adb3"); a.services.add(another);
        new BluetoothGattCharacteristic(another,"adb2",8);
        discover(a); check(a.readCalls==0); p.cancel(); check(r.detail.contains("0 组候选接口"));

        r=new Results(); p=start(r); a=current();
        BluetoothGattService many=new BluetoothGattService("180a"); a.services.add(many);
        List<BluetoothGattCharacteristic> items=new ArrayList<>();
        for(int i=0;i<13;i++) items.add(new BluetoothGattCharacteristic(many,"2a26",2));
        Handler.advance(14000); a.cb.onConnectionStateChange(a,0,2); Handler.advance(0);
        Handler.advance(9000); a.cb.onServicesDiscovered(a,0); Handler.advance(0);
        for(int i=0;i<12;i++){Handler.advance(3900);a.read(items.get(i),0);}
        check(a.readCalls==12); a.signal(); a.signal(); Handler.advance(1200);
        b=current(); Handler.advance(14000); b.cb.onConnectionStateChange(b,0,2); Handler.advance(0);
        Handler.advance(6000); check(r.count==1 && b.closed && r.summary.contains("时间上限"));
        b.cb.onServicesDiscovered(b,0); Handler.advance(0); check(r.count==1);
        System.out.println("BLE flow checks passed: Android 12/13 reads, serial operations, late callbacks, timeouts, read rejection, cancellation, no private reads, same-service hints");
    }
}
